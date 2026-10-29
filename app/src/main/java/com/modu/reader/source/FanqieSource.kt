package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * 番茄小说书源：**发现源**。
 *
 * 番茄的列表只能通过 JSON 接口拿到（HTML 首页的书籍全在 `__NEXT_DATA__` 里、且分页是 JS），
 * 所以这里不走 CSS 规则引擎，直接解析 `/api/rank/list`：
 *
 * ```
 * GET https://fanqienovel.com/api/rank/list?type={0|1|2}
 * -> { "data": { "list": [ {bookId, bookName, author, abstract, thumbUri}, ... ] } }
 * ```
 *
 * 实测结论（2026-10）：
 * - 只有 `type` = 0 / 1 / 2 有数据，每类约 7~10 本；
 * - `page` 参数无效（第 1 页与第 2 页内容相同），所以**没有翻页**；
 * - 正文页 `/reader/{id}` 由 JS 渲染，抓不到；书籍页 `/page/{bookId}` 是 SSR 的，有书名/作者/简介/状态 + 章节链接。
 *
 * 因此这个源只负责「按分类发现书」，正文要靠换源到第三方源。
 */
open class FanqieSource(override val cfg: SourceConfig) : BookSource {

    private val apiBase get() = cfg.baseUrl.ifBlank { "https://fanqienovel.com" }

    private suspend fun getJson(url: String): JSONObject = withContext(Dispatchers.IO) {
        val text = Http.get(url, cfg.charset, cfg.headers)
        JSONObject(text)
    }

    private suspend fun getHtml(url: String) = withContext(Dispatchers.IO) {
        Jsoup.parse(Http.get(url, cfg.charset, cfg.headers), url)
    }

    override suspend fun categories(): List<Category> = cfg.categories

    /** 分类 url 里存的是接口路径，如 `/api/rank/list?type=1` */
    override suspend fun list(category: Category, page: Int): List<Book> {
        // 接口不支持翻页：只有第 1 页有数据
        if (page > 1) return emptyList()
        val url = absolute(category.url)
        val root = try {
            getJson(url)
        } catch (e: Exception) {
            throw IllegalStateException("番茄接口请求失败：${e.message ?: ""}")
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Book>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("bookId").trim()
            val name = o.optString("bookName").trim()
            if (id.isBlank() || name.isBlank()) continue
            out.add(
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
        return out
    }

    /** 番茄没有公开的静态搜索接口，发现源不提供搜索 */
    override suspend fun search(key: String, page: Int): List<Book> {
        throw IllegalStateException("番茄小说是发现源，不支持站内搜索；请用换源在第三方源里找正文")
    }

    override suspend fun detail(book: Book): Book {
        val doc = getHtml(book.url)
        val name = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        // 「温幸运 / 著」
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

    /** 书籍页里有章节链接，但正文页抓不到，所以这里只用于展示目录条数 */
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
            "番茄的正文页由 JS 渲染，静态抓取取不到。\n请用「换源」到第三方书源阅读正文。"
        )
    }

    private fun absolute(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u
        else apiBase + (if (u.startsWith("/")) u else "/$u")
    }

    companion object {
        const val ID = "fanqie"
    }
}
