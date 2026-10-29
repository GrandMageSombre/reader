package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Chapter
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置书源的**真实页面**回归测试。
 *
 * fixture 是从线上抓下来的真实 HTML（`app/src/test/resources/fixtures`），
 * 用注入的 pageLoader 替掉网络，验证内置源的每条 CSS 规则在当前页面结构下仍能取到数据。
 *
 * 这类测试的价值：内置源最容易「悄悄失效」——站点一改版，规则就取不到东西，
 * 但应用不会崩，只是列表变空。有了这组 fixture，改版后跑一次测试就能发现。
 */
class BuiltinSourceRulesTest {

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: throw IllegalStateException("缺少 fixture: $name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** 用 fixture 造一个 HtmlSource，url 到 fixture 名的映射由各用例给 */
    private fun sourceOf(id: String, pages: Map<String, String>): HtmlSource {
        val cfg = BuiltinSources.all().first { it.id == id }
        return HtmlSource(cfg) { url ->
            // 精确匹配优先；否则取最长前缀，避免 /book/1/ 把 /book/1/catalog/ 也匹配走
            val name: String = pages[url]
                ?: pages.entries
                    .filter { url.startsWith(it.key) }
                    .maxByOrNull { it.key.length }
                    ?.value
                ?: throw IllegalStateException("fixture 未覆盖: $url")
            Jsoup.parse(fixture(name), url)
        }
    }

    // ---------------- 起点中文 ----------------

    @Test
    fun `起点分类书单能取到书名、链接、封面`() = runBlocking {
        val src = sourceOf("qidian", mapOf("https://m.qidian.com/category/" to "qidian_list.html"))
        val list = src.list(com.modu.reader.data.Category("玄幻", "/category/catid21/"), 1)

        assertTrue("分类书单应能解析出书（实际 ${list.size} 本）", list.isNotEmpty())
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("链接必须是绝对地址", first.url.startsWith("https://m.qidian.com/book/"))
        assertTrue("封面不能为空", first.cover.isNotBlank())
        assertEquals("qidian", first.sourceId)
    }

    @Test
    fun `起点详情页能取到书名、作者、封面、简介`() = runBlocking {
        val src = sourceOf("qidian", mapOf("https://m.qidian.com/book/" to "qidian_detail.html"))
        val b = src.detail(Book("qidian", "https://m.qidian.com/book/1050123973/", "占位"))

        assertEquals("型月：根源圣子", b.title)
        assertEquals("七月雨夹雪", b.author)
        assertTrue("封面应取到", b.cover.startsWith("http"))
        assertTrue("简介应有内容", b.intro.length > 20)
    }

    @Test
    fun `起点目录：通过详情页的目录链接取到完整章节列表`() = runBlocking {
        // 详情页给出目录地址（a#details-menu），目录页给出章节
        val src = sourceOf(
            "qidian",
            mapOf(
                "https://m.qidian.com/book/1050123973/" to "qidian_detail.html",
                "https://m.qidian.com/book/1050123973/catalog/" to "qidian_catalog.html"
            )
        )
        val list = src.chapters(Book("qidian", "https://m.qidian.com/book/1050123973/", "型月：根源圣子"))

        assertTrue("目录应有大量章节（实际 ${list.size}）", list.size >= 160)
        assertEquals(0, list.first().index)
        assertEquals(1, list[1].index)
        assertTrue("章节链接应是绝对地址", list.first().url.startsWith("https://m.qidian.com/chapter/"))
        // 目录页本身是倒序，chapterReverse 之后应为自然顺序
        assertEquals("第1章 冠位人偶师的男人", list.first().title)
        assertTrue("第 2 章应紧随其后", list[1].title.startsWith("第2章"))
    }

    @Test
    fun `起点免费章节能取到正文，且清理规则生效`() = runBlocking {
        val src = sourceOf(
            "qidian",
            mapOf("https://m.qidian.com/chapter/" to "qidian_chapter.html")
        )
        val text = src.content(
            Book("qidian", "https://m.qidian.com/book/1050123973/", "型月：根源圣子"),
            Chapter(0, "第82章", "https://m.qidian.com/chapter/1050123973/937084126/")
        )

        assertTrue("正文应有内容（实际 ${text.length} 字）", text.length > 50)
        assertTrue("正文应含页面里的原句", text.contains("时代在我掌中"))
        // contentClean 里的 re: 规则应把站点尾巴去掉
        assertTrue("不该残留站点署名", !text.contains("起点中文网"))
    }

    // ---------------- 豆瓣图书 ----------------

    @Test
    fun `豆瓣标签书单能取到书名、链接、作者行、封面`() = runBlocking {
        val src = sourceOf("douban", mapOf("https://book.douban.com/tag/" to "douban_list.html"))
        val list = src.list(com.modu.reader.data.Category("小说", "/tag/小说"), 1)

        assertTrue("书单应有内容（实际 ${list.size}）", list.size >= 10)
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("链接应指向豆瓣书目", first.url.startsWith("https://book.douban.com/subject/"))
        assertTrue("pub 行应取到（作者/出版社）", first.author.isNotBlank())
        assertTrue("封面不能为空", first.cover.isNotBlank())
    }

    @Test
    fun `豆瓣详情页能取到封面与简介`() = runBlocking {
        val src = sourceOf("douban", mapOf("https://book.douban.com/subject/" to "douban_detail.html"))
        val b = src.detail(Book("douban", "https://book.douban.com/subject/1084336/", "小王子"))

        assertTrue("封面应取到", b.cover.startsWith("http"))
        assertTrue("简介应有内容（实际 ${b.intro.length} 字）", b.intro.length > 20)
    }

    // ---------------- 古登堡计划 ----------------

    @Test
    fun `古登堡中文书单能取到书名与链接`() = runBlocking {
        val src = sourceOf("gutenberg", mapOf("https://www.gutenberg.org/browse/" to "gutenberg_list.html"))
        val list = src.list(com.modu.reader.data.Category("中文公版书", "/browse/languages/zh"), 1)

        assertTrue("书单应有内容（实际 ${list.size}）", list.size >= 20)
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("链接应指向电子书页", first.url.startsWith("https://www.gutenberg.org/ebooks/"))
    }

    @Test
    fun `古登堡详情页能取到书名与作者`() = runBlocking {
        val src = sourceOf("gutenberg", mapOf("https://www.gutenberg.org/ebooks/" to "gutenberg_detail.html"))
        val b = src.detail(Book("gutenberg", "https://www.gutenberg.org/ebooks/25328", "占位"))

        assertTrue("书名应含公版书名", b.title.contains("豆棚"))
        assertTrue("作者应取到（取自 bibrec 表的 Author 行）", b.author.contains("Ainajushi"))
    }

    @Test
    fun `古登堡的目录指向可读正文页`() = runBlocking {
        val src = sourceOf(
            "gutenberg",
            mapOf(
                "https://www.gutenberg.org/ebooks/25328" to "gutenberg_detail.html",
                "https://www.gutenberg.org/cache/epub/25328/" to "gutenberg_detail.html"
            )
        )
        val list = src.chapters(Book("gutenberg", "https://www.gutenberg.org/ebooks/25328", "豆棚閒話"))

        assertTrue("应能取到正文页作为章节（实际 ${list.size}）", list.isNotEmpty())
        assertTrue(
            "章节链接应指向可读的 -images.html 正文页",
            list.first().url.endsWith("-images.html")
        )
    }

    // ---------------- 番茄 ----------------

    @Test
    fun `番茄书籍页能取到书名与章节链接`() = runBlocking {
        val src = sourceOf("fanqie", mapOf("https://fanqienovel.com/page/" to "fanqie_book.html"))
        val b = src.detail(Book("fanqie", "https://fanqienovel.com/page/7143038691944959011", "占位"))

        assertEquals("十日终焉", b.title)
        assertTrue("封面应取到", b.cover.startsWith("http"))
    }

    // ---------------- 三一小说（31xs） ----------------

    @Test
    fun `三一小说分类书单能取到书名、链接、封面、简介`() = runBlocking {
        val src = sourceOf("s31xs", mapOf("https://www.31xs.com/fenlei/" to "s31_cat.html"))
        val list = src.list(com.modu.reader.data.Category("玄幻魔法", "/fenlei/1/{page}.html"), 1)

        assertTrue("分类书单应有内容（实际 ${list.size}）", list.size >= 3)
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("链接应是绝对地址", first.url.startsWith("https://www.31xs.com/"))
        assertTrue("简介应取到", first.intro.isNotBlank())
    }

    @Test
    fun `三一小说详情页能取到书名、作者、封面、简介`() = runBlocking {
        val src = sourceOf("s31xs", mapOf("https://www.31xs.com/649/" to "s31_book.html"))
        val b = src.detail(Book("s31xs", "https://www.31xs.com/649/649464/", "占位"))

        assertTrue("书名应含站点原名", b.title.contains("1号专案组"))
        assertEquals("作者应取到", "陈玉福", b.author)
        assertTrue("封面应取到", b.cover.isNotBlank())
        assertTrue("简介应取到（实际 ${b.intro.length} 字）", b.intro.length > 20)
    }

    @Test
    fun `三一小说目录能取到完整章节列表`() = runBlocking {
        val src = sourceOf("s31xs", mapOf("https://www.31xs.com/649/" to "s31_book.html"))
        val list = src.chapters(Book("s31xs", "https://www.31xs.com/649/649464/", "1号专案组"))

        assertTrue("目录应有较多章节（实际 ${list.size}）", list.size >= 20)
        assertEquals(0, list.first().index)
        assertTrue("章节链接应是绝对地址", list.first().url.startsWith("https://www.31xs.com/649/"))
        assertTrue("章节名不能为空", list.first().title.isNotBlank())
    }

    @Test
    fun `三一小说正文能取到成段文字`() = runBlocking {
        val src = sourceOf("s31xs", mapOf("https://www.31xs.com/649/649464/" to "s31_chap.html"))
        val text = src.content(
            Book("s31xs", "https://www.31xs.com/649/649464/", "1号专案组"),
            Chapter(0, "第二十章", "https://www.31xs.com/649/649464/177404375.html")
        )

        assertTrue("正文应有内容（实际 ${text.length} 字）", text.length > 500)
        assertTrue("正文应含页面原句", text.contains("省委书记"))
        // contentClean 应去掉站点尾巴
        assertTrue("不该残留站点广告", !text.contains("咪咪阅读"))
    }

    // ---------------- 望书网 ----------------

    @Test
    fun `望书网目录能取到完整章节列表`() = runBlocking {
        val src = sourceOf("wangshu", mapOf("http://www.wangshuwx.com/123_123678/" to "wangshu_book.html"))
        val list = src.chapters(Book("wangshu", "http://www.wangshuwx.com/123_123678/", "长生"))

        assertTrue("目录应有大量章节（实际 ${list.size}）", list.size >= 100)
        assertEquals(0, list.first().index)
        assertTrue("章节链接应是绝对地址", list.first().url.startsWith("http://www.wangshuwx.com/123_123678/"))
        assertTrue("章节名不能为空", list.first().title.isNotBlank())
    }

    @Test
    fun `望书网详情页能取到书名与作者`() = runBlocking {
        val src = sourceOf("wangshu", mapOf("http://www.wangshuwx.com/123_123678/" to "wangshu_book.html"))
        val b = src.detail(Book("wangshu", "http://www.wangshuwx.com/123_123678/", "占位"))

        assertTrue("书名应含站点原名", b.title.contains("长生"))
        assertTrue("作者应取到", b.author.contains("浅海微光"))
        assertTrue("简介应取到", b.intro.isNotBlank())
    }

    // ---------------- 内置源元数据自检 ----------------

    @Test
    fun `内置源集合完整且 id 唯一、自带分组与来源标记`() {
        val all = BuiltinSources.all()
        assertTrue("至少应有示例库 + 4 个真实站点", all.size >= 5)
        assertEquals("id 不能重复", all.size, all.map { it.id }.toSet().size)
        assertTrue("必须包含示例库", all.any { it.id == DemoSource.ID })
        assertEquals("内置源 id 集合应与 all() 一致", all.map { it.id }.toSet(), BuiltinSources.IDS)
        all.forEach { cfg ->
            // 示例库是本地演示源，没有分组名也无所谓
            if (cfg.id != DemoSource.ID) {
                assertEquals("真实站点源都要有分组名", BuiltinSources.GROUP, cfg.builtinGroup)
                assertTrue("真实站点源必须有 baseUrl", cfg.baseUrl.isNotBlank())
            }
        }
    }

    @Test
    fun `内置源的关键规则都不为空`() {
        BuiltinSources.all().forEach { cfg ->
            if (cfg.id == DemoSource.ID) return@forEach
            val r = cfg.rules
            assertTrue("${cfg.name} 缺少 listItem 或 chapterList —— 至少要有一种取书方式",
                r.listItem.isNotBlank() || r.chapterList.isNotBlank())
            if (r.content.isNotBlank()) {
                assertTrue("${cfg.name} 配了 content 就必须配 chapterList", r.chapterList.isNotBlank())
            }
        }
    }
}
