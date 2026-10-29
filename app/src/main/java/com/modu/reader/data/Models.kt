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

/**
 * 换源记录：把「发现源」里的一本书，映射到某个第三方源上阅读。
 *
 * - [bookUrl]：发现源里的书地址（书架、进度都按它记）
 * - [sourceId] + [sourceBookUrl]：真正拿去抓目录与正文的那本书
 * - [score]：匹配得分，供「换源」列表里排序展示
 */
data class BookSwap(
    val bookUrl: String,
    val sourceId: String,
    val sourceBookUrl: String,
    val matchedTitle: String = "",
    val matchedAuthor: String = "",
    val score: Int = 0
)

/**
 * 换源候选：在第三方源里搜到的一本书。
 */
data class SwapCandidate(
    val sourceId: String,
    val sourceName: String,
    val bookUrl: String,
    val title: String,
    val author: String = "",
    val cover: String = "",
    val score: Int = 0
) {
    /** 是否与目标书同名 */
    val exact: Boolean get() = score >= 100
}
