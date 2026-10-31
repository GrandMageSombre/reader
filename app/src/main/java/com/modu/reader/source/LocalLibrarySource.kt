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

    /** 运行时地址：优先「上次发现/手填的」，其次书源配置里的 baseUrl */
    @Volatile
    private var resolved: String? = null

    private val api: String
        get() = resolved
            ?: com.modu.reader.data.SettingsStore.localSpiderApi.ifBlank { "" }
                .ifBlank { cfg.baseUrl }
                .ifBlank { DEFAULT_API }
                .trimEnd('/')

    /**
     * 连接前先确认地址可用；连不上就**自动发现**（UDP 广播找电脑），
     * 发现到就换过去。这样用户不用知道电脑的局域网 IP。
     */
    private suspend fun ensureReachable(): String = withContext(Dispatchers.IO) {
        resolved?.let { return@withContext it }
        val candidates = listOfNotNull(
            com.modu.reader.data.SettingsStore.localSpiderApi.takeIf { it.isNotBlank() },
            cfg.baseUrl.takeIf { it.isNotBlank() },
            DEFAULT_API
        ).distinct()
        for (c in candidates) {
            if (ping(c)) {
                resolved = c.trimEnd('/')
                return@withContext resolved!!
            }
        }
        // 都不通 → 局域网广播找一次
        val found = LocalDiscovery.discover()
        if (found != null && ping(found)) {
            resolved = found
            return@withContext found
        }
        resolved = candidates.first().trimEnd('/')
        resolved!!
    }

    /**
     * 探活：只做 TCP 连接（600ms 超时），别拿 HTTP GET 去等 15 秒。
     * 地址对不对由后续真实请求确认，这里只负责「快速排除明显不通的候选」。
     */
    private fun ping(base: String): Boolean {
        val hostPort = base.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val host = hostPort.substringBefore(':')
        val port = hostPort.substringAfter(':', "80").substringBefore('/').toIntOrNull() ?: 80
        return try {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), 600) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- 接口访问 ----------------
    private suspend fun get(path: String, vararg params: Pair<String, String?>): JSONObject =
        withContext(Dispatchers.IO) {
            val base = ensureReachable()
            val query = params.mapNotNull { (k, v) ->
                v?.let { "$k=${URLEncoder.encode(it, "UTF-8")}" }
            }.joinToString("&")
            val url = if (query.isEmpty()) base + path else base + path + "?" + query
            val body = try {
                Http.get(url, "utf-8", cfg.headers)
            } catch (e: Exception) {
                throw IllegalStateException(
                    "连不上本地爬虫库（$base）。\n" +
                        "① 电脑上跑：python -m tools.spider.cli serve --port 8765\n" +
                        "② 手机和电脑连同一个 WiFi（有些路由开了「AP 隔离」也会连不上）\n" +
                        "③ 已开启局域网自动发现，确认电脑防火墙放行 8765/UDP 8766",
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
        return when {
            o.optBoolean("preview") ->
                "$text\n\n（以上为站方试读片段；完整正文可在「换源阅读」里看）"
            o.optString("source") == "collector" ->
                "$text\n\n（本章正文来自采集站补全，排版可能与正站略有差异）"
            else -> text
        }
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
