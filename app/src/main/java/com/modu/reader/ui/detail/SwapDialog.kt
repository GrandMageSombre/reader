package com.modu.reader.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.modu.reader.data.BookSwap
import com.modu.reader.data.SwapCandidate
import com.modu.reader.ui.CoverImage
import com.modu.reader.ui.ErrorView
import com.modu.reader.ui.LoadingView

/**
 * 换源面板。
 *
 * 背景：起点 VIP 与番茄的正文抓不到，但同一本书通常在第三方源有全本。
 * 这里把「按书名在其它书源里搜到的候选」列出来，选一个就切过去读。
 */
@Composable
fun SwapDialog(
    bookTitle: String,
    loading: Boolean,
    error: String?,
    candidates: List<SwapCandidate>,
    current: BookSwap?,
    onPick: (SwapCandidate) -> Unit,
    onClearSwap: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var showAll by remember { mutableStateOf(false) }
    val shown = remember(candidates, showAll) {
        if (showAll) candidates else candidates.filter { it.exact }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 40.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 16.dp)) {
                Text("换源阅读", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "在其它书源里按书名搜索《$bookTitle》",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().height(180.dp)) {
                    LoadingView("正在其它书源里搜索…")
                }

                error != null -> Box(Modifier.fillMaxWidth().height(200.dp)) {
                    ErrorView(error, onRetry = onRetry)
                }

                candidates.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "采集站里没搜到这本书",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "起点/番茄榜单上的新书，采集站通常还没收录——这是数据源的限制，" +
                            "不是应用出错。\n\n" +
                            "可以试试：\n" +
                            "· 在「设置 → 书源管理」里把采集站全部启用，能多覆盖一些；\n" +
                            "· 书名可能有差异（去掉副标题再搜）；\n" +
                            "· 换一本已完结、较热门的书，收录率会高很多。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRetry) { Text("重新搜索") }
                }

                else -> {
                    if (shown.isEmpty()) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "没找到同名书，但有 ${candidates.size} 个相近结果",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { showAll = true }) { Text("显示全部结果") }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { "${it.sourceId}#${it.bookUrl}" }) { c ->
                            SwapRow(c) { onPick(c) }
                        }
                        if (!showAll && candidates.size > shown.size) {
                            item {
                                TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("还有 ${candidates.size - shown.size} 个相近结果")
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (current != null) {
                    TextButton(onClick = onClearSwap) { Text("取消换源") }
                    Text(
                        "当前：${current.matchedTitle.ifBlank { "已换源" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun SwapRow(c: SwapCandidate, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = c.cover,
            title = c.title,
            modifier = Modifier.size(width = 40.dp, height = 54.dp),
            corner = 6.dp
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                c.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(c.sourceName)
                    if (c.author.isNotBlank()) append(" · ").append(c.author)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (c.exact) {
            Text(
                "同名",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Text(
                "相近",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
