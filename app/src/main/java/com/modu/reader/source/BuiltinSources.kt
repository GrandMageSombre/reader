package com.modu.reader.source

import com.modu.reader.data.Category

/**
 * 内置书源。所有规则都用真实页面核对过（fixture 见
 * `app/src/test/resources/fixtures`，回归测试见 `BuiltinSourceRulesTest`）。
 *
 * 边界说明：本项目**不做**任何反爬绕过与付费墙处理。
 * 所以这里只收录「公开页面能直接读到」的内容，取不到的字段宁可留空。
 *
 * **内置 ≠ 默认开启**：`DEFAULT_ON` 里的是「开箱即用」的源，其余内置源默认关闭，
 * 由用户在「设置 → 书源管理」里自己勾选启用（用户可自选）。
 *
 * | 内置源 | 能拿到 | 拿不到 | 默认 |
 * |--------|--------|--------|------|
 * | 示例书库 | 本地公版节选，全部离线 | — | ✅ 开 |
 * | 三一小说（31xs） | 分类书单、完整目录、正文全文 | — | ✅ 开 |
 * | 望书网 | 分类书单、目录、正文、站内搜索 | — | 关 |
 * | 起点中文（移动版） | 分类书单、详情、完整目录、免费章节正文 | VIP 正文、站内搜索 | 关 |
 * | 豆瓣图书 | 标签书单、书名/作者/出版社/评分/简介/封面 | 正文（豆瓣不提供） | 关 |
 * | 古登堡计划 | 中文公版书全文 | — | ✅ 开 |
 * | 番茄小说 | 书籍页的书名/作者/封面/状态 | 分类书单、正文 | 关 |
 * | 微信读书 | 分类书单里的书名与封面 | 目录、正文 | 关 |
 */
object BuiltinSources {

    /** 内置源统一分组名 */
    const val GROUP = "内置"

    /**
     * 默认启用的内置源。
     *
     * 只放「不涉及第三方聚合站」的源（本地示例库 + 公版书），
     * 其余一律默认关闭，让用户自己选——第三方聚合站的可用性与版权状态差异很大。
     */
    val DEFAULT_ON: Set<String> = setOf("demo", "s31xs", "gutenberg")

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    // ---------------- 起点中文（移动版 m.qidian.com） ----------------

    private fun qidian() = SourceConfig(
        id = "qidian",
        name = "起点中文",
        baseUrl = "https://m.qidian.com",
        builtinGroup = GROUP,
        note = "起点移动版公开页：按分类发现书（书名/作者/简介/封面）+ 免费章节正文。" +
            "VIP 章节抓不到——用「换源」到第三方书源读全本。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://m.qidian.com/"),
        categories = listOf(
            // catid 来自 https://m.qidian.com/category/ 的导航，实测这些 id 都有书单
            Category("玄幻", "/category/catid21/"),
            Category("奇幻", "/category/catid1/"),
            Category("武侠", "/category/catid2/"),
            Category("仙侠", "/category/catid22/"),
            Category("都市", "/category/catid4/"),
            Category("历史", "/category/catid5/"),
            Category("军事", "/category/catid6/"),
            Category("游戏", "/category/catid7/"),
            Category("体育", "/category/catid8/"),
            Category("科幻", "/category/catid9/"),
            Category("悬疑灵异", "/category/catid10/"),
            Category("轻小说", "/category/catid12/"),
            Category("现实", "/category/catid15/")
        ),
        rules = SourceRules(
            // 分类页的书卡：li 带 _bookItem_ 类，书名在 figcaption>h2，作者在紧随其后的 p
            listItem = "li[class*=_bookItem_]",
            listName = "h2",
            listLink = "a@href",
            listAuthor = "p",
            listCover = "img@data-src",
            // 详情页有完整的 og:novel:* 元信息
            detailName = "h1",
            detailAuthor = "meta[property='og:novel:author']@content",
            detailCover = "meta[property='og:image']@content",
            detailIntro = "meta[property='og:description']@content",
            // 目录不在详情页：#details-menu 指向 /book/{id}/catalog/
            chapterUrl = "a#details-menu@href",
            // 目录项：正文卷里每条是 <a class="_chapterItem_…"><div><h2>第N章</h2>，
            // 页面顶部另有一个 h3 的「最新章节」入口，用类名把两者区分开
            chapterList = "a[class*=_chapterItem_]",
            chapterName = "h2",
            chapterLink = "href",
            // 目录页的正文卷列表本身就是正序（第1章在前），不需要反转
            chapterReverse = false,
            // 正文容器是 <main class="content">，注意 main 标签自己就带 content 类
            content = "main.content",
            contentClean = listOf(
                "script",
                "style",
                "re:^\\s*本章未完.*$",
                "re:^\\s*求(推荐|月)票.*$",
                "re:^\\s*（本章完）\\s*$",
                "re:^\\s*起点中文网.*$",
                "re:^\\s*此章节非限免章节.*$"
            )
        )
    )

    // ---------------- 豆瓣图书 ----------------

    private fun douban() = SourceConfig(
        id = "douban",
        name = "豆瓣图书",
        baseUrl = "https://book.douban.com",
        builtinGroup = GROUP,
        note = "豆瓣图书：按标签浏览书目，书名、作者、出版社、评分、简介、封面齐全。" +
            "豆瓣不提供正文，用于查书与找书。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://book.douban.com/"),
        categories = listOf(
            Category("小说", "/tag/小说"),
            Category("外国文学", "/tag/外国文学"),
            Category("中国文学", "/tag/中国文学"),
            Category("随笔", "/tag/随笔"),
            Category("科幻", "/tag/科幻"),
            Category("推理", "/tag/推理"),
            Category("历史", "/tag/历史"),
            Category("哲学", "/tag/哲学")
        ),
        rules = SourceRules(
            listItem = "li.subject-item",
            listName = "h2 a@title",
            listLink = "h2 a@href",
            // pub 里是「作者 / 出版社 / 日期 / 价格」，整行给作者位足够用
            listAuthor = "div.pub",
            listCover = "div.pic img@src",
            listIntro = "p.intro",
            detailName = "span[property='v:itemreviewed']",
            detailCover = "meta[itemprop='image']@content",
            detailIntro = "meta[property='og:description']@content"
        )
    )

    // ---------------- 古登堡计划 ----------------

    private fun gutenberg() = SourceConfig(
        id = "gutenberg",
        name = "古登堡计划",
        baseUrl = "https://www.gutenberg.org",
        builtinGroup = GROUP,
        note = "Project Gutenberg 中文公版书：可直接读全文，版权干净。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://www.gutenberg.org/"),
        categories = listOf(
            Category("中文公版书", "/browse/languages/zh")
        ),
        rules = SourceRules(
            // 列表页的条目是 li.pgdbetext，书名就在 <a> 的文本里，作者是分组的 h2
            listItem = "li.pgdbetext",
            listName = "a",
            listLink = "a@href",
            listAuthor = "h2 a",
            detailName = "h1",
            // 作者在 bibliographic record 表的 Author 行
            detailAuthor = "table.bibrec tr:has(th:contains(Author)) td a",
            detailIntro = "table.bibrec",
            // 古登堡一本书就是一个可读 HTML：目录项 = 那个正文页，用正文页标题当章节名
            chapterUrl = "a[href*='/cache/epub/'][href$='-images.html']@href",
            chapterList = "a[href*='/cache/epub/'][href$='-images.html']",
            chapterName = "a",
            chapterLink = "href",
            // 正文容器：古登堡把全文放在 <body> 里，靠 contentClean 去掉页头页脚
            content = "body",
            contentClean = listOf(
                "script",
                "style",
                "header",
                "footer",
                "#pg-header",
                "#pg-footer",
                ".pg-boilerplate",
                "#pg-machine-header",
                "nav"
            )
        )
    )

    // ---------------- 番茄小说 ----------------

    /**
     * 番茄的列表只能通过 JSON 接口拿（HTML 首页的书全在 `__NEXT_DATA__` 里、分页是 JS）。
     *
     * 实测：`/api/rank/list?type=N` 只有 type=0/1/2 有数据，每类 7~10 本，`page` 参数无效。
     * 所以「分类」三项就对应这三个接口，无翻页——这也是为什么要配换源。
     */
    private fun fanqie() = SourceConfig(
        id = FanqieSource.ID,
        name = "番茄小说",
        baseUrl = "https://fanqienovel.com",
        builtinGroup = GROUP,
        note = "发现源：按分类列出番茄的书（书名/作者/简介/封面）。" +
            "正文页由 JS 渲染抓不到，请用「换源」到第三方书源阅读。接口每类只有约 10 本、不支持翻页。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://fanqienovel.com/"),
        categories = listOf(
            Category("热榜一", "/api/rank/list?type=0"),
            Category("热榜二", "/api/rank/list?type=1"),
            Category("热榜三", "/api/rank/list?type=2")
        ),
        rules = SourceRules()
    )

    // ---------------- 微信读书 ----------------

    /**
     * 微信读书分类页的书单是 SSR 的，但书名在 `<img>` 上、没有分类入口参数化能力，
     * 正文与目录需要登录态。因此只收录书单页，作为「发现」用途。
     */
    private fun weread() = SourceConfig(
        id = "weread",
        name = "微信读书",
        baseUrl = "https://weread.qq.com",
        builtinGroup = GROUP,
        note = "微信读书公开书单页：书名与封面可读。目录与正文需要登录，本项目未接入登录态。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://weread.qq.com/"),
        categories = listOf(Category("分类书单", "/web/category/")),
        rules = SourceRules(
            listItem = "a[href^='/web/reader/']",
            listName = "img@alt",
            listLink = "href",
            listCover = "img@src"
        )
    )

    // ---------------- 三一小说（31xs） ----------------

    /**
     * 笔趣阁系模板站：分类书单、详情、目录、正文全都是服务端渲染，召回率最高的一个。
     * 已在真实页面核对：分类页 `div.l div.item`（书名 dl>dt>a）、目录 `#list dd a`、
     * 正文 `#content`（内含 `#booktxt`，165 段 <p>）。
     */
    private fun s31xs() = SourceConfig(
        id = "s31xs",
        name = "三一小说",
        baseUrl = "https://www.31xs.com",
        builtinGroup = GROUP,
        note = "笔趣阁系模板站：分类书单、完整目录、正文都可直接读。" +
            "站点为聚合性质，内容版权状态请自行判断。",
        headers = mapOf("User-Agent" to UA, "Referer" to "https://www.31xs.com/"),
        categories = listOf(
            Category("玄幻魔法", "/fenlei/1/{page}.html"),
            Category("武侠修真", "/fenlei/2/{page}.html"),
            Category("都市言情", "/fenlei/3/{page}.html"),
            Category("历史军事", "/fenlei/4/{page}.html"),
            Category("科幻灵异", "/fenlei/5/{page}.html"),
            Category("游戏竞技", "/fenlei/6/{page}.html")
        ),
        rules = SourceRules(
            // 分类页书卡：div.l / div.ll 下的 div.item
            listItem = "div.l div.item, div.ll div.item",
            listName = "dl dt a",
            listLink = "dl dt a@href",
            listAuthor = "div.btm",
            listCover = "div.image img@data-original",
            listIntro = "dl dd",
            // 三一小说没有可用的静态搜索页，搜索靠其它源
            searchUrl = "",
            detailName = "#info h1",
            detailAuthor = "#info p a",
            detailCover = "#fmimg img@src",
            // #intro 里是简介，站点用 <br> 换行
            detailIntro = "#intro",
            chapterList = "#list dd a",
            chapterName = "text",
            chapterLink = "href",
            content = "#content",
            contentClean = listOf(
                "script", "style",
                "re:^\\s*加入书签.*$",
                "re:^\\s*请记住本书首发域名.*$",
                "re:.*咪.*咪.*阅.*读.*",
                "re:.*换源App.*",
                "re:^\\s*本章未完.*$"
            )
        )
    )

    // ---------------- 望书网（wangshuwx） ----------------

    /**
     * 与「八一中文」同模板：分类页书卡在 `div.ll div.item`，目录 `#list dd a`（实测 242 章），
     * 正文在 `#content` 里用 `<br />` 分行（由 Rule.extractText 按块拆段）。
     */
    private fun wangshu() = SourceConfig(
        id = "wangshu",
        name = "望书网",
        baseUrl = "http://www.wangshuwx.com",
        builtinGroup = GROUP,
        note = "笔趣阁系模板站：分类、目录（实测可到 240+ 章）、正文都可读；站内搜索可用。" +
            "站点为聚合性质，内容版权状态请自行判断。",
        headers = mapOf("User-Agent" to UA, "Referer" to "http://www.wangshuwx.com/"),
        categories = listOf(
            Category("玄幻", "/xuanhuanxiaoshuo/"),
            Category("修真", "/xiuzhenxiaoshuo/"),
            Category("都市", "/dushixiaoshuo/"),
            Category("穿越", "/chuanyuexiaoshuo/"),
            Category("网游", "/wangyouxiaoshuo/"),
            Category("科幻", "/kehuanxiaoshuo/"),
            Category("排行", "/paihangbang/")
        ),
        rules = SourceRules(
            searchUrl = "/modules/article/search.php?searchkey={key}",
            // 分类页：卡片式 div.item，书名在 dl>dt>a
            listItem = "div.ll div.item, div.l div.item",
            listName = "dl dt a",
            listLink = "dl dt a@href",
            listAuthor = "dl dt span",
            listCover = "div.image img@src",
            listIntro = "dl dd",
            // 搜索结果页：表格 tr#nr，书名是第一个 td 里的 a，作者是第 3 个 td
            searchItem = "table.grid tr#nr",
            searchName = "td:nth-of-type(1) a",
            searchLink = "td:nth-of-type(1) a@href",
            searchAuthor = "td:nth-of-type(3)",
            detailName = "#info h1",
            detailAuthor = "#info p",
            detailCover = "#fmimg img@src",
            detailIntro = "#intro",
            chapterList = "#list dd a",
            chapterName = "text",
            chapterLink = "href",
            content = "#content",
            contentClean = listOf(
                "script", "style",
                "re:^\\s*请记住本书首发域名.*$",
                "re:.*咪.*咪.*阅.*读.*",
                "re:.*换源App.*",
                "re:^\\s*本章未完.*$"
            )
        )
    )

    /** 示例书库（公版节选，不联网） */
    fun demo() = DemoSource.config()

    /** 全部内置源，顺序即书城里的展示顺序 */
    fun all(): List<SourceConfig> = listOf(
        demo(),
        s31xs(),
        wangshu(),
        qidian(),
        douban(),
        gutenberg(),
        fanqie(),
        weread()
    )

    /** 内置源 id 集合：这些源不可删除 */
    val IDS: Set<String> = all().map { it.id }.toSet()
}
