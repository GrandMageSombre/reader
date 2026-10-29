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
import com.modu.reader.data.Chapter
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

    LaunchedEffect(bookUrl, reloadKey) {
        loading = true
        error = null
        chaptersError = null
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
            }
        }
    }
}
