package com.modu.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地库：书架 / 章节缓存 / 正文缓存 / 换源记录。
 * 只用系统 SQLite，不引注解处理器，编译更快也更稳。
 *
 * 版本历史：
 * - v1：shelf / chapters / content
 * - v2：新增 swap（换源记录）。**加表必须升版本号**，否则已装过的用户
 *   onCreate/onUpgrade 都不会跑，表永远建不出来（v1.4.0 就踩过这个坑）。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "modu.db", null, DB_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 外键约束暂时不用，但打开它让未来的表可以显式声明
        db.setForeignKeyConstraintsEnabled(false)
        // WAL：阅读时后台还会写正文缓存（本机抓取 / 预取），不开 WAL 容易撞 database is locked
        try {
            setWriteAheadLoggingEnabled(true)
        } catch (e: Exception) {
            // 个别机型/文件系统不支持就算了，退回默认模式
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS shelf(
                source_id TEXT NOT NULL,
                book_url TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                author TEXT NOT NULL DEFAULT '',
                cover TEXT NOT NULL DEFAULT '',
                intro TEXT NOT NULL DEFAULT '',
                chapter_index INTEGER NOT NULL DEFAULT 0,
                chapter_title TEXT NOT NULL DEFAULT '',
                chapter_count INTEGER NOT NULL DEFAULT 0,
                scroll INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(source_id, book_url))"""
        )
        createChaptersTable(db)
        createContentTable(db)
        createSwapTable(db)
        createDownloadTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只有缓存表结构变化时才重建缓存，书架保留
        if (oldVersion < 2) {
            // v1 -> v2：只加 swap 表，不动其它表（v1 的表结构没变）
            createSwapTable(db)
        }
        // 兜底：任何版本升级后都确保所有表存在。
        // 用 IF NOT EXISTS，所以对已有表无副作用——这样即使某次漏了分支也不会炸。
        createChaptersTable(db)
        createContentTable(db)
        createSwapTable(db)
        createDownloadTable(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 降级（装回旧版本）不该崩：确保当前版本需要的表都在
        createChaptersTable(db)
        createContentTable(db)
        createSwapTable(db)
        createDownloadTable(db)
    }

    private fun createChaptersTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chapters(
                book_url TEXT NOT NULL,
                idx INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(book_url, idx))"""
        )
    }

    private fun createContentTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS content(
                ckey TEXT PRIMARY KEY,
                body TEXT NOT NULL DEFAULT '',
                ts INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    /** 整本下载进度：断点续抓、以及「这本下到第几章了」 */
    private fun createDownloadTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS download_task(
                book_url TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                source_id TEXT NOT NULL DEFAULT '',
                total INTEGER NOT NULL DEFAULT 0,
                done INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0,
                finished INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    /** 换源记录：「发现源」的书 -> 第三方源上的书 */
    private fun createSwapTable(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS swap(
                book_url TEXT PRIMARY KEY,
                source_id TEXT NOT NULL DEFAULT '',
                source_book_url TEXT NOT NULL DEFAULT '',
                matched_title TEXT NOT NULL DEFAULT '',
                matched_author TEXT NOT NULL DEFAULT '',
                score INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0)"""
        )
    }

    // ---------------- 书架 ----------------

    fun shelfList(): List<ShelfBook> {
        val out = ArrayList<ShelfBook>()
        readableDatabase.rawQuery(
            "SELECT source_id, book_url, title, author, cover, intro, chapter_index," +
                " chapter_title, chapter_count, scroll, updated_at FROM shelf ORDER BY updated_at DESC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ShelfBook(
                        sourceId = c.getString(0),
                        bookUrl = c.getString(1),
                        title = c.getString(2),
                        author = c.getString(3),
                        cover = c.getString(4),
                        intro = c.getString(5),
                        chapterIndex = c.getInt(6),
                        chapterTitle = c.getString(7),
                        chapterCount = c.getInt(8),
                        scroll = c.getInt(9),
                        updatedAt = c.getLong(10)
                    )
                )
            }
        }
        return out
    }

    fun isOnShelf(sourceId: String, bookUrl: String): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM shelf WHERE source_id=? AND book_url=? LIMIT 1",
            arrayOf(sourceId, bookUrl)
        ).use { return it.moveToFirst() }
    }

    fun addToShelf(book: Book) {
        val db = writableDatabase
        val exists = isOnShelf(book.sourceId, book.url)
        val cv = ContentValues().apply {
            put("source_id", book.sourceId)
            put("book_url", book.url)
            put("title", book.title)
            put("author", book.author)
            put("cover", book.cover)
            put("intro", book.intro)
            put("updated_at", System.currentTimeMillis())
        }
        if (exists) {
            db.update("shelf", cv, "source_id=? AND book_url=?", arrayOf(book.sourceId, book.url))
        } else {
            cv.put("chapter_index", 0)
            cv.put("chapter_title", "")
            cv.put("chapter_count", 0)
            cv.put("scroll", 0)
            db.insert("shelf", null, cv)
        }
    }

    fun removeFromShelf(sourceId: String, bookUrl: String) {
        writableDatabase.delete("shelf", "source_id=? AND book_url=?", arrayOf(sourceId, bookUrl))
    }

    fun saveProgress(
        sourceId: String,
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterCount: Int,
        scroll: Int
    ) {
        writableDatabase.execSQL(
            "UPDATE shelf SET chapter_index=?, chapter_title=?, chapter_count=?, scroll=?, updated_at=? " +
                "WHERE source_id=? AND book_url=?",
            arrayOf<Any>(chapterIndex, chapterTitle, chapterCount, scroll, System.currentTimeMillis(), sourceId, bookUrl)
        )
    }

    fun progressOf(sourceId: String, bookUrl: String): Triple<Int, Int, String>? {
        readableDatabase.rawQuery(
            "SELECT chapter_index, scroll, chapter_title FROM shelf WHERE source_id=? AND book_url=? LIMIT 1",
            arrayOf(sourceId, bookUrl)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Triple(c.getInt(0), c.getInt(1), c.getString(2))
        }
    }

    // ---------------- 章节缓存 ----------------

    fun saveChapters(bookUrl: String, chapters: List<Chapter>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chapters", "book_url=?", arrayOf(bookUrl))
            chapters.forEach { ch ->
                val cv = ContentValues().apply {
                    put("book_url", bookUrl)
                    put("idx", ch.index)
                    put("title", ch.title)
                    put("url", ch.url)
                }
                db.insert("chapters", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun loadChapters(bookUrl: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        readableDatabase.rawQuery(
            "SELECT idx, title, url FROM chapters WHERE book_url=? ORDER BY idx ASC",
            arrayOf(bookUrl)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Chapter(c.getInt(0), c.getString(1), c.getString(2)))
            }
        }
        return out
    }

    // ---------------- 正文缓存 ----------------

    fun saveContent(key: String, body: String) {
        val cv = ContentValues().apply {
            put("ckey", key)
            put("body", body)
            put("ts", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("content", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadContent(key: String): String? {
        readableDatabase.rawQuery("SELECT body FROM content WHERE ckey=? LIMIT 1", arrayOf(key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun clearContentCache() {
        writableDatabase.delete("content", null, null)
    }

    fun clearAllCache() {
        writableDatabase.delete("content", null, null)
        writableDatabase.delete("chapters", null, null)
    }

    // ---------------- 整本下载 ----------------

    /** 记录/更新一本的下载进度（断点续抓靠它） */
    fun saveDownloadTask(
        bookUrl: String,
        title: String,
        sourceId: String,
        total: Int,
        done: Int,
        failed: Int,
        finished: Boolean
    ) {
        val cv = ContentValues().apply {
            put("book_url", bookUrl)
            put("title", title)
            put("source_id", sourceId)
            put("total", total)
            put("done", done)
            put("failed", failed)
            put("finished", if (finished) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        try {
            writableDatabase.insertWithOnConflict(
                "download_task", null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
        } catch (e: android.database.sqlite.SQLiteException) {
            createDownloadTable(writableDatabase)
            writableDatabase.insertWithOnConflict(
                "download_task", null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
        }
    }

    /** 这本书的下载进度：(total, done, failed, finished)；没记录返回 null */
    fun downloadTask(bookUrl: String): IntArray? {
        return try {
            readableDatabase.rawQuery(
                "SELECT total, done, failed, finished FROM download_task WHERE book_url=?",
                arrayOf(bookUrl)
            ).use { c ->
                if (!c.moveToFirst()) null
                else intArrayOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            null
        }
    }

    /** 所有下载过的书（按最近更新排） */
    fun downloadTasks(): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        try {
            readableDatabase.rawQuery(
                "SELECT book_url, done, total FROM download_task ORDER BY updated_at DESC", null
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(Triple(c.getString(0), c.getInt(1), c.getInt(2)))
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            // 表不存在就当没有
        }
        return out
    }

    // ---------------- 换源 ----------------

    fun saveSwap(s: BookSwap) {
        val cv = ContentValues().apply {
            put("book_url", s.bookUrl)
            put("source_id", s.sourceId)
            put("source_book_url", s.sourceBookUrl)
            put("matched_title", s.matchedTitle)
            put("matched_author", s.matchedAuthor)
            put("score", s.score)
            put("updated_at", System.currentTimeMillis())
        }
        try {
            writableDatabase.insertWithOnConflict("swap", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: android.database.sqlite.SQLiteException) {
            // 表缺失（历史版本的库没升级）时自愈一次，再不行才放弃
            createSwapTable(writableDatabase)
            writableDatabase.insertWithOnConflict("swap", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /**
     * 读换源记录。
     *
     * 表缺失时返回 null（= 没换过源）而不是抛异常——换源只是附加能力，
     * 不该因为它让整本书的目录都加载不了。
     */
    fun loadSwap(bookUrl: String): BookSwap? {
        return try {
            readableDatabase.rawQuery(
                "SELECT book_url, source_id, source_book_url, matched_title, matched_author, score" +
                    " FROM swap WHERE book_url=? LIMIT 1",
                arrayOf(bookUrl)
            ).use { c ->
                if (!c.moveToFirst()) return null
                BookSwap(
                    bookUrl = c.getString(0),
                    sourceId = c.getString(1),
                    sourceBookUrl = c.getString(2),
                    matchedTitle = c.getString(3),
                    matchedAuthor = c.getString(4),
                    score = c.getInt(5)
                )
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            // 顺手把表补上，下次就正常了
            try {
                createSwapTable(writableDatabase)
            } catch (ignored: Exception) {
                // 补不上也不能影响阅读
            }
            null
        }
    }

    fun clearSwap(bookUrl: String): BookSwap? {
        val old = loadSwap(bookUrl)
        try {
            writableDatabase.delete("swap", "book_url=?", arrayOf(bookUrl))
        } catch (e: android.database.sqlite.SQLiteException) {
            // 表本来就不存在时无需清理
        }
        // 换源后以前的目录/正文缓存都不再适用
        writableDatabase.delete("chapters", "book_url=?", arrayOf(bookUrl))
        return old
    }

    fun clearAllSwap() {
        try {
            writableDatabase.delete("swap", null, null)
        } catch (e: android.database.sqlite.SQLiteException) {
            // 忽略
        }
    }

    companion object {
        /** 加表/改表必须 +1，否则老用户的库不会升级 */
        const val DB_VERSION = 3
    }
}
