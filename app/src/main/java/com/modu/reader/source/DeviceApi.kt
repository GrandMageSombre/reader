package com.modu.reader.source

import android.content.Context
import com.modu.reader.data.Book
import com.modu.reader.data.Chapter
import com.modu.reader.net.DeviceHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 手机端直连的「站点能力」集合：起点书库/目录/正文、番茄榜单/目录/正文。
 *
 * 全部走 [DeviceHttp]（自动过指纹页与验证码页），所以上层书源与抓取器都不需要自己管 cookie。
 * 解析仍集中在 [DeviceParsers]，这里只负责拼 URL 与挑字段。
 */
object DeviceApi {

    const val QIDIAN = "https://www.qidian.com"
    const val FANQIE = "https://fanqienovel.com"

    /**
     * 起点大类（chanId 取自 PC 站分类导航，实测均有书单）。
     * 手机端只暴露这些公开入口，足够当「书城」用。
     */
    val QIDIAN_CATEGORIES: List<Pair<String, String>> = listOf(
        "玄幻" to "21", "奇幻" to "1", "武侠" to "2", "仙侠" to "22",
        "都市" to "4", "历史" to "5", "军事" to "6", "游戏" to "7",
        "体育" to "8", "科幻" to "9", "悬疑灵异" to "10", "轻小说" to "12",
        "现实" to "15", "短篇" to "20076", "诸天无限" to "20109"
    )

    // ---------------- 起点 ----------------

    /** 书库列表：`/all/chanId{id}/` 或 `/all/chanId{id}-page{n}/` */
    suspend fun qidianList(
        context: Context,
        chanId: String,
        page: Int,
        sourceId: String
    ): List<Book> = withContext(Dispatchers.IO) {
        val base = "$QIDIAN/all/chanId$chanId"
        val url = if (page <= 1) "$base/" else "$base-page$page/"
        val html = DeviceHttp.get(context, url, expect = "all-book-list")
        if (html.isBlank()) return@withContext emptyList()
        DeviceParsers.parseQidianList(html, sourceId)
    }

    /** 全站书库（无筛选） */
    suspend fun qidianAll(context: Context, page: Int, sourceId: String): List<Book> =
        withContext(Dispatchers.IO) {
            val url = if (page <= 1) "$QIDIAN/all/" else "$QIDIAN/all/page$page/"
            val html = DeviceHttp.get(context, url, expect = "all-book-list")
            if (html.isBlank()) emptyList() else DeviceParsers.parseQidianList(html, sourceId)
        }

    suspend fun qidianDetail(context: Context, bookUrl: String, sourceId: String): Book =
        withContext(Dispatchers.IO) {
            val html = DeviceHttp.get(context, bookUrl, expect = "catalog-volume")
            if (html.isBlank()) return@withContext Book(sourceId, bookUrl, "")
            DeviceParsers.parseQidianDetail(html, bookUrl, sourceId)
        }

    suspend fun qidianChapters(context: Context, bookUrl: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val html = DeviceHttp.get(context, bookUrl, expect = "catalog-volume")
            if (html.isBlank()) emptyList() else DeviceParsers.parseQidianCatalog(html)
        }

    /** 正文：返回 (文本, 是否只有试读) */
    suspend fun qidianText(
        context: Context,
        chapterUrl: String,
        referer: String
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val html = DeviceHttp.get(context, chapterUrl, referer = referer, expect = "read-content")
        if (html.isBlank()) return@withContext "" to false
        DeviceParsers.qidianTextOf(html)
    }

    // ---------------- 番茄 ----------------

    /** 榜单书池（站点没有按分类列书的接口，这就是上限） */
    suspend fun fanqieRank(context: Context, sourceId: String): List<Book> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, Book>()
            for (type in 0..2) {
                val body = DeviceHttp.get(context, "$FANQIE/api/rank/list?type=$type")
                val list = runCatching {
                    JSONObject(body).optJSONObject("data")?.optJSONArray("list")
                }.getOrNull() ?: continue
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val id = o.optString("bookId").trim()
                    val name = o.optString("bookName").trim()
                    if (id.isBlank() || name.isBlank()) continue
                    out.putIfAbsent(
                        id,
                        Book(
                            sourceId = sourceId,
                            url = "$FANQIE/page/$id",
                            title = name,
                            author = o.optString("author").trim(),
                            cover = o.optString("thumbUri").trim(),
                            intro = o.optString("abstract").trim(),
                            category = "番茄榜单"
                        )
                    )
                }
            }
            out.values.toList()
        }

    suspend fun fanqieDetail(context: Context, bookUrl: String, sourceId: String): Book =
        withContext(Dispatchers.IO) {
            val id = fanqieId(bookUrl) ?: return@withContext Book(sourceId, bookUrl, "")
            val body = DeviceHttp.get(context, "$FANQIE/api/book/info?bookId=$id")
            val d = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
                ?: return@withContext Book(sourceId, bookUrl, "")
            Book(
                sourceId = sourceId,
                url = bookUrl,
                title = d.optString("bookName"),
                author = d.optString("authorName"),
                cover = d.optString("thumbUrl"),
                intro = d.optString("abstract"),
                category = d.optString("category"),
                status = when (d.optInt("creationStatus")) {
                    1 -> "连载"
                    2 -> "完结"
                    else -> ""
                }
            )
        }

    suspend fun fanqieChapters(context: Context, bookUrl: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val id = fanqieId(bookUrl) ?: return@withContext emptyList()
            val body = DeviceHttp.get(
                context, "$FANQIE/api/reader/directory/detail?bookId=$id"
            )
            if (body.isBlank()) return@withContext emptyList()
            val page = runCatching { JSONObject(body).optJSONObject("data") }.getOrNull()
                ?: return@withContext emptyList()
            DeviceParsers.parseFanqieCatalog(
                JSONObject().put("chapterListWithVolume", page.optJSONArray("chapterListWithVolume"))
            )
        }

    /** 正文：用内置字形表把私有区码位还原 */
    suspend fun fanqieText(
        context: Context,
        chapterUrl: String,
        referer: String
    ): String = withContext(Dispatchers.IO) {
        FanqieGlyph.ensureLoaded(context)
        val html = DeviceHttp.get(context, chapterUrl, referer = referer)
        DeviceParsers.fanqieTextOf(html)
    }

    // ---------------- 搜索 ----------------

    /**
     * 站内搜索：起点搜索页是 JS 跳转（`/soushu/{kw}.html`），能拿到结果就用，
     * 拿不到就回空——**不编造结果**，界面会显示「换个关键词 / 去分类里翻」。
     */
    suspend fun search(context: Context, key: String, page: Int, sourceId: String): List<Book> =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext emptyList()
            val kw = URLEncoder.encode(key.trim(), "UTF-8")
            val body = DeviceHttp.get(context, "$QIDIAN/soushu/$kw.html")
            val qidian = DeviceParsers.parseQidianList(body, sourceId)
            if (qidian.isNotEmpty() || page > 1) return@withContext qidian
            // 起点搜不到时，从番茄榜单里按书名/作者兜一层
            fanqieRank(context, sourceId).filter {
                it.title.contains(key, true) || it.author.contains(key, true)
            }
        }

    fun fanqieId(url: String): String? =
        Regex("/(?:page|book|reader)/(\\d+)").find(url)?.groupValues?.get(1)
            ?: url.filter { it.isDigit() }.takeIf { it.length > 5 }
}
