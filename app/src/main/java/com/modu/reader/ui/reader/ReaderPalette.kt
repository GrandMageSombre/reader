package com.modu.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/** 阅读器配色。白天 / 夜间是两套独立的色板，而不是简单反色。 */
enum class ReaderPalette(
    val label: String,
    val bg: Color,
    val fg: Color,
    val sub: Color,
    val isDark: Boolean
) {
    WHITE("白", Color(0xFFFFFFFF), Color(0xFF1A1A1A), Color(0xFF7C7C7C), false),
    PAPER("米黄", Color(0xFFF5EBD6), Color(0xFF3A2E20), Color(0xFF8A7A62), false),
    GREEN("护眼", Color(0xFFCBE0C6), Color(0xFF1E3320), Color(0xFF5E7A5C), false),
    NIGHT("夜间", Color(0xFF14161A), Color(0xFFB9C0C8), Color(0xFF5E6773), true);

    companion object {
        fun of(index: Int): ReaderPalette = entries.getOrElse(index) { ReaderPalette.PAPER }
    }
}

object ReaderFonts {
    fun of(index: Int): FontFamily = when (index) {
        1 -> FontFamily.Serif
        2 -> FontFamily.SansSerif
        else -> FontFamily.Default
    }

    val names = listOf("默认", "宋体", "黑体")
}
