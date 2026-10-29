# 墨读 · MoDuReader

一个**规则驱动**的 Android 小说阅读器：分类、书单、目录、正文全部由「书源 JSON」描述，
换站点只改配置、不改代码。

> **使用声明**：本项目为本地自用工具。应用**不内置、不代理、不分发**任何第三方站点内容；
> 书源由使用者自行编写与维护，内容的版权状态、可访问性由使用者自行判断。请勿用于商业用途或传播他人作品。

---

## 功能

| 模块 | 说明 |
|------|------|
| 书城 | 书源自带分类（对应站点自己的分类体系），分类下无限翻页；支持单书源搜索 |
| 书架 | 网格书架、封面、阅读进度百分比、继续阅读、移出书架、书籍详情 |
| 阅读器 | 上下滚动阅读，**字号 13–34 可调**、**行距 / 段距可调**、**宋体 / 黑体 / 默认字体**、**白 / 米黄 / 护眼 / 夜间四套配色**、亮度调节、阅读时常亮 |
| 目录 | 全本章节目录，当前章高亮，点击直达 |
| 进度 | 记住「第几章 + 滚动位置」，退出重进自动回到原位 |
| 缓存 | 章节正文、目录落 SQLite；离线可看已读章节，正文缓存优先（翻回去不重复请求） |
| 书源 | 导入 / 删除 / 启停 JSON 书源，剪贴板粘贴导入，一键复制模板；内置示例源不可删 |

## 书源规则引擎

一个书源就是一份 JSON（完整字段与语义见 [`docs/书源规则.md`](docs/书源规则.md)）：

```json
{
  "id": "my_source",
  "name": "我的书源",
  "baseUrl": "https://example.com",
  "categories": [{ "name": "玄幻", "url": "/list/xuanhuan/{page}.html" }],
  "rules": {
    "listItem": ".book-list li",
    "listName": "h3 a@text",
    "listLink": "h3 a@href",
    "chapterList": "#list dd a",
    "chapterName": "text",
    "chapterLink": "href",
    "content": "#content",
    "contentClean": ["script", ".ad", "re:本章未完"]
  }
}
```

规则写法 `CSS选择器@取值`，取值支持 `text` / `ownText` / `html` / `href` / `src` / `attr:xxx` / `abs:href`；
省略 `@` 即按取文本处理；只写 `text`、`href` 表示对「当前节点自己」取值（章节列表逐项取值的写法）。

**关于起点 / 番茄**：这两个站点的正文接口有请求签名、加密字体和付费章节，
本项目**不提供**绕过这些机制的规则或代码。你要接它们，只能接你有权访问的部分（例如自有账号可访问的公开页），
规则自己写在书源 JSON 里；引擎本身对站点无感知。

## 技术栈

- Kotlin + Jetpack Compose（Material 3），单 Activity + Navigation Compose
- 抓取：`HttpURLConnection` + Jsoup，无 OkHttp / 无 Retrofit
- 存储：系统 `SQLiteOpenHelper`（书架 / 目录 / 正文）+ `SharedPreferences`（阅读设置）
- 依赖极简：只有 Jsoup 与 Coil（封面），没有注解处理器、没有 Room / Hilt

```
app/src/main/java/com/modu/reader/
├── App.kt                     # Application：初始化设置、数据库、书源、仓库
├── MainActivity.kt
├── data/                      # Models / Db / SettingsStore
├── net/Http.kt                # 极简 HTTP + 编码识别（gbk/utf-8）
├── source/
│   ├── SourceConfig.kt        # 书源 JSON 模型与解析
│   ├── Rule.kt                # 规则求值 + 正文按块拆段
│   ├── BookSource.kt          # 书源能力接口（接自建后端只需实现它）
│   ├── HtmlSource.kt          # 规则驱动的实现
│   ├── DemoSource.kt          # 内置公版示例源
│   └── SourceManager.kt       # 导入 / 删除 / 启停
├── repo/BookRepo.kt           # 书源 + 缓存 + 书架
└── ui/                        # shelf / browse / detail / reader / settings
```

## 构建

本机已验证的版本组合（与 `Desktop/player` 工程一致）：

| 项 | 版本 |
|----|------|
| Gradle | 9.3.1（腾讯镜像，`gradle/wrapper/gradle-wrapper.properties`） |
| AGP | 9.1.0 |
| Kotlin | 2.4.21（Compose 编译器随 Kotlin 走） |
| Compose BOM | 2026.09.00 |
| JDK | 24（`JAVA_HOME=D:\java`） |
| SDK | compileSdk / targetSdk 36，minSdk 26（`local.properties` 指向 `D:\Android\Sdk`） |

```bash
# 真机 / 模拟器
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 发行包（沿用 debug 签名，直接可装）
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 本机踩过的坑：Gradle/AGP 组合下 Kotlin 增量缓存偶发损坏（`Could not close incremental caches`），
> `gradle.properties` 里已设 `kotlin.incremental=false`；Maven 仓库优先走阿里云镜像。

## 内置示例源

「示例书库」不联网，内容为公版古典名著节选（西游记 / 红楼梦 / 三国演义 / 水浒传 / 唐诗 / 古文观止 /
聊斋 / 论语），用于验证书架、分类、翻章、字号主题切换等全部交互。删不掉，也不涉及任何版权内容。

## 未做 / 已知限制

- 阅读器是**上下滚动**模式，没有做仿真翻页（分页测量与手势需要另一套渲染管线）
- 不支持章节正文多页拼接（`/1.html`、`/2.html` 这种需要书源里另配规则）
- 书源自检还没做（导入后要在书城里点一次才知道规则对不对）
- 搜索只搜当前书源，没有跨源聚合搜索
