package com.modu.reader.repo

import com.modu.reader.App
import com.modu.reader.data.Book
import com.modu.reader.data.BookSwap
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.data.ShelfBook
import com.modu.reader.data.SwapCandidate
import com.modu.reader.source.BookSource
import com.modu.reader.source.DiscoverySource
import com.modu.reader.source.SwapMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 业务胶水层：书源 + 本地缓存 + 书架。
 * 网络请求全部在 IO 线程，UI 只调这里。
 */
class BookRepo(private val app: App) {

    /** 生命周期跟 App 一致，用于「界面已经关掉也要落盘」的写操作 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        val text = src.content(book, chapter)
        if (text.isNotBlank()) app.db.saveContent(key, text)
        text
    }

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
}
