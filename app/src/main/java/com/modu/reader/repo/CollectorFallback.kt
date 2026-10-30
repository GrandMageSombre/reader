package com.modu.reader.repo

import com.modu.reader.data.Book
import com.modu.reader.data.Chapter
import com.modu.reader.source.Paywall
import kotlinx.coroutines.CancellationException

/**
 * 付费章兜底：在采集站里「按书名搜索 → 用章名对齐 → 取正文」。
 *
 * 拆成独立对象是为了能在 JVM 单测里用假站点跑通整条链路（Repo 里有 Db/App，不好造）。
 * 调用方把每个采集站包成 [Site]（三个挂起函数），这里只负责编排与取舍。
 */
internal object CollectorFallback {

    /** 一个候选站点：搜索 / 目录 / 正文各一个挂起函数 */
    data class Site(
        val name: String,
        val search: suspend (key: String) -> List<Book>,
        val chapters: suspend (Book) -> List<Chapter>,
        val content: suspend (Book, Chapter) -> String
    )

    const val MAX_SITES = 3
    const val MAX_BOOKS_PER_SITE = 2

    /**
     * @param positionRatio 目标章在全书中的相对位置（0f~1f）；负数表示不纠偏。
     *        用比例而不是绝对下标，是因为各站总章数不同（起点 3386 / 采集站 3418）。
     * @param minChars  正文少于此长度视为没抓到（防采集站返回「本章未完」占位页）
     * @return 命中的正文；全都没命中返回 null
     */
    suspend fun resolve(
        bookTitle: String,
        chapterTitle: String,
        positionRatio: Float,
        sites: List<Site>,
        minChars: Int = Paywall.SUSPICIOUS_CHARS,
        maxSites: Int = MAX_SITES,
        maxBooksPerSite: Int = MAX_BOOKS_PER_SITE
    ): String? {
        for (site in sites.take(maxSites)) {
            val hits = try {
                site.search(bookTitle)
                    .filter { Paywall.sameBook(it.title, bookTitle) }
                    .take(maxBooksPerSite)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                continue
            }
            for (hit in hits) {
                val list = try {
                    site.chapters(hit)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    continue
                }
                if (list.isEmpty()) continue
                val hint = if (positionRatio >= 0f) {
                    (positionRatio * list.size).toInt().coerceIn(0, list.size - 1)
                } else {
                    -1
                }
                val idx = Paywall.findChapter(list.map { it.title }, chapterTitle, hint)
                if (idx < 0) continue
                val body = try {
                    site.content(hit, list[idx])
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    continue
                }
                if (body.isNotBlank() && body.length > minChars) return body
            }
        }
        return null
    }
}
