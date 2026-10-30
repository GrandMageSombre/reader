package com.modu.reader.source

import android.content.Context
import com.modu.reader.data.Book
import com.modu.reader.data.Chapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.coroutines.coroutineContext

/**
 * 整本抓取：把某本书的目录 + 前 N 章正文一次性抓到（供详情页「本机抓取」用）。
 *
 * 与 [DeviceSource] 的关系：书源负责「按需取一章」，这里负责「一次抓一批」。
 * 两者共用 [DeviceApi]（同一套 cookie / 反爬 / 解析），只是调用方式不同。
 *
 * **本类只抓、不落库**：结果通过 [Result] 交回 `BookRepo`，由它决定怎么存。
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

    suspend fun crawl(
        context: Context,
        sourceId: String,
        bookUrl: String,
        limit: Int = 30,
        onCatalog: (List<Chapter>) -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            when {
                bookUrl.contains("fanqienovel.com") ->
                    crawlFanqie(context, bookUrl, limit, onCatalog, onProgress)
                else -> crawlQidian(context, bookUrl, limit, onCatalog, onProgress)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(outcome = Outcome(log = "抓取出错：${e.message ?: e.javaClass.simpleName}"))
        }
    }

    private suspend fun crawlQidian(
        context: Context,
        bookUrl: String,
        limit: Int,
        onCatalog: (List<Chapter>) -> Unit,
        onProgress: (String) -> Unit
    ): Result {
        onProgress("过起点反爬并取目录（首次会慢一点）…")
        val chapters = try {
            DeviceApi.qidianChapters(context, bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(outcome = Outcome(log = "取目录失败：${e.message}"))
        }
        if (chapters.isEmpty()) {
            return Result(outcome = Outcome(log = "起点目录为空（可能被风控，过几分钟再试）"))
        }
        onCatalog(chapters)
        onProgress("目录 ${chapters.size} 章，开始抓正文（本次 $limit 章）…")

        val contents = ArrayList<Pair<Int, String>>()
        var paid = 0
        var failed = 0
        for ((i, ch) in chapters.take(limit).withIndex()) {
            coroutineContext.ensureActive()
            val pair = try {
                DeviceApi.qidianText(context, ch.url, bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "" to false
            }
            val text = pair.first
            if (text.isBlank()) {
                failed++
                continue
            }
            if (pair.second || Paywall.needsFallback(text)) {
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

    private suspend fun crawlFanqie(
        context: Context,
        bookUrl: String,
        limit: Int,
        onCatalog: (List<Chapter>) -> Unit,
        onProgress: (String) -> Unit
    ): Result {
        FanqieGlyph.ensureLoaded(context)
        onProgress("取番茄目录…")
        val chapters = try {
            DeviceApi.fanqieChapters(context, bookUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result(outcome = Outcome(log = "取目录失败：${e.message}"))
        }
        if (chapters.isEmpty()) {
            return Result(outcome = Outcome(log = "番茄目录为空（风控中，歇几分钟再试）"))
        }
        onCatalog(chapters)
        onProgress("目录 ${chapters.size} 章，开始抓正文（本次 $limit 章）…")

        val contents = ArrayList<Pair<Int, String>>()
        var paid = 0
        var undecoded = 0
        var failed = 0
        for ((i, ch) in chapters.take(limit).withIndex()) {
            coroutineContext.ensureActive()
            val text = try {
                DeviceApi.fanqieText(context, ch.url, bookUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            when {
                text.isBlank() -> failed++
                FanqieGlyph.obfuscatedRatio(text) > 0.02f -> undecoded++
                else -> {
                    contents.add(ch.index to text)
                    onProgress("已抓 ${contents.size}/$limit 章（第 ${i + 1} 章）…")
                }
            }
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
}

/**
 * 纯解析函数：不碰网络、不碰数据库，单测可以直接喂 HTML / JSON。
 */
internal object DeviceParsers {

    /** 起点书库列表：`div.all-book-list li` 卡片（类目/状态/简介都能取到） */
    fun parseQidianList(html: String, sourceId: String): List<Book> {
        val doc = Jsoup.parse(html)
        val out = ArrayList<Book>()
        doc.select("div.all-book-list li").forEach { li ->
            val info = li.selectFirst("div.book-mid-info") ?: return@forEach
            val a = info.selectFirst("h2 a") ?: return@forEach
            val href = a.attr("href")
            val bid = a.attr("data-bid").ifBlank {
                Regex("/book/(\\d+)").find(href)?.groupValues?.get(1) ?: ""
            }
            if (bid.isBlank()) return@forEach
            val authorEl = info.selectFirst("p.author a.name") ?: info.selectFirst("p.author a")
            val cats = info.select("p.author a")
                .filter { it != authorEl }
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
            val img = li.selectFirst("div.book-img-box img") ?: li.selectFirst("img")
            var cover = img?.attr("src").orEmpty().trim()
            if (cover.startsWith("//")) cover = "https:$cover"
            out.add(
                Book(
                    sourceId = sourceId,
                    url = "https://www.qidian.com/book/$bid/",
                    title = a.text().trim(),
                    author = authorEl?.text()?.trim().orEmpty(),
                    cover = cover,
                    intro = info.selectFirst("p.intro")?.text()?.trim().orEmpty(),
                    status = info.selectFirst("p.author span")?.text()?.trim().orEmpty(),
                    category = cats.joinToString("/")
                )
            )
        }
        return out
    }

    /** 起点详情页：书名/作者/简介/封面 */
    fun parseQidianDetail(html: String, bookUrl: String, sourceId: String): Book {
        val doc = Jsoup.parse(html)
        val desc = doc.selectFirst("meta[property='og:description']")
            ?: doc.selectFirst("meta[name=description]")
        var cover = doc.selectFirst("meta[property='og:image']")?.attr("content").orEmpty()
        if (cover.startsWith("//")) cover = "https:$cover"
        return Book(
            sourceId = sourceId,
            url = bookUrl,
            title = doc.selectFirst("h1")?.text()?.trim().orEmpty(),
            author = doc.selectFirst("a.writer-name")?.text()?.trim().orEmpty(),
            cover = cover,
            intro = desc?.attr("content")?.trim().orEmpty()
        )
    }

    /** 起点章节页：正文段落 + 是否只有试读 */
    fun qidianTextOf(html: String): Pair<String, Boolean> {
        val main = Jsoup.parse(html).selectFirst("main[id^=c-]") ?: return "" to false
        val text = main.select("p").joinToString("\n") { it.text().trim() }
            .lines().filter { it.isNotBlank() }.joinToString("\n")
        val preview = html.contains("订阅本章") || html.contains("本章为VIP章节")
        return text to preview
    }

    /** 番茄章节页：从 `__INITIAL_STATE__` 取正文并用内置字形表还原 */
    fun fanqieTextOf(html: String): String {
        val reader = jsonAfter(html, "window.__INITIAL_STATE__=")?.optJSONObject("reader")
        val data = reader?.optJSONObject("chapterData")
            ?: reader?.optJSONObject("preview")?.optJSONObject("chapterData")
            ?: return ""
        val raw = data.optString("content")
        if (raw.isBlank()) return ""
        return FanqieGlyph.decode(htmlToText(raw), fontIdOf(html))
    }

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
