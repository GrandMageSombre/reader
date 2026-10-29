package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * 规则驱动的 HTML 书源。整个抓取流程只有这一个类，
 * 换站点 = 换一份 JSON，不用改代码。
 *
 * 要拿「完整」资源，规则里有这几条通道：
 *  - 目录分页：`chapterUrl` 带 `{page}`，或 `chapterNext` 指「下一页」链接；
 *  - 目录不在详情页：`chapterUrl` 单独指定目录页地址；
 *  - 正文分页：`contentNext` 指「下一页」链接（`/1.html`、`/2.html` 这类）；
 *  - 正文多容器：`content` 匹配到多个容器时，按文档顺序合并（嵌套命中的内层会被去掉）。
 */
class HtmlSource(
    override val cfg: SourceConfig,
    /** 传输层注入点：留空走 Http + Jsoup；单测可替换成假页面 */
    private val pageLoader: (suspend (String) -> Document)? = null
) : BookSource {

    private val r get() = cfg.rules

    private suspend fun load(url: String): Document =
        pageLoader?.invoke(url) ?: withContext(Dispatchers.IO) {
            Jsoup.parse(Http.get(url, cfg.charset, cfg.headers), url)
        }

    private fun abs(base: String, link: String): String {
        val l = link.trim()
        if (l.isEmpty()) return ""
        // 锚点 / 脚本 / data 之类不是章节地址，直接当无效处理，
        // 否则 content() 会把详情页当正文抓下来并写进缓存
        if (l.startsWith("#") || l.startsWith("javascript:") ||
            l.startsWith("data:") || l.startsWith("about:")
        ) return ""
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        val resolved = try {
            URI(base).resolve(l).toString()
        } catch (e: Exception) {
            return ""
        }
        return if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else ""
    }

    override suspend fun categories(): List<Category> = cfg.categories

    override suspend fun list(category: Category, page: Int): List<Book> {
        if (cfg.baseUrl.isBlank()) throw IllegalStateException("书源缺少 baseUrl")
        if (r.listItem.isBlank()) throw IllegalStateException("书源缺少 rules.listItem")
        val path = category.url.replace("{page}", page.toString())
        val url = abs(cfg.baseUrl, path)
        val doc = load(url)
        return parseList(doc, url)
    }

    override suspend fun search(key: String, page: Int): List<Book> {
        val tpl = r.searchUrl
        if (tpl.isBlank()) throw IllegalStateException("书源没有配置 searchUrl")
        val kw = URLEncoder.encode(key, "UTF-8")
        val path = tpl.replace("{key}", kw).replace("{page}", page.toString())
        val url = abs(cfg.baseUrl, path)
        val doc = load(url)
        return parseList(doc, url)
    }

    private fun parseList(doc: Document, pageUrl: String): List<Book> {
        val items = Rule.selectAll(r.listItem, doc)
        val out = ArrayList<Book>(items.size)
        items.forEach { el ->
            val title = Rule.eval(r.listName.ifBlank { "a@text" }, el)
            val link = Rule.eval(r.listLink.ifBlank { "a@href" }, el)
            if (title.isBlank() || link.isBlank()) return@forEach
            out.add(
                Book(
                    sourceId = cfg.id,
                    url = abs(pageUrl, link),
                    title = title,
                    author = Rule.eval(r.listAuthor, el),
                    cover = abs(pageUrl, Rule.eval(r.listCover, el)),
                    intro = Rule.eval(r.listIntro, el)
                )
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun detail(book: Book): Book {
        val doc = load(book.url)
        val name = Rule.eval(r.detailName, doc)
        val author = Rule.eval(r.detailAuthor, doc)
        val cover = Rule.eval(r.detailCover, doc)
        val intro = Rule.eval(r.detailIntro, doc)
        val status = Rule.eval(r.detailStatus, doc)
        return book.copy(
            title = name.ifBlank { book.title },
            author = author.ifBlank { book.author },
            cover = abs(book.url, cover).ifBlank { book.cover },
            intro = intro.ifBlank { book.intro },
            status = status.ifBlank { book.status }
        )
    }

    /**
     * 章节目录：支持「目录页和详情页不同地址」与「目录分页」。
     *
     * `chapterUrl` 有三种写法：
     *  - 留空 → 抓详情页
     *  - 以 `http` / `/` / `{` 开头（URL 或模板）→ 直接当目录页地址，可含 `{page}` 分页
     *  - 其他写法（如 `a#details-menu@href`）→ 当规则，从**详情页**里取出目录页地址
     *
     * `chapterNext` 可跟着「下一页」链接继续翻；`chapterReverse` 反转拼接后的全量目录。
     * 翻页上限 [MAX_CHAPTER_PAGES]；抓到空页、重复页、或下一页取不到就停。
     */
    override suspend fun chapters(book: Book): List<Chapter> {
        if (r.chapterList.isBlank()) throw IllegalStateException("书源缺少 rules.chapterList")
        val nameRule = r.chapterName.ifBlank { "text" }
        val linkRule = r.chapterLink.ifBlank { "href" }

        val tpl = r.chapterUrl.trim()
        val detailDoc = if (tpl.isBlank() || !looksLikeUrl(tpl)) load(book.url) else null
        val resolved = when {
            tpl.isBlank() -> book.url
            looksLikeUrl(tpl) -> abs(book.url, pageUrl(tpl, 1))
            else -> abs(book.url, Rule.eval(tpl, detailDoc!!))
        }.ifBlank { book.url }
        val byTemplate = looksLikeUrl(tpl) && tpl.contains("{page}")

        val out = ArrayList<Chapter>()
        val visitedPages = HashSet<String>()
        val visitedChapters = HashSet<String>()
        var url = resolved
        var page = 0
        // 详情页已经抓过就直接复用，避免同一地址请求两次
        var doc: Document? = if (url == book.url) detailDoc else null

        while (url.isNotBlank() && page < MAX_CHAPTER_PAGES && visitedPages.add(url)) {
            page++
            val pageDoc = doc ?: try {
                load(url)
            } catch (e: Exception) {
                // 首页失败照旧抛出（BookRepo 会回落本地缓存）；后续页失败则保留已抓到的目录
                if (page == 1) throw e
                break
            }
            doc = null
            val els = Rule.selectAll(r.chapterList, pageDoc)
            var added = 0
            els.forEach { el ->
                val t = Rule.eval(nameRule, el)
                val u = Rule.eval(linkRule, el)
                if (t.isBlank() || u.isBlank()) return@forEach
                // 相对链接以「当前目录页」为基准解析：chapterUrl 独立时也能拼对
                val cu = abs(url, u)
                if (cu.isNotBlank() && visitedChapters.add(cu)) {
                    out.add(Chapter(0, t, cu))
                    added++
                }
            }
            // 这一页没解析出章节（空页）或全是重复项 → 已经翻到底
            if (els.isEmpty() || added == 0) break
            url = when {
                byTemplate -> abs(book.url, pageUrl(tpl, page + 1))
                r.chapterNext.isNotBlank() -> abs(url, Rule.eval(r.chapterNext, pageDoc))
                else -> ""
            }
        }

        val list = if (r.chapterReverse) out.reversed() else out
        return list.mapIndexed { i, c -> c.copy(index = i) }
    }

    /** 判断 chapterUrl 填的是「地址/模板」而不是「选择器规则」 */
    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        // 带 @ 取值的一律是选择器规则（如 a#details-menu@href）
        if (t.contains('@')) return false
        return t.startsWith("http://") || t.startsWith("https://") ||
            t.startsWith("//") || t.startsWith("/") || t.startsWith("?") ||
            t.startsWith("{")
    }

    private fun pageUrl(tpl: String, page: Int) = tpl.replace("{page}", page.toString())

    /**
     * 正文：按 `content` 抓取（可多容器合并），并按 `contentNext` 拼接后续分页。
     */
    override suspend fun content(book: Book, chapter: Chapter): String {
        if (r.content.isBlank()) throw IllegalStateException("书源缺少 rules.content")
        val cleanSelectors = r.contentClean.filter { !it.startsWith("re:") }
        val regexes = r.contentClean.filter { it.startsWith("re:") }.mapNotNull {
            try {
                Regex(it.removePrefix("re:"))
            } catch (e: Exception) {
                null
            }
        }

        val parts = ArrayList<String>()
        val visited = HashSet<String>()
        var url = chapter.url
        var page = 0

        while (url.isNotBlank() && page < MAX_CONTENT_PAGES && visited.add(url)) {
            page++
            val doc = try {
                load(url)
            } catch (e: Exception) {
                // 首页失败照旧抛出（阅读器里「重新抓取本章」能重来）；后续页失败则保留已抓到的部分
                if (page == 1) throw e
                break
            }
            val roots = outermost(Rule.selectAll(r.content, doc))
            if (roots.isEmpty()) {
                if (page == 1) throw IllegalStateException("正文规则没匹配到内容")
                break
            }
            roots.forEach { cleanElement(it, cleanSelectors) }
            val text = roots.joinToString("\n") { Rule.extractText(it) }
            if (text.isNotBlank()) parts.add(text)
            url = if (r.contentNext.isBlank()) "" else abs(url, Rule.eval(r.contentNext, doc))
        }
        if (parts.isEmpty()) throw IllegalStateException("正文规则没匹配到内容")

        var text = parts.joinToString("\n")
        if (regexes.isNotEmpty()) {
            text = text.split('\n')
                .filter { line -> regexes.none { it.containsMatchIn(line) } }
                .joinToString("\n")
        }
        return text
    }

    /** 只保留最外层匹配：嵌套命中的内层内容已包含在外层里，合并会导致重复 */
    private fun outermost(elements: List<Element>): List<Element> =
        elements.filter { el ->
            elements.none { other -> other !== el && el.parents().any { p -> p === other } }
        }

    private fun cleanElement(root: Element, selectors: List<String>) {
        selectors.forEach { sel ->
            try {
                root.select(sel).forEach { it.remove() }
            } catch (e: Exception) {
                // 忽略写错的清理选择器
            }
        }
    }

    private companion object {
        /** 单章最多拼接几页（含第一页） */
        const val MAX_CONTENT_PAGES = 10

        /** 目录最多翻几页，防止分页规则写错时无限翻 */
        const val MAX_CHAPTER_PAGES = 20
    }
}
