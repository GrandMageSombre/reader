package com.modu.reader.repo

import com.modu.reader.App
import com.modu.reader.data.Book
import com.modu.reader.data.BookSwap
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.data.ShelfBook
import com.modu.reader.data.SwapCandidate
import com.modu.reader.source.BookSource
import com.modu.reader.source.DeviceCrawl
import com.modu.reader.source.DiscoverySource
import com.modu.reader.source.Paywall
import com.modu.reader.source.SwapMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 业务胶水层：书源 + 本地缓存 + 书架。
 * 网络请求全部在 IO 线程，UI 只调这里。
 */
class BookRepo(private val app: App) {

    /** 手机端抓取的状态：任务是 App 级的，离开详情页不会被取消 */
    data class CrawlState(
        val bookUrl: String,
        val running: Boolean,
        val message: String,
        val finished: Boolean = false
    )

    /** 生命周期跟 App 一致，用于「界面已经关掉也要落盘」的写操作与长任务 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 有付费墙的两个源：正文是试读/空的时候才去采集站兜底 */
    private val PAID_WALL_SOURCES = setOf("qidian", "fanqie")

    /** 付费章兜底结果缓存（key = 书名|章名，只缓存**命中**，失败下次还能重试） */
    private val chapterFallbackCache = ConcurrentHashMap<String, String>()

    private val _crawlState = MutableStateFlow<CrawlState?>(null)

    /** 本机抓取的进度/结果，界面用 collectAsState 订阅 */
    val crawlState: StateFlow<CrawlState?> = _crawlState

    private fun src(id: String): BookSource =
        app.sources.source(id) ?: throw IllegalStateException("书源不存在或已被删除")

    suspend fun categories(sourceId: String): List<Category> = withContext(Dispatchers.IO) {
        src(sourceId).categories()
    }

    suspend fun list(sourceId: String, category: Category, page: Int): List<Book> =
        withContext(Dispatchers.IO) { src(sourceId).list(category, page) }

    suspend fun search(sourceId: String, key: String, page: Int): List<Book> =
        withContext(Dispatchers.IO) { src(sourceId).search(key, page) }

    suspend fun detail(sourceId: String, url: String, fallbackTitle: String = ""): Book =
        withContext(Dispatchers.IO) {
            val b = src(sourceId).detail(Book(sourceId, url, fallbackTitle))
            if (app.db.isOnShelf(sourceId, url)) app.db.addToShelf(b)
            b
        }

    /**
     * 章节目录：优先网络，失败或为空时回落到本地缓存。
     * 若这本书换过源，目录从换源的目标书源取。
     */
    suspend fun chapters(sourceId: String, bookUrl: String, title: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val cached = app.db.loadChapters(bookUrl)
            try {
                val (src, book) = effective(sourceId, bookUrl, title)
                val fresh = src.chapters(book)
                if (fresh.isNotEmpty()) {
                    app.db.saveChapters(bookUrl, fresh)
                    fresh
                } else {
                    cached
                }
            } catch (e: Exception) {
                if (cached.isNotEmpty()) cached else throw e
            }
        }

    /**
     * 正文：本地缓存优先（翻回去看不用再联网），没有才抓。
     * force = true 时无视缓存重新抓（阅读器里「重试」用）。
     * 若这本书换过源，正文从换源的目标书源抓。
     */
    suspend fun content(
        sourceId: String,
        bookUrl: String,
        bookTitle: String,
        chapter: Chapter,
        force: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val key = contentKey(bookUrl, chapter.index)
        if (!force) {
            app.db.loadContent(key)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        val (src, book) = effective(sourceId, bookUrl, bookTitle)
        var text = ""
        var failure: Exception? = null
        try {
            text = src.content(book, chapter)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            failure = e
        }
        // 起点 VIP / 番茄付费章：站点只给试读、空正文，甚至直接报错
        // → 自动去采集站按「书名 + 章名」捞正文
        if (sourceId in PAID_WALL_SOURCES &&
            (failure != null || Paywall.needsFallback(text))
        ) {
            val alt = chapterFromCollectors(bookUrl, bookTitle, chapter, sourceId)
            if (!alt.isNullOrBlank()) {
                text = alt
                failure = null
            }
        }
        if (text.isBlank()) {
            throw failure ?: IllegalStateException("这一章正文是空的：${chapter.title}")
        }
        // 试读片段不进缓存：否则下次直接命中缓存，再也不会去兜底
        if (!Paywall.looksPaid(text)) app.db.saveContent(key, text)
        text
    }

    /**
     * 付费章兜底：在采集站里按书名搜索，再用「书名 + 章名」对齐到同一章。
     *
     * 只在付费墙挡住时调用（[Paywall.needsFallback]）；**只缓存命中的正文**，
     * 没命中的下次还能重试（一次临时网络失败不该让这章永久失效）。
     */
    private suspend fun chapterFromCollectors(
        bookUrl: String,
        bookTitle: String,
        chapter: Chapter,
        excludeSourceId: String
    ): String? {
        val chapterTitle = chapter.title
        val cacheKey = "$bookTitle|$chapterTitle"
        chapterFallbackCache[cacheKey]?.let { return it }

        val swapSourceId = app.db.loadSwap(bookUrl)?.sourceId
        val sites = app.sources.available()
            .filter {
                it.id != excludeSourceId && it.id != swapSourceId &&
                    !app.sources.isDiscovery(it.id)
            }
            .mapNotNull { cfg ->
                app.sources.source(cfg.id)?.let { s ->
                    CollectorFallback.Site(
                        name = cfg.name,
                        search = { key -> s.search(key, 1) },
                        chapters = { book -> s.chapters(book) },
                        content = { book, ch -> s.content(book, ch) }
                    )
                }
            }

        // 起点这边一共多少章：把「第 N 章」折算成相对位置，再由每个采集站按自己的目录长度换算
        val localTotal = try {
            app.db.loadChapters(bookUrl).size
        } catch (e: Exception) {
            0
        }
        val ratio = if (localTotal > 0) {
            (chapter.index + 0.5f) / localTotal
        } else {
            -1f
        }

        val found = CollectorFallback.resolve(
            bookTitle = bookTitle,
            chapterTitle = chapterTitle,
            positionRatio = ratio,
            sites = sites
        )
        if (found != null) {
            if (chapterFallbackCache.size > 200) chapterFallbackCache.clear()
            chapterFallbackCache[cacheKey] = found
        }
        return found
    }

    /**
     * 手机端直连抓取（WebView 过反爬 + 本机抓目录/正文）。
     *
     * 任务挂在 App 级作用域上：用户翻到阅读器或返回上一页，抓取会继续跑完，
     * 进度通过 [crawlState] 订阅。同一时间只允许一个任务。
     */
    fun startCrawl(sourceId: String, bookUrl: String, title: String, limit: Int = 30) {
        if (_crawlState.value?.running == true) return
        _crawlState.value = CrawlState(bookUrl, true, "准备中…")
        io.launch {
            try {
                val result = DeviceCrawl().crawl(
                    context = app,
                    sourceId = sourceId,
                    bookUrl = bookUrl,
                    limit = limit,
                    onCatalog = { chapters -> app.db.saveChapters(bookUrl, chapters) },
                    onProgress = { msg -> _crawlState.value = CrawlState(bookUrl, true, msg) }
                )
                // 目录已经在 onCatalog 里落库（中断也留得下），正文成功后统一写缓存
                result.contents.forEach { (idx, text) ->
                    app.db.saveContent(contentKey(bookUrl, idx), text)
                }
                _crawlState.value = CrawlState(bookUrl, false, result.outcome.log, finished = true)
            } catch (ce: CancellationException) {
                _crawlState.value = CrawlState(bookUrl, false, "抓取已取消", finished = true)
                throw ce
            } catch (e: Exception) {
                _crawlState.value = CrawlState(
                    bookUrl, false, "抓取出错：${e.message ?: e.javaClass.simpleName}",
                    finished = true
                )
            }
        }
    }

    /** 抓完后直接从本地库读目录（不重新联网，避免把成功反显成失败） */
    suspend fun cachedChapters(bookUrl: String): List<Chapter> =
        withContext(Dispatchers.IO) { app.db.loadChapters(bookUrl) }

    /** 阅读进度（章节序号 / 滚动位置 / 章节标题），强制走 IO */
    suspend fun progress(sourceId: String, bookUrl: String): Triple<Int, Int, String>? =
        withContext(Dispatchers.IO) { app.db.progressOf(sourceId, bookUrl) }

    // ---------------- 换源 ----------------

    /**
     * 在其它书源里找这本书的正文来源。
     *
     * 默认排除当前书源与所有「发现源」（起点/番茄），因为它们本来就抓不到正文。
     */
    suspend fun findSwapCandidates(
        sourceId: String,
        bookUrl: String,
        title: String,
        author: String
    ): List<SwapCandidate> = withContext(Dispatchers.IO) {
        val pool = app.sources.available()
            .filter { it.id != sourceId && !app.sources.isDiscovery(it.id) }
            .mapNotNull { app.sources.source(it.id) }
        SwapMatcher.find(Book(sourceId, bookUrl, title, author), pool)
    }

    suspend fun swapOf(bookUrl: String): BookSwap? =
        withContext(Dispatchers.IO) { app.db.loadSwap(bookUrl) }

    suspend fun setSwap(swap: BookSwap) = withContext(Dispatchers.IO) {
        app.db.saveSwap(swap)
    }

    /** 取消换源，回到原书源 */
    suspend fun clearSwap(bookUrl: String) = withContext(Dispatchers.IO) {
        app.db.clearSwap(bookUrl)
    }

    /**
     * 取出「实际用来抓目录/正文」的书与源：
     * 有换源记录就用第三方源上的那本，否则用原书。
     */
    private suspend fun effective(sourceId: String, bookUrl: String, title: String): Pair<BookSource, Book> {
        val swap = app.db.loadSwap(bookUrl)
        if (swap != null) {
            val src = app.sources.source(swap.sourceId)
            if (src != null) {
                val realTitle = swap.matchedTitle.ifBlank { title }
                return src to Book(swap.sourceId, swap.sourceBookUrl, realTitle)
            }
            // 换源的目标书源被删了：清掉记录，回落到原书
            app.db.clearSwap(bookUrl)
        }
        return src(sourceId) to Book(sourceId, bookUrl, title)
    }

    suspend fun prefetch(sourceId: String, bookUrl: String, bookTitle: String, chapter: Chapter) {
        try {
            val key = contentKey(bookUrl, chapter.index)
            if (!app.db.loadContent(key).isNullOrBlank()) return
            val (src, book) = effective(sourceId, bookUrl, bookTitle)
            val text = src.content(book, chapter)
            if (text.isNotBlank()) app.db.saveContent(key, text)
        } catch (e: Exception) {
            // 预取失败无所谓
        }
    }

    private fun contentKey(bookUrl: String, index: Int) = "$bookUrl#$index"

    // ---------------- 书架 ----------------

    suspend fun shelf(): List<ShelfBook> = withContext(Dispatchers.IO) { app.db.shelfList() }

    suspend fun isOnShelf(sourceId: String, bookUrl: String): Boolean =
        withContext(Dispatchers.IO) { app.db.isOnShelf(sourceId, bookUrl) }

    suspend fun addToShelf(book: Book) = withContext(Dispatchers.IO) { app.db.addToShelf(book) }

    suspend fun removeFromShelf(sourceId: String, bookUrl: String) =
        withContext(Dispatchers.IO) { app.db.removeFromShelf(sourceId, bookUrl) }

    fun saveProgressAsync(
        sourceId: String,
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterCount: Int,
        scroll: Int
    ) {
        io.launch {
            try {
                app.db.saveProgress(sourceId, bookUrl, chapterIndex, chapterTitle, chapterCount, scroll)
            } catch (e: Exception) {
                // 忽略写库异常
            }
        }
    }

    fun clearCacheAsync() {
        io.launch {
            try {
                app.db.clearAllCache()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    fun contentCached(bookUrl: String, index: Int): Boolean =
        !app.db.loadContent(contentKey(bookUrl, index)).isNullOrBlank()

    /** 供预取/阅读器判断缓存时用（IO 线程读盘，别在 UI 线程直接调） */
    suspend fun contentCachedAsync(bookUrl: String, index: Int): Boolean =
        withContext(Dispatchers.IO) { contentCached(bookUrl, index) }
}
