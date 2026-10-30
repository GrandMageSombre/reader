package com.modu.reader.source

import android.content.Context
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import com.modu.reader.net.WebGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.coroutines.coroutineContext

/**
 * 手机端直连抓取：不依赖 PC，直接在 App 里抓起点 / 番茄的目录与正文。
 *
 * 和 PC 版（tools/spider）的分工：
 *
 * | 能力 | PC 版 | 手机版（本类） |
 * |------|-------|----------------|
 * | 过起点 probe.js | headless Edge + CDP 取 cookie | **WebView 跑一遍页面**取 cookie（[WebGate]） |
 * | 过番茄验证码中间页 | headless Edge | 同上 |
 * | 番茄字体反混淆 | 解析 woff2 + 轮廓哈希 | 内置字形表（[FanqieGlyph]） |
 * | 全站枚举（13 万册） | 适合 | 不适合（耗电、易被风控），只按书抓 |
 * | VIP / 付费章 | 拿不到 | 拿不到（服务端不下发，见 docs/付费墙与换源.md） |
 *
 * **本类只抓、不落库**：结果通过 [Result] 交回 `BookRepo`，由它决定怎么存。
 * 这样抓取逻辑可以脱离 `Db` 测，也避免 source 层直接改数据库。
 */
class DeviceCrawl {

    data class Outcome(
        val chapters: Int = 0,
        val fetched: Int = 0,
        val paid: Int = 0,
        val undecoded: Int = 0,
        val failed: Int = 0,
        val log: String = ""
    )

    /** 抓取结果：目录 + 正文（idx -> 文本），落库由调用方负责 */
    data class Result(
        val chapters: List<Chapter> = emptyList(),
        val contents: List<Pair<Int, String>> = emptyList(),
        val outcome: Outcome = Outcome()
    )

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // ---------------- 入口 ----------------
    suspend fun crawl(
        context: Context,
        sourceId: String,
        bookUrl: String,
        limit: Int = 30,
        onCatalog: (List<Chapter>) -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            when (sourceId) {
                "qidian" -> crawlQidian(context, bookUrl, limit, onCatalog, onProgress)
                FanqieSource.ID -> crawlFanqie(context, bookUrl, limit, onCatalog, onProgress)
                else -> Result(outcome = Outcome(log = "这个书源不支持本机抓取（只支持起点 / 番茄）"))
            }
        } catch (e: CancellationException) {
            // 用户离开界面：必须原样抛出，否则协程「假完成」、后台继续跑几十秒
            throw e
        } catch (e: Exception) {
            Result(outcome = Outcome(log = "抓取出错：${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ---------------- 起点 ----------------
    private suspend fun crawlQidian(
        context: Context,
        bookUrl: String,
        limit: Int,
        onCatalog: (List<Chapter>) -> Unit,
        onProgress: (String) -> Unit
    ): Result {
        val bid = Regex("/book/(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: return Result(outcome = Outcome(log = "不是起点书籍地址：$bookUrl"))

        onProgress("过起点反爬（WebView 跑 probe.js）…")
        var cookie = WebGate.cookieFor(context, "https://www.qidian.com/all/")

        var html = http("https://www.qidian.com/book/$bid/", cookie, qidianReferer)
        if (!html.contains("catalog-volume")) {
            onProgress("cookie 没拿到，重过一次…")
            cookie = WebGate.cookieFor(context, "https://www.qidian.com/all/", force = true)
            html = http("https://www.qidian.com/book/$bid/", cookie, qidianReferer)
        }
        if (!html.contains("catalog-volume")) {
            return Result(outcome = Outcome(log = "起点书籍页没取到目录（可能被风控，过几分钟再试）"))
        }

        val chapters = DeviceParsers.parseQidianCatalog(html)
        if (chapters.isEmpty()) return Result(outcome = Outcome(log = "目录解析为空"))
        onCatalog(chapters)
        onProgress("目录 ${chapters.size} 章，开始抓正文（本次 $limit 章）…")

        val contents = ArrayList<Pair<Int, String>>()
        var paid = 0
        var failed = 0
        var reAuth = false
        for ((i, ch) in chapters.take(limit).withIndex()) {
            coroutineContext.ensureActive()
            var body = http(ch.url, cookie, "https://www.qidian.com/book/$bid/")
            if (body.isBlank() && !reAuth) {
                // 全书只补一次 cookie，避免每章都去开一次 WebView
                reAuth = true
                cookie = WebGate.cookieFor(context, "https://www.qidian.com/all/", force = true)
                body = http(ch.url, cookie, "https://www.qidian.com/book/$bid/")
            }
            if (body.isBlank()) {
                failed++
                continue
            }
            val main = Jsoup.parse(body).selectFirst("main[id^=c-]")
            if (main == null) {
                failed++
                continue
            }
            val text = main.select("p").joinToString("\n") { it.text().trim() }
                .lines().filter { it.isNotBlank() }.joinToString("\n")
            if (Paywall.needsFallback(text)) {
                paid++   // VIP 章只给约 130 字试读
                continue
            }
            contents.add(ch.index to text)
            onProgress("已抓 ${contents.size}/$limit 章（第 ${i + 1} 章）…")
        }
        return Result(
            chapters = chapters,
            contents = contents,
            outcome = Outcome(
                chapters = chapters.size, fetched = contents.size, paid = paid, failed = failed,
                log = "起点：目录 ${chapters.size} 章 · 全文 ${contents.size} 章 · " +
                    "VIP/试读 $paid 章 · 失败 $failed"
            )
        )
    }

    // ---------------- 番茄 ----------------
    private suspend fun crawlFanqie(
        context: Context,
        bookUrl: String,
        limit: Int,
        onCatalog: (List<Chapter>) -> Unit,
        onProgress: (String) -> Unit
    ): Result {
        FanqieGlyph.ensureLoaded(context)
        val bookId = Regex("/(?:page|book)/(\\d+)").find(bookUrl)?.groupValues?.get(1)
            ?: bookUrl.filter { it.isDigit() }.takeIf { it.length > 5 }
            ?: return Result(outcome = Outcome(log = "不是番茄书籍地址：$bookUrl"))

        onProgress("取番茄目录…")
        var cookie = WebGate.cookieFor(context, "https://fanqienovel.com/")
        var html = http("https://fanqienovel.com/page/$bookId", cookie, fanqieReferer)
        var state = DeviceParsers.jsonAfter(html, "window.__INITIAL_STATE__=")
        if (state == null) {
            onProgress("被验证码中间页挡了，过一下…")
            cookie = WebGate.cookieFor(context, "https://fanqienovel.com/", force = true)
            html = http("https://fanqienovel.com/page/$bookId", cookie, fanqieReferer)
            state = DeviceParsers.jsonAfter(html, "window.__INITIAL_STATE__=")
        }
        val page = state?.optJSONObject("page")
            ?: return Result(outcome = Outcome(log = "番茄目录页没取到（风控中，歇几分钟再试）"))

        val chapters = DeviceParsers.parseFanqieCatalog(page)
        if (chapters.isEmpty()) return Result(outcome = Outcome(log = "番茄目录为空"))
        onCatalog(chapters)
        onProgress("目录 ${chapters.size} 章，开始抓正文（本次 $limit 章）…")

        val contents = ArrayList<Pair<Int, String>>()
        var paid = 0
        var undecoded = 0
        var failed = 0
        var reAuth = false
        for ((i, ch) in chapters.take(limit).withIndex()) {
            coroutineContext.ensureActive()
            var pageHtml = http(ch.url, cookie, "https://fanqienovel.com/page/$bookId")
            var reader = DeviceParsers.jsonAfter(pageHtml, "window.__INITIAL_STATE__=")
                ?.optJSONObject("reader")
            if (reader == null && !reAuth) {
                reAuth = true
                cookie = WebGate.cookieFor(context, ch.url, force = true)
                pageHtml = http(ch.url, cookie, "https://fanqienovel.com/page/$bookId")
                reader = DeviceParsers.jsonAfter(pageHtml, "window.__INITIAL_STATE__=")
                    ?.optJSONObject("reader")
            }
            val data = reader?.optJSONObject("chapterData")
                ?: reader?.optJSONObject("preview")?.optJSONObject("chapterData")
            if (data == null) {
                failed++
                continue
            }
            val raw = data.optString("content")
            if (raw.isBlank()) {
                paid++
                continue
            }
            val fontId = DeviceParsers.fontIdOf(pageHtml)
            val text = DeviceParsers.htmlToText(raw).let { FanqieGlyph.decode(it, fontId) }
            if (FanqieGlyph.obfuscatedRatio(text) > 0.02f) {
                // 字形表没覆盖（站点换了字体）：不猜、不静默当成功
                undecoded++
                continue
            }
            contents.add(ch.index to text)
            onProgress("已抓 ${contents.size}/$limit 章（第 ${i + 1} 章）…")
        }
        return Result(
            chapters = chapters,
            contents = contents,
            outcome = Outcome(
                chapters = chapters.size, fetched = contents.size, paid = paid,
                undecoded = undecoded, failed = failed,
                log = "番茄：目录 ${chapters.size} 章 · 全文 ${contents.size} 章 · " +
                    "付费/锁定 $paid 章" +
                    (if (undecoded > 0) " · 字形未还原 $undecoded 章" else "") +
                    (if (failed > 0) " · 失败 $failed 章" else "")
            )
        )
    }

    private fun http(url: String, cookie: String, referer: String): String = try {
        Http.get(
            url, "utf-8",
            mapOf(
                "User-Agent" to desktopUa,
                "Referer" to referer,
                "Cookie" to cookie,
                "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8"
            )
        )
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val qidianReferer = "https://www.qidian.com/"
        const val fanqieReferer = "https://fanqienovel.com/"
    }
}

/**
 * 纯解析函数：不碰网络、不碰数据库，单测可以直接喂 HTML / JSON。
 */
internal object DeviceParsers {

    /** 起点目录：按 chapter id 去重，避免「最新章节」区块与正文章节重复计数导致 idx 错位 */
    fun parseQidianCatalog(html: String): List<Chapter> {
        val doc = Jsoup.parse(html)
        val uniq = LinkedHashMap<String, Chapter>()
        doc.select("div.catalog-volume").forEach { vol ->
            vol.select("li.chapter-item a").forEach { a ->
                val m = Regex("/chapter/(\\d+)/(\\d+)").find(a.attr("href")) ?: return@forEach
                val cid = m.groupValues[2]
                if (uniq.containsKey(cid)) return@forEach
                val url = "https://www.qidian.com/chapter/${m.groupValues[1]}/$cid/"
                val name = a.text().trim().ifBlank { a.attr("title") }
                uniq[cid] = Chapter(uniq.size, name, url)
            }
        }
        return uniq.values.toList()
    }

    fun parseFanqieCatalog(page: JSONObject): List<Chapter> {
        val out = LinkedHashMap<String, Chapter>()
        val volumes = page.optJSONArray("chapterListWithVolume") ?: return emptyList()
        for (i in 0 until volumes.length()) {
            val vol = volumes.optJSONArray(i) ?: continue
            for (j in 0 until vol.length()) {
                val ch = vol.optJSONObject(j) ?: continue
                val itemId = ch.optString("itemId").trim()
                if (itemId.isBlank() || out.containsKey(itemId)) continue
                val title = ch.optString("title").trim().ifBlank { "第${out.size + 1}章" }
                out[itemId] = Chapter(out.size, title, "https://fanqienovel.com/reader/$itemId")
            }
        }
        return out.values.toList()
    }

    fun htmlToText(raw: String): String =
        Jsoup.parse(raw).select("p").joinToString("\n") { it.text().trim() }
            .lines().filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank { Jsoup.parse(raw).text() }

    /** 页面里 `@font-face` 的字体文件名（去掉 .woff2）；认不出返回 null，走字形表 default */
    fun fontIdOf(html: String): String? =
        Regex("@font-face\\s*\\{[^}]*?url\\(\\s*[\"']?https://[^)\"']*?/([^/)\"']+)\\.woff2")
            .find(html)?.groupValues?.get(1)

    /** 从 HTML 里抠出 `marker` 后面的 JSON 对象（花括号配对，字符串里的括号不算） */
    fun jsonAfter(html: String, marker: String): JSONObject? {
        val i = html.indexOf(marker)
        if (i < 0) return null
        val start = html.indexOf('{', i)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var k = start
        while (k < html.length) {
            val c = html[k]
            when {
                inStr -> when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
                c == '"' -> inStr = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return try {
                            JSONObject(html.substring(start, k + 1))
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
            k++
        }
        return null
    }
}
