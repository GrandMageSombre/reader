package com.modu.reader.source

/**
 * 起点中文书源：**发现源**。
 *
 * 直接复用 [HtmlSource] 的规则引擎（`m.qidian.com` 的分类书单、详情、完整目录、
 * 免费章节正文都是 SSR 的，规则能取到），只是额外打上 [DiscoverySource] 标记：
 * 换源时会被跳过（VIP 章节抓不到，拿它当正文源没有意义）。
 *
 * 同时实现 [DeviceCrawlable]：详情页的「本机抓取」按钮据此出现
 * （用 WebView 过掉 probe.js，在手机上直接抓目录与免费章节）。
 */
class QidianSource(cfg: SourceConfig) : HtmlSource(cfg), DiscoverySource, DeviceCrawlable

/** 番茄小说同理：JSON 列表 + SSR 书籍页，但正文页要 JS，所以也走本机抓取。 */
class FanqieDiscoverySource(cfg: SourceConfig) : FanqieSource(cfg), DiscoverySource, DeviceCrawlable
