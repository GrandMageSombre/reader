package com.modu.reader.source

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import java.io.File

/**
 * 书源注册表：
 *  - 内置源（示例书库 / 起点中文 / 豆瓣图书 / 古登堡计划 / 番茄小说 / 微信读书）
 *    由代码提供，**不可删除**，但可以在书源管理里停用
 *  - 用户导入的 JSON 存在 files/sources/<id>.json
 *  - 增删改之后 reload()，界面通过 sources 这个可观察列表自动刷新
 *
 * 内置源的启用状态：用户停用后写一个 `files/sources/.builtin-disabled`
 * （每行一个 id），这样内置源本身不用落盘也能记住开关。
 */
class SourceManager(context: Context) {

    private val dir = File(context.filesDir, "sources")
    private val disabledFile = File(dir, ".builtin-disabled")

    private val _sources = mutableStateListOf<SourceConfig>()

    val sources: List<SourceConfig> get() = _sources

    init {
        reload()
    }

    private fun disabledIds(): Set<String> {
        if (!disabledFile.exists()) return emptySet()
        return try {
            disabledFile.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveDisabled(ids: Set<String>) {
        try {
            dir.mkdirs()
            disabledFile.writeText(ids.joinToString("\n"))
        } catch (e: Exception) {
            // 忽略：写不进去最多是重启后又启用
        }
    }

    fun reload() {
        _sources.clear()
        val off = disabledIds()
        BuiltinSources.all().forEach { cfg ->
            _sources.add(
                cfg.copy(
                    builtin = true,
                    builtinGroup = BuiltinSources.GROUP,
                    origin = ORIGIN_BUILTIN,
                    // 默认开启集合里的源默认启用；其余要用户自己勾选
                    enabled = if (cfg.id in BuiltinSources.DEFAULT_ON) {
                        cfg.id !in off
                    } else {
                        cfg.id !in off && cfg.id in enabledIds()
                    }
                )
            )
        }
        dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
        files?.sortedBy { it.name }?.forEach { f ->
            try {
                val cfg = SourceConfig.parse(f.readText())
                // 内置源不允许被同名文件覆盖
                if (cfg.id !in BuiltinSources.IDS) _sources.add(cfg)
            } catch (e: Exception) {
                // 坏文件忽略，不影响其它书源
            }
        }
    }

    /**
     * 用户手动启用过的「非默认」内置源 id。
     *
     * 内置源的开关不落 json（内置源本来就是代码里定义的），
     * 所以非默认源的「已启用」状态单独记在 `.builtin-enabled` 里。
     */
    private fun enabledIds(): Set<String> {
        val f = File(dir, ".builtin-enabled")
        if (!f.exists()) return emptySet()
        return try {
            f.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun saveEnabled(ids: Set<String>) {
        try {
            dir.mkdirs()
            File(dir, ".builtin-enabled").writeText(ids.joinToString("\n"))
        } catch (e: Exception) {
            // 忽略
        }
    }

    fun byId(id: String): SourceConfig? = _sources.firstOrNull { it.id == id }

    /**
     * 取书源实现。内置的「示例书库」走 DemoSource（本地数据），
     * 其余（含其它内置源与用户导入源）一律走规则驱动的 HtmlSource。
     */
    fun source(id: String): BookSource? {
        val cfg = byId(id) ?: return null
        return when {
            cfg.id == DemoSource.ID -> DemoSource(cfg)
            // 手机直抓：App 内的起点书库 + 番茄榜单，不需要 PC 接口
            cfg.id == DeviceSource.ID -> DeviceSource(cfg)
            // 本地爬虫库：数据来自 PC 上 tools/spider 抓好的库，走 JSON 接口
            cfg.id == LocalLibrarySource.ID -> {
                // 地址可能来自「局域网自动发现」，覆盖内置的默认值
                val discovered = com.modu.reader.data.SettingsStore.localSpiderApi
                LocalLibrarySource(
                    if (discovered.isBlank()) cfg else cfg.copy(baseUrl = discovered)
                )
            }
            // 番茄走 JSON 接口（HTML 列表拿不到），且标为发现源
            cfg.id == FanqieSource.ID -> FanqieDiscoverySource(cfg)
            // 起点：分类发现 + 免费章节，VIP 抓不到 → 标为发现源
            cfg.id == "qidian" -> QidianSource(cfg)
            // 采集站：一个书源持有一组镜像，正文抓不到时自动换站重试
            cfg.id in MULTI_MIRROR -> BiqugeMultiSource(cfg, mirrorHosts(cfg.id))
            else -> HtmlSource(cfg)
        }
    }

    /** 支持多镜像容错的采集站 */
    private val MULTI_MIRROR = setOf("xbiquwk", "wangshu")

    /**
     * 每个采集站的备用镜像（按实测可用性排序）。
     *
     * 实测（2026-10，12 个候选站）：只有望书网(8/26)、2K小说(6/26) 的搜索能用，
     * 八一中文与其他站多半 ConnectionReset / 404。所以镜像组不做大，只放真能用的。
     */
    private fun mirrorHosts(id: String): List<String> = when (id) {
        "xbiquwk" -> listOf("https://www.biquwx.la")
        "wangshu" -> listOf("https://www.2kxs.info", "http://www.zwduxs.com")
        else -> emptyList()
    }

    /** 这个源是不是「只负责发现书、正文要靠换源」的类型 */
    fun isDiscovery(id: String): Boolean = source(id) is DiscoverySource

    /** 这个源支不支持「手机端直连抓取」（详情页据此显示「本机抓取」按钮） */
    fun isDeviceCrawlable(id: String): Boolean = source(id) is DeviceCrawlable

    fun available(): List<SourceConfig> = _sources.filter { it.enabled }

    fun isBuiltin(id: String): Boolean = id in BuiltinSources.IDS

    /**
     * 书城里能作为「入口」的源。
     *
     * 用户要求书城只暴露起点与番茄两个入口——它们负责按官方分类发现书，
     * 正文交给采集站（通过换源）。采集站仍然在「书源管理」里可配、可单独打开。
     */
    fun storefronts(): List<SourceConfig> =
        available().filter { it.id in BuiltinSources.STOREFRONT_IDS }

    /** 导入一份书源 JSON（墨读自己的格式），返回解析后的配置 */
    fun importJson(text: String): SourceConfig {
        val cfg = SourceConfig.parse(text)
        if (cfg.id in BuiltinSources.IDS) {
            throw IllegalArgumentException("id「${cfg.id}」已被内置书源占用，请换一个")
        }
        dir.mkdirs()
        File(dir, "${cfg.id}.json").writeText(SourceConfig.toJson(cfg))
        reload()
        return cfg
    }

    /**
     * 导入第三方（Legado / 「阅读」格式）书源，自动转换成墨读的规则格式。
     *
     * @return 成功导入的配置 + 每个源里没能转换的字段
     */
    fun importLegado(text: String): List<LegadoImporter.Result> {
        val results = LegadoImporter.parseAll(text)
        if (results.isEmpty()) throw IllegalArgumentException("没有解析到任何书源")
        dir.mkdirs()
        val done = ArrayList<LegadoImporter.Result>(results.size)
        results.forEach { r ->
            var cfg = r.config
            var id = cfg.id
            // id 冲突时加序号，不覆盖已有书源
            var n = 2
            while (id in BuiltinSources.IDS || File(dir, "$id.json").exists()) {
                id = "${cfg.id}_$n"
                n++
            }
            if (id != cfg.id) cfg = cfg.copy(id = id)
            File(dir, "$id.json").writeText(SourceConfig.toJson(cfg))
            done.add(r.copy(config = cfg))
        }
        reload()
        return done
    }

    /** 识别一份文本是墨读格式还是第三方 Legado 格式 */
    fun looksLikeLegado(text: String): Boolean {
        val t = text.trim()
        return t.contains("\"bookSourceUrl\"") || t.contains("\"bookSourceName\"")
    }

    fun remove(id: String) {
        if (id in BuiltinSources.IDS) return
        File(dir, "$id.json").delete()
        // 顺带清掉停用记录，避免同 id 再导入时继承旧的停用状态
        val off = disabledIds()
        if (id in off) saveDisabled(off - id)
        reload()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        if (id in BuiltinSources.IDS) {
            val off = disabledIds().toMutableSet()
            if (enabled) off.remove(id) else off.add(id)
            saveDisabled(off)
            // 非默认源还要记一笔「用户主动启用过」，否则重载后又变回关闭
            if (id !in BuiltinSources.DEFAULT_ON) {
                val on = enabledIds().toMutableSet()
                if (enabled) on.add(id) else on.remove(id)
                saveEnabled(on)
            }
            reload()
            return
        }
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

    /**
     * 一键启用全部内置书源（书源管理页的「全部启用」）。
     * 内置源的开关存在 `.builtin-disabled` / `.builtin-enabled` 里，这里一次清干净。
     */
    fun enableAllBuiltin() {
        saveDisabled(emptySet())
        saveEnabled(BuiltinSources.IDS - BuiltinSources.DEFAULT_ON)
        reload()
    }

    fun exportDir(): File = dir

    companion object {
        const val ORIGIN_BUILTIN = "内置"
        const val ORIGIN_LEGADO = "第三方"
        const val ORIGIN_IMPORT = "导入"
    }
}
