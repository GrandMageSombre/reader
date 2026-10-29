package com.modu.reader.data

/** 书籍（列表 / 详情通用） */
data class Book(
    val sourceId: String,
    val url: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val intro: String = "",
    val status: String = "",
    val latestChapter: String = ""
)

/** 章节 */
data class Chapter(
    val index: Int,
    val title: String,
    val url: String
)

/** 书源里定义的一个分类 */
data class Category(
    val name: String,
    val url: String
)

/** 书架条目（带阅读进度） */
data class ShelfBook(
    val sourceId: String,
    val bookUrl: String,
    val title: String,
    val author: String,
    val cover: String,
    val intro: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterCount: Int,
    val scroll: Int,
    val updatedAt: Long
) {
    val percent: Int
        get() = if (chapterCount <= 0) 0
        else (((chapterIndex + 1).toFloat() / chapterCount) * 100).toInt().coerceIn(0, 100)
}
