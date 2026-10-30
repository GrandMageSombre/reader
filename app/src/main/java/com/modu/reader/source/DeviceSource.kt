package com.modu.reader.source

import android.content.Context
import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter

/**
 * 「手机直抓」内置书源：**完全在 App 内**抓起点书库与番茄榜单，不需要 PC、不需要另接接口。
 *
 * 链路：
 *
 * ```
 * WebGate（隐藏 WebView 过 probe.js / 验证码）
 *     └─ DeviceHttp（自动带票，挑战页自动重试）
 *          └─ DeviceApi（起点书库/目录/正文、番茄榜单/目录/正文）
 *               └─ 本类（BookSource 实现）→ 书城 / 详情 / 阅读器
 * ```
 *
 * 与「本地爬虫库」（`LocalLibrarySource`）的区别：那个要 PC 上跑 `serve.py` 并填 IP；
 * 这个什么都不用配，代价是只抓你在看的那几本（全站铺量仍建议 PC 跑）。
 */
class DeviceSource(override val cfg: SourceConfig) : BookSource, DeviceCrawlable {

    private val context: Context get() = com.modu.reader.App.instance

    override suspend fun categories(): List<Category> =
        DeviceApi.QIDIAN_CATEGORIES.map { (name, chanId) -> Category(name, chanId) } +
            Category("番茄榜单", FANQIE_CATEGORY)

    override suspend fun list(category: Category, page: Int): List<Book> {
        if (category.url == FANQIE_CATEGORY) {
            return if (page > 1) emptyList() else DeviceApi.fanqieRank(context, cfg.id)
        }
        val chanId = category.url.filter { it.isDigit() }
        return if (chanId.isBlank()) {
            DeviceApi.qidianAll(context, page, cfg.id)
        } else {
            DeviceApi.qidianList(context, chanId, page, cfg.id)
        }
    }

    override suspend fun search(key: String, page: Int): List<Book> =
        DeviceApi.search(context, key, page, cfg.id)

    override suspend fun detail(book: Book): Book = when {
        book.url.contains("fanqienovel.com") ->
            DeviceApi.fanqieDetail(context, book.url, cfg.id)
        else -> DeviceApi.qidianDetail(context, book.url, cfg.id)
    }

    override suspend fun chapters(book: Book): List<Chapter> = when {
        book.url.contains("fanqienovel.com") -> DeviceApi.fanqieChapters(context, book.url)
        else -> DeviceApi.qidianChapters(context, book.url)
    }

    override suspend fun content(book: Book, chapter: Chapter): String = when {
        book.url.contains("fanqienovel.com") -> {
            val text = DeviceApi.fanqieText(context, chapter.url, book.url)
            if (text.isBlank()) throw IllegalStateException("番茄这一章没取到正文（可能被风控）")
            text
        }
        else -> {
            val (text, preview) = DeviceApi.qidianText(context, chapter.url, book.url)
            if (text.isBlank()) throw IllegalStateException("起点这一章没取到正文（可能被风控）")
            if (preview) "$text\n\n（本章为站方试读，完整正文可在换源里读）" else text
        }
    }

    companion object {
        const val ID = "device"
        private const val FANQIE_CATEGORY = "fanqie-rank"
    }
}
