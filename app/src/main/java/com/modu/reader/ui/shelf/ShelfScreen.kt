package com.modu.reader.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.data.ShelfBook
import com.modu.reader.ui.CoverImage
import com.modu.reader.ui.DisclaimerDialog
import com.modu.reader.ui.EmptyView
import com.modu.reader.ui.Routes
import com.modu.reader.ui.VIcon
import kotlinx.coroutines.launch

@Composable
fun ShelfScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val scope = rememberCoroutineScope()

    var books by remember { mutableStateOf<List<ShelfBook>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showDecl by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        books = app.repo.shelf()
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("书架", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (books.isEmpty()) "还没有收藏的书" else "${books.size} 本在架",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showDecl = true }) {
                VIcon(R.drawable.ic_info, "使用声明", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { nav.navigate(Routes.BROWSE) }) {
                VIcon(R.drawable.ic_search, "去书城找书", modifier = Modifier.size(22.dp))
            }
        }

        if (showDecl) {
            DisclaimerDialog(onDismiss = { showDecl = false })
        }

        if (loaded && books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyView(
                        title = "书架还是空的",
                        desc = "内置「示例书库」已经可以直接看；去书城选分类，或在书源管理里导入自己的书源",
                        modifier = Modifier.height(230.dp)
                    )
                    Button(onClick = { nav.navigate(Routes.BROWSE) }) { Text("去书城") }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                items(books, key = { it.sourceId + it.bookUrl }) { b ->
                    ShelfItem(
                        book = b,
                        onClick = {
                            nav.navigate(Routes.reader(b.sourceId, b.bookUrl, b.title, b.chapterIndex))
                        },
                        onDetail = {
                            nav.navigate(Routes.detail(b.sourceId, b.bookUrl, b.title))
                        },
                        onRemove = {
                            scope.launch {
                                app.repo.removeFromShelf(b.sourceId, b.bookUrl)
                                books = app.repo.shelf()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfItem(
    book: ShelfBook,
    onClick: () -> Unit,
    onDetail: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Box {
            Column(modifier = Modifier.clickable(onClick = onClick)) {
                CoverImage(
                    url = book.cover,
                    title = book.title,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (book.chapterTitle.isNotBlank()) "读到 ${book.percent}%" else "尚未开始",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { expanded = true },
                contentAlignment = Alignment.Center
            ) {
                VIcon(
                    R.drawable.ic_more,
                    "更多",
                    modifier = Modifier.size(15.dp),
                    tint = Color.White
                )
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("书籍详情") }, onClick = {
                    expanded = false
                    onDetail()
                })
                DropdownMenuItem(text = { Text("移出书架") }, onClick = {
                    expanded = false
                    onRemove()
                })
            }
        }
    }
}
