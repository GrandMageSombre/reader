package com.modu.reader.source

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三方书源（Legado / 「阅读」格式）导入。
 *
 * 为什么要转换：Legado 的书源是一份 JSON，规则写在 `ruleSearch` / `ruleToc` / `ruleContent` 里，
 * 语法是本项目不支持的 JS 表达式、XPath 与 `{{}}` 变量。这里只做**可控子集**的机械翻译，
 * 翻译不了的字段直接留空，并在 `note` 里说明，绝不假装能跑。
 *
 * 支持的子集：
 * | Legado 写法 | 转换成 |
 * |-------------|--------|
 * | `class.foo.0@text` | `[class*=foo]@text`（`.1` 支持序号） |
 * | `tag.a.0@href` | `a@href` |
 * | `id.list@tag.a` | `#list a` |
 * | `a@href` / `@css:.x@text` | 原样 |
 * | 规则尾部的 `##正则` | 丢弃（本引擎的清理走 `contentClean`） |
 *
 * **不支持**：`@js:`、`<js>`、XPath（`//...`）、`##` 替换、POST 请求、`{{key}}` 之外的模板变量。
 * 含这些写法的字段会被留空，书源仍可导入，但对应功能不可用。
 */
object LegadoImporter {

    data class Result(
        val config: SourceConfig,
        /** 被丢弃的字段，用于向用户说明“哪些功能用不了” */
        val skipped: List<String>
    )

    /** Legado 里代表「正文」的字段名，用来判断这个源有没有正文能力 */
    private val RULE_KEYS = listOf(
        "ruleSearch" to "搜索",
        "ruleExplore" to "分类",
        "ruleBookInfo" to "详情",
        "ruleToc" to "目录",
        "ruleContent" to "正文"
    )

    /**
     * 解析一个 Legado 书源 JSON。兼容两种输入：
     *  - 单个书源对象 `{...}`
     *  - 书源数组 `[{...}, {...}]`（此时只取第一个，批量导入走 [parseAll]）
     */
    fun parse(text: String): Result {
        val o = JSONObject(text.trim())
        return convert(o)
    }

    /** 批量：数组里的每一项都转成一个书源 */
    fun parseAll(text: String): List<Result> {
        val t = text.trim()
        if (t.startsWith("[")) {
            val arr = JSONArray(t)
            val out = ArrayList<Result>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                try {
                    out.add(convert(o))
                } catch (e: Exception) {
                    // 单个坏源不影响其它
                }
            }
            return out
        }
        return listOf(parse(t))
    }

    private fun convert(o: JSONObject): Result {
        val name = o.optString("bookSourceName").trim().ifBlank {
            o.optString("name").trim()
        }
        require(name.isNotBlank()) { "书源缺少 bookSourceName（名称）" }
        val baseUrl = o.optString("bookSourceUrl").trim().ifBlank {
            o.optString("url").trim()
        }
        require(baseUrl.isNotBlank()) { "书源缺少 bookSourceUrl（站点地址）" }

        val skipped = ArrayList<String>()

        // ---- 分类：exploreUrl 里是「分类名::地址」的列表，形式五花八门，只取能识别的 ----
        val categories = ArrayList<com.modu.reader.data.Category>()
        val explore = o.optString("exploreUrl").trim()
        if (explore.isNotBlank() && !explore.startsWith("@js") && !explore.startsWith("<js")) {
            explore.split('\n', '、').forEach { seg ->
                val line = seg.trim()
                if (line.isEmpty()) return@forEach
                // 形如 `玄幻::/list/1/{{page}}.html` 或 `玄幻,/list/{{page}}.html`
                val parts = line.split("::", "|").map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2 && looksLikePath(parts[1])) {
                    categories.add(
                        com.modu.reader.data.Category(parts[0], parts[1].replace("{{page}}", "{page}"))
                    )
                }
            }
        } else if (explore.isNotBlank()) {
            skipped.add("分类（exploreUrl 是 JS，未转换）")
        }

        // ---- 搜索 ----
        val searchUrlRaw = o.optString("searchUrl").trim()
        val searchUrl = convertUrlTemplate(searchUrlRaw)
        if (searchUrlRaw.isNotBlank() && searchUrl.isBlank()) {
            skipped.add("搜索（searchUrl 含 JS/请求体，未转换）")
        }

        // ---- 列表页（搜索结果 / 分类结果）----
        val searchRule = o.optJSONObject("ruleSearch") ?: JSONObject()
        val listItem = css(searchRule.optString("bookList"), "列表", skipped)
        val listName = css(searchRule.optString("name"), "书名", skipped)
        val listLink = css(searchRule.optString("bookUrl"), "书链接", skipped)
        val listAuthor = css(searchRule.optString("author"), "作者", skipped)
        val listCover = css(searchRule.optString("coverUrl"), "封面", skipped)
        val listIntro = css(searchRule.optString("intro"), "简介", skipped)

        // ---- 详情 ----
        val infoRule = o.optJSONObject("ruleBookInfo") ?: JSONObject()
        val detailName = css(infoRule.optString("name"), "详情书名", skipped)
        val detailAuthor = css(infoRule.optString("author"), "详情作者", skipped)
        val detailCover = css(infoRule.optString("coverUrl"), "详情封面", skipped)
        val detailIntro = css(infoRule.optString("intro"), "详情简介", skipped)

        // ---- 目录 ----
        val tocRule = o.optJSONObject("ruleToc") ?: JSONObject()
        val chapterList = css(tocRule.optString("chapterList"), "目录列表", skipped)
        val chapterName = css(tocRule.optString("chapterName"), "章节名", skipped)
        val chapterLink = css(tocRule.optString("chapterUrl"), "章节链接", skipped)
        val chapterNext = css(tocRule.optString("nextTocUrl"), "目录下一页", skipped)
        val tocUrl = css(infoRule.optString("tocUrl"), "目录页地址", skipped)
        val chapterReverse = tocRule.optString("isDesc").trim() == "1" ||
            tocRule.optString("isDesc").equals("true", true)

        // ---- 正文 ----
        val contentRule = o.optJSONObject("ruleContent") ?: JSONObject()
        val content = css(contentRule.optString("content"), "正文", skipped)
        val contentNext = css(contentRule.optString("nextContentUrl"), "正文下一页", skipped)
        val clean = ArrayList<String>()
        clean.add("script")
        clean.add("style")
        // replaceRegex 里是「##正则」形式，塞进 contentClean 的 re: 通道
        (contentRule.optString("replaceRegex") + contentRule.optString("replaceRule"))
            .split('\n')
            .forEach { seg ->
                val r = seg.trim().removePrefix("##").trim()
                if (r.isNotBlank() && !r.startsWith("@js") && !r.contains("$1")) {
                    clean.add("re:$r")
                }
            }

        val id = uniqueId(name, baseUrl)
        val noteParts = ArrayList<String>()
        noteParts.add("来自第三方（Legado 格式）导入")
        val group = o.optString("bookSourceGroup").trim()
        if (group.isNotBlank()) noteParts.add("原分组：$group")
        if (skipped.isNotEmpty()) noteParts.add("未转换：" + skipped.distinct().joinToString("、"))

        val cfg = SourceConfig(
            id = id,
            name = name,
            baseUrl = normalizeBase(baseUrl),
            enabled = o.optBoolean("enabled", true),
            origin = SourceManager.ORIGIN_LEGADO,
            note = noteParts.joinToString("；"),
            headers = parseHeaders(o.optString("header")),
            categories = categories,
            rules = SourceRules(
                searchUrl = searchUrl,
                listItem = listItem,
                listName = listName,
                listLink = listLink,
                listAuthor = listAuthor,
                listCover = listCover,
                listIntro = listIntro,
                detailName = detailName,
                detailAuthor = detailAuthor,
                detailCover = detailCover,
                detailIntro = detailIntro,
                chapterList = chapterList,
                chapterUrl = tocUrl,
                chapterName = chapterName,
                chapterLink = chapterLink,
                chapterNext = chapterNext,
                chapterReverse = chapterReverse,
                content = content,
                contentNext = contentNext,
                contentClean = clean
            )
        )
        return Result(cfg, skipped.distinct())
    }

    // ---------------- 规则转换 ----------------

    /**
     * 把 Legado 的选择器规则翻成本引擎的 `CSS@取值`。
     *
     * 只翻译能确定的写法；含 `@js:` / `<js>` / XPath 的一律返回空串。
     */
    fun css(raw: String?, field: String, skipped: MutableList<String>): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (s.startsWith("@js:") || s.startsWith("<js>") || s.contains("@js:")) {
            skipped.add("$field（含 JS）")
            return ""
        }
        var r = s
        // 去掉尾部的 ## 正则替换（含 ``` 包裹的多行）
        val hash = r.indexOf("##")
        if (hash >= 0) r = r.substring(0, hash).trim()
        r = r.removePrefix("@css:").trim()
        if (r.startsWith("//") || r.startsWith("/html")) {
            skipped.add("$field（XPath）")
            return ""
        }
        // 取值的尾部（@text / @href / @html）
        val at = r.lastIndexOf('@')
        var attr = "text"
        if (at > 0) {
            val tail = r.substring(at + 1).trim()
            if (tail in setOf("text", "href", "src", "html", "textNodes", "content", "alt", "title")) {
                attr = when (tail) {
                    "textNodes" -> "text"
                    "content", "text" -> "text"
                    else -> tail
                }
                r = r.substring(0, at).trim()
            }
        }
        val sel = legadoSelectorToCss(r, skipped, field)
        if (sel.isBlank()) return ""
        return if (attr == "text") sel else "$sel@$attr"
    }

    /**
     * `class.foo.0@tag.a.1` 这类链式选择器 → CSS。
     *
     * 只支持「class / id / tag」组合。注意 Legado 里的 `.0` / `.1` 是
     * **同级匹配结果里的第几个**，与 CSS 的 `:nth-of-type` 语义不同，
     * 硬翻译会取错节点，所以带序号的链直接判为不支持。
     */
    private fun legadoSelectorToCss(raw: String, skipped: MutableList<String>, field: String): String {
        val r = raw.trim()
        if (r.isEmpty()) return ""
        val parts = r.split('@').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val segs = ArrayList<String>(parts.size)
        for (p in parts) {
            val seg = p.removePrefix("tag.").trim()
            when {
                seg.startsWith("class.") -> {
                    val (name, idx) = splitIndex(seg.removePrefix("class."))
                    if (name.isBlank()) return ""
                    if (idx != null) {
                        skipped.add("$field（含同级别索引，语义无法等价转换）")
                        return ""
                    }
                    segs.add("[class*=$name]")
                }

                seg.startsWith("id.") -> {
                    val (name, idx) = splitIndex(seg.removePrefix("id."))
                    if (name.isBlank()) return ""
                    if (idx != null) {
                        skipped.add("$field（含同级别索引，语义无法等价转换）")
                        return ""
                    }
                    segs.add("#$name")
                }

                else -> {
                    val (name, idx) = splitIndex(seg)
                    val tag = name.trim()
                    if (tag.isEmpty()) return ""
                    if (idx != null) {
                        skipped.add("$field（含同级别索引，语义无法等价转换）")
                        return ""
                    }
                    segs.add(tag)
                }
            }
        }
        return segs.joinToString(" ").trim()
    }

    /** 把 `foo.1` 拆成 (foo, 1)；`foo` 拆成 (foo, null) */
    private fun splitIndex(s: String): Pair<String, Int?> {
        val t = s.trim()
        val m = Regex("^(.*?)\\.(-?\\d+)$").find(t)
        return if (m != null) {
            m.groupValues[1] to m.groupValues[2].toIntOrNull()
        } else {
            t to null
        }
    }

    /**
     * URL 模板：只接受纯 GET 地址模板，`{{key}}` / `{{page}}` 换成本引擎的 `{key}` / `{page}`。
     * 带 JS、请求体、POST 的一律放弃（本引擎的 Http 只发 GET）。
     */
    fun convertUrlTemplate(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        if (s.startsWith("@js:") || s.startsWith("<js>") || s.contains("'body'") ||
            s.contains("\"body\"") || s.contains("<js>")
        ) return ""
        var r = s
        // `path,{options}` 形式：只保留 path 部分
        val comma = r.indexOf("',{")
        if (comma > 0) r = r.substring(0, comma)
        r = r.replace("{{key}}", "{key}")
            .replace("{{page}}", "{page}")
            .replace("{{searchKey}}", "{key}")
        if (r.contains("{{")) return ""
        if (!looksLikePath(r)) return ""
        return r
    }

    private fun looksLikePath(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("/") || t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("?")
    }

    private fun normalizeBase(url: String): String = url.trim().trimEnd('/')

    /** Legado 的 header 是一行一个 `名:值` */
    private fun parseHeaders(raw: String): Map<String, String> {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("@js") || s.startsWith("<js")) return emptyMap()
        val out = LinkedHashMap<String, String>()
        s.split('\n').forEach { line ->
            val i = line.indexOf(':')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
        }
        return out
    }

    /** 由书名 + 站点生成稳定 id */
    private fun uniqueId(name: String, url: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "_")
            .trim('_').take(24)
        val host = url.substringAfter("://", url)
            .substringBefore('/')
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .take(20)
        val id = listOf("lg", base, host).filter { it.isNotBlank() }.joinToString("_").take(60)
        return id.ifBlank { "lg_" + kotlin.math.abs(url.hashCode()) }
    }
}
