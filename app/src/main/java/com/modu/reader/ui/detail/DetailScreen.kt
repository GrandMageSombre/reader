package com.modu.reader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.data.Book
import com.modu.reader.data.BookSwap
import com.modu.reader.data.Chapter
import com.modu.reader.data.SwapCandidate
import com.modu.reader.ui.CoverImage
import com.modu.reader.ui.ErrorView
import com.modu.reader.ui.LoadingView
import com.modu.reader.ui.Routes
import com.modu.reader.ui.SectionTitle
import com.modu.reader.ui.VIcon
import com.modu.reader.ui.friendlyError
import com.modu.reader.ui.reader.TocSheet
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    nav: NavHostController,
    sourceId: String,
    bookUrl: String,
    fallbackTitle: String
) {
    val app = LocalContext.current.applicationContext as App
    val scope = rememberCoroutineScope()

    var book by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var onShelf by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var readIndex by remember { mutableStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }
    var chaptersError by remember { mutableStateOf<String?>(null) }

    // ---- 换源 ----
    var showSwap by remember { mutableStateOf(false) }
    var swapLoading by remember { mutableStateOf(false) }
    var swapError by remember { mutableStateOf<String?>(null) }
    var swapCandidates by remember { mutableStateOf<List<SwapCandidate>>(emptyList()) }
    var currentSwap by remember { mutableStateOf<BookSwap?>(null) }
    var swapKey by remember { mutableStateOf(0) }

    // ---- 本机抓取（起点/番茄直连）：任务挂在 App 上，离开本页也不会被取消 ----
    val crawl by app.repo.crawlState.collectAsState()
    val crawlMine = crawl?.takeIf { it.bookUrl == bookUrl }
    val deviceBusy = crawlMine?.running == true
    val deviceState = crawlMine?.message.orEmpty()

    // 抓完后直接从本地库刷新目录，不再联网（避免把抓取成功反显成加载失败）
    LaunchedEffect(crawlMine?.finished, crawlMine?.message) {
        if (crawlMine?.finished == true) {
            chapters = app.repo.cachedChapters(bookUrl)
            if (chapters.isNotEmpty()) chaptersError = null
        }
    }

    LaunchedEffect(bookUrl, reloadKey) {
        loading = true
        error = null
        chaptersError = null
        try {
            currentSwap = app.repo.swapOf(bookUrl)
        } catch (_: Exception) {
            currentSwap = null
        }
        try {
            val d = app.repo.detail(sourceId, bookUrl, fallbackTitle)
            book = d
            onShelf = app.repo.isOnShelf(sourceId, bookUrl)
            val p = app.repo.progress(sourceId, bookUrl)
            readIndex = p?.first ?: 0
            try {
                chapters = app.repo.chapters(sourceId, bookUrl, d.title)
            } catch (e: Exception) {
                chapters = emptyList()
                chaptersError = friendlyError(e)
            }
        } catch (e: Exception) {
            error = friendlyError(e)
        } finally {
            loading = false
        }
    }

    // 换源：按书名在其它书源里搜索
    LaunchedEffect(showSwap, swapKey) {
        if (!showSwap) return@LaunchedEffect
        val b = book ?: return@LaunchedEffect
        swapLoading = true
        swapError = null
        swapCandidates = emptyList()
        try {
            swapCandidates = app.repo.findSwapCandidates(sourceId, bookUrl, b.title, b.author)
        } catch (e: Exception) {
            swapError = friendlyError(e)
        } finally {
            swapLoading = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                VIcon(R.drawable.ic_back, "返回", modifier = Modifier.size(22.dp))
            }
            Text(
                "书籍详情",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
        }

        when {
            loading -> LoadingView("正在加载详情…", Modifier.fillMaxSize())
            error != null -> ErrorView(error!!, onRetry = { reloadKey++ }, Modifier.fillMaxSize())
            book == null -> ErrorView("没有拿到书籍信息", null, Modifier.fillMaxSize())
            else -> {
                val b = book!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 32.dp
                    )
                ) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            CoverImage(
                                url = b.cover,
                                title = b.title,
                                modifier = Modifier.size(width = 112.dp, height = 156.dp),
                                corner = 12.dp
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    b.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    b.author.ifBlank { "佚名" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (b.status.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        b.status,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "共 ${chapters.size} 章",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    nav.navigate(Routes.reader(sourceId, bookUrl, b.title, readIndex))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (onShelf && readIndex > 0) "继续阅读" else "开始阅读")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        if (onShelf) {
                                            app.repo.removeFromShelf(sourceId, bookUrl)
                                            onShelf = false
                                        } else {
                                            app.repo.addToShelf(b)
                                            onShelf = true
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (onShelf) "已在书架" else "加入书架")
                            }
                        }
                    }

                    // 换源：起点 VIP / 番茄正文抓不到时，用这条路径读全本
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    swapKey++
                                    showSwap = true
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (currentSwap != null) "已换源 · 点击更换" else "换源阅读")
                            }
                            // 起点 / 番茄：本机直连抓一次（WebView 过反爬，目录+正文进本地缓存）
                            if (app.sources.isDeviceCrawlable(sourceId)) {
                                OutlinedButton(
                                    onClick = {
                                        val b = book ?: return@OutlinedButton
                                        app.repo.startCrawl(sourceId, bookUrl, b.title, limit = 30)
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = !deviceBusy
                                ) {
                                    Text(if (deviceBusy) "抓取中…" else "本机抓取")
                                }
                            }
                        }
                        if (deviceState.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                deviceState,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (currentSwap != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "正文来自「${app.sources.byId(currentSwap!!.sourceId)?.name ?: "其它书源"}」" +
                                    " ·《${currentSwap!!.matchedTitle}》",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    item {
                        SectionTitle("简介")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(14.dp)
                        ) {
                            Text(
                                b.intro.ifBlank { "这本书还没有简介。" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) 60 else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (b.intro.length > 90) {
                                TextButton(
                                    onClick = { expanded = !expanded },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                                ) {
                                    Text(if (expanded) "收起" else "展开")
                                }
                            }
                        }
                    }

                    item {
                        SectionTitle("目录") {
                            if (chapters.size > 12) {
                                TextButton(onClick = { showToc = true }) { Text("全部 ${chapters.size} 章") }
                            }
                        }
                    }

                    val preview = chapters.take(12)
                    items(preview.size) { i ->
                        val ch = preview[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    nav.navigate(Routes.reader(sourceId, bookUrl, b.title, ch.index))
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ch.title,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (ch.index == readIndex) {
                                Text(
                                    "上次读到",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (chapters.isEmpty()) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    chaptersError ?: "目录没取到，进阅读器时会再试一次",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (showToc) {
                    TocSheet(
                        chapters = chapters,
                        currentIndex = readIndex,
                        onPick = { ch ->
                            showToc = false
                            nav.navigate(Routes.reader(sourceId, bookUrl, b.title, ch.index))
                        },
                        onDismiss = { showToc = false }
                    )
                }

                if (showSwap) {
                    SwapDialog(
                        bookTitle = b.title,
                        loading = swapLoading,
                        error = swapError,
                        candidates = swapCandidates,
                        current = currentSwap,
                        onPick = { c ->
                            scope.launch {
                                app.repo.setSwap(
                                    BookSwap(
                                        bookUrl = bookUrl,
                                        sourceId = c.sourceId,
                                        sourceBookUrl = c.bookUrl,
                                        matchedTitle = c.title,
                                        matchedAuthor = c.author,
                                        score = c.score
                                    )
                                )
                                showSwap = false
                                try {
                                    currentSwap = app.repo.swapOf(bookUrl)
                                } catch (_: Exception) {
                                    currentSwap = null
                                }
                                // 换源后目录会变，重新拉一次
                                reloadKey++
                            }
                        },
                        onClearSwap = {
                            scope.launch {
                                app.repo.clearSwap(bookUrl)
                                currentSwap = null
                                showSwap = false
                                reloadKey++
                            }
                        },
                        onRetry = { swapKey++ },
                        onDismiss = { showSwap = false }
                    )
                }
            }
        }
    }
}
