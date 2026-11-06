# 墨读（reader）项目约定

## 这是什么
「墨读」MoDuReader：规则驱动的 Android 小说阅读器（Kotlin + Compose）。
书源 = 一份 JSON（分类/书单/详情/目录/正文全部 CSS 选择器描述），语法文档 `docs/书源规则.md`，模板 `docs/书源模板.json`。
**分层红线**：书源规则引擎只写 CSS 选择器，不碰反爬/签名/付费墙；
破解与全量搬运在另一条路 —— PC 端 `tools/spider/crack/` + `harvest.py`、App 端「破解补全」
（`source/Crack.kt` + `BookRepo.crackFill`），口径文档 `docs/破解全量资源.md`。
两边判定阈值单点定义：`tools/spider/align.py` 的 `MIN_FULL_CHARS = 220` ↔ App `Crack.MIN_FULL_CHARS`。

## 工程事实
- 代码：`app/src/main/java/com/modu/reader/`（data / net / source / repo / ui 五层）。
- 依赖极简：只有 Jsoup + Coil，无 Room/Hilt/Retrofit/注解处理器；SQLite 用裸 SQLiteOpenHelper。
- 构建版本组合与沙箱坑见 `.workbuddy/memory/2026-10-29.md`。
- 发布物归档在 `releases/`，版本记录在根目录 `版本说明.md`（沿用用户的总览表 + 详情节格式），审查报告在 `docs/reviews/`。
- 交付语言：界面与文档全中文。
