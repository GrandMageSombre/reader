# 墨读（reader）项目约定

## 这是什么
「墨读」MoDuReader：规则驱动的 Android 小说阅读器（Kotlin + Compose）。
书源 = 一份 JSON（分类/书单/详情/目录/正文全部 CSS 选择器描述），语法文档 `docs/书源规则.md`，模板 `docs/书源模板.json`。
**红线**：不实现起点/番茄等站点的反爬绕过与付费墙抓取，规则由用户自行维护。

## 工程事实
- 代码：`app/src/main/java/com/modu/reader/`（data / net / source / repo / ui 五层）。
- 依赖极简：只有 Jsoup + Coil，无 Room/Hilt/Retrofit/注解处理器；SQLite 用裸 SQLiteOpenHelper。
- 构建版本组合与沙箱坑见 `.workbuddy/memory/2026-10-29.md`。
- 发布物归档在 `releases/`，版本记录在根目录 `版本说明.md`（沿用用户的总览表 + 详情节格式），审查报告在 `docs/reviews/`。
- 交付语言：界面与文档全中文。
