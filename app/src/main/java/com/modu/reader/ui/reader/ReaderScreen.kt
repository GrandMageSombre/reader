package com.modu.reader.ui.reader

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.data.Chapter
import com.modu.reader.data.SettingsStore
import com.modu.reader.ui.VIcon
import com.modu.reader.ui.findActivity
import com.modu.reader.ui.friendlyError
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 阅读器状态：目录、当前章、正文段落 */
private class ReaderState(
    private val app: App,
    private val sourceId: String,
    private val bookUrl: String,
    private val bookTitle: String,
    private val startIndex: Int
) {
    var chapters by mutableStateOf<List<Chapter>>(emptyList())
        private set
    var index by mutableStateOf(0)
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var initialScroll by mutableStateOf(0)
        private set

    /** 章节请求序号：快速连点上一章/下一章时，晚到的旧响应不许覆盖新章节 */
    private var seq = 0

    suspend fun init() {
        loading = true
        error = null
        try {
            val progress = app.repo.progress(sourceId, bookUrl)
            initialScroll = progress?.second ?: 0
            val list = app.repo.chapters(sourceId, bookUrl, bookTitle)
            if (list.isEmpty()) {
                error = "目录为空，检查书源的 chapterList 规则"
                loading = false
                return
            }
            chapters = list
            val target = when {
                startIndex in list.indices -> startIndex
                else -> progress?.first ?: 0
            }
            open(target.coerceIn(0, list.size - 1))
        } catch (e: Exception) {
            error = friendlyError(e)
            loading = false
        }
    }

    suspend fun open(i: Int, force: Boolean = false) {
        if (i !in chapters.indices) return
        val my = ++seq
        loading = true
        error = null
        try {
            val text = app.repo.content(sourceId, bookUrl, bookTitle, chapters[i], force)
            if (my != seq) return
            val parsed = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (parsed.isEmpty()) {
                error = "这一章正文是空的，可能是正文规则没匹配上"
                loading = false
                return
            }
            if (my != seq) return
            lines = parsed
            index = i
            save(0)
        } catch (e: Exception) {
            if (my != seq) return
            error = friendlyError(e)
        } finally {
            if (my == seq) loading = false
        }
    }

    fun save(scroll: Int) {
        val ch = chapters.getOrNull(index) ?: return
        app.repo.saveProgressAsync(sourceId, bookUrl, index, ch.title, chapters.size, scroll)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    nav: NavHostController,
    sourceId: String,
    bookUrl: String,
    bookTitle: String,
    startIndex: Int
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as App
    val activity = ctx.findActivity()
    val scope = rememberCoroutineScope()

    val palette = ReaderPalette.of(SettingsStore.themeIndex)
    val scrollState = rememberScrollState()
    val state = remember(sourceId, bookUrl) {
        ReaderState(app, sourceId, bookUrl, bookTitle, startIndex)
    }

    var bars by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var needRestore by remember { mutableStateOf(true) }
    var drag by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(state) { state.init() }

    LaunchedEffect(state.index, state.lines.size) {
        if (state.lines.isNotEmpty()) {
            if (needRestore) {
                scrollState.scrollTo(state.initialScroll.coerceAtLeast(0))
                needRestore = false
            } else {
                scrollState.scrollTo(0)
            }
        }
    }

    // 退出阅读器时保存进度
    DisposableEffect(state) {
        onDispose { state.save(scrollState.value) }
    }

    // 常亮
    DisposableEffect(SettingsStore.keepScreenOn) {
        val w = activity?.window
        if (SettingsStore.keepScreenOn) {
            w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // 亮度
    DisposableEffect(SettingsStore.brightness) {
        val w = activity?.window
        if (w != null) {
            val lp = w.attributes
            lp.screenBrightness = if (SettingsStore.brightness < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                SettingsStore.brightness
            }
            w.attributes = lp
        }
        onDispose {
            if (w != null) {
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    BackHandler(enabled = showToc || showSettings) {
        showToc = false
        showSettings = false
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val body = TextStyle(
        color = palette.fg,
        fontSize = SettingsStore.fontSize.sp,
        lineHeight = (SettingsStore.fontSize * SettingsStore.lineHeight).sp,
        fontFamily = ReaderFonts.of(SettingsStore.fontIndex),
        textIndent = TextIndent(firstLine = 2.em),
        letterSpacing = 0.2.sp
    )

    Box(Modifier.fillMaxSize().background(palette.bg)) {

        when {
            state.error != null && state.lines.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("这一页没打开", color = palette.fg, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.error ?: "",
                        color = palette.sub,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { scope.launch { state.open(state.index, force = true) } }) {
                        Text("重新抓取本章", color = palette.fg)
                    }
                    TextButton(onClick = { nav.popBackStack() }) {
                        Text("返回", color = palette.sub)
                    }
                }
            }

            state.lines.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.dp,
                        color = palette.sub
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("加载中…", color = palette.sub, style = MaterialTheme.typography.bodySmall)
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .pointerInput(Unit) {
                            detectTapGestures { bars = !bars }
                        }
                        .padding(horizontal = 22.dp)
                ) {
                    Spacer(Modifier.height(topInset + 46.dp))

                    Text(
                        text = state.chapters.getOrNull(state.index)?.title ?: bookTitle,
                        color = palette.sub,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(20.dp))

                    state.lines.forEach { line ->
                        Text(
                            text = line,
                            style = body,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = (SettingsStore.fontSize * SettingsStore.paragraphSpacing).dp)
                        )
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            onClick = {
                                if (state.index > 0) scope.launch { state.open(state.index - 1) }
                            },
                            enabled = state.index > 0
                        ) { Text("上一章", color = if (state.index > 0) palette.fg else palette.sub) }

                        Spacer(Modifier.width(24.dp))

                        TextButton(
                            onClick = {
                                if (state.index < state.chapters.size - 1) {
                                    scope.launch { state.open(state.index + 1) }
                                }
                            },
                            enabled = state.index < state.chapters.size - 1
                        ) {
                            Text(
                                "下一章",
                                color = if (state.index < state.chapters.size - 1) palette.fg else palette.sub
                            )
                        }
                    }
                    Spacer(Modifier.height(180.dp))
                }
            }
        }

        // 顶部工具栏
        Box(Modifier.align(Alignment.TopCenter)) {
            AnimatedVisibility(visible = bars, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = palette.bg, shadowElevation = 6.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .height(52.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            VIcon(R.drawable.ic_back, "返回", tint = palette.fg)
                        }
                        Text(
                            bookTitle,
                            color = palette.fg,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showToc = true }) {
                            VIcon(R.drawable.ic_list, "目录", tint = palette.fg)
                        }
                    }
                }
            }
        }

        // 底部工具栏
        Box(Modifier.align(Alignment.BottomCenter)) {
            AnimatedVisibility(visible = bars, enter = fadeIn(), exit = fadeOut()) {
                Surface(color = palette.bg, shadowElevation = 10.dp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (state.index > 0) scope.launch { state.open(state.index - 1) }
                                }
                            ) { Text("上一章", color = palette.fg) }

                            Slider(
                                value = drag ?: state.index.toFloat(),
                                onValueChange = { drag = it },
                                onValueChangeFinished = {
                                    val target = (drag ?: state.index.toFloat()).roundToInt()
                                    drag = null
                                    if (target != state.index) {
                                        scope.launch { state.open(target) }
                                    }
                                },
                                valueRange = 0f..(maxOf(1, state.chapters.size - 1)).toFloat(),
                                modifier = Modifier.weight(1f)
                            )

                            TextButton(
                                onClick = {
                                    if (state.index < state.chapters.size - 1) {
                                        scope.launch { state.open(state.index + 1) }
                                    }
                                }
                            ) { Text("下一章", color = palette.fg) }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${state.index + 1} / ${state.chapters.size}",
                                color = palette.sub,
                                style = MaterialTheme.typography.labelSmall
                            )
                            TextButton(onClick = { showToc = true }) {
                                Text("目录", color = palette.fg)
                            }
                            TextButton(onClick = { showSettings = true }) {
                                Text("设置", color = palette.fg)
                            }
                            TextButton(onClick = {
                                SettingsStore.updateThemeIndex(
                                    if (palette.isDark) SettingsStore.THEME_PAPER else SettingsStore.THEME_NIGHT
                                )
                            }) {
                                Text(if (palette.isDark) "日间" else "夜间", color = palette.fg)
                            }
                        }
                    }
                }
            }
        }

        if (state.loading && state.lines.isNotEmpty()) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                    color = palette.sub
                )
            }
        }
    }

    if (showToc) {
        TocSheet(
            chapters = state.chapters,
            currentIndex = state.index,
            onPick = { ch ->
                showToc = false
                scope.launch { state.open(ch.index) }
            },
            onDismiss = { showToc = false }
        )
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }) {
            Text(
                "阅读设置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, bottom = 4.dp)
            )
            ReaderSettingsPanel(
                onBrightnessChange = { v ->
                    val w = activity?.window ?: return@ReaderSettingsPanel
                    val lp = w.attributes
                    lp.screenBrightness = if (v < 0) {
                        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    } else v
                    w.attributes = lp
                }
            )
        }
    }
}
