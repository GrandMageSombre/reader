package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.net.Http
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换源闭环的**联网**验证（默认跳过，用 `-DswapLive=1` 打开）。
 *
 * 普通单测不该依赖网络，所以这里用假设跳过；真正要验证番茄接口 /
 * 第三方源搜索是否可用时，手动打开跑一次。
 */
class SwapLiveTest {

    private val live = System.getProperty("swapLive") == "1"

    @Test
    fun `番茄 discover 接口按分类返回书`() = runBlocking {
        if (!live) return@runBlocking
        val cfg = BuiltinSources.all().first { it.id == FanqieSource.ID }
        val src = FanqieSource(cfg)
        val cat = src.categories().first()
        val list = src.list(cat, 1)

        println("番茄分类「${cat.name}」拿到 ${list.size} 本")
        list.take(3).forEach {
            println("  ${it.title} / ${it.author}  ${it.url}")
            println("     简介: ${it.intro.take(40)}")
            println("     封面: ${it.cover.take(60)}")
        }
        assertTrue("番茄应能返回书", list.isNotEmpty())
        assertTrue("书名不能为空", list.all { it.title.isNotBlank() })
        assertTrue("书链接应指向书籍页", list.all { it.url.contains("/page/") })
    }

    @Test
    fun `番茄接口不支持翻页`() = runBlocking {
        if (!live) return@runBlocking
        val cfg = BuiltinSources.all().first { it.id == FanqieSource.ID }
        val src = FanqieSource(cfg)
        val cat = src.categories().first()
        assertTrue("第 1 页有数据", src.list(cat, 1).isNotEmpty())
        assertTrue("第 2 页应为空（实测接口不支持翻页）", src.list(cat, 2).isEmpty())
    }

    @Test
    fun `起点分类能列书且能取到详情`() = runBlocking {
        if (!live) return@runBlocking
        val cfg = BuiltinSources.all().first { it.id == "qidian" }
        val src = HtmlSource(cfg)
        val cat = cfg.categories.first()
        val list = src.list(cat, 1)
        println("起点分类「${cat.name}」(${cat.url}) 拿到 ${list.size} 本")
        list.take(3).forEach { println("  ${it.title} / ${it.author}") }
        assertTrue("起点分类应有书（分类 id 可能已失效）", list.isNotEmpty())

        val b = src.detail(list.first())
        println("详情: ${b.title} / ${b.author} / 简介 ${b.intro.length} 字")
        assertTrue("详情书名不能为空", b.title.isNotBlank())
    }

    @Test
    fun `换源：番茄的书能在第三方源搜到`() = runBlocking {
        if (!live) return@runBlocking
        val fanqie = FanqieSource(BuiltinSources.all().first { it.id == FanqieSource.ID })
        val books = fanqie.list(fanqie.categories().first(), 1)
        assertTrue("番茄应返回书", books.isNotEmpty())

        val pool = listOf("s31xs", "wangshu").mapNotNull { id ->
            BuiltinSources.all().firstOrNull { it.id == id }?.let { HtmlSource(it) }
        }
        var hit = 0
        books.take(4).forEach { b ->
            val cands = SwapMatcher.find(b, pool)
            val exact = cands.filter { it.exact }
            println("《${b.title}》-> 候选 ${cands.size}，同名 ${exact.size}")
            exact.take(2).forEach { println("    ✅ ${it.sourceName} 《${it.title}》 ${it.bookUrl}") }
            if (exact.isNotEmpty()) hit++
        }
        println("换源命中: $hit / 4")
        // 命中率不是 100%：番茄独家书在第三方站确实没有，这里不断言比例
    }
}
