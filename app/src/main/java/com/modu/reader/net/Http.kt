package com.modu.reader.net

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 极简 HTTP 客户端：只用 HttpURLConnection，不引 OkHttp。
 * 自动跟随重定向、自动识别 gb2312/gbk/utf-8 编码。
 */
object Http {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun get(
        url: String,
        charset: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 25000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", headers["User-Agent"] ?: UA)
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
                headers.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true)) setRequestProperty(k, v)
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) return ""
            val cs = charset?.takeIf { it.isNotBlank() }
                ?: detectCharset(bytes, conn.contentType)
                ?: "utf-8"
            val real = try {
                Charset.forName(cs)
            } catch (e: Exception) {
                Charsets.UTF_8
            }
            return String(bytes, real)
        } finally {
            conn?.disconnect()
        }
    }

    private fun detectCharset(bytes: ByteArray, contentType: String?): String? {
        contentType?.let { ct ->
            Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
                .find(ct)?.let { return normalize(it.groupValues[1]) }
        }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.let { return normalize(it.groupValues[1]) }
        return null
    }

    private fun normalize(name: String): String = when (name.lowercase()) {
        "gb2312", "gbk", "gb18030" -> "gb18030"
        "utf8" -> "utf-8"
        else -> name.lowercase()
    }
}
