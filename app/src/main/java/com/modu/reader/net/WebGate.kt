package com.modu.reader.net

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「手机端过反爬」的桥：用一个不显示的 WebView 跑一遍页面，把站点下发的 cookie 取出来。
 *
 * 为什么需要它（实测）：
 * - 起点 PC 站裸请求返回 **202 + probe.js**，那是一个指纹脚本，跑完才下发通行 cookie；
 * - 番茄连续抓多了会掉进 ByteDance 的**验证码中间页**，同样要真浏览器跑一次。
 *
 * 手机上本来就有 WebView（真 Chromium），这两步都能过——所以「在手机上抓」
 * 并不需要把 PC 那套 headless Edge 搬过来，用这个类就行。
 *
 * 拿到 cookie 后交给 [com.modu.reader.source.DeviceCrawl]，后续请求全是普通 GET。
 */
object WebGate {

    /**
     * 与 [com.modu.reader.source.DeviceCrawl] 请求时用的 UA 必须**一致**：
     * cookie 是按 UA/指纹下发的，WebView 用移动 UA 拿的票，换个桌面 UA 去用容易被判异常。
     */
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 进程内缓存：同一地址只过一次（有上限，别当永久字典） */
    private val cache = HashMap<String, String>()
    private const val CACHE_MAX = 16

    /**
     * 跑一遍 [url]，返回该地址的 Cookie 头。
     *
     * @param waitMs 页面 load 完之后再等多久，给 probe.js / 验证码 sdk 留执行时间
     */
    suspend fun cookieFor(
        context: Context,
        url: String,
        waitMs: Long = 6000L,
        force: Boolean = false
    ): String = withContext(Dispatchers.Main) {
        if (!force) cache[url]?.let { return@withContext it }

        CookieManager.getInstance().setAcceptCookie(true)
        val view = try {
            WebView(context.applicationContext)
        } catch (e: Exception) {
            return@withContext ""
        }
        var finished = false
        try {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
            }
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) {
                    finished = true
                }

                // 主文档加载失败也要放行，否则每次都白等满 20 秒
                override fun onReceivedError(
                    v: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) finished = true
                }
            }
            view.loadUrl(url)
            val deadline = System.currentTimeMillis() + 20_000
            while (!finished && System.currentTimeMillis() < deadline) delay(200)
            delay(waitMs)
            val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
            if (cookie.isNotBlank()) {
                if (cache.size >= CACHE_MAX) cache.clear()
                cache[url] = cookie
            }
            cookie
        } finally {
            try {
                view.stopLoading()
                view.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 站点已登录时，把登录态 cookie 也一起拿回来（登录页由界面负责展示） */
    suspend fun loginCookie(context: Context, url: String): String =
        cookieFor(context, url, waitMs = 8000L, force = true)

    fun clear() = cache.clear()
}
