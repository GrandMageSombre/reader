package com.modu.reader.source

import com.modu.reader.data.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源**全链路**验证（联网，`-PswapLive` 打开）：
 * 番茄分类发现 → 换源到望书网 → 取目录 → 读书正文。
 *
 * 这是用户要的核心效果：「分类里选起点/番茄 → 按分类抓书 → 正文由第三方源提供」。
 */
class SwapChainLiveTest {

    private val live = System.getProperty("swapLive") == "1"

    @Test
    fun `番茄发现-换源-正文 全链路`() = runBlocking {
        if (!live) return@runBlocking
        // 1) 番茄按分类发现
        val fanqie = FanqieSource(BuiltinSources.all().first { it.id == FanqieSource.ID })
        val books = fanqie.list(fanqie.categories().first(), 1)
        assertTrue("番茄应返回书", books.isNotEmpty())
        val target = books.first()
        println("① 番茄发现：《${target.title}》 / ${target.author}")

        // 2) 换源：在第三方源里搜
        val wangshu = HtmlSource(BuiltinSources.all().first { it.id == "wangshu" })
        val cands = SwapMatcher.find(target, listOf(wangshu))
        val best = cands.firstOrNull { it.exact }
        println("② 换源候选 ${cands.size} 个，同名 ${cands.count { it.exact }} 个")
        assertTrue("应能换源到同名书", best != null)
        println("   选中：${best!!.sourceName} 《${best.title}》 ${best.bookUrl}")

        // 3) 用第三方源取目录
        val swappedBook = Book(best.sourceId, best.bookUrl, best.title)
        val chapters = wangshu.chapters(swappedBook)
        println("③ 目录 ${chapters.size} 章，前 3：${chapters.take(3).map { it.title }}")
        assertTrue("目录应有章节", chapters.isNotEmpty())

        // 4) 读第一章正文
        val text = wangshu.content(swappedBook, chapters.first())
        println("④ 正文 ${text.length} 字，开头：${text.take(80).replace("\n", " ")}")
        assertTrue("正文应有内容", text.length > 200)
        assertTrue("正文应是中文", text.count { it in '\u4e00'..'\u9fff' } > 100)
    }

    @Test
    fun `起点发现-换源-正文 全链路`() = runBlocking {
        if (!live) return@runBlocking
        val qidian = QidianSource(BuiltinSources.all().first { it.id == "qidian" })
        val books = qidian.list(BuiltinSources.all().first { it.id == "qidian" }.categories.first(), 1)
        assertTrue("起点应返回书", books.isNotEmpty())

        val wangshu = HtmlSource(BuiltinSources.all().first { it.id == "wangshu" })
        var ok = 0
        books.take(3).forEach { b ->
            val cands = SwapMatcher.find(b, listOf(wangshu))
            val best = cands.firstOrNull { it.exact }
            println("《${b.title}》 -> ${if (best != null) "✅ ${best.title} ${best.bookUrl}" else "无同名"}")
            if (best != null) {
                val chaps = wangshu.chapters(Book(best.sourceId, best.bookUrl, best.title))
                if (chaps.isNotEmpty()) {
                    val t = wangshu.content(Book(best.sourceId, best.bookUrl, best.title), chaps.first())
                    println("   目录 ${chaps.size} 章，正文 ${t.length} 字")
                    if (t.length > 200) ok++
                }
            }
        }
        println("起点换源可读：$ok / 3")
    }
}
