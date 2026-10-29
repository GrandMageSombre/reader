package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter

/**
 * 书源能力接口。想接自己的后端（JSON API、私有库）时，
 * 实现这个接口并在 SourceManager 里注册即可，UI 层完全不用改。
 */
interface BookSource {

    val cfg: SourceConfig

    /** 分类列表，直接对应站点自己的分类 */
    suspend fun categories(): List<Category>

    /** 某个分类下的书，page 从 1 开始 */
    suspend fun list(category: Category, page: Int): List<Book>

    /** 搜索 */
    suspend fun search(key: String, page: Int): List<Book>

    /** 补全详情 */
    suspend fun detail(book: Book): Book

    /** 章节目录 */
    suspend fun chapters(book: Book): List<Chapter>

    /** 正文（一行一段） */
    suspend fun content(book: Book, chapter: Chapter): String
}
