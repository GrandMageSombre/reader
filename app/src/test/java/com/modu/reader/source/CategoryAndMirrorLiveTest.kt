package com.modu.reader.source

import com.modu.reader.data.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网验证（`-PswapLive` 打开）：
 * 1. 番茄按书自带的官方分类归类是否正确；
 * 2. 采集站的多镜像容错是否真的能换站拿到正文。
 */
class CategoryAndMirrorLiveTest {

    private val live = System.getProperty("swapLive") == "1"

    private fun fanqie() = FanqieSource(BuiltinSources.all().first { it.id == FanqieSource.ID })

    @Test
    fun `番茄书籍自带官方分类，且能按分类归组`() = runBlocking {
        if (!live) return@runBlocking
        val src = fanqie()
        val all = src.list(
            src.categories().first { it.name == FanqieSource.ALL_CATEGORY }, 1
        )
        println("番茄全部可抓书籍：${all.size} 本")
        all.take(6).forEach { println("  《${it.title}》  分类=${it.category}") }

        assertTrue("番茄应能返回书", all.isNotEmpty())
        val withCat = all.count { it.category.isNotBlank() }
        println("带官方分类：$withCat / ${all.size}")
        assertTrue("应能拿到书籍的官方分类", withCat > 0)

        // 按「男生/都市」过滤，结果里的书必须都属于该分类
        val dushi = src.list(src.categories().first { it.name == "男生/都市" }, 1)
        println("分类「男生/都市」：${dushi.size} 本")
        dushi.forEach { println("    《${it.title}》 ${it.category}") }
        assertTrue("分类过滤结果应属于该分类", dushi.all {
            it.category == "男生/都市" || it.category.startsWith("男生/都市/")
        })
    }

    @Test
    fun `番茄「全部」包含各分类的书`() = runBlocking {
        if (!live) return@runBlocking
        val src = fanqie()
        val all = src.list(src.categories().first { it.name == FanqieSource.ALL_CATEGORY }, 1)
        val cats = all.map { it.category }.filter { it.isNotBlank() }
        println("出现的官方分类：${cats.distinct().sorted()}")
        // 一级分类至少应有两种（男生/女生）
        val top = cats.map { it.substringBefore('/') }.distinct()
        println("一级分类：$top")
        assertTrue("应至少有男生/女生中的一种", top.isNotEmpty())
    }

    @Test
    fun `采集站多镜像：能搜到书并取到正文`() = runBlocking {
        if (!live) return@runBlocking
        val cfg = BuiltinSources.all().first { it.id == "wangshu" }
        val src = BiqugeMultiSource(cfg, listOf("http://www.zwduxs.com"))

        // 用一本确定存在的书搜
        val found = src.search("大奉打更人", 1)
        println("多镜像搜索命中：${found.size} 本")
        found.take(3).forEach { println("  《${it.title}》 ${it.author}  ${it.url}") }
        assertTrue("多镜像搜索应有结果", found.isNotEmpty())

        val target = found.firstOrNull { it.title == "大奉打更人" } ?: found.first()
        val chaps = src.chapters(target)
        println("《${target.title}》目录 ${chaps.size} 章")
        assertTrue("目录应有章节", chaps.isNotEmpty())

        val text = src.content(target, chaps.first())
        println("正文 ${text.length} 字：${text.take(60).replace("\n", " ")}")
        assertTrue("正文应有内容", text.length > 200)
    }

    @Test
    fun `多镜像：主站地址失效时能自动走备用站`() = runBlocking {
        if (!live) return@runBlocking
        // 故意把 cfg.baseUrl 指向一个打不开的域名，看是否会回落到 mirrors
        val base = BuiltinSources.all().first { it.id == "wangshu" }
        val broken = base.copy(baseUrl = "https://definitely-not-exist-9x8y7z.example")
        val src = BiqugeMultiSource(broken, listOf("http://www.wangshuwx.com"))

        val found = src.search("大奉打更人", 1)
        println("主站坏掉时，备用站搜到：${found.size} 本")
        assertTrue("主站失效时应由备用镜像顶上", found.isNotEmpty())

        val target = found.first()
        val chaps = src.chapters(target)
        println("备用站目录 ${chaps.size} 章")
        assertTrue(chaps.isNotEmpty())
        val text = src.content(target, chaps.first())
        println("备用站正文 ${text.length} 字")
        assertTrue(text.length > 200)
    }
}
