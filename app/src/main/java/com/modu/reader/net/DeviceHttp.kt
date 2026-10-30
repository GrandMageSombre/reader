package com.modu.reader.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 手机端直连用的 HTTP 门面：**自动带票**。
 *
 * 起点/番茄的公开页有时候直接 200，有时候先给一张指纹页或验证码中间页。
 * 这里统一处理：先拿 cookie（[WebGate] 的隐藏 WebView 过一遍），发请求；
 * 如果回来的东西明显是挑战页（很短 / 含 TCaptcha / 没有正文容器），
 * 就强制重新过一次再试一次，仍失败才把原文返回给调用方判断。
 *
 * 这样上层（书源、抓取器）都不必关心「这一站今天要不要过验证码」。
 */
object DeviceHttp {

    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** host -> cookie */
    private val cookies = HashMap<String, String>()

    private fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore("/")

    private fun rootOf(url: String): String {
        val scheme = url.substringBefore("://")
        return "$scheme://${hostOf(url)}/"
    }

    fun forget(url: String) {
        cookies.remove(hostOf(url))
    }

    private suspend fun cookieFor(context: Context, url: String, force: Boolean): String {
        val host = hostOf(url)
        if (!force) cookies[host]?.let { return it }
        val cookie = WebGate.cookieFor(context, rootOf(url), force = force)
        if (cookie.isNotBlank()) cookies[host] = cookie
        return cookie
    }

    /** 明显不是正常页面（验证码 / 指纹页 / 空壳） */
    private fun looksChallenged(body: String): Boolean {
        if (body.length < 3000) return true
        return body.contains("TCaptcha") || body.contains("captcha") ||
            body.contains("probe.js")
    }

    /**
     * GET 一个页面/接口。
     *
     * @param expect 用来判断「这页是不是真内容」的关键片段（如 `catalog-volume`）；
     *               给了它，命中挑战页就会自动换票重试一次
     */
    suspend fun get(
        context: Context,
        url: String,
        referer: String? = null,
        expect: String? = null
    ): String = withContext(Dispatchers.IO) {
        var cookie = cookieFor(context, url, force = false)
        var body = fetch(url, cookie, referer)
        val bad = body.isBlank() ||
            (expect != null && !body.contains(expect) && looksChallenged(body))
        if (bad) {
            forget(url)
            cookie = cookieFor(context, url, force = true)
            body = fetch(url, cookie, referer)
        }
        body
    }

    private fun fetch(url: String, cookie: String, referer: String?): String = try {
        Http.get(
            url, "utf-8",
            buildMap {
                put("User-Agent", UA)
                put("Cookie", cookie)
                put("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
                put("Referer", referer ?: rootOf(url))
            }
        )
    } catch (e: Exception) {
        ""
    }
}
