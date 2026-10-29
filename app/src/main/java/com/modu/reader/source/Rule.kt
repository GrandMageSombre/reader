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

    private val ATTRS = setOf("text", "html", "ownText", "href", "src", "title", "alt", "value")

    private fun isAttrToken(s: String): Boolean =
        s in ATTRS || s.startsWith("attr:") || s.startsWith("abs:")

    private fun split(rule: String): Pair<String, String> {
        val r = rule.trim()
        val i = r.lastIndexOf('@')
        if (i < 0) {
            return if (isAttrToken(r)) "" to r else r to "text"
        }
        val tail = r.substring(i + 1).trim()
        return if (isAttrToken(tail)) r.substring(0, i).trim() to tail else r to "text"
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
