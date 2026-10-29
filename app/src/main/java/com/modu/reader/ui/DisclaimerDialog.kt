package com.modu.reader.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.modu.reader.data.SettingsStore

/**
 * 启动声明：本软件为 umena 个人自用，禁止商业用途。
 *
 * - 默认每次启动都弹（[SettingsStore.showDisclaimer]）
 * - 弹窗底部勾选框「每次打开时显示此声明」，取消勾选后不再自动弹
 * - 首页右上角「i」随时可重看；重看时把勾重新勾上即可恢复自动弹出
 */
@Composable
fun DisclaimerDialog(onDismiss: () -> Unit) {
    var showEveryTime by remember { mutableStateOf(SettingsStore.showDisclaimer) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("使用声明", fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    "本软件为 umena 个人自用软件，仅供学习交流。\n" +
                        "禁止任何形式的商业用途。\n\n" +
                        "书源内容来自互联网公开页面，版权归原站及作者所有；" +
                        "请支持正版，阅读付费章节请前往正版平台。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = showEveryTime,
                            onValueChange = {
                                showEveryTime = it
                                SettingsStore.updateShowDisclaimer(it)
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = showEveryTime, onCheckedChange = null)
                    Text(
                        "每次打开时显示此声明",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        }
    )
}
