package com.modu.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 把技术异常翻译成用户能看懂的一句话，细节不打在界面上 */
fun friendlyError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "连不上这个站点（域名解析失败）"
    is java.net.SocketTimeoutException -> "请求超时，站点太慢或网络不通"
    is java.net.ConnectException -> "连接被拒绝，站点可能已经挂了"
    is javax.net.ssl.SSLException -> "HTTPS 握手失败，站点证书有问题"
    // SQLite 原始报错（"no such table: swap (code 1 SQLITE_ERROR)"）不能给用户看
    is android.database.sqlite.SQLiteException ->
        "本地数据出了点问题。请到「设置」里清一次缓存；还不行就重装应用"

    is java.io.IOException -> "网络请求失败" + brief(e)
    is IllegalStateException -> e.message?.takeIf { it.isNotBlank() && !looksTechnical(it) }
        ?: "请求失败"

    is IllegalArgumentException -> e.message?.takeIf { it.isNotBlank() && !looksTechnical(it) }
        ?: "书源配置有误"

    else -> e.message?.takeIf { it.isNotBlank() && !looksTechnical(it) } ?: "未知错误"
}

/** 兜底：明显是技术术语的 message 就不往界面上放 */
private fun looksTechnical(msg: String): Boolean {
    val m = msg.lowercase()
    return m.contains("sqlite") || m.contains("no such table") || m.contains("sql ") ||
        m.contains("exception") || m.contains("nullpointer") ||
        Regex("\\bat [a-z]+\\.[a-z0-9_.]+\\(").containsMatchIn(m)
}

/** 附在错误后面的一小截原始信息，过长或含技术栈就丢掉 */
private fun brief(e: Throwable): String {
    val m = e.message?.trim().orEmpty()
    if (m.isEmpty() || looksTechnical(m)) return ""
    return "：" + m.take(80)
}

@Composable
fun VIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(id = id),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint
    )
}

/** 封面：没有图就显示书名首字，永远不会是一片空白 */
@Composable
fun CoverImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.take(1),
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
fun LoadingView(text: String = "加载中…", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyView(title: String, desc: String = "", modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VIcon(
            id = com.modu.reader.R.drawable.ic_book,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        )
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ErrorView(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "出错了",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 小节标题 */
@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 单行书籍条目（列表用） */
@Composable
fun BookRow(
    title: String,
    author: String,
    cover: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = ""
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(url = cover, title = title, modifier = Modifier.size(width = 46.dp, height = 62.dp), corner = 6.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (subtitle.isNotBlank()) subtitle else author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
