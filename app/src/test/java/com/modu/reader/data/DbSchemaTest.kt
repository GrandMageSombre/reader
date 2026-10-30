package com.modu.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库版本与建表回归测试。
 *
 * 背景：v1.4.0 加了 `swap` 表却忘了升版本号，老用户 `onCreate`/`onUpgrade` 都不跑，
 * 表永远不存在 → 详情页报 `no such table: swap`。
 * 这组测试守住两条纪律：
 *
 * 1. `DB_VERSION` 必须 >= 2（有 swap 表）；
 * 2. 所有建表语句都用 `IF NOT EXISTS`，保证可重复执行（幂等）。
 *
 * 注：真正的 SQL 执行需要 Android 运行时，这里只做源码级静态校验，
 * 幂等性靠读源码断言——这样在纯 JVM 环境下也能跑。
 */
class DbSchemaTest {

    private fun source(name: String): String {
        val candidates = listOf(
            "app/src/main/java/com/modu/reader/data/$name",
            "src/main/java/com/modu/reader/data/$name"
        )
        for (p in candidates) {
            val f = java.io.File(p)
            if (f.exists()) return f.readText()
        }
        throw IllegalStateException("找不到源码文件 $name（工作目录 ${java.io.File(".").absolutePath}）")
    }

    private val dbSrc by lazy { source("Db.kt") }

    @Test
    fun `数据库版本号足以覆盖 swap 表`() {
        assertTrue(
            "DB_VERSION 必须 >= 2，否则加了 swap 表老用户也不会升级",
            Db.DB_VERSION >= 2
        )
    }

    @Test
    fun `SQLiteOpenHelper 用的是 DB_VERSION 常量`() {
        assertTrue(
            "构造器必须传 DB_VERSION，不能写死字面量（写死就容易忘升级）",
            dbSrc.contains("\"modu.db\", null, DB_VERSION")
        )
    }

    @Test
    fun `所有建表语句都是 IF NOT EXISTS，可重复执行`() {
        // 抽出所有 CREATE TABLE 语句，逐条检查
        val creates = Regex("""CREATE TABLE(\s+IF NOT EXISTS)?\s+""")
            .findAll(dbSrc)
            .toList()
        assertTrue("应当至少有几条 CREATE TABLE", creates.size >= 3)
        creates.forEachIndexed { i, m ->
            assertTrue(
                "第 ${i + 1} 条 CREATE TABLE 缺少 IF NOT EXISTS（重复执行会抛 table already exists）",
                m.groupValues[1].isNotBlank()
            )
        }
    }

    @Test
    fun `onUpgrade 不再无条件 DROP 缓存表`() {
        // v1 的 onUpgrade 会无条件 DROP chapters/content；v2 起只在必要时处理，
        // 避免每次升级都把用户缓存清空
        val upgrade = dbSrc.substringAfter("override fun onUpgrade")
            .substringBefore("override fun onDowngrade")
        assertTrue(
            "onUpgrade 里不应无条件 DROP 表（会清空用户缓存）",
            !upgrade.contains("DROP TABLE")
        )
    }

    @Test
    fun `onUpgrade 与 onDowngrade 都补齐 swap 表`() {
        val upgrade = dbSrc.substringAfter("override fun onUpgrade")
            .substringBefore("private fun createChaptersTable")
        assertTrue("onUpgrade 应调用 createSwapTable", upgrade.contains("createSwapTable(db)"))

        val downgrade = dbSrc.substringAfter("override fun onDowngrade")
            .substringBefore("private fun createChaptersTable")
        assertTrue("onDowngrade 也应补齐表，装回旧版不该崩", downgrade.contains("createSwapTable(db)"))
    }

    @Test
    fun `换源读写在表缺失时不会抛异常`() {
        val swapPart = dbSrc.substringAfter("fun loadSwap")
            .substringBefore("fun clearAllSwap")
        assertTrue(
            "loadSwap 必须捕获 SQLiteException，否则表缺失会让整本书的目录加载失败",
            swapPart.contains("SQLiteException")
        )
    }

    @Test
    fun `建表语句声明的列与读写方法一致`() {
        // swap 表声明的列
        val swapDdl = dbSrc.substringAfter("CREATE TABLE IF NOT EXISTS swap")
            .substringBefore("\"\"\"")
        listOf("book_url", "source_id", "source_book_url", "matched_title", "matched_author", "score", "updated_at")
            .forEach { col ->
                assertTrue("swap 表缺少列 $col", swapDdl.contains(col))
            }
    }

    @Test
    fun `整本下载进度表会随版本升级一起建出来`() {
        val dbSrc = source("Db.kt")
        assertTrue(
            "DB_VERSION 必须是 3 及以上（download_task 是 v3 加的）",
            Db.DB_VERSION >= 3
        )
        assertTrue("要有 download_task 建表语句", dbSrc.contains("download_task"))
        assertTrue(
            "onCreate / onUpgrade 都要调用 createDownloadTable，否则老用户升上来会缺表",
            dbSrc.split("createDownloadTable(db)").size - 1 >= 3
        )
        assertTrue("进度要能读出来", dbSrc.contains("fun downloadTask("))
    }
}
