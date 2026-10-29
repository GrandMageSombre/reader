package com.modu.reader.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.source.SourceConfig
import com.modu.reader.ui.VIcon

private const val TEMPLATE = """{
  "id": "my_source",
  "name": "我的书源",
  "baseUrl": "https://example.com",
  "charset": "utf-8",
  "headers": {
    "User-Agent": "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
  },
  "categories": [
    { "name": "玄幻", "url": "/list/xuanhuan/{page}.html" },
    { "name": "都市", "url": "/list/dushi/{page}.html" }
  ],
  "rules": {
    "searchUrl": "/search?q={key}&page={page}",
    "listItem": ".book-list li",
    "listName": "h3 a@text",
    "listLink": "h3 a@href",
    "listAuthor": ".author@text",
    "listCover": "img@src",
    "listIntro": ".intro@text",
    "detailName": "h1@text",
    "detailAuthor": "#info .author@text",
    "detailCover": ".cover img@src",
    "detailIntro": "#intro@text",
    "detailStatus": ".status@text",
    "chapterList": "#list dd a",
    "chapterName": "text",
    "chapterLink": "href",
    "chapterReverse": false,
    "content": "#content",
    "contentClean": ["script", ".ad", "re:本章未完"]
  }
}"""

@Composable
fun SourceScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val cm = remember { ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    var showImport by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val sources = app.sources.sources

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                VIcon(R.drawable.ic_back, "返回", modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("书源管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${sources.size} 个书源 · ${sources.count { it.enabled }} 个已启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showImport = true; importError = null }) { Text("导入") }
        }

        if (message != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    message!!,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { message = null }) { Text("知道了") }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(sources, key = { it.id }) { s ->
                SourceCard(
                    cfg = s,
                    onToggle = { app.sources.setEnabled(s.id, it) },
                    onDelete = {
                        app.sources.remove(s.id)
                        message = "已删除「${s.name}」"
                    }
                )
            }
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp)
                ) {
                    Text("怎么加书源", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. 点「复制模板」拿到一份完整 JSON；\n" +
                            "2. 把 baseUrl、categories、rules 换成目标站点的；\n" +
                            "3. 点「导入」粘贴进来保存即可。\n\n" +
                            "分类、列表、详情、目录、正文全部由 CSS 规则驱动，语法见项目里的 docs/书源规则.md。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = {
                            cm.setPrimaryClip(ClipData.newPlainText("书源模板", TEMPLATE))
                            message = "模板已复制到剪贴板"
                        }) { Text("复制模板") }
                        TextButton(onClick = {
                            importText = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                            importError = null
                            showImport = true
                        }) { Text("从剪贴板导入") }
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Button(onClick = { showImport = true; importError = null }, modifier = Modifier.fillMaxWidth()) {
                Text("导入书源 JSON")
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入书源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it; importError = null },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                        placeholder = { Text("把书源 JSON 粘贴到这里") },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    if (importError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            importError!!,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val cfg = app.sources.importJson(importText)
                        showImport = false
                        importText = ""
                        message = "已导入「${cfg.name}」"
                    } catch (e: Exception) {
                        importError = e.message ?: "导入失败"
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SourceCard(cfg: SourceConfig, onToggle: (Boolean) -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                cfg.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (cfg.builtin) {
                Text(
                    "内置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(6.dp))
            } else {
                Switch(checked = cfg.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    VIcon(
                        R.drawable.ic_delete,
                        "删除",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "id: ${cfg.id}" + if (cfg.baseUrl.isNotBlank()) " · ${cfg.baseUrl}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (cfg.note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                cfg.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (cfg.categories.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "分类：" + cfg.categories.joinToString("、") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
