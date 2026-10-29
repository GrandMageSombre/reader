package com.modu.reader.source

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 规则求值器。
 *
 * 规则写法：`CSS选择器@取值`，取值支持
 *  - text     （默认）取可见文本
 *  - ownText  只取本节点直属文本
 *  - html     取内部 HTML
 *  - href / src / title / alt / value  取属性
 *  - attr:xxx 取任意属性
 *  - abs:href 取绝对地址（相对 baseUri 解析）
 *
 * 省略 `@` 时按 `选择器@text` 处理；只写 `text` / `href` 这类取值时，
 * 表示对「当前节点自己」取值（章节列表里逐项取值要用这种写法）。
 */
object Rule {

    /**
     * 可以出现在 `@` 后面的取值关键字。
     *
     * 必须严格白名单：如果放宽成「任意标识符」，那么 `listName = "a"`（裸标签选择器）
     * 会被误判成「对当前节点取 a 属性」，选择器变空、规则静默失效。
     * 需要取任意属性时用 `attr:xxx`。
     */
    private val ATTRS = setOf(
        "text", "html", "ownText", "href", "src", "title", "alt", "value",
        "content", "id", "class", "name", "datetime"
    )

    /** `@` 后面是否是取值（关键字 / attr: / abs: / data-* 属性） */
    private fun isAttrToken(s: String): Boolean =
        s in ATTRS || s.startsWith("attr:") || s.startsWith("abs:") ||
            s.startsWith("data-")

    /**
     * 拆分 `选择器@取值`。
     *
     * 注意：`@` 也可能出现在 CSS 属性选择器里（如 `meta[property='og:image']@content`），
     * 裸取值的 `@` 只能在**引号之外**找，否则会把选择器从引号中间劈开。
     */
    private fun split(rule: String): Pair<String, String> {
        val r = rule.trim()
        if (isAttrToken(r)) return "" to r
        val i = lastAtOutsideQuotes(r)
        if (i < 0) return r to "text"
        val tail = r.substring(i + 1).trim()
        return if (isAttrToken(tail)) r.substring(0, i).trim() to tail else r to "text"
    }

    /** 找到最后一个不在引号内的 `@`，找不到返回 -1 */
    private fun lastAtOutsideQuotes(s: String): Int {
        var quote: Char? = null
        var result = -1
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '@' -> result = i
            }
            i++
        }
        return result
    }

    fun selectAll(rule: String?, root: Element): List<Element> {
        if (rule.isNullOrBlank()) return emptyList()
        val (css, _) = split(rule)
        if (css.isEmpty()) return listOf(root)
        return try {
            root.select(css)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun selectFirst(rule: String?, root: Element): Element? = selectAll(rule, root).firstOrNull()

    fun value(el: Element, attr: String): String = when {
        attr == "text" -> el.text().trim()
        attr == "html" -> el.html()
        attr == "ownText" -> el.ownText().trim()
        attr.startsWith("abs:") -> {
            val a = attr.removePrefix("abs:")
            el.absUrl(a).ifBlank { el.attr(a) }.trim()
        }
        attr.startsWith("attr:") -> el.attr(attr.removePrefix("attr:")).trim()
        else -> el.attr(attr).trim()
    }

    fun eval(rule: String?, root: Element): String {
        if (rule.isNullOrBlank()) return ""
        val el = selectFirst(rule, root) ?: return ""
        val (_, attr) = split(rule)
        return value(el, attr)
    }

    fun evalAll(rule: String?, root: Element): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        val (_, attr) = split(rule)
        return selectAll(rule, root).map { value(it, attr) }.filter { it.isNotBlank() }
    }

    /**
     * 正文提取：按 br / 块级标签拆行，返回「一行一段」的纯文本。
     */
    fun extractText(root: Element): String {
        val sb = StringBuilder()
        walk(root, sb)
        val lines = sb.toString()
            .replace('\u00a0', ' ')
            .replace("\r", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "li", "h1", "h2", "h3", "h4", "h5", "h6",
        "tr", "td", "blockquote", "pre", "dd", "dt"
    )

    private fun walk(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.wholeText)
            is Element -> {
                val tag = node.tagName().lowercase()
                if (tag == "br") {
                    sb.append('\n')
                    return
                }
                if (tag == "script" || tag == "style") return
                val block = tag in BLOCK_TAGS
                if (block) sb.append('\n')
                var i = 0
                val children = node.childNodes()
                while (i < children.size) {
                    walk(children[i], sb)
                    i++
                }
                if (block) sb.append('\n')
            }
            else -> {
                var i = 0
                val children = node.childNodes()
                while (i < children.size) {
                    walk(children[i], sb)
                    i++
                }
            }
        }
    }
}
