package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * 规则驱动的 HTML 书源。整个抓取流程只有这一个类，
 * 换站点 = 换一份 JSON，不用改代码。
 */
class HtmlSource(override val cfg: SourceConfig) : BookSource {

    private val r get() = cfg.rules

    private suspend fun load(url: String): Document = withContext(Dispatchers.IO) {
        val html = Http.get(url, cfg.charset, cfg.headers)
        Jsoup.parse(html, url)
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

    override suspend fun chapters(book: Book): List<Chapter> {
        if (r.chapterList.isBlank()) throw IllegalStateException("书源缺少 rules.chapterList")
        val doc = load(book.url)
        val els = Rule.selectAll(r.chapterList, doc)
        val nameRule = r.chapterName.ifBlank { "text" }
        val linkRule = r.chapterLink.ifBlank { "href" }
        var list = els.mapNotNull { el ->
            val t = Rule.eval(nameRule, el)
            val u = Rule.eval(linkRule, el)
            if (t.isBlank() || u.isBlank()) null
            else Chapter(0, t, abs(book.url, u))
        }
        if (r.chapterReverse) list = list.reversed()
        return list.mapIndexed { i, c -> c.copy(index = i) }
    }

    override suspend fun content(book: Book, chapter: Chapter): String {
        if (r.content.isBlank()) throw IllegalStateException("书源缺少 rules.content")
        val doc = load(chapter.url)
        val root = Rule.selectFirst(r.content, doc)
            ?: throw IllegalStateException("正文规则没匹配到内容")
        r.contentClean
            .filter { !it.startsWith("re:") }
            .forEach { sel ->
                try {
                    root.select(sel).forEach { it.remove() }
                } catch (e: Exception) {
                    // 忽略写错的清理选择器
                }
            }
        var text = Rule.extractText(root)
        val regexes = r.contentClean.filter { it.startsWith("re:") }.mapNotNull {
            try {
                Regex(it.removePrefix("re:"))
            } catch (e: Exception) {
                null
            }
        }
        if (regexes.isEmpty()) return text
        text = text.split('\n')
            .filter { line -> regexes.none { it.containsMatchIn(line) } }
            .joinToString("\n")
        return text
    }
}
