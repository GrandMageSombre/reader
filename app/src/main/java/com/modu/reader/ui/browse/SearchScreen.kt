package com.modu.reader.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.modu.reader.App
import com.modu.reader.R
import com.modu.reader.data.Book
import com.modu.reader.ui.EmptyView
import com.modu.reader.ui.VIcon

@Composable
fun SearchScreen(nav: NavHostController, sourceId: String, sourceName: String) {
    val app = LocalContext.current.applicationContext as App

    var input by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }

    val pager = remember(sourceId, submitted) {
        if (submitted.isBlank()) null
        else Pager<Book>(fetch = { page -> app.repo.search(sourceId, submitted, page) }, keyOf = { it.url })
    }

    LaunchedEffect(pager) { pager?.reset() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                VIcon(R.drawable.ic_back, "返回", modifier = Modifier.size(22.dp))
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("书名 / 作者") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = input.trim() })
            )
            TextButton(onClick = { submitted = input.trim() }) { Text("搜索") }
        }

        Text(
            "书源：$sourceName",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp)
        )

        if (submitted.isBlank() || pager == null) {
            EmptyView("输入关键字开始搜索", "搜索结果取决于当前书源的 rules.searchUrl", Modifier.fillMaxSize())
        } else {
            BookGrid(nav, pager)
        }
    }
}
