package com.modu.reader.source

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import java.io.File

/**
 * 书源注册表：
 *  - 内置「示例书库」永远存在
 *  - 用户导入的 JSON 存在 files/sources/<id>.json
 *  - 增删改之后 reload()，界面通过 sources 这个可观察列表自动刷新
 */
class SourceManager(context: Context) {

    private val dir = File(context.filesDir, "sources")

    private val _sources = mutableStateListOf<SourceConfig>()

    val sources: List<SourceConfig> get() = _sources

    init {
        reload()
    }

    fun reload() {
        _sources.clear()
        _sources.add(DemoSource.config())
        dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        files?.sortedBy { it.name }?.forEach { f ->
            try {
                val cfg = SourceConfig.parse(f.readText())
                if (cfg.id != DemoSource.ID) _sources.add(cfg)
            } catch (e: Exception) {
                // 坏文件忽略，不影响其它书源
            }
        }
    }

    fun byId(id: String): SourceConfig? = _sources.firstOrNull { it.id == id }

    fun source(id: String): BookSource? {
        val cfg = byId(id) ?: return null
        return if (cfg.builtin || cfg.id == DemoSource.ID) DemoSource(cfg) else HtmlSource(cfg)
    }

    fun available(): List<SourceConfig> = _sources.filter { it.enabled }

    /** 导入一份书源 JSON，返回解析后的配置 */
    fun importJson(text: String): SourceConfig {
        val cfg = SourceConfig.parse(text)
        if (cfg.id == DemoSource.ID) throw IllegalArgumentException("id 不能是内置示例源的 demo")
        dir.mkdirs()
        File(dir, "${cfg.id}.json").writeText(SourceConfig.toJson(cfg))
        reload()
        return cfg
    }

    fun remove(id: String) {
        if (id == DemoSource.ID) return
        File(dir, "$id.json").delete()
        reload()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (id == DemoSource.ID) return
        val f = File(dir, "$id.json")
        if (!f.exists()) return
        try {
            val cfg = SourceConfig.parse(f.readText()).copy(enabled = enabled)
            f.writeText(SourceConfig.toJson(cfg))
            reload()
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun rawJson(id: String): String = byId(id)?.let { SourceConfig.toJson(it) } ?: ""

    fun exportDir(): File = dir
}
