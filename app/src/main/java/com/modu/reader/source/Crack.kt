package com.modu.reader.source

import com.modu.reader.data.Chapter

/**
 * 破解判定（**纯逻辑**，不联网、不读库）。
 *
 * 和 PC 端 `tools/spider/crack/plan.py` 是同一套口径（连字面表都要一致）：
 *
 * | 判定 | 含义 | 处理 |
 * |------|------|------|
 * | [Kind.FULL] | 全文 | 入库、算「已破解」 |
 * | [Kind.SHORT] | 有字但短得反常（公告/感言/被截断） | 记失败，下一轮再试 |
 * | [Kind.PREVIEW] | 站方试读（起点 ~136 字、带上「订阅本章」） | 走采集站补全 |
 * | [Kind.LOCKED] | 明确说没买/锁了 | 走采集站补全 |
 * | [Kind.EMPTY] | 空正文 / 内部错误 | 下一轮再试 |
 * | [Kind.BLOCKED] | 被风控 / WAF 脚本壳 / 网络失败 | 停下来歇，不当作「这本书没有」 |
 *
 * 阈值只在 [Paywall.SUSPICIOUS_CHARS] 定义一次（220，实测起点 VIP 试读 126–136 字）。
 * 之前 App 与 PC 各自写过 220 / 400 / 200 三个数字，结果是一边认为「已拿到全文」、
 * 另一边认为「还是试读」，互相覆盖缓存 —— 这类口径必须单点定义。
 *
 * **判定顺序**：风控 → WAF 壳 → 锁定 → 空 → 试读 → 长度。
 * 风控必须在最前：note 里同时有 `chapterLock` 与「验证码」时若判成 LOCKED，
 * 熔断就不会触发，爬虫会一直打已经被封的站点。
 */
object Crack {

    enum class Kind {
        FULL, SHORT, PREVIEW, LOCKED, EMPTY, BLOCKED;

        /** 只有 FULL 能入库 */
        val usable: Boolean get() = this == FULL

        /** 还要不要继续换下一条路 */
        val needMore: Boolean get() = this != FULL
    }

    /** 与 [Paywall.SUSPICIOUS_CHARS] 同一个数，别在这里另写一个 */
    const val MIN_FULL_CHARS = Paywall.SUSPICIOUS_CHARS

    /** 连续被风控几次就停（PC 端 `runner.MAX_BLOCKED_STREAK` 同一个数） */
    const val MAX_BLOCKED_STREAK = 3

    /** 付费/试读字样（比 [Paywall.PREVIEW_HINTS] 多两条：番茄的会员引导与 app 侧标记） */
    val PREVIEW_MARKS: List<String> = Paywall.PREVIEW_HINTS + listOf("付费后可读", "本章未完，请订阅")

    /** 接口层的锁定标记（与 PC 端 `plan.LOCK_MARKS` 逐条一致） */
    val LOCK_MARKS: List<String> = listOf(
        "本章尚未购买", "章节已锁定", "需付费", "need_pay", "needPay",
        "chapterLock", "isChapterLock"
    )

    /** 风控/网络类：判 BLOCKED（参与熔断）。注意 `\b403\b`：别把「14030 字」当状态码 */
    private val BLOCK_PATTERN = Regex(
        "(?<![\\d.])403(?![\\d.])|429|风控|验证码|captcha|timeout|timed out|connection|" +
            "reset by peer|ssl|proxy",
        RegexOption.IGNORE_CASE
    )

    /** WAF 拦下来时拿到的是脚本壳（实测 m.qidian.com 825 字节）：不是正文，也不能算「空」 */
    private val SCRIPT_MARKS = listOf(
        "function(", "document.", "_0x", "captcha", "window.__", "eval(", "var ref"
    )

    /** 采集站/正站模板噪声行：先剥掉再算长度，否则「本章未完请点击下一页」会把短章撑成全文 */
    val JUNK_MARKS: List<String> = listOf(
        "本章未完", "请记住本书", "手机版阅读", "笔趣阁", "www.", "http://", "https://",
        "最新网址", "加入书签", "投推荐票", "求月票", "本章完", "（本章完）"
    )

    /** 剥噪声行；长行（≥120 字）不剥，避免按行误删正文 */
    fun stripJunk(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line -> line.length < 120 && JUNK_MARKS.any { line.contains(it) } }
            .joinToString("\n")

    private fun cjkRatio(text: String): Float {
        var total = 0
        var cjk = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            total++
            if (ch.code in 0x4E00..0x9FFF) cjk++
        }
        return if (total == 0) 0f else cjk.toFloat() / total
    }

    /** WAF/JS 壳：有脚本特征且几乎没有汉字 */
    fun looksLikeScript(text: String, note: String = ""): Boolean {
        val body = if (text.length > note.length) text else note
        if (body.length < 40) return false
        return SCRIPT_MARKS.any { body.contains(it) } && cjkRatio(body) < 0.3f
    }

    /** 一段正文 + 备注 → 成色 */
    fun of(text: String, note: String = ""): Kind {
        if (BLOCK_PATTERN.containsMatchIn(note)) return Kind.BLOCKED
        if (looksLikeScript(text, note)) return Kind.BLOCKED
        val low = note.lowercase()
        if (LOCK_MARKS.any { low.contains(it.lowercase()) }) return Kind.LOCKED
        val body = stripJunk(text)
        if (body.isBlank()) return Kind.EMPTY
        // 试读标记可能出现在正文前，也可能跟在正文尾（「……试读结束」），两头都扫
        val head = body.take(600)
        val tail = body.takeLast(200)
        if (PREVIEW_MARKS.any { head.contains(it) } || PREVIEW_MARKS.any { tail.contains(it) } ||
            PREVIEW_MARKS.any { note.contains(it) }
        ) {
            return Kind.PREVIEW
        }
        if (body.length < MIN_FULL_CHARS) return Kind.SHORT
        return Kind.FULL
    }

    fun isFull(text: String): Boolean = of(text) == Kind.FULL

    /** 一条待补的章 */
    data class Gap(val index: Int, val title: String)

    /**
     * 还差哪些章：目录里没有缓存正文的，就是待补。
     *
     * [cached] 由调用方注入（读库在 IO 线程），所以本函数仍是纯的、可单测。
     */
    fun gaps(chapters: List<Chapter>, cached: (Int) -> Boolean): List<Gap> =
        chapters.filterNot { cached(it.index) }
            .map { Gap(it.index, it.title) }

    /** 一轮补全的战果（详情页一行字说清，别让用户猜） */
    data class Report(
        val total: Int = 0,
        val full: Int = 0,
        val failed: Int = 0,
        val blocked: Int = 0
    ) {
        fun text(): String = buildString {
            append("破解补全：待补 $total 章 · 成功 $full 章")
            if (failed > 0) append(" · 失败 $failed 章")
            if (blocked > 0) append(" · 被风控中断 $blocked 次")
        }
    }
}
