package com.modu.reader.repo

import com.modu.reader.data.Book
import com.modu.reader.data.Chapter
import com.modu.reader.source.Paywall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 付费章兜底链路：搜索 → 书目过滤 → 章名对齐 → 取正文。
 *
 * 用假站点跑通编排，不碰网络也不碰数据库；对齐规则本身在 PaywallAndGlyphTest 里压过。
 */
class CollectorFallbackTest {

    private fun book(title: String, url: String = "http://site/book/1") =
        Book("fake", url, title)

    private fun chapters(vararg titles: String) =
        titles.mapIndexed { i, t -> Chapter(i, t, "http://site/ch/$i") }

    private fun site(
        name: String,
        books: List<Book>,
        toc: List<Chapter>,
        body: (Chapter) -> String = { "正文".repeat(200) },
        failOnSearch: Boolean = false
    ) = CollectorFallback.Site(
        name = name,
        search = {
            if (failOnSearch) throw IllegalStateException("站点挂了")
            books
        },
        chapters = { toc },
        content = { _, ch -> body(ch) }
    )

    @Test
    fun `第一个站点命中就返回它的正文`() = runBlocking {
        val toc = chapters("第一章 剧本不对", "第二章 一定要考武科！")
        val text = CollectorFallback.resolve(
            bookTitle = "全球高武",
            chapterTitle = "第2章 一定要考武科！",
            positionRatio = 0.5f,
            sites = listOf(site("望书网", listOf(book("全球高武")), toc)),
            minChars = 10
        )
        assertEquals("正文".repeat(200), text)
    }

    @Test
    fun `前一个站点报错就落到下一个`() = runBlocking {
        val toc = chapters("第一章 剧本不对")
        val text = CollectorFallback.resolve(
            bookTitle = "全球高武",
            chapterTitle = "第一章 剧本不对",
            positionRatio = 0f,
            sites = listOf(
                site("坏站点", listOf(book("全球高武")), toc, failOnSearch = true),
                site("好站点", listOf(book("全球高武")), toc, body = { "备用正文".repeat(100) })
            ),
            minChars = 10
        )
        assertEquals("备用正文".repeat(100), text)
    }

    @Test
    fun `书名对不上的搜索结果直接跳过`() = runBlocking {
        val toc = chapters("第一章 剧本不对")
        val text = CollectorFallback.resolve(
            bookTitle = "全球高武",
            chapterTitle = "第一章 剧本不对",
            positionRatio = 0f,
            sites = listOf(
                site("别站", listOf(book("全球高武之死气面板")), toc),
                site("本站", listOf(book("全球高武")), toc, body = { "正确正文".repeat(100) })
            ),
            minChars = 10
        )
        assertEquals("正确正文".repeat(100), text)
    }

    @Test
    fun `正文太短（占位页）就继续找下一本`() = runBlocking {
        val toc = chapters("第一章 剧本不对")
        val text = CollectorFallback.resolve(
            bookTitle = "全球高武",
            chapterTitle = "第一章 剧本不对",
            positionRatio = 0f,
            sites = listOf(
                site("占位站", listOf(book("全球高武")), toc, body = { "本章未完，请稍后刷新" }),
                site("正常站", listOf(book("全球高武")), toc, body = { "正常正文".repeat(100) })
            ),
            minChars = 50
        )
        assertEquals("正常正文".repeat(100), text)
    }

    @Test
    fun `同名章按相对位置纠偏`() = runBlocking {
        // 采集站目录里「感谢」出现两次（idx=1 与 idx=3）；位置比例 0.9 应取后一个
        val toc = chapters("第一章 开局", "感谢", "第二章 出发", "感谢")
        val text = CollectorFallback.resolve(
            bookTitle = "某书",
            chapterTitle = "感谢",
            positionRatio = 0.9f,
            sites = listOf(
                CollectorFallback.Site(
                    name = "站",
                    search = { listOf(book("某书")) },
                    chapters = { toc },
                    content = { _, ch -> "章${ch.index}的正文" + "字".repeat(300) }
                )
            ),
            minChars = 10
        )
        assertTrue("应取到 idx=3 的那个「感谢」，实际: ${text?.take(8)}", text!!.startsWith("章3"))
    }

    @Test
    fun `都没有命中返回 null`() = runBlocking {
        val toc = chapters("第一章 剧本不对")
        val text = CollectorFallback.resolve(
            bookTitle = "全球高武",
            chapterTitle = "第九章 不存在的章",
            positionRatio = 0.8f,
            sites = listOf(site("站", listOf(book("全球高武")), toc)),
            minChars = 10
        )
        assertNull(text)
    }

    @Test
    fun `最多只问 maxSites 个站点`() = runBlocking {
        var asked = 0
        val many = (1..5).map { i ->
            CollectorFallback.Site(
                name = "站$i",
                search = { asked++; emptyList() },
                chapters = { emptyList() },
                content = { _, _ -> "" }
            )
        }
        CollectorFallback.resolve("某书", "第一章 x", 0f, many, maxSites = 2)
        assertEquals(2, asked)
    }

}
