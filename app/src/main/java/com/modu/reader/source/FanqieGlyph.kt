package com.modu.reader.source

import android.content.Context
import org.json.JSONObject

/**
 * 番茄正文字体反混淆（手机版）。
 *
 * 番茄把正文里一批常用字换成了私有区码位（U+E000–U+F8FF），靠 `@font-face`
 * 下发的子集字体渲染。实测这份字体**同一版本对所有章节通用**（一份字体 = 一套替换表），
 * 所以不需要在手机上解析 woff2，内置一张「码位 → 真字」表即可。
 *
 * 表在 `assets/fanqie_glyphs.json`，由 PC 端 `tools/make_glyphmap.py` 用轮廓哈希生成。
 * 表读不到（或为空）时**不标记已加载**，下次还能再试；解不出的码位原样保留，绝不猜字。
 */
object FanqieGlyph {

    const val ASSET = "fanqie_glyphs.json"

    /** 一次装载的不可变快照：要么整份可用，要么整份缺席，避免「半张表」状态 */
    private class Tables(val byFont: Map<String, Map<Int, String>>) {
        val default: Map<Int, String> get() = byFont["default"] ?: emptyMap()
    }

    @Volatile
    private var tables: Tables? = null

    /** 测试与「换字体后重新装载」用 */
    fun reset() {
        tables = null
    }

    fun ensureLoaded(context: Context) {
        if (tables != null) return
        val text = try {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            ""
        }
        loadFromJson(text)
    }

    /**
     * 从 JSON 装载字形表（`{"fonts":{"字体名":{"e3e9":"在"}}}`）。
     * 拆出来是为了单测能直接喂字符串，不用 Android Context。
     *
     * @return 装载到的字体份数；0 表示 JSON 有问题（此时保持未加载，可重试）
     */
    fun loadFromJson(text: String): Int {
        val out = HashMap<String, Map<Int, String>>()
        if (text.isNotBlank()) {
            try {
                val root = JSONObject(text).optJSONObject("fonts") ?: JSONObject()
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val obj = root.optJSONObject(id) ?: continue
                    val m = HashMap<Int, String>(obj.length())
                    val codes = obj.keys()
                    while (codes.hasNext()) {
                        val hex = codes.next()
                        val code = hex.toIntOrNull(16) ?: continue
                        val ch = obj.optString(hex)
                        if (ch.isNotEmpty()) m[code] = ch
                    }
                    if (m.isNotEmpty()) out[id] = m
                }
            } catch (e: Exception) {
                out.clear()
            }
        }
        if (out.isEmpty()) return 0
        tables = Tables(out)
        return out.size
    }

    /** 取某份字体的表；认不出字体名时用 default */
    private fun table(fontId: String?): Map<Int, String> {
        val t = tables ?: return emptyMap()
        if (fontId != null) {
            t.byFont[fontId]?.let { return it }
        }
        return t.default
    }

    /** 正文里是否还有私有区码位（= 还没还原干净） */
    fun hasObfuscation(text: String): Boolean =
        text.any { it.code in 0xE000..0xF8FF }

    fun obfuscatedRatio(text: String): Float {
        var pua = 0
        var total = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            total++
            if (ch.code in 0xE000..0xF8FF) pua++
        }
        return if (total == 0) 0f else pua.toFloat() / total
    }

    /**
     * 还原正文。认不出的码位原样保留（宁可留个方框，也不猜字）。
     *
     * @param fontId 页面 `@font-face` 的字体文件名（去掉 .woff2）；未知时走 default
     */
    fun decode(text: String, fontId: String? = null): String {
        if (!hasObfuscation(text)) return text
        val map = table(fontId)
        if (map.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            if (code in 0xE000..0xF8FF) {
                sb.append(map[code] ?: ch)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
