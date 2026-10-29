package com.modu.reader.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.data.Book
import com.modu.reader.data.Category
import com.modu.reader.ui.CoverImage
import com.modu.reader.ui.EmptyView
import com.modu.reader.ui.ErrorView
import com.modu.reader.ui.Routes
import com.modu.reader.ui.VIcon
import com.modu.reader.ui.LoadingView
import com.modu.reader.ui.friendlyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 通用分页器：列表页和搜索页共用 */
class Pager<T>(private val fetch: suspend (Int) -> List<T>) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var end by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var page = 0

    suspend fun reset() {
        items.clear()
        page = 0
        end = false
        error = null
        loadMore()
    }

    suspend fun loadMore() {
        if (loading || end) return
        loading = true
        try {
            val list = fetch(page + 1)
            if (list.isEmpty()) {
                end = true
            } else {
                page += 1
                items.addAll(list)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            loading = false
        }
    }

    fun retry() {
        error = null
    }
}

@Composable
fun BrowseScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as App
    val sources = app.sources.available()

    var sourceId by remember { mutableStateOf(sources.firstOrNull()?.id ?: "") }
    var category by remember { mutableStateOf<Category?>(null) }
    var cats by remember { mutableStateOf<List<Category>>(emptyList()) }
    var catsLoading by remember { mutableStateOf(false) }
    var catsError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    val current = sources.firstOrNull { it.id == sourceId }

    LaunchedEffect(sourceId, reloadKey) {
        category = null
        cats = emptyList()
        catsError = null
        if (sourceId.isBlank()) return@LaunchedEffect
        catsLoading = true
        try {
            cats = app.repo.categories(sourceId)
        } catch (e: Exception) {
            catsError = friendlyError(e)
        } finally {
            catsLoading = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (category != null) {
                IconButton(onClick = { category = null }) {
                    VIcon(R.drawable.ic_back, "返回", modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    category?.name ?: "书城",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    current?.name ?: "没有可用书源",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = {
                    if (sourceId.isNotBlank()) {
                        nav.navigate(Routes.search(sourceId, current?.name ?: ""))
                    }
                }
            ) {
                VIcon(R.drawable.ic_search, "搜索", modifier = Modifier.size(22.dp))
            }
        }

        if (sources.size > 1 && category == null) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(sources, key = { it.id }) { s ->
                    FilterChip(
                        selected = s.id == sourceId,
                        onClick = { sourceId = s.id },
                        label = { Text(s.name) }
                    )
                }
            }
        }

        when {
            sources.isEmpty() -> EmptyView(
                if (app.sources.sources.isEmpty()) "还没有书源" else "书源都被停用了",
                "去「设置 → 书源管理」导入或启用书源",
                Modifier.fillMaxSize()
            )

            category != null -> CategoryBooks(nav, sourceId, category!!)

            catsLoading -> LoadingView("正在读取分类…", Modifier.fillMaxSize())

            catsError != null -> ErrorView(catsError!!, onRetry = { reloadKey++ })

            else -> Column(Modifier.fillMaxSize()) {
                // 发现源提示：起点/番茄的正文抓不到，要换源
                if (app.sources.isDiscovery(sourceId)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "这里是「发现源」：只能看到书名/简介。" +
                                "打开书后点「换源阅读」，就能用其它书源读正文。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                CategoryGrid(cats) { category = it }
            }
        }
    }
}

@Composable
private fun CategoryGrid(cats: List<Category>, onPick: (Category) -> Unit) {
    if (cats.isEmpty()) {
        EmptyView(
            "这个书源没有定义分类",
            "可以直接用右上角搜索找书，或在书源 JSON 的 categories 里补上分类",
            Modifier.fillMaxSize()
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(cats.size) { i ->
            val c = cats[i]
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { onPick(c) }
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "查看全部",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryBooks(nav: NavHostController, sourceId: String, cat: Category) {
    val app = LocalContext.current.applicationContext as App
    val pager = remember(sourceId, cat) {
        Pager<Book> { page -> app.repo.list(sourceId, cat, page) }
    }

    LaunchedEffect(pager) { pager.reset() }

    BookGrid(nav, pager)
}

@Composable
fun BookGrid(nav: NavHostController, pager: Pager<Book>) {
    val scope = rememberCoroutineScope()

    if (pager.items.isEmpty()) {
        when {
            pager.loading -> LoadingView("正在加载…", Modifier.fillMaxSize())
            pager.error != null -> ErrorView(pager.error!!, onRetry = {
                pager.retry()
                scope.launch { pager.loadMore() }
            })
            else -> EmptyView("这里还没有书", "换个分类试试", Modifier.fillMaxSize())
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(pager.items, key = { it.url }) { book ->
            BookCell(book) {
                nav.navigate(Routes.detail(book.sourceId, book.url, book.title))
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    pager.loading -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                    pager.error != null -> TextButton(onClick = {
                        pager.retry()
                        scope.launch { pager.loadMore() }
                    }) { Text("加载失败，点此重试") }
                    pager.end -> Text(
                        "— 没有更多了 —",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> LaunchedEffect(pager.items.size) { pager.loadMore() }
                }
            }
        }
    }
}

@Composable
private fun BookCell(book: Book, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        CoverImage(
            url = book.cover,
            title = book.title,
            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
        }
    }
}
