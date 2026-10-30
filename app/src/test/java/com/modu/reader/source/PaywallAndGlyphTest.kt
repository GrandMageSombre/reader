package com.modu.reader.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 付费墙判断、章节名对齐、番茄字形还原、本机抓取的 HTML/JSON 解析。
 *
 * 这些纯函数决定「要不要换源 / 抓到的是不是完整章」，错一个就会把正常章节
 * 误判成试读、或者把目录索引算错（连带阅读进度错位），所以单独压一遍。
 */
class PaywallAndGlyphTest {

    @Before
    fun resetGlyphTables() {
        // 字形表是 object 里的全局状态，逐条测试必须从干净状态开始
        FanqieGlyph.reset()
    }

    // ---------------- 付费墙判断 ----------------

    @Test
    fun `起点VIP试读片段会被判成付费`() {
        val preview = "　　李景恬听得骤然一惊，连忙将乌黑的长发盘起，从床间摸出一把细长的短匕，边走着边将匕" +
            "首藏进袖中。\n　　她很清楚，自己这点修为，在那些大修士面前连个照面都撑不住。\n订阅本章"
        assertTrue(Paywall.looksPaid(preview))
        assertTrue(Paywall.needsFallback(preview))
    }

    @Test
    fun `正常章节不会被误判`() {
        val body = (1..40).joinToString("\n") { "　　这是第${it}段正常正文，长度足够长，用来验证不会被误判成试读片段。" }
        assertTrue("正常正文应有 ${body.length} 字", body.length > 400)
        assertFalse(Paywall.looksPaid(body))
        assertFalse(Paywall.needsFallback(body))
    }

    @Test
    fun `空正文按付费处理`() {
        assertTrue(Paywall.looksPaid(""))
        assertTrue(Paywall.looksPaid("   \n  "))
        assertTrue(Paywall.needsFallback(""))
    }

    @Test
    fun `短章本身不触发兜底`() {
        // 作者感言常常只有一两百字，本来就能读；不能因为「短」就去全网搜（可能被错章覆盖）
        val note = "今天请假一天，明天两更，谢谢大家支持！"
        assertTrue("短章仍算「可疑」，供统计用", Paywall.looksPaid(note))
        assertFalse("但不该触发换源兜底", Paywall.needsFallback(note))
    }

    @Test
    fun `刚好到阈值的正文不算付费`() {
        val text = "字".repeat(Paywall.SUSPICIOUS_CHARS)
        assertFalse(Paywall.looksPaid(text))
        assertTrue(Paywall.looksPaid("字".repeat(Paywall.SUSPICIOUS_CHARS - 1)))
    }

    // ---------------- 章节名对齐 ----------------

    @Test
    fun `章节名归一化去掉序号与括注`() {
        assertEquals("我已非红尘", Paywall.normTitle("第一千六百四十章 我已非红尘。（1+1/2）"))
        assertEquals("剧本不对", Paywall.normTitle("第一章 剧本不对"))
        assertEquals("大圣归来", Paywall.normTitle("第 2 章 大圣归来"))
    }

    @Test
    fun `多层卷章前缀也能剥掉`() {
        assertEquals("穷文富武", Paywall.normTitle("第11卷 第3章 穷文富武"))
        // 章名本身就是「第3章」时不能剥成空串，否则永远对不上
        assertEquals("第3章", Paywall.normTitle("第3章"))
        assertEquals("楔子", Paywall.normTitle("楔子"))
    }

    @Test
    fun `两站的同一章能对上`() {
        assertTrue(Paywall.sameChapter("第1640章 我已非红尘（1+1/2）", "我已非红尘"))
        assertTrue(Paywall.sameChapter("第三章 穷文富武", "第3章 穷文富武"))
        assertFalse(Paywall.sameChapter("第三章 穷文富武", "第四章 武道社"))
    }

    @Test
    fun `在候选目录里定位到目标章`() {
        val toc = listOf("第一章 剧本不对", "第二章 一定要考武科！", "第三章 穷文富武")
        assertEquals(2, Paywall.findChapter(toc, "第3章 穷文富武"))
        assertEquals(0, Paywall.findChapter(toc, "剧本不对"))
        assertEquals(-1, Paywall.findChapter(toc, "第五章 不存在的章"))
        assertEquals(-1, Paywall.findChapter(emptyList(), "第一章 剧本不对"))
    }

    @Test
    fun `同名章按预期位置取最近的`() {
        // 采集站常有重复的「感谢」章，只取第一个会错位到书尾
        val toc = listOf("第一章 开局", "感谢", "第二章 出发", "感谢", "第三章 归来", "感谢")
        assertEquals(1, Paywall.findChapter(toc, "感谢", hintIndex = 0))
        assertEquals(3, Paywall.findChapter(toc, "感谢", hintIndex = 3))
        assertEquals(5, Paywall.findChapter(toc, "感谢", hintIndex = 5))
    }

    @Test
    fun `书名匹配允许一方包含另一方`() {
        assertTrue(Paywall.sameBook("《玄鉴仙族》", "玄鉴仙族"))
        assertTrue(Paywall.sameBook("全球高武", "全球高武（校对版）"))
        assertFalse(Paywall.sameBook("玄鉴仙族", "凡人修仙传"))
    }

    // ---------------- 番茄字形还原 ----------------

    @Test
    fun `内置字形表能还原私有区码位`() {
        val n = FanqieGlyph.loadFromJson(
            """{"fonts":{"default":{"e3e9":"在","e3ea":"主","e3eb":"角"}}}"""
        )
        assertEquals(1, n)
        val raw = "\ue3e9\ue3ea\ue3eb，钟鸣响。"
        assertTrue("原始文本应含私有区码位", FanqieGlyph.hasObfuscation(raw))
        val text = FanqieGlyph.decode(raw)
        assertFalse("还原后不应再有私有区码位", FanqieGlyph.hasObfuscation(text))
        assertEquals("在主角，钟鸣响。", text)
    }

    @Test
    fun `认不出的码位原样保留而不是乱猜`() {
        FanqieGlyph.loadFromJson("""{"fonts":{"default":{"e3e9":"在"}}}""")
        assertEquals("在\ue999", FanqieGlyph.decode("\ue3e9\ue999"))
    }

    @Test
    fun `表没装载时正文原样返回`() {
        val raw = "\ue3e9\ue3ea"
        assertEquals(raw, FanqieGlyph.decode(raw))
        assertEquals(0, FanqieGlyph.loadFromJson(""))
        assertEquals(0, FanqieGlyph.loadFromJson("""{"fonts":{}}"""))
        assertEquals(raw, FanqieGlyph.decode(raw))
    }

    @Test
    fun `没有混淆的正文原样返回`() {
        val plain = "这是普通正文，没有私有区码位。"
        FanqieGlyph.loadFromJson("""{"fonts":{"default":{"e3e9":"在"}}}""")
        assertEquals(plain, FanqieGlyph.decode(plain))
        assertEquals(0f, FanqieGlyph.obfuscatedRatio(plain), 0.0001f)
    }

    @Test
    fun `按字体名取表，未知字体回退 default`() {
        FanqieGlyph.loadFromJson(
            """{"fonts":{"abc":{"e3e9":"甲"},"default":{"e3e9":"在"}}}"""
        )
        assertEquals("甲", FanqieGlyph.decode("\ue3e9", "abc"))
        assertEquals("在", FanqieGlyph.decode("\ue3e9", "unknown-font"))
    }

    @Test
    fun `起点书库列表解析出书名作者分类封面`() {
        val html = """
            <div class="all-book-list"><ul class="all-img-list cf">
              <li><div class="book-img-box"><a href="//www.qidian.com/book/1035420986/" data-bid="1035420986">
                <img src="//bookcover.yuewen.com/qdbimg/349573/1035420986/150" alt="玄鉴仙族在线阅读"></a></div>
                <div class="book-mid-info">
                  <h2><a href="//www.qidian.com/book/1035420986/" data-bid="1035420986">玄鉴仙族</a></h2>
                  <p class="author"><a class="name" href="//my.qidian.com/author/430784443/">季越人</a><em>|</em>
                    <a href="//www.qidian.com/xianxia/">仙侠</a><i>·</i>
                    <a class="go-sub-type" data-typeid="22" data-subtypeid="18" href="//www.qidian.com/all/chanId22-subCateId18/">修真文明</a>
                    <em>|</em><span>连载</span></p>
                  <p class="intro">陆江仙熬夜猝死，残魂却附在一面满是裂痕的青灰色铜镜上。</p>
                </div></li>
            </ul></div>
        """.trimIndent()
        val list = DeviceParsers.parseQidianList(html, "device")
        assertEquals(1, list.size)
        val b = list[0]
        assertEquals("玄鉴仙族", b.title)
        assertEquals("季越人", b.author)
        assertTrue("封面要补 https", b.cover.startsWith("https://bookcover"))
        assertEquals("连载", b.status)
        assertTrue("分类取不到时也要有值: ${b.category}", b.category.contains("仙侠"))
        assertEquals("https://www.qidian.com/book/1035420986/", b.url)
        assertEquals("device", b.sourceId)
    }

    @Test
    fun `起点章节页正文与试读标记`() {
        val free = "<main id='c-1'><p>第一段</p><p>第二段</p></main>"
        val (t1, p1) = DeviceParsers.qidianTextOf(free)
        assertEquals("第一段\n第二段", t1)
        assertFalse(p1)
        val vip = "<main id='c-2'><p>试读片段</p></main><a>订阅本章</a>"
        val (t2, p2) = DeviceParsers.qidianTextOf(vip)
        assertEquals("试读片段", t2)
        assertTrue(p2)
    }

    // ---------------- 本机抓取的解析 ----------------

    @Test
    fun `起点目录解析按章节 id 去重并保持顺序`() {
        val html = """
            <div class="catalog-volume">
              <h3 class="volume-name">第一卷 · 共2章</h3>
              <ul class="volume-chapters">
                <li class="chapter-item"><a class="chapter-name"
                    href="//www.qidian.com/chapter/1035420986/730944635/">第一章 剧本不对</a></li>
                <li class="chapter-item"><a class="chapter-name"
                    href="//www.qidian.com/chapter/1035420986/730944636/">第二章 一定要考武科！</a></li>
              </ul>
            </div>
            <div class="catalog-volume">
              <h3 class="volume-name">最新章节</h3>
              <ul class="volume-chapters">
                <li class="chapter-item"><a class="chapter-name"
                    href="//www.qidian.com/chapter/1035420986/730944636/">第二章 一定要考武科！</a></li>
              </ul>
            </div>
        """.trimIndent()
        val list = DeviceParsers.parseQidianCatalog(html)
        assertEquals("重复出现的章节要按 id 去掉", 2, list.size)
        assertEquals(0, list[0].index)
        assertEquals("第二章 一定要考武科！", list[1].title)
        assertTrue(list[0].url.startsWith("https://www.qidian.com/chapter/1035420986/"))
    }

    @Test
    fun `番茄目录解析展开分卷并跳过重复 itemId`() {
        val page = JSONObject(
            """{"chapterListWithVolume":[[
                 {"itemId":"a1","title":"第一章 仙家四境"},
                 {"itemId":"a2","title":"第二章 大圣归来"}],[
                 {"itemId":"a2","title":"第二章 大圣归来"},
                 {"itemId":"a3","title":""}]]}"""
        )
        val list = DeviceParsers.parseFanqieCatalog(page)
        assertEquals(3, list.size)
        assertEquals(listOf(0, 1, 2), list.map { it.index })
        assertEquals("第3章", list[2].title)          // 标题为空时补一个
        assertTrue(list[0].url.endsWith("/reader/a1"))
    }

    @Test
    fun `从页面里抠 state 能处理转义与嵌套`() {
        val html = """<script>window.__INITIAL_STATE__={"a":{"b":"x\"}y"},"c":1};</script>"""
        val state = DeviceParsers.jsonAfter(html, "window.__INITIAL_STATE__=")
        assertEquals("""x"}y""", state!!.getJSONObject("a").getString("b"))
        assertEquals(1, state.getInt("c"))
        assertEquals(null, DeviceParsers.jsonAfter("<html></html>", "window.__INITIAL_STATE__="))
    }
}
