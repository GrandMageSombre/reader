package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.SwapCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 换源：把「发现源」（起点 / 番茄）里的一本书，在其它书源里按书名搜出来。
 *
 * 这是本项目「完整看到内容」的关键一环：
 * 起点 VIP / 番茄正文抓不到，但同一本书通常在第三方源有全本，搜索即可。
 *
 * 匹配打分（[score]）：
 * - 同名 = 100，作者也一致再 +20（用于把正确的书排前面）
 * - 书名包含关系 = 60，作者一致再 +10（「大奉打更人」vs「影视：从大奉打更人开始」）
 * - 其余不计
 *
 * 约定：只返回 score > 0 的候选，按分数降序。
 */
object SwapMatcher {

    /** 书名归一化：去空白、全半角括号、常见前后缀 */
    fun normalize(s: String): String = s
        .replace('\u3000', ' ')
        .replace(Regex("[\\s]+"), "")
        .replace(Regex("[《》〈〉\"'“”‘’()（）\\[\\]]"), "")
        .trim()

    fun score(targetTitle: String, targetAuthor: String, candTitle: String, candAuthor: String): Int {
        val t = normalize(targetTitle)
        val c = normalize(candTitle)
        if (t.isBlank() || c.isBlank()) return 0
        val authorHit = targetAuthor.isNotBlank() && candAuthor.isNotBlank() &&
            (normalize(targetAuthor).let { normalize(candAuthor).contains(it) } ||
                normalize(candAuthor).let { normalize(targetAuthor).contains(it) })

        return when {
            t == c -> if (authorHit) 120 else 100
            c.contains(t) || t.contains(c) -> if (authorHit) 70 else 60
            else -> 0
        }
    }

    /**
     * 在若干书源里按书名搜索，汇总候选。
     *
     * @param sources 参与换源的源（建议排除发现源本身）
     * @param limitPerSource 每个源最多取几个候选，防止候选爆炸
     */
    suspend fun find(
        book: Book,
        sources: List<BookSource>,
        limitPerSource: Int = 8
    ): List<SwapCandidate> = withContext(Dispatchers.IO) {
        val key = book.title.trim()
        if (key.isBlank()) return@withContext emptyList()

        val results = coroutineScope {
            sources.map { src ->
                async {
                    try {
                        val found = src.search(key, 1)
                        found.mapNotNull { b ->
                            val s = score(book.title, book.author, b.title, b.author)
                            if (s <= 0) null
                            else SwapCandidate(
                                sourceId = src.cfg.id,
                                sourceName = src.cfg.name,
                                bookUrl = b.url,
                                title = b.title,
                                author = b.author,
                                cover = b.cover,
                                score = s
                            )
                        }.sortedByDescending { it.score }.take(limitPerSource)
                    } catch (e: Exception) {
                        // 单个源失败不影响其它源
                        emptyList()
                    }
                }
            }.awaitAll()
        }
        results.flatten()
            .distinctBy { it.sourceId to it.bookUrl }
            .sortedWith(compareByDescending<SwapCandidate> { it.score }.thenBy { it.sourceName })
    }
}
