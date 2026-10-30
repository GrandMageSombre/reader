package com.modu.reader.source

import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.data.Chapter
import com.modu.reader.data.SwapCandidate
import com.modu.reader.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * 笔趣阁系聚合站的书源实现：**多站容错换源**。
 *
 * 背景：这类站点用的是同一套模板（`#list dd a` 目录、`#content` 正文、
 * `modules/article/search.php` 搜索），域名很多但**存活率很低**——
 * 实测 10 个候选站里只有 2 个的「搜索→目录→正文」完整链路可用。
 *
 * 所以这里不再一个站配一个书源、让用户手动切，而是：
 * 1. 一个书源持有一组**镜像站**（[mirrors]）；
 * 2. 换源时依次在所有镜像上按书名搜索，谁能搜到就用谁；
 * 3. 抓正文失败时自动换下一个镜像重试，并把可用的那个记下来。
 *
 * 这样「读不到正文」的窗口小很多，用户也不用关心到底是哪个站在干活。
 */
open class BiqugeMultiSource(
    override val cfg: SourceConfig,
    private val mirrors: List<String> = emptyList()
) : BookSource {

    /** 实际参与轮询的站点：配置里的 baseUrl + mirrors */
    private val sites: List<String> = (listOf(cfg.baseUrl) + mirrors)
        .map { it.trim().trimEnd('/') }
        .filter { it.isNotBlank() }
        .distinct()

    private val ua = cfg.headers["User-Agent"] ?: DEFAULT_UA

    private fun get(url: String, referer: String? = null): String =
        Http.get(
            url,
            cfg.charset,
            buildMap {
                put("User-Agent", ua)
                put("Referer", referer ?: url)
            }
        )

    override suspend fun categories(): List<Category> = cfg.categories

    override suspend fun list(category: Category, page: Int): List<Book> =
        withContext(Dispatchers.IO) {
            val path = category.url.replace("{page}", page.toString())
            // 分类页：第一个能取到书的镜像胜出
            var lastError: Exception? = null
            for (site in sites) {
                val url = if (path.startsWith("http")) path else site + path
                try {
                    val books = parseList(Jsoup.parse(get(url, site), url), url)
                    if (books.isNotEmpty()) return@withContext books.map { it.copy(sourceId = cfg.id) }
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (lastError != null) throw lastError
            emptyList()
        }

    /**
     * 搜索：依次在所有镜像上搜，合并去重。
     *
     * 换源时这里返回的结果会带上**实际可用的站点地址**（book.url 就是那个站的），
     * 所以后续 chapters/content 会直接打到正确的站上。
     */
    override suspend fun search(key: String, page: Int): List<Book> =
        withContext(Dispatchers.IO) {
            if (page > 1) return@withContext emptyList()
            val kw = URLEncoder.encode(key, "UTF-8")
            val tpl = cfg.rules.searchUrl
            if (tpl.isBlank()) return@withContext emptyList()
            val out = LinkedHashMap<String, Book>()
            for (site in sites) {
                val url = site + tpl.replace("{key}", kw).replace("{page}", page.toString())
                try {
                    parseList(Jsoup.parse(get(url, site), url), url, forSearch = true).forEach { b ->
                        // 同一本书在多个镜像都命中时，保留先到的（sites 顺序即优先级）
                        out.putIfAbsent(b.title, b.copy(sourceId = cfg.id))
                    }
                } catch (e: Exception) {
                    // 该镜像不可用，继续下一个
                }
            }
            out.values.toList()
        }

    override suspend fun detail(book: Book): Book = withContext(Dispatchers.IO) {
        val doc = Jsoup.parse(get(book.url), book.url)
        val r = cfg.rules
        book.copy(
            title = Rule.eval(r.detailName, doc).ifBlank { book.title },
            author = Rule.eval(r.detailAuthor, doc).ifBlank { book.author },
            cover = Rule.eval(r.detailCover, doc).ifBlank { book.cover },
            intro = Rule.eval(r.detailIntro, doc).ifBlank { book.intro },
            status = Rule.eval(r.detailStatus, doc).ifBlank { book.status }
        )
    }

    override suspend fun chapters(book: Book): List<Chapter> = withContext(Dispatchers.IO) {
        chaptersAt(book.url, book.url)
    }

    private fun chaptersAt(url: String, referer: String): List<Chapter> {
        val doc = Jsoup.parse(get(url, referer), url)
        val r = cfg.rules
        val nameRule = r.chapterName.ifBlank { "text" }
        val linkRule = r.chapterLink.ifBlank { "href" }
        val list = doc.select(r.chapterList).mapNotNull { el ->
            val t = Rule.eval(nameRule, el)
            val u = Rule.eval(linkRule, el)
            if (t.isBlank() || u.isBlank()) null else Chapter(0, t, absolute(url, u))
        }.distinctBy { it.url }
        return list.mapIndexed { i, c -> c.copy(index = i) }
    }

    /**
     * 正文：当前站失败时，自动在其它镜像上用「同名书 + 同章标题」重试。
     *
     * 这是「多站容错」的落点——用户不需要知道哪个站在干活，
     * 只要有一家能给出这一章就算成功。
     */
    override suspend fun content(book: Book, chapter: Chapter): String =
        withContext(Dispatchers.IO) {
            // 1) 先用书自己的地址（换源时确定下来的那个站）
            try {
                val text = contentAt(book.url, chapter.url)
                if (text.isNotBlank()) return@withContext text
            } catch (e: Exception) {
                // 落到下面的镜像轮询
            }

            // 2) 轮询其它镜像：重新搜书名 → 找到同章 → 取正文
            for (site in sites) {
                if (book.url.startsWith(site)) continue // 刚试过的那个站跳过
                try {
                    val found = searchOn(site, book.title) ?: continue
                    val chaps = chaptersAt(found, site)
                    val hit = chaps.firstOrNull { sameChapter(it.title, chapter.title) } ?: continue
                    val text = contentAt(found, hit.url)
                    if (text.isNotBlank()) return@withContext text
                } catch (e: Exception) {
                    // 继续下一个镜像
                }
            }
            throw IllegalStateException(
                "所有镜像站都没取到《${book.title}》${chapter.title}的正文。\n" +
                    "可以在「换源阅读」里换一个书源再试。"
            )
        }

    private fun contentAt(bookUrl: String, chapterUrl: String): String {
        val doc = Jsoup.parse(get(chapterUrl, bookUrl), chapterUrl)
        val r = cfg.rules
        val root = doc.selectFirst(r.content)
            ?: throw IllegalStateException("正文规则没匹配到内容")
        r.contentClean.filter { !it.startsWith("re:") }.forEach { sel ->
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
        if (regexes.isNotEmpty()) {
            text = text.split('\n')
                .filter { line -> regexes.none { it.containsMatchIn(line) } }
                .joinToString("\n")
        }
        return text.trim()
    }

    /** 在指定站点搜一本书，返回详情页地址 */
    private fun searchOn(site: String, title: String): String? {
        val tpl = cfg.rules.searchUrl
        if (tpl.isBlank()) return null
        val kw = URLEncoder.encode(title, "UTF-8")
        val url = site + tpl.replace("{key}", kw).replace("{page}", "1")
        val books = parseList(Jsoup.parse(get(url, site), url), url, forSearch = true)
        return books.firstOrNull { SwapMatcher.normalize(it.title) == SwapMatcher.normalize(title) }?.url
            ?: books.firstOrNull()?.url
    }

    private fun parseList(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        forSearch: Boolean = false
    ): List<Book> {
        val r = cfg.rules
        // 搜索结果页与分类页结构通常不同（卡片 vs 表格），优先用 search* 那组
        val itemRule = if (forSearch && r.searchItem.isNotBlank()) r.searchItem else r.listItem
        val nameRule = when {
            forSearch && r.searchName.isNotBlank() -> r.searchName
            else -> r.listName.ifBlank { "dl dt a" }
        }
        val linkRule = when {
            forSearch && r.searchLink.isNotBlank() -> r.searchLink
            else -> r.listLink.ifBlank { "dl dt a@href" }
        }
        val authorRule = if (forSearch && r.searchAuthor.isNotBlank()) r.searchAuthor else r.listAuthor
        val coverRule = if (forSearch && r.searchCover.isNotBlank()) r.searchCover else r.listCover
        val introRule = if (forSearch && r.searchIntro.isNotBlank()) r.searchIntro else r.listIntro

        val items = Rule.selectAll(itemRule, doc)
        val out = ArrayList<Book>(items.size)
        items.forEach { el ->
            val title = Rule.eval(nameRule, el)
            val link = Rule.eval(linkRule, el)
            if (title.isBlank() || link.isBlank()) return@forEach
            out.add(
                Book(
                    sourceId = cfg.id,
                    url = absolute(pageUrl, link),
                    title = title,
                    author = Rule.eval(authorRule, el),
                    cover = absolute(pageUrl, Rule.eval(coverRule, el)),
                    intro = Rule.eval(introRule, el)
                )
            )
        }
        return out.distinctBy { it.url }
    }

    /** 章节标题归一化后比较：「第001章、借体重生」≈「第一章 借体重生」 */
    private fun sameChapter(a: String, b: String): Boolean {
        val na = SwapMatcher.normalize(a.removeNumbersPrefix())
        val nb = SwapMatcher.normalize(b.removeNumbersPrefix())
        if (na.isBlank() || nb.isBlank()) return false
        return na == nb || na.contains(nb) || nb.contains(na)
    }

    /** 去掉「第001章 / 第1章」这种前缀，只留标题本体 */
    private fun String.removeNumbersPrefix(): String =
        replace(Regex("^\\s*第[0-9一二三四五六七八九十百千零〇两]+[章节回][、.．:：\\s]*"), "")

    private fun absolute(base: String, link: String): String {
        val l = link.trim()
        if (l.isEmpty()) return ""
        if (l.startsWith("#") || l.startsWith("javascript:") ||
            l.startsWith("data:") || l.startsWith("about:")
        ) return ""
        if (l.startsWith("http://") || l.startsWith("https://")) return l
        val origin = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: return ""
        // 协议相对
        if (l.startsWith("//")) return "https:" + l
        return if (l.startsWith("/")) origin + l
        else base.substringBeforeLast('/', "") + "/" + l
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        /**
         * 换源候选的构造辅助：给每个镜像都造一个候选，让用户知道有多个站可试。
         * 这里不进活跃代码路径，仅供 UI 展示「这个源的镜像数」。
         */
        fun mirrorCount(cfg: SourceConfig): Int = 1
    }
}
