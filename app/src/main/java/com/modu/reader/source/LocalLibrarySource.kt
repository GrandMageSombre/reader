package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 本地爬虫库书源：直接读 PC 上爬虫（`tools/spider`）已经抓好的数据。
 *
 * 数据链路：
 *
 * ```
 * 起点 / 番茄  ──爬虫──►  data/library.db  ──serve.py──►  http://<PC>:8765/api/...
 *                                                              │
 *                                                        本类（Android）──► 书架 / 阅读器
 * ```
 *
 * 好处是抓取的重活全在电脑上跑（并发、限速、断点续抓、番茄字体反混淆），
 * 手机端只做一次 JSON 读，不用在手机上重复过反爬、也不用担心耗电。
 *
 * 接口约定见 `tools/spider/serve.py`；`baseUrl` 就是电脑的 `host:port`。
 */
class LocalLibrarySource(override val cfg: SourceConfig) : BookSource {

    private val api: String
        get() = cfg.baseUrl.ifBlank { DEFAULT_API }.trimEnd('/')

    // ---------------- 接口访问 ----------------
    private suspend fun get(path: String, vararg params: Pair<String, String?>): JSONObject =
        withContext(Dispatchers.IO) {
            val query = params.mapNotNull { (k, v) ->
                v?.let { "$k=${URLEncoder.encode(it, "UTF-8")}" }
            }.joinToString("&")
            val url = if (query.isEmpty()) api + path else api + path + "?" + query
            val body = try {
                Http.get(url, "utf-8", cfg.headers)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "连不上本地爬虫库（$api）。\n" +
                        "在电脑上先跑：python -m tools.spider.cli serve --port 8765\n" +
                        "手机和电脑要在同一个 WiFi 下，书源地址填电脑的局域网 IP。",
                    e
                )
            }
            try {
                JSONObject(body)
            } catch (e: Exception) {
                throw IllegalStateException("本地爬虫库返回的不是 JSON：${body.take(80)}", e)
            }
        }

    private fun JSONObject.books(): List<Book> {
        val arr = optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Book>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").trim()
            if (id.isBlank()) continue
            out.add(
                Book(
                    sourceId = cfg.id,
                    url = PREFIX + id,
                    title = o.optString("title").trim(),
                    author = o.optString("author").trim(),
                    cover = o.optString("cover").trim(),
                    intro = o.optString("intro").trim(),
                    status = o.optString("status").trim(),
                    category = o.optString("category").trim()
                )
            )
        }
        return out
    }

    // ---------------- BookSource ----------------
    override suspend fun categories(): List<Category> {
        val o = get("/api/categories")
        val arr = o.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val name = c.optString("name").trim()
            if (name.isNotBlank()) out.add(Category(name, name))
        }
        return out
    }

    override suspend fun list(category: Category, page: Int): List<Book> =
        get("/api/books", "cat" to category.url, "page" to page.toString()).books()

    override suspend fun search(key: String, page: Int): List<Book> {
        if (key.isBlank()) return emptyList()
        return get("/api/search", "key" to key.trim(), "page" to page.toString()).books()
    }

    override suspend fun detail(book: Book): Book {
        val id = bookId(book.url) ?: return book
        val o = get("/api/book/$id")
        return book.copy(
            title = o.optString("title").ifBlank { book.title },
            author = o.optString("author").ifBlank { book.author },
            cover = o.optString("cover").ifBlank { book.cover },
            intro = o.optString("intro").ifBlank { book.intro },
            status = o.optString("status").ifBlank { book.status },
            category = o.optString("category").ifBlank { book.category }
        )
    }

    override suspend fun chapters(book: Book): List<Chapter> {
        val id = bookId(book.url) ?: return emptyList()
        val o = get("/api/book/$id/chapters")
        val arr = o.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Chapter>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val idx = c.optInt("idx", -1)
            if (idx < 0) continue
            val title = c.optString("title").trim().ifBlank { "第${idx + 1}章" }
            val mark = when {
                c.optBoolean("vip") && c.optBoolean("cached") -> "（试读）"
                c.optBoolean("vip") -> ""
                !c.optBoolean("cached") -> "（未缓存）"
                else -> ""
            }
            out.add(Chapter(idx, title + mark, "$PREFIX$id#$idx"))
        }
        return out
    }

    override suspend fun content(book: Book, chapter: Chapter): String {
        val id = bookId(book.url) ?: throw IllegalStateException("本地书 id 解析失败：${book.url}")
        val idx = chapter.url.substringAfterLast('#').toIntOrNull()
            ?: throw IllegalStateException("本地章节号解析失败：${chapter.url}")
        val o = get("/api/book/$id/chapter/$idx")
        val text = o.optString("text")
        if (text.isBlank()) throw IllegalStateException("这一章还没爬到，先在电脑上跑一次抓正文")
        return text
    }

    companion object {
        const val ID = "local_spider"
        const val DEFAULT_API = "http://127.0.0.1:8765"

        /** 书的地址前缀：`local:<源>:<站点 id>` */
        private const val PREFIX = "local:"

        fun bookId(url: String): String? =
            url.removePrefix(PREFIX).trim().takeIf { it.isNotBlank() && url.startsWith(PREFIX) }
    }
}
