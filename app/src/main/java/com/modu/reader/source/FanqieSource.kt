package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 番茄小说书源：**发现源**。
 *
 * ## 为什么不能按分类直接抓
 *
 * 实测（2026-10）番茄没有任何可用的「按分类列书」入口：
 * - HTML 首页完全由 JS 渲染，`__NEXT_DATA__` 也没有书籍数据；
 * - `/api/rank/list` 只有 `type` = 0/1/2 三档有数据，**共 26 本**；
 * - `page` / `gender` / `category_id` / `dim` 等参数**全部无效**（结果一模一样）；
 * - `/api/category/list`、`/api/book/category`、`/api/book/recommend` 等一律 404；
 * - 书籍页没有相关推荐，无法顺着扩池。
 *
 * ## 现在的做法
 *
 * 好消息是**每本书自带官方三级分类**（`/api/book/info` 的 `category` 字段，
 * 如 `男生/都市/都市重生`、`女生/古代言情/宫闱宅斗`）。
 * 所以这里把三档榜单的 26 本全抓下来，**按书自身的官方分类归组**展示。
 *
 * 代价是每本书要多一次 `book/info` 请求，所以结果按 [CACHE_TTL] 缓存 30 分钟。
 *
 * ## 正文
 *
 * 正文页 `/reader/{id}` 由 JS 渲染，抓不到 → 必须换源到第三方书源阅读。
 */
open class FanqieSource(override val cfg: SourceConfig) : BookSource {

    private val apiBase get() = cfg.baseUrl.ifBlank { "https://fanqienovel.com" }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(Http.get(url, cfg.charset, cfg.headers))
    }

    private suspend fun getHtml(url: String) = withContext(Dispatchers.IO) {
        Jsoup.parse(Http.get(url, cfg.charset, cfg.headers), url)
    }

    override suspend fun categories(): List<Category> = cfg.categories

    /**
     * 分类里存的是官方分类名（如 `男生/都市`）；[ALL_CATEGORY] 表示「全部」。
     */
    override suspend fun list(category: Category, page: Int): List<Book> {
        if (page > 1) return emptyList() // 没有分页
        val all = allBooks()
        if (category.name == ALL_CATEGORY || category.url == ALL_URL) return all
        return all.filter { matchCategory(it, category.name) }
    }

    /** 判断一本书是否属于某个官方分类（一级或二级都能匹配） */
    private fun matchCategory(b: Book, key: String): Boolean {
        val cat = b.category
        if (cat.isBlank()) return false
        return cat == key || cat.startsWith("$key/") || cat.substringBeforeLast('/') == key
    }

    override suspend fun search(key: String, page: Int): List<Book> {
        // 番茄没有公开搜索接口；但可以在本地榜单里做包含匹配，聊胜于无
        if (page > 1) return emptyList()
        val k = key.trim()
        if (k.isBlank()) return emptyList()
        return allBooks().filter { it.title.contains(k) || it.author.contains(k) }
    }

    /**
     * 抓三档榜单的全部书 + 逐本补官方分类。
     *
     * 分类拿不到时归到「未分类」，不让整页失败。
     */
    private suspend fun allBooks(): List<Book> = withContext(Dispatchers.IO) {
        val cached = cache
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.first < CACHE_TTL) return@withContext cached.second

        val raw = LinkedHashMap<String, Book>()
        for (t in RANK_TYPES) {
            val root = try {
                getJson("$apiBase/api/rank/list?type=$t")
            } catch (e: Exception) {
                continue
            }
            val list = root.optJSONObject("data")?.optJSONArray("list") ?: continue
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optString("bookId").trim()
                val name = o.optString("bookName").trim()
                if (id.isBlank() || name.isBlank()) continue
                raw.putIfAbsent(
                    id,
                    Book(
                        sourceId = cfg.id,
                        url = "$apiBase/page/$id",
                        title = name,
                        author = o.optString("author").trim(),
                        cover = o.optString("thumbUri").trim(),
                        intro = o.optString("abstract").trim()
                    )
                )
            }
        }
        if (raw.isEmpty()) return@withContext emptyList()

        // 并发补官方分类
        val withCat = coroutineScope {
            raw.entries.map { (id, b) ->
                async {
                    val cat = try {
                        val info = getJson("$apiBase/api/book/info?bookId=$id")
                        info.optJSONObject("data")?.optString("category")?.trim().orEmpty()
                    } catch (e: Exception) {
                        ""
                    }
                    b.copy(category = cat)
                }
            }.awaitAll()
        }
        cache = now to withCat
        withCat
    }

    override suspend fun detail(book: Book): Book {
        val doc = getHtml(book.url)
        val name = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        val author = doc.selectFirst("[class*=author]")?.text()
            ?.substringBefore("/")?.trim().orEmpty()
        val cover = doc.selectFirst("img[src*=byteimg]")?.absUrl("src").orEmpty()
            .ifBlank { doc.selectFirst("img[src*=fqnovelpic]")?.absUrl("src").orEmpty() }
        val intro = doc.selectFirst("[class*=abstract]")?.text()?.trim().orEmpty()
        val status = Regex("(已完结|连载中)").find(doc.text())?.value.orEmpty()
        return book.copy(
            title = name.ifBlank { book.title },
            author = author.ifBlank { book.author },
            cover = cover.ifBlank { book.cover },
            intro = intro.ifBlank { book.intro },
            status = status.ifBlank { book.status }
        )
    }

    /** 书籍页里有章节链接；正文抓不到，所以这个目录只用于展示与换源对照 */
    override suspend fun chapters(book: Book): List<Chapter> {
        val doc = getHtml(book.url)
        return doc.select("a[href^=/reader/]").mapIndexedNotNull { i, a ->
            val href = a.attr("href").trim()
            val title = a.text().trim()
            if (href.isBlank() || title.isBlank()) null
            else Chapter(i, title, absolute(href))
        }
    }

    override suspend fun content(book: Book, chapter: Chapter): String {
        throw IllegalStateException(
            "番茄的正文页由 JS 渲染，静态抓取取不到。\n请用「换源阅读」到第三方书源读正文。"
        )
    }

    private fun absolute(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u
        else apiBase + (if (u.startsWith("/")) u else "/$u")
    }

    companion object {
        const val ID = "fanqie"

        /** 参与抓取的榜单档位（实测只有这三档有数据） */
        val RANK_TYPES = listOf(0, 1, 2)

        const val ALL_CATEGORY = "全部"
        const val ALL_URL = "/api/rank/list?type=all"

        /** 分类结果缓存时长：每本书要多一次 book/info，缓存 30 分钟 */
        private const val CACHE_TTL = 30 * 60 * 1000L

        @Volatile
        private var cache: Pair<Long, List<Book>>? = null

        /** 让测试与「刷新」入口能清缓存 */
        fun invalidateCache() {
            cache = null
        }
    }
}

/** 番茄书自带的官方分类（如 `男生/都市/都市重生`），空串表示没取到 */
val Book.sourceCat: String get() = category
