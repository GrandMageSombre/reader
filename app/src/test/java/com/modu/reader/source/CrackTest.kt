package com.modu.reader.source

import com.modu.reader.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 破解判定：一章算不算「全文」、还差哪些章、战果怎么描述。
 *
 * 这套判定同时被三处引用：详情页「破解补全」、整本下载的付费章统计、
 * 阅读器「这一章要不要去采集站捞」。口径错了会连锁出错
 * （试读当全文 → 缓存污染；全文当试读 → 反复抓、越抓越少），所以单独压。
 */
class CrackTest {

    private fun body(chars: Int = 400) =
        (1..(chars / 40 + 1)).joinToString("\n") { "　　这是第${it}段正常正文，用来验证长度判定。" }

    // ---------------- 成色判定 ----------------

    @Test
    fun `足够长的正文是全文`() {
        assertEquals(Crack.Kind.FULL, Crack.of(body()))
    }

    @Test
    fun `空正文是空`() {
        assertEquals(Crack.Kind.EMPTY, Crack.of(""))
        assertEquals(Crack.Kind.EMPTY, Crack.of("  \n\t "))
    }

    @Test
    fun `短得反常算 SHORT 而不是全文`() {
        assertEquals(Crack.Kind.SHORT, Crack.of("两个字"))
        assertEquals(Crack.Kind.SHORT, Crack.of("字".repeat(Crack.MIN_FULL_CHARS - 1)))
        assertEquals(Crack.Kind.FULL, Crack.of("字".repeat(Crack.MIN_FULL_CHARS)))
    }

    @Test
    fun `带订阅标记的算试读`() {
        assertEquals(Crack.Kind.PREVIEW, Crack.of(body() + "\n订阅本章"))
        assertEquals(Crack.Kind.PREVIEW, Crack.of(body(), note = "本章为VIP章节"))
    }

    @Test
    fun `note 里的锁定标记优先于正文`() {
        assertEquals(Crack.Kind.LOCKED, Crack.of("", note = "chapterLock"))
        assertEquals(Crack.Kind.LOCKED, Crack.of(body(), note = "isChapterLock=true"))
    }

    @Test
    fun `风控与 403 算 BLOCKED 而不是「没有内容」`() {
        assertEquals(Crack.Kind.BLOCKED, Crack.of("", "被风控（验证码中间页）"))
        assertEquals(Crack.Kind.BLOCKED, Crack.of("", "HTTP 403"))
        assertFalse("风控不能当成书的属性", Crack.of("", note = "HTTP 403").usable)
    }

    @Test
    fun `风控压过锁定`() {
        // note 里同时有 chapterLock 和验证码时，必须判 BLOCKED 才参与熔断
        assertEquals(Crack.Kind.BLOCKED, Crack.of("", "chapterLock + 验证码中间页"))
    }

    @Test
    fun `14030 字不会被当成 403`() {
        // 曾经用子串 "403" 判风控，「共 14030 字」被误判 → 整轮抓取中断
        assertEquals(Crack.Kind.FULL, Crack.of(body(), note = "共 14030 字"))
    }

    @Test
    fun `WAF 脚本壳判 BLOCKED`() {
        val shell = "<html><script>!function(){var m=document.cookie.match(/x-waf-captcha/);" +
            "if(m){window.location.reload()}}catch(e){}}</script></html>"
        assertEquals(Crack.Kind.BLOCKED, Crack.of(shell))
        assertTrue("脚本壳不能算全文", !Crack.isFull(shell))
    }

    @Test
    fun `模板噪声行不算字数`() {
        val noisy = "本章未完，请点击下一页继续阅读\n请记住本书首发域名\n真正的正文"
        assertEquals("真正的正文", Crack.stripJunk(noisy))
        // 剥完只剩一小段 → 仍然是 SHORT，不会被噪声撑成全文
        assertEquals(Crack.Kind.SHORT, Crack.of(noisy))
    }

    @Test
    fun `只有 FULL 能入库`() {
        assertTrue(Crack.isFull(body()))
        assertFalse(Crack.isFull(body() + "\n订阅本章"))
        assertFalse(Crack.isFull(""))
    }

    // ---------------- 缺口计算 ----------------

    @Test
    fun `缺口就是没有缓存的章`() {
        val chapters = (0 until 5).map { Chapter(it, "第${it + 1}章", "https://x/$it") }
        val gaps = Crack.gaps(chapters) { it % 2 == 0 }   // 偶数章已缓存
        assertEquals(listOf(1, 3), gaps.map { it.index })
        assertEquals("第2章", gaps.first().title)
    }

    @Test
    fun `全都有缓存时缺口为空`() {
        val chapters = (0 until 3).map { Chapter(it, "第${it + 1}章", "u$it") }
        assertTrue(Crack.gaps(chapters) { true }.isEmpty())
        assertEquals(3, Crack.gaps(chapters) { false }.size)
    }

    // ---------------- 战果描述 ----------------

    @Test
    fun `战果文案把三类数都说清`() {
        val text = Crack.Report(total = 120, full = 100, failed = 18, blocked = 2).text()
        assertTrue(text.contains("待补 120 章"))
        assertTrue(text.contains("成功 100 章"))
        assertTrue(text.contains("失败 18 章"))
        assertTrue(text.contains("风控中断 2 次"))
    }

    @Test
    fun `没有失败时不写多余的零`() {
        val text = Crack.Report(total = 10, full = 10).text()
        assertFalse("没失败就不该出现失败字样", text.contains("失败"))
        assertFalse("没风控就不该出现风控字样", text.contains("风控"))
    }

    // ---------------- 起点移动页解析 ----------------

    @Test
    fun `起点移动页能取到正文`() {
        val html = "<html><body><div id=\"chapter-content\">" +
            "<p>绯红，是一片纯粹的红。那红来自天上，也来自他自己的眼睛。</p>" +
            "<p>他做了一个很长的梦，梦里有人在叫他的名字。</p></div></body></html>"
        val (text, preview) = DeviceParsers.qidianMobileTextOf(html)
        assertTrue(text.contains("绯红"))
        assertEquals(2, text.split("\n").size)
        assertFalse(preview)
    }

    @Test
    fun `WAF 脚本壳不算正文`() {
        // 实测：m.qidian.com 裸请求 200 但只有 825 字节的 WAF 脚本，没有正文节点
        val html = "<html><script>!function(){var m=document.cookie.match(/x-waf-captcha/)}()</script></html>"
        val (text, preview) = DeviceParsers.qidianMobileTextOf(html)
        assertTrue("没有正文节点就该返回空", text.isEmpty())
        assertFalse(preview)
    }

    @Test
    fun `移动页带订阅字样时标成试读`() {
        val html = "<html><body><div class=\"content\"><p>一段正文。</p></div><div>订阅本章</div></body></html>"
        val (text, preview) = DeviceParsers.qidianMobileTextOf(html)
        assertEquals("一段正文。", text)
        assertTrue(preview)
    }
}
