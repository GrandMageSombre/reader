package com.modu.reader.repo

import com.modu.reader.App
import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.data.ShelfBook
import com.modu.reader.source.BookSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 业务胶水层：书源 + 本地缓存 + 书架。
 * 网络请求全部在 IO 线程，UI 只调这里。
 */
class BookRepo(private val app: App) {

    /** 生命周期跟 App 一致，用于「界面已经关掉也要落盘」的写操作 */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun src(id: String): BookSource =
        app.sources.source(id) ?: throw IllegalStateException("书源不存在或已被删除")

    suspend fun categories(sourceId: String): List<Category> = withContext(Dispatchers.IO) {
        src(sourceId).categories()
    }

    suspend fun list(sourceId: String, category: Category, page: Int): List<Book> =
        withContext(Dispatchers.IO) { src(sourceId).list(category, page) }

    suspend fun search(sourceId: String, key: String, page: Int): List<Book> =
        withContext(Dispatchers.IO) { src(sourceId).search(key, page) }

    suspend fun detail(sourceId: String, url: String, fallbackTitle: String = ""): Book =
        withContext(Dispatchers.IO) {
            val b = src(sourceId).detail(Book(sourceId, url, fallbackTitle))
            if (app.db.isOnShelf(sourceId, url)) app.db.addToShelf(b)
            b
        }

    /**
     * 章节目录：优先网络，失败或为空时回落到本地缓存。
     */
    suspend fun chapters(sourceId: String, bookUrl: String, title: String): List<Chapter> =
        withContext(Dispatchers.IO) {
            val cached = app.db.loadChapters(bookUrl)
            try {
                val fresh = src(sourceId).chapters(Book(sourceId, bookUrl, title))
                if (fresh.isNotEmpty()) {
                    app.db.saveChapters(bookUrl, fresh)
                    fresh
                } else {
                    cached
                }
            } catch (e: Exception) {
                if (cached.isNotEmpty()) cached else throw e
            }
        }

    /**
     * 正文：本地缓存优先（翻回去看不用再联网），没有才抓。
     * force = true 时无视缓存重新抓（阅读器里「重试」用）。
     */
    suspend fun content(
        sourceId: String,
        bookUrl: String,
        bookTitle: String,
        chapter: Chapter,
        force: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val key = contentKey(bookUrl, chapter.index)
        if (!force) {
            app.db.loadContent(key)?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        }
        val text = src(sourceId).content(Book(sourceId, bookUrl, bookTitle), chapter)
        if (text.isNotBlank()) app.db.saveContent(key, text)
        text
    }

    /** 阅读进度（章节序号 / 滚动位置 / 章节标题），强制走 IO */
    suspend fun progress(sourceId: String, bookUrl: String): Triple<Int, Int, String>? =
        withContext(Dispatchers.IO) { app.db.progressOf(sourceId, bookUrl) }

    suspend fun prefetch(sourceId: String, bookUrl: String, bookTitle: String, chapter: Chapter) {
        try {
            val key = contentKey(bookUrl, chapter.index)
            if (!app.db.loadContent(key).isNullOrBlank()) return
            val text = src(sourceId).content(Book(sourceId, bookUrl, bookTitle), chapter)
            if (text.isNotBlank()) app.db.saveContent(key, text)
        } catch (e: Exception) {
            // 预取失败无所谓
        }
    }

    private fun contentKey(bookUrl: String, index: Int) = "$bookUrl#$index"

    // ---------------- 书架 ----------------

    suspend fun shelf(): List<ShelfBook> = withContext(Dispatchers.IO) { app.db.shelfList() }

    suspend fun isOnShelf(sourceId: String, bookUrl: String): Boolean =
        withContext(Dispatchers.IO) { app.db.isOnShelf(sourceId, bookUrl) }

    suspend fun addToShelf(book: Book) = withContext(Dispatchers.IO) { app.db.addToShelf(book) }

    suspend fun removeFromShelf(sourceId: String, bookUrl: String) =
        withContext(Dispatchers.IO) { app.db.removeFromShelf(sourceId, bookUrl) }

    fun saveProgressAsync(
        sourceId: String,
        bookUrl: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterCount: Int,
        scroll: Int
    ) {
        io.launch {
            try {
                app.db.saveProgress(sourceId, bookUrl, chapterIndex, chapterTitle, chapterCount, scroll)
            } catch (e: Exception) {
                // 忽略写库异常
            }
        }
    }

    fun clearCacheAsync() {
        io.launch {
            try {
                app.db.clearAllCache()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    fun contentCached(bookUrl: String, index: Int): Boolean =
        !app.db.loadContent(contentKey(bookUrl, index)).isNullOrBlank()
}
