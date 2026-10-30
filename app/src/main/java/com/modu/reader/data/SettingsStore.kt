package com.modu.reader.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 阅读设置：字号、行距、字体、背景、亮度。
 * 全部落在 SharedPreferences，App 启动时 init 一次。
 *
 * 注意：属性名是 fontSize 这类，Kotlin 会自动生成 setFontSize，
 * 所以写库的方法统一叫 updateXxx，避免 JVM 签名冲突。
 */
object SettingsStore {

    const val THEME_WHITE = 0
    const val THEME_PAPER = 1
    const val THEME_GREEN = 2
    const val THEME_NIGHT = 3

    const val FONT_DEFAULT = 0
    const val FONT_SERIF = 1
    const val FONT_SANS = 2

    private const val PREF = "modu_reader_prefs"

    private var sp: SharedPreferences? = null

    var fontSize by mutableStateOf(19f)
        private set
    var lineHeight by mutableStateOf(1.8f)
        private set
    var fontIndex by mutableStateOf(FONT_SERIF)
        private set
    var themeIndex by mutableStateOf(THEME_PAPER)
        private set
    var keepScreenOn by mutableStateOf(true)
        private set
    /** -1 表示跟随系统，0..1 为手动亮度 */
    var brightness by mutableStateOf(-1f)
        private set
    var paragraphSpacing by mutableStateOf(0.8f)
        private set

    /** 启动声明：每次打开都弹（用户可在弹窗里取消勾选） */
    var showDisclaimer by mutableStateOf(true)
        private set

    /**
     * 「本地爬虫库」的地址：`http://192.168.1.20:8765`。
     *
     * 空串表示还没连过——此时进书城会走局域网自动发现（UDP 8766），
     * 发现到就写回这里，之后直接复用。
     */
    var localSpiderApi by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp = p
        fontSize = p.getFloat("font_size", 19f)
        lineHeight = p.getFloat("line_height", 1.8f)
        fontIndex = p.getInt("font_index", FONT_SERIF)
        themeIndex = p.getInt("theme_index", THEME_PAPER)
        keepScreenOn = p.getBoolean("keep_screen_on", true)
        brightness = p.getFloat("brightness", -1f)
        paragraphSpacing = p.getFloat("paragraph_spacing", 0.8f)
        showDisclaimer = p.getBoolean("show_disclaimer", true)
        localSpiderApi = p.getString("local_spider_api", "").orEmpty()
    }

    fun updateLocalSpiderApi(api: String) {
        localSpiderApi = api.trim()
        sp?.edit()?.putString("local_spider_api", localSpiderApi)?.apply()
    }

    fun updateFontSize(v: Float) {
        fontSize = v.coerceIn(13f, 34f)
        sp?.edit()?.putFloat("font_size", fontSize)?.apply()
    }

    fun updateLineHeight(v: Float) {
        lineHeight = v.coerceIn(1.2f, 2.6f)
        sp?.edit()?.putFloat("line_height", lineHeight)?.apply()
    }

    fun updateParagraphSpacing(v: Float) {
        paragraphSpacing = v.coerceIn(0.2f, 2.0f)
        sp?.edit()?.putFloat("paragraph_spacing", paragraphSpacing)?.apply()
    }

    fun updateFontIndex(v: Int) {
        fontIndex = v
        sp?.edit()?.putInt("font_index", v)?.apply()
    }

    fun updateThemeIndex(v: Int) {
        themeIndex = v
        sp?.edit()?.putInt("theme_index", v)?.apply()
    }

    fun updateKeepScreenOn(v: Boolean) {
        keepScreenOn = v
        sp?.edit()?.putBoolean("keep_screen_on", v)?.apply()
    }

    fun updateBrightness(v: Float) {
        brightness = v
        sp?.edit()?.putFloat("brightness", v)?.apply()
    }

    fun updateShowDisclaimer(v: Boolean) {
        showDisclaimer = v
        sp?.edit()?.putBoolean("show_disclaimer", v)?.apply()
    }
}
