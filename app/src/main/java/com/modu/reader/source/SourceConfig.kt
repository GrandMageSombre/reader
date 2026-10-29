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
    val detailName: String = "",
    val detailAuthor: String = "",
    val detailCover: String = "",
    val detailIntro: String = "",
    val detailStatus: String = "",
    val chapterList: String = "",
    val chapterName: String = "",
    val chapterLink: String = "",
    val chapterReverse: Boolean = false,
    val content: String = "",
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
                    detailName = ro.optString("detailName"),
                    detailAuthor = ro.optString("detailAuthor"),
                    detailCover = ro.optString("detailCover"),
                    detailIntro = ro.optString("detailIntro"),
                    detailStatus = ro.optString("detailStatus"),
                    chapterList = ro.optString("chapterList"),
                    chapterName = ro.optString("chapterName"),
                    chapterLink = ro.optString("chapterLink"),
                    chapterReverse = ro.optBoolean("chapterReverse", false),
                    content = ro.optString("content"),
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
            ro.put("detailName", r.detailName)
            ro.put("detailAuthor", r.detailAuthor)
            ro.put("detailCover", r.detailCover)
            ro.put("detailIntro", r.detailIntro)
            ro.put("detailStatus", r.detailStatus)
            ro.put("chapterList", r.chapterList)
            ro.put("chapterName", r.chapterName)
            ro.put("chapterLink", r.chapterLink)
            ro.put("chapterReverse", r.chapterReverse)
            ro.put("content", r.content)
            val ca = JSONArray()
            r.contentClean.forEach { ca.put(it) }
            ro.put("contentClean", ca)
            o.put("rules", ro)
            return o.toString(2)
        }
    }
}
