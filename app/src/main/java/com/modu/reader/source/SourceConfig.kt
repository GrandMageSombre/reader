package com.modu.reader.source

import com.modu.reader.data.Category
import org.json.JSONArray
import org.json.JSONObject

/** 一个书源里所有 CSS 规则 */
data class SourceRules(
    val searchUrl: String = "",
    val listItem: String = "",
    val listName: String = "",
    val listAuthor: String = "",
    val listCover: String = "",
    val listLink: String = "",
    val listIntro: String = "",
    /**
     * 列表/搜索结果的「书名 / 作者」清洗规则，按顺序应用：
     * `re:xxx` 是正则（命中部分被删除），其余当字面量删除。
     * 典型用途：起点书卡的书名取自 img@alt，尾部带「在线阅读」后缀。
     */
    val listClean: List<String> = emptyList(),
    /**
     * 搜索结果页与分类页结构不同时，用这组规则覆盖 [listItem] 等。
     * 留空表示搜索结果页复用列表页规则。
     *
     * 典型场景：分类页是卡片（`div.item` + `dl>dt>a`），
     * 搜索结果是表格（`table.grid tr` + `td a`）。
     */
    val searchItem: String = "",
    val searchName: String = "",
    val searchLink: String = "",
    val searchAuthor: String = "",
    val searchCover: String = "",
    val searchIntro: String = "",
    val detailName: String = "",
    val detailAuthor: String = "",
    val detailCover: String = "",
    val detailIntro: String = "",
    val detailStatus: String = "",
    val chapterList: String = "",
    val chapterUrl: String = "",
    val chapterName: String = "",
    val chapterLink: String = "",
    val chapterNext: String = "",
    val chapterReverse: Boolean = false,
    val content: String = "",
    val contentNext: String = "",
    val contentClean: List<String> = emptyList()
)

/**
 * 书源配置。全部字段都能落成 JSON，方便导入 / 导出 / 手改。
 *
 * 完整语法见 docs/书源规则.md
 */
data class SourceConfig(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val charset: String = "",
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    /** 内置源分组名（如「内置」）；非内置源为空串 */
    val builtinGroup: String = "",
    /** 这个源是从哪来的：`内置` / `导入` / `第三方`，用于书源管理页展示 */
    val origin: String = "",
    val note: String = "",
    val headers: Map<String, String> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val rules: SourceRules = SourceRules()
) {

    companion object {

        fun parse(text: String): SourceConfig {
            val o = try {
                JSONObject(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("不是合法的 JSON：" + (e.message ?: ""))
            }
            val name = o.optString("name").trim()
            var id = o.optString("id").trim()
            if (id.isBlank()) id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            require(name.isNotBlank()) { "书源缺少 name 字段" }
            require(id.isNotBlank()) { "书源缺少 id 字段" }

            val ro = o.optJSONObject("rules") ?: JSONObject()
            val cleanArray = ro.optJSONArray("contentClean")
            val clean = ArrayList<String>()
            if (cleanArray != null) {
                for (i in 0 until cleanArray.length()) {
                    val s = cleanArray.optString(i)
                    if (s.isNotBlank()) clean.add(s)
                }
            }
            val lcArray = ro.optJSONArray("listClean")
            val listClean = ArrayList<String>()
            if (lcArray != null) {
                for (i in 0 until lcArray.length()) {
                    val s = lcArray.optString(i)
                    if (s.isNotBlank()) listClean.add(s)
                }
            }

            val cats = ArrayList<Category>()
            o.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val n = c.optString("name").trim()
                    if (n.isBlank()) continue
                    cats.add(Category(n, c.optString("url").trim()))
                }
            }

            val hs = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.let { h ->
                val keys = h.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    hs[k] = h.optString(k)
                }
            }

            return SourceConfig(
                id = id,
                name = name,
                baseUrl = o.optString("baseUrl").trim(),
                charset = o.optString("charset").trim(),
                enabled = o.optBoolean("enabled", true),
                builtin = o.optBoolean("builtin", false),
                builtinGroup = o.optString("builtinGroup").trim(),
                origin = o.optString("origin").trim(),
                note = o.optString("note").trim(),
                headers = hs,
                categories = cats,
                rules = SourceRules(
                    searchUrl = ro.optString("searchUrl"),
                    listItem = ro.optString("listItem"),
                    listName = ro.optString("listName"),
                    listAuthor = ro.optString("listAuthor"),
                    listCover = ro.optString("listCover"),
                    listLink = ro.optString("listLink"),
                    listIntro = ro.optString("listIntro"),
                    listClean = listClean,
                    searchItem = ro.optString("searchItem"),
                    searchName = ro.optString("searchName"),
                    searchLink = ro.optString("searchLink"),
                    searchAuthor = ro.optString("searchAuthor"),
                    searchCover = ro.optString("searchCover"),
                    searchIntro = ro.optString("searchIntro"),
                    detailName = ro.optString("detailName"),
                    detailAuthor = ro.optString("detailAuthor"),
                    detailCover = ro.optString("detailCover"),
                    detailIntro = ro.optString("detailIntro"),
                    detailStatus = ro.optString("detailStatus"),
                    chapterList = ro.optString("chapterList"),
                    chapterUrl = ro.optString("chapterUrl"),
                    chapterName = ro.optString("chapterName"),
                    chapterLink = ro.optString("chapterLink"),
                    chapterNext = ro.optString("chapterNext"),
                    chapterReverse = ro.optBoolean("chapterReverse", false),
                    content = ro.optString("content"),
                    contentNext = ro.optString("contentNext"),
                    contentClean = clean
                )
            )
        }

        fun toJson(cfg: SourceConfig): String {
            val o = JSONObject()
            o.put("id", cfg.id)
            o.put("name", cfg.name)
            o.put("baseUrl", cfg.baseUrl)
            o.put("charset", cfg.charset)
            o.put("enabled", cfg.enabled)
            if (cfg.builtinGroup.isNotBlank()) o.put("builtinGroup", cfg.builtinGroup)
            if (cfg.origin.isNotBlank()) o.put("origin", cfg.origin)
            if (cfg.note.isNotBlank()) o.put("note", cfg.note)
            if (cfg.headers.isNotEmpty()) {
                val h = JSONObject()
                cfg.headers.forEach { (k, v) -> h.put(k, v) }
                o.put("headers", h)
            }
            val cats = JSONArray()
            cfg.categories.forEach { c ->
                cats.put(JSONObject().put("name", c.name).put("url", c.url))
            }
            o.put("categories", cats)

            val r = cfg.rules
            val ro = JSONObject()
            ro.put("searchUrl", r.searchUrl)
            ro.put("listItem", r.listItem)
            ro.put("listName", r.listName)
            ro.put("listAuthor", r.listAuthor)
            ro.put("listCover", r.listCover)
            ro.put("listLink", r.listLink)
            ro.put("listIntro", r.listIntro)
            val lca = JSONArray()
            r.listClean.forEach { lca.put(it) }
            ro.put("listClean", lca)
            ro.put("searchItem", r.searchItem)
            ro.put("searchName", r.searchName)
            ro.put("searchLink", r.searchLink)
            ro.put("searchAuthor", r.searchAuthor)
            ro.put("searchCover", r.searchCover)
            ro.put("searchIntro", r.searchIntro)
            ro.put("detailName", r.detailName)
            ro.put("detailAuthor", r.detailAuthor)
            ro.put("detailCover", r.detailCover)
            ro.put("detailIntro", r.detailIntro)
            ro.put("detailStatus", r.detailStatus)
            ro.put("chapterList", r.chapterList)
            ro.put("chapterUrl", r.chapterUrl)
            ro.put("chapterName", r.chapterName)
            ro.put("chapterLink", r.chapterLink)
            ro.put("chapterNext", r.chapterNext)
            ro.put("chapterReverse", r.chapterReverse)
            ro.put("content", r.content)
            ro.put("contentNext", r.contentNext)
            val ca = JSONArray()
            r.contentClean.forEach { ca.put(it) }
            ro.put("contentClean", ca)
            o.put("rules", ro)
            return o.toString(2)
        }
    }
}
