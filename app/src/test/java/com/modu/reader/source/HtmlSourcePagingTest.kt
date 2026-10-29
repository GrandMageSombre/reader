package com.modu.reader.source

import com.modu.reader.data.Book
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * HtmlSource 抓全路径的离线回归测试。
 *
 * 用构造函数注入的假页面（pageLoader）替掉网络，覆盖四条通道：
 * 目录分页模板 / 目录「下一页」 / 目录独立地址 / 正文多容器与正文分页。
 */
class HtmlSourcePagingTest {

    private val book = Book(sourceId = "t", url = "https://site.com/book/9.html", title = "测试书")

    private fun source(rules: SourceRules, pages: Map<String, String>): HtmlSource {
        val cfg = SourceConfig(id = "t", name = "测试源", baseUrl = "https://site.com", rules = rules)
        return HtmlSource(cfg) { url ->
            val html = pages[url] ?: throw IllegalStateException("没有这个页面: $url")
            Jsoup.parse(html, url)
        }
    }

    /** 造一个目录页：n 个章节链接，序号从 start 开始，便于让不同页的链接互不重复 */
    private fun catalog(names: List<String>, start: Int = 1, next: String = ""): String {
        val links = names.mapIndexed { i, n ->
            "<a href=\"/c/${start + i}.html\">$n</a>"
        }.joinToString("")
        return "<div id=\"list\">$links</div>$next"
    }

    private val chapterRules = SourceRules(
        chapterList = "#list a",
        chapterName = "text",
        chapterLink = "href"
    )

    @Test
    fun `目录分页模板：翻到空页为止并把各页章节按顺序拼起来`() = runBlocking {
        val pages = mapOf(
            "https://site.com/book/9/1.html" to catalog(listOf("一", "二")),
            "https://site.com/book/9/2.html" to catalog(listOf("三", "四"), start = 3),
            "https://site.com/book/9/3.html" to catalog(emptyList())
        )
        val src = source(chapterRules.copy(chapterUrl = "/book/9/{page}.html"), pages)

        val list = src.chapters(book)

        assertEquals(4, list.size)
        assertEquals(listOf("一", "二", "三", "四"), list.map { it.title })
        assertEquals(listOf(0, 1, 2, 3), list.map { it.index })
        assertEquals("https://site.com/c/4.html", list.last().url)
    }

    @Test
    fun `目录下一页链接：跟着 chapterNext 翻页，回到已访问页就停`() = runBlocking {
        val pages = mapOf(
            "https://site.com/book/9/1.html" to
                catalog(listOf("一"), next = "<a class=\"next\" href=\"/book/9/2.html\">下一页</a>"),
            "https://site.com/book/9/2.html" to
                catalog(listOf("二"), start = 2, next = "<a class=\"next\" href=\"/book/9/1.html\">下一页</a>")
        )
        val src = source(
            chapterRules.copy(chapterUrl = "/book/9/1.html", chapterNext = "a.next@href"),
            pages
        )

        val list = src.chapters(book)

        assertEquals(listOf("一", "二"), list.map { it.title })
    }

    @Test
    fun `目录不在详情页：从 chapterUrl 指定的地址取目录`() = runBlocking {
        val pages = mapOf(
            "https://site.com/book/9/catalog.html" to catalog(listOf("一", "二")),
            "https://site.com/book/9.html" to "<h1>详情页没有目录</h1>"
        )
        val src = source(chapterRules.copy(chapterUrl = "/book/9/catalog.html"), pages)

        val list = src.chapters(book)

        assertEquals(2, list.size)
        assertEquals("https://site.com/c/1.html", list.first().url)
    }

    @Test
    fun `chapterReverse 作用于拼接后的全量目录`() = runBlocking {
        val pages = mapOf(
            "https://site.com/book/9/1.html" to catalog(listOf("一", "二")),
            "https://site.com/book/9/2.html" to catalog(listOf("三"), start = 3)
        )
        val src = source(
            chapterRules.copy(chapterUrl = "/book/9/{page}.html", chapterReverse = true),
            pages
        )

        val list = src.chapters(book)

        assertEquals(listOf("三", "二", "一"), list.map { it.title })
        assertEquals(listOf(0, 1, 2), list.map { it.index })
    }

    @Test
    fun `目录后续页请求失败：保留已抓到的目录`() = runBlocking {
        val pages = mapOf(
            "https://site.com/book/9/1.html" to catalog(listOf("一", "二"))
            // 第 2 页不存在 → 请求失败，应停止翻页而不是整本书的目录都拿不到
        )
        val src = source(chapterRules.copy(chapterUrl = "/book/9/{page}.html"), pages)

        val list = src.chapters(book)

        assertEquals(listOf("一", "二"), list.map { it.title })
    }

    @Test
    fun `目录第一页失败：抛错让上层回落缓存`() {
        val src = source(chapterRules, emptyMap())

        assertThrows(IllegalStateException::class.java) {
            runBlocking { src.chapters(book) }
        }
    }

    private fun contentSource(html: String, rules: SourceRules): HtmlSource =
        source(rules, mapOf("https://site.com/c/1.html" to html))

    private fun chapter1() = com.modu.reader.data.Chapter(0, "第一章", "https://site.com/c/1.html")

    @Test
    fun `正文多容器：按文档顺序合并`() = runBlocking {
        val html = "<div id=\"content\"><p>甲</p></div><div id=\"content2\"><p>乙</p></div>"
        val src = contentSource(html, SourceRules(content = "#content, #content2"))

        assertEquals("甲\n乙", src.content(book, chapter1()))
    }

    @Test
    fun `正文嵌套命中：内层不重复计入`() = runBlocking {
        val html = "<div class=\"wrap\"><div class=\"content\"><p>X</p></div></div>"
        val src = contentSource(html, SourceRules(content = ".wrap, .content"))

        assertEquals("X", src.content(book, chapter1()))
    }

    @Test
    fun `正文分页：按 contentNext 把多页拼完整`() = runBlocking {
        val pages = mapOf(
            "https://site.com/c/1.html" to
                "<div id=\"content\"><p>上半</p></div><a class=\"next\" href=\"/c/1_2.html\">下一页</a>",
            "https://site.com/c/1_2.html" to "<div id=\"content\"><p>下半</p></div>"
        )
        val src = source(SourceRules(content = "#content", contentNext = "a.next@href"), pages)

        assertEquals("上半\n下半", src.content(book, chapter1()))
    }

    @Test
    fun `正文后续页失败：保留已抓到的部分`() = runBlocking {
        val pages = mapOf(
            "https://site.com/c/1.html" to
                "<div id=\"content\"><p>上半</p></div><a class=\"next\" href=\"/c/1_2.html\">下一页</a>"
        )
        val src = source(SourceRules(content = "#content", contentNext = "a.next@href"), pages)

        assertEquals("上半", src.content(book, chapter1()))
    }

    @Test
    fun `正文首页匹配不到容器：明确报错`() {
        val src = contentSource("<div id=\"other\">没有正文</div>", SourceRules(content = "#content"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { src.content(book, chapter1()) }
        }
    }

    @Test
    fun `正文正则清理对拼接后的每一行生效`() = runBlocking {
        val pages = mapOf(
            "https://site.com/c/1.html" to
                "<div id=\"content\"><p>正文一</p><p>本章未完，请点击下一页</p></div>" +
                "<a class=\"next\" href=\"/c/1_2.html\">下一页</a>",
            "https://site.com/c/1_2.html" to
                "<div id=\"content\"><p>正文二</p><p>本章未完，请点击下一页</p></div>"
        )
        val rules = SourceRules(
            content = "#content",
            contentNext = "a.next@href",
            contentClean = listOf("re:本章未完")
        )
        val src = source(rules, pages)

        assertEquals("正文一\n正文二", src.content(book, chapter1()))
    }
}
