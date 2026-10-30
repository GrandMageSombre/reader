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

        // 分类页的入口是 a[href*=/book/]（完整卡片只有 5 张，其余书名在 img@alt）
        assertTrue("分类书单应有 20+ 本（实际 ${list.size} 本）", list.size >= 20)
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("书名不该残留「在线阅读」后缀（实际 ${first.title}）", !first.title.endsWith("在线阅读"))
        // 协议相对地址 //m.qidian.com/... 必须被补成绝对地址
        assertTrue("链接必须是绝对地址（实际 ${first.url}）", first.url.startsWith("https://m.qidian.com/book/"))
        assertTrue("封面不能为空", first.cover.isNotBlank())
        assertTrue("封面也应是绝对地址（实际 ${first.cover}）", first.cover.startsWith("http"))
        assertEquals("qidian", first.sourceId)
        assertEquals("同一本书的重复入口应去重", list.size, list.map { it.url }.toSet().size)
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

    // ---------------- 番茄（现在是 API 发现源） ----------------

    @Test
    fun `番茄按书自带的官方分类归类`() {
        val cfg = BuiltinSources.all().first { it.id == FanqieSource.ID }
        assertTrue("番茄应配好分类", cfg.categories.isNotEmpty())

        // 第一项是「全部」，其余是番茄自己的官方分类（如「男生/都市」）
        assertEquals("第一项应是「全部」", FanqieSource.ALL_CATEGORY, cfg.categories.first().name)
        val official = cfg.categories.drop(1).map { it.name }
        assertTrue("应配置多个官方分类（实际 $official）", official.size >= 5)
        assertTrue(
            "官方分类应带「男生/」或「女生/」前缀（番茄的分类体系）: $official",
            official.all { it.startsWith("男生/") || it.startsWith("女生/") }
        )
        // 正文抓不到，必须靠换源
        assertTrue("番茄不应配置 HTML 正文规则（正文页是 JS 渲染的）", cfg.rules.content.isBlank())
    }

    @Test
    fun `书城入口是起点、番茄与手机直抓`() {
        val ids = BuiltinSources.STOREFRONT_IDS
        assertEquals("书城入口应有 3 个", 3, ids.size)
        assertTrue("必须包含起点", ids.contains("qidian"))
        assertTrue("必须包含番茄", ids.contains(FanqieSource.ID))
        // 手机直抓：完全在 App 内跑，用户不需要配 PC 接口
        assertTrue("必须包含手机直抓", ids.contains(DeviceSource.ID))
        // 「本地爬虫库」要 PC 上跑 serve，不该占书城入口，只在书源管理里可选
        assertTrue("本地爬虫库不应作为书城入口", !ids.contains(LocalLibrarySource.ID))
        assertTrue("采集站不应作为书城入口", !ids.contains("wangshu") && !ids.contains("xbiquwk"))
    }

    @Test
    fun `手机直抓书源支持直连能力标记`() {
        val src = DeviceSource(BuiltinSources.device())
        assertEquals("device", src.cfg.id)
        assertTrue("手机直抓要能被「本机抓取」识别", src is DeviceCrawlable)
    }

    @Test
    fun `本地爬虫库的书籍地址能在本地 id 与接口地址间来回解析`() {
        val src = LocalLibrarySource(BuiltinSources.localLibrary())
        assertEquals("local_spider", src.cfg.id)
        assertEquals("http://127.0.0.1:8765", src.cfg.baseUrl)
        assertEquals("qidian:1035420986", LocalLibrarySource.bookId("local:qidian:1035420986"))
        assertEquals("fanqie:6753575799414066190",
            LocalLibrarySource.bookId("local:fanqie:6753575799414066190"))
        // 不是本地地址时不要瞎猜
        assertEquals(null, LocalLibrarySource.bookId("https://www.qidian.com/book/1/"))
    }

    // ---------------- 笔尖中文（xbiquwk） ----------------

    @Test
    fun `笔尖中文分类书单能取到书名、链接、作者`() = runBlocking {
        val src = sourceOf("xbiquwk", mapOf("http://www.xbiquwk.com/list/" to "xbq_cat.html"))
        val list = src.list(com.modu.reader.data.Category("玄幻小说", "/list/1_1.html"), 1)

        assertTrue("分类书单应有 20+ 本（实际 ${list.size}）", list.size >= 20)
        val first = list.first()
        assertTrue("书名不能为空", first.title.isNotBlank())
        assertTrue("链接应是绝对地址（实际 ${first.url}）", first.url.startsWith("http://www.xbiquwk.com/"))
        assertTrue("作者应取到", first.author.isNotBlank())
    }

    @Test
    fun `笔尖中文详情页能取到书名、作者（剥前缀）、封面、简介`() = runBlocking {
        val src = sourceOf("xbiquwk", mapOf("http://www.xbiquwk.com/0_383/" to "xbq_book.html"))
        val b = src.detail(Book("xbiquwk", "http://www.xbiquwk.com/0_383/", "占位"))

        assertEquals("元尊", b.title)
        assertEquals("作者应剥掉「作 者:」前缀", "天蚕土豆", b.author)
        assertTrue("封面应取到", b.cover.isNotBlank())
        assertTrue("简介应取到（实际 ${b.intro.length} 字）", b.intro.length > 10)
    }

    @Test
    fun `笔尖中文目录能取到完整章节列表`() = runBlocking {
        val src = sourceOf("xbiquwk", mapOf("http://www.xbiquwk.com/0_383/" to "xbq_book.html"))
        val list = src.chapters(Book("xbiquwk", "http://www.xbiquwk.com/0_383/", "元尊"))

        assertTrue("目录应有大量章节（实际 ${list.size}）", list.size >= 500)
        assertEquals(0, list.first().index)
        assertTrue("章节链接应是绝对地址", list.first().url.startsWith("http://www.xbiquwk.com/0_383/"))
    }

    @Test
    fun `笔尖中文正文能取到成段文字`() = runBlocking {
        val src = sourceOf(
            "xbiquwk",
            mapOf(
                "http://www.xbiquwk.com/0_383/1596644.html" to "xbq_chap.html",
                "http://www.xbiquwk.com/0_383/" to "xbq_book.html"
            )
        )
        val text = src.content(
            Book("xbiquwk", "http://www.xbiquwk.com/0_383/", "元尊"),
            Chapter(0, "第一章", "http://www.xbiquwk.com/0_383/1596644.html")
        )

        assertTrue("正文应有内容（实际 ${text.length} 字）", text.length > 200)
    }

    @Test
    fun `笔尖中文搜索能取到结果表格`() = runBlocking {
        val src = sourceOf("xbiquwk", mapOf("http://www.xbiquwk.com/modules/article/search.php" to "xbq_search.html"))
        val list = src.search("斗罗", 1)

        assertTrue("搜索应有结果（实际 ${list.size}）", list.size >= 3)
        val first = list.first()
        assertTrue("书名应含关键字", first.title.contains("斗罗"))
        assertTrue("链接应是绝对地址（实际 ${first.url}）", first.url.startsWith("http://www.xbiquwk.com/"))
        assertTrue("作者应取到", first.author.isNotBlank())
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
            // 番茄走 JSON 接口，不依赖 HTML 规则；它靠 categories 取书
            if (cfg.id == FanqieSource.ID) {
                assertTrue("番茄必须有分类（走 api）", cfg.categories.isNotEmpty())
                return@forEach
            }
            // 手机直抓走 DeviceApi（WebView 取票 + JSON/HTML 自解析），也不用 CSS 规则
            if (cfg.id == DeviceSource.ID) {
                assertTrue("手机直抓必须有 baseUrl", cfg.baseUrl.startsWith("http"))
                return@forEach
            }
            // 本地爬虫库走自己的 JSON 接口（LocalLibrarySource），不用 CSS 规则
            if (cfg.id == LocalLibrarySource.ID) {
                assertTrue("本地爬虫库必须有 baseUrl（指向 PC 上的 serve）",
                    cfg.baseUrl.startsWith("http"))
                return@forEach
            }
            val r = cfg.rules
            assertTrue(
                "${cfg.name} 缺少 listItem 或 chapterList —— 至少要有一种取书方式",
                r.listItem.isNotBlank() || r.chapterList.isNotBlank()
            )
            if (r.content.isNotBlank()) {
                assertTrue("${cfg.name} 配了 content 就必须配 chapterList", r.chapterList.isNotBlank())
            }
        }
    }

    @Test
    fun `搜索页与分类页结构不同时用 search 规则覆盖`() {
        val ws = BuiltinSources.all().first { it.id == "wangshu" }
        // 望书网：分类页是卡片、搜索页是表格，必须分开配置
        assertTrue("望书网应配置 searchItem", ws.rules.searchItem.isNotBlank())
        assertTrue("望书网的 listItem 与 searchItem 应不同", ws.rules.listItem != ws.rules.searchItem)
        assertTrue("望书网应配 searchUrl", ws.rules.searchUrl.isNotBlank())
    }
}
