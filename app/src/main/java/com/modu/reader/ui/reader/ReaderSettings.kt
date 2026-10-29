package com.modu.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.modu.reader.data.SettingsStore

/**
 * 阅读设置面板：字号 / 行距 / 段距 / 字体 / 背景 / 亮度 / 常亮。
 * 阅读器里和「设置」页共用同一套控件，改完立刻生效。
 */
@Composable
fun ReaderSettingsPanel(
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    onBrightnessChange: ((Float) -> Unit)? = null
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
            TextButton(onClick = { SettingsStore.updateFontSize(SettingsStore.fontSize - 1f) }) {
                Text("A-", fontWeight = FontWeight.Medium)
            }
            Slider(
                value = SettingsStore.fontSize,
                onValueChange = { SettingsStore.updateFontSize(it) },
                valueRange = 13f..34f,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { SettingsStore.updateFontSize(SettingsStore.fontSize + 1f) }) {
                Text("A+", fontWeight = FontWeight.Medium)
            }
            Text(
                "${SettingsStore.fontSize.toInt()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("行距", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
            Slider(
                value = SettingsStore.lineHeight,
                onValueChange = { SettingsStore.updateLineHeight(it) },
                valueRange = 1.2f..2.6f,
                modifier = Modifier.weight(1f)
            )
            Text(
                String.format("%.1f", SettingsStore.lineHeight),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("段距", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
            Slider(
                value = SettingsStore.paragraphSpacing,
                onValueChange = { SettingsStore.updateParagraphSpacing(it) },
                valueRange = 0.2f..2.0f,
                modifier = Modifier.weight(1f)
            )
            Text(
                String.format("%.1f", SettingsStore.paragraphSpacing),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("字体", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReaderFonts.names.forEachIndexed { i, name ->
                FilterChip(
                    selected = SettingsStore.fontIndex == i,
                    onClick = { SettingsStore.updateFontIndex(i) },
                    label = { Text(name) }
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("背景", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ReaderPalette.entries.forEach { p ->
                val selected = SettingsStore.themeIndex == p.ordinal
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(p.bg)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable { SettingsStore.updateThemeIndex(p.ordinal) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("文", color = p.fg, style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        p.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("亮度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
            Slider(
                value = if (SettingsStore.brightness < 0) 0.6f else SettingsStore.brightness,
                onValueChange = {
                    SettingsStore.updateBrightness(it)
                    onBrightnessChange?.invoke(it)
                },
                valueRange = 0.1f..1f,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                SettingsStore.updateBrightness(-1f)
                onBrightnessChange?.invoke(-1f)
            }) { Text("跟随系统") }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("阅读时常亮", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = SettingsStore.keepScreenOn,
                onCheckedChange = { SettingsStore.updateKeepScreenOn(it) }
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "提示：夜间模式也可以直接改成深色背景，字号范围 13–34。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
