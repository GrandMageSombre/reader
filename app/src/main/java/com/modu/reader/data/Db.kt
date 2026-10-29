package com.modu.reader.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地库：书架 / 章节缓存 / 正文缓存。
 * 只用系统 SQLite，不引注解处理器，编译更快也更稳。
 */
class Db(context: Context) : SQLiteOpenHelper(context, "modu.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE shelf(
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
        db.execSQL(
            """CREATE TABLE chapters(
                book_url TEXT NOT NULL,
                idx INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(book_url, idx))"""
        )
        db.execSQL(
            """CREATE TABLE content(
                ckey TEXT PRIMARY KEY,
                body TEXT NOT NULL DEFAULT '',
                ts INTEGER NOT NULL DEFAULT 0)"""
        )
        createSwapTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 只有缓存表结构变化时才走到这里：重建缓存，书架保留
        db.execSQL("DROP TABLE IF EXISTS chapters")
        db.execSQL("DROP TABLE IF EXISTS content")
        db.execSQL(
            """CREATE TABLE chapters(
                book_url TEXT NOT NULL,
                idx INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                url TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(book_url, idx))"""
        )
        db.execSQL(
            """CREATE TABLE content(
                ckey TEXT PRIMARY KEY,
                body TEXT NOT NULL DEFAULT '',
                ts INTEGER NOT NULL DEFAULT 0)"""
        )
        createSwapTable(db)
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
        writableDatabase.insertWithOnConflict("swap", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadSwap(bookUrl: String): BookSwap? {
        readableDatabase.rawQuery(
            "SELECT book_url, source_id, source_book_url, matched_title, matched_author, score" +
                " FROM swap WHERE book_url=? LIMIT 1",
            arrayOf(bookUrl)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BookSwap(
                bookUrl = c.getString(0),
                sourceId = c.getString(1),
                sourceBookUrl = c.getString(2),
                matchedTitle = c.getString(3),
                matchedAuthor = c.getString(4),
                score = c.getInt(5)
            )
        }
    }

    fun clearSwap(bookUrl: String): BookSwap? {
        val old = loadSwap(bookUrl)
        writableDatabase.delete("swap", "book_url=?", arrayOf(bookUrl))
        // 换源后以前的目录/正文缓存都不再适用
        writableDatabase.delete("chapters", "book_url=?", arrayOf(bookUrl))
        return old
    }

    fun clearAllSwap() {
        writableDatabase.delete("swap", null, null)
    }
}
