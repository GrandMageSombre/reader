package com.modu.reader.source

/**
 * 「这章是不是付费墙挡着」的判断 + 跨站章节名对齐。
 *
 * 起点 VIP 章只下发约 130 字试读（页面带「订阅本章」），番茄付费章可能直接是空正文。
 * 这类正文明摆着不能读，就该走采集站换源——判断逻辑集中在这里，别散到 UI 里。
 *
 * 阈值只在这里定义一次：Repo 决定要不要兜底、DeviceCrawl 决定哪些章算付费，
 * 都引用本类常量，避免三处各写一个数字（曾经 220 / 400 / 200 三个口径打架）。
 */
object Paywall {

    /** 页面上出现这些字样，基本可以断定是付费/试读 */
    val PREVIEW_HINTS = listOf(
        "订阅本章", "本章为VIP章节", "本章为 VIP 章节", "试读结束",
        "购买本章", "本章尚未购买", "登录后阅读", "开通会员"
    )

    /** 太短就当可疑：起点 VIP 试读实测约 130 字，正常章节 1000+ */
    const val SUSPICIOUS_CHARS = 220

    /**
     * 要不要去采集站兜底：**空正文，或明确带付费/试读标记**。
     *
     * 注意这里**不**用「短就是付费」——作者感言、公告常常只有一两百字，
     * 那种章节本来能读，去全网搜一通反而可能被错章覆盖。
     */
    fun needsFallback(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        return PREVIEW_HINTS.any { t.contains(it) }
    }

    /** 更宽的「像付费」判断：给需要统计/展示的地方用（空、带标记、或短得反常） */
    fun looksPaid(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (PREVIEW_HINTS.any { t.contains(it) }) return true
        return t.length < SUSPICIOUS_CHARS
    }

    /** 章节序号：阿拉伯数字（含全角）与中文数字、〇廿卅 */
    private const val NUM = "0-9０-９一二三四五六七八九十百千零两〇廿卅"
    private val PREFIX = Regex("^(第[$NUM]+[章节回卷话])+")
    private val PAREN = Regex("[（(][^）)]{0,12}[）)]")
    private val PUNCT = Regex("[，。！？、：；·“”‘’\"'…—\\-_.·]")

    /**
     * 章节名归一化：去空白、去「第12章/第3卷」前缀（可连续多层）、去括注、去标点。
     *
     * 例：`第11卷 第3章 穷文富武` → `穷文富武`；`第1640章 我已非红尘（1+1/2）` → `我已非红尘`。
     * 剥完变空（比如章名就叫「第3章」）时回退到只去标点的原文，避免把整章判成对不上。
     */
    fun normTitle(raw: String): String {
        var s = raw.replace(Regex("[\\s\\u3000]+"), "")
        s = PREFIX.replace(s, "")
        if (s.isBlank()) s = raw.replace(Regex("[\\s\\u3000]+"), "")
        s = PAREN.replace(s, "")
        s = PUNCT.replace(s, "")
        return s.lowercase().ifBlank { raw.trim().lowercase() }
    }

    /** 两边的章节名能不能对上（一方包含另一方即视为同一章） */
    fun sameChapter(a: String, b: String): Boolean {
        val x = normTitle(a)
        val y = normTitle(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /** 采集站常见的书名噪声：括注、「全本」「校对版」这类后缀 */
    private val BOOK_NOISE = Regex(
        "[（(][^）)]{0,12}[）)]|全本|完结|校对版|精校版?|无删减|TXT|txt|最新章节"
    )

    /**
     * 书名是否同一本。
     *
     * 只认「去掉括注/常见后缀后完全相等」——不能用包含匹配：
     * 搜《全球高武》会搜出《全球高武之死气面板》这种同人作，包含匹配会把同人正文当成原著。
     */
    fun sameBook(a: String, b: String): Boolean {
        fun clean(s: String) = BOOK_NOISE.replace(s, "")
            .replace(Regex("[《》\\s\\u3000·:：]"), "").lowercase()
        val x = clean(a)
        val y = clean(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y
    }

    /**
     * 在候选目录里找最匹配的章节下标；-1 表示没找到。
     *
     * [hintIndex] 是按「两站总章数比例」折算出来的预期位置。采集站常有同名章
     * （「感谢」「请假条」这类），只按标题取第一个会错位，所以命中多个时取**离预期位置最近**的。
     */
    fun findChapter(
        candidates: List<String>,
        target: String,
        hintIndex: Int = -1
    ): Int {
        val t = normTitle(target)
        if (t.isEmpty() || candidates.isEmpty()) return -1

        fun nearest(indices: List<Int>): Int =
            if (hintIndex < 0) indices.first()
            else indices.minByOrNull { kotlin.math.abs(it - hintIndex) }!!

        val exact = candidates.indices.filter { normTitle(candidates[it]) == t }
        if (exact.isNotEmpty()) return nearest(exact)

        val loose = candidates.indices.filter { sameChapter(candidates[it], target) }
        if (loose.isNotEmpty()) return nearest(loose)

        return -1
    }
}
