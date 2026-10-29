package com.modu.reader.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第三方书源（Legado / 「阅读」格式）导入器的转换测试。
 *
 * 重点验证「能转的转对、不能转的明确拒绝」，不允许出现「看起来导入成功但规则是错的」。
 */
class LegadoImporterTest {

    private fun json(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":$v" }

    @Test
    fun `基础字段映射：名称、地址、分组、启停`() {
        val text = json(
            "bookSourceName" to "\"铅笔小说\"",
            "bookSourceUrl" to "\"https://www.23qb.com\"",
            "bookSourceGroup" to "\"测试组\"",
            "enabled" to "true"
        )
        val r = LegadoImporter.parse(text)

        assertEquals("铅笔小说", r.config.name)
        assertEquals("https://www.23qb.com", r.config.baseUrl)
        assertEquals(SourceManager.ORIGIN_LEGADO, r.config.origin)
        assertTrue("备注里应写明来自第三方", r.config.note.contains("第三方"))
        assertTrue("备注里应保留原分组", r.config.note.contains("测试组"))
    }

    @Test
    fun `class 与 tag 选择器能转成 CSS（无序号写法）`() {
        val text = json(
            "bookSourceName" to "\"转选择器\"",
            "bookSourceUrl" to "\"https://a.example\"",
            "ruleSearch" to """{"bookList":"class.item","name":"tag.h3@tag.a@text","bookUrl":"tag.a@href"}"""
        )
        val r = LegadoImporter.parse(text)

        assertEquals("[class*=item]", r.config.rules.listItem)
        // name / bookUrl 是相对 bookList 项求值的，所以不能再带 bookList 的选择器
        assertEquals("h3 a", r.config.rules.listName)
        assertEquals("a@href", r.config.rules.listLink)
    }

    @Test
    fun `带同级别索引的选择器会被拒绝，避免取错节点`() {
        // Legado 的 `.0` / `.1` 是「同级匹配里的第几个」，CSS 没有等价写法
        val text = json(
            "bookSourceName" to "\"带索引\"",
            "bookSourceUrl" to "\"https://a2.example\"",
            "ruleSearch" to """{"bookList":"class.item","name":"tag.h3.0@tag.a.0@text"}"""
        )
        val r = LegadoImporter.parse(text)

        assertEquals("带索引的链不能瞎猜", "", r.config.rules.listName)
        assertTrue("必须记录被拒绝的原因", r.skipped.any { it.contains("索引") })
    }

    @Test
    fun `id 选择器能转成 CSS`() {
        val text = json(
            "bookSourceName" to "\"id选择器\"",
            "bookSourceUrl" to "\"https://b.example\"",
            "ruleToc" to """{"chapterList":"id.list@tag.a","chapterName":"text","chapterUrl":"href"}"""
        )
        val r = LegadoImporter.parse(text)

        assertEquals("#list a", r.config.rules.chapterList)
        assertEquals("text", r.config.rules.chapterName)
        assertEquals("href", r.config.rules.chapterLink)
    }

    @Test
    fun `含 JS 的规则会被拒绝并记录原因`() {
        val text = json(
            "bookSourceName" to "\"带JS\"",
            "bookSourceUrl" to "\"https://c.example\"",
            "searchUrl" to "\"@js:someScript()\"",
            "ruleContent" to """{"content":"@js:decode(result)"}"""
        )
        val r = LegadoImporter.parse(text)

        assertEquals("搜索规则应为空（JS 不支持）", "", r.config.rules.searchUrl)
        assertEquals("正文规则应为空（JS 不支持）", "", r.config.rules.content)
        assertTrue("应记录被拒绝的字段", r.skipped.any { it.contains("搜索") })
        assertTrue("应记录被拒绝的字段", r.skipped.any { it.contains("正文") })
    }

    @Test
    fun `XPath 规则会被拒绝`() {
        val text = json(
            "bookSourceName" to "\"XPath源\"",
            "bookSourceUrl" to "\"https://d.example\"",
            "ruleBookInfo" to """{"name":"//meta[@property='og:title']/@content"}"""
        )
        val r = LegadoImporter.parse(text)

        assertEquals("XPath 不支持，应为空", "", r.config.rules.detailName)
        assertTrue(r.skipped.any { it.contains("XPath") })
    }

    @Test
    fun `带请求体的 searchUrl 会被拒绝，纯 GET 模板能转`() {
        val ok = LegadoImporter.convertUrlTemplate("/search?key={{key}}&page={{page}}")
        assertEquals("/search?key={key}&page={page}", ok)

        val post = LegadoImporter.convertUrlTemplate(
            "/modules/article/search.php,{'method':'post','body':'searchkey={{key}}'}"
        )
        assertEquals("POST 不支持，应返回空串", "", post)

        val js = LegadoImporter.convertUrlTemplate("@js:buildUrl()")
        assertEquals("JS 不支持，应返回空串", "", js)
    }

    @Test
    fun `tail 的双井号正则会被去掉，取值保留`() {
        val text = json(
            "bookSourceName" to "\"正则尾\"",
            "bookSourceUrl" to "\"https://e.example\"",
            "ruleSearch" to """{"bookList":"class.item","author":"tag.p@text##作者："}"""
        )
        val r = LegadoImporter.parse(text)

        // `##作者：` 属于 Legado 的替换语法，本引擎不支持 → 丢掉正则部分；
        // `@text` 是本引擎的默认取值，转换后省略即可
        assertEquals("p", r.config.rules.listAuthor)
        // 非 text 的取值必须保留
        val text2 = json(
            "bookSourceName" to "\"正则尾2\"",
            "bookSourceUrl" to "\"https://e2.example\"",
            "ruleSearch" to """{"bookList":"class.item","coverUrl":"tag.img@src##^http"}"""
        )
        assertEquals("img@src", LegadoImporter.parse(text2).config.rules.listCover)
    }

    @Test
    fun `数组格式能批量导入`() {
        val text = """[
            {"bookSourceName":"源A","bookSourceUrl":"https://a.example"},
            {"bookSourceName":"源B","bookSourceUrl":"https://b.example"}
        ]"""
        val list = LegadoImporter.parseAll(text)

        assertEquals(2, list.size)
        assertEquals("源A", list[0].config.name)
        assertEquals("源B", list[1].config.name)
        // id 必须不同，否则导入时互相覆盖
        assertTrue("两个源的 id 不能相同", list[0].config.id != list[1].config.id)
    }

    @Test
    fun `缺少名称或地址时抛错，不会产出半成品书源`() {
        val noName = json("bookSourceUrl" to "\"https://x.example\"")
        var threw = false
        try {
            LegadoImporter.parse(noName)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("缺少 bookSourceName 应报错", threw)

        val noUrl = json("bookSourceName" to "\"没有地址\"")
        threw = false
        try {
            LegadoImporter.parse(noUrl)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("缺少 bookSourceUrl 应报错", threw)
    }

    @Test
    fun `真实第三方书源样本能被解析，且不会静默产出错规则`() {
        // 取自 XIU2/Yuedu 合集里的「速读谷」，改写成无索引写法（带索引的会按要求拒绝）
        val text = """
        {
          "bookSourceName": "速读谷",
          "bookSourceUrl": "https://www.sudugu.co",
          "bookSourceGroup": "无分组",
          "enabled": true,
          "searchUrl": "/modules/article/search.php,{'method':'post','body':'searchkey={{key}}&action=login'}",
          "ruleSearch": {
            "bookList": "class.bookbox",
            "name": "tag.a@text",
            "bookUrl": "tag.a@href",
            "author": "class.author@text##作者："
          },
          "ruleBookInfo": {
            "name": "tag.img@alt",
            "author": "class.itemtxt@tag.p@tag.a@text##作者："
          },
          "ruleToc": {
            "chapterList": "id.list@tag.a",
            "chapterName": "text",
            "chapterUrl": "href",
            "nextTocUrl": "@css:#pages .gr:not([href='#'])@href"
          },
          "ruleContent": {
            "content": "class.con@html",
            "nextContentUrl": "//div[@class='prenext']/span/a[text()='下一页']/@href"
          }
        }
        """.trimIndent()

        val r = LegadoImporter.parse(text)

        assertEquals("速读谷", r.config.name)
        // 可转的部分（name/bookUrl 等相对 bookList 项求值，不带前缀）
        assertEquals("[class*=bookbox]", r.config.rules.listItem)
        assertEquals("a", r.config.rules.listName)
        assertEquals("a@href", r.config.rules.listLink)
        assertEquals("#list a", r.config.rules.chapterList)
        assertEquals("[class*=con]@html", r.config.rules.content)
        // 不可转的部分必须留空，而不是瞎猜
        assertEquals("POST 搜索不支持", "", r.config.rules.searchUrl)
        assertEquals("XPath 正文下一页不支持", "", r.config.rules.contentNext)
        assertTrue("必须把不可用的功能讲清楚", r.skipped.isNotEmpty())
    }
}
