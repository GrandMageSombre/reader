package com.modu.reader.ui

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.modu.reader.R
import com.modu.reader.data.SettingsStore
import com.modu.reader.ui.browse.BrowseScreen
import com.modu.reader.ui.browse.SearchScreen
import com.modu.reader.ui.detail.DetailScreen
import com.modu.reader.ui.reader.ReaderPalette
import com.modu.reader.ui.reader.ReaderScreen
import com.modu.reader.ui.settings.SettingsScreen
import com.modu.reader.ui.settings.SourceScreen
import com.modu.reader.ui.shelf.ShelfScreen
import com.modu.reader.ui.theme.MoDuTheme

object Routes {
    const val SHELF = "shelf"
    const val BROWSE = "browse"
    const val SETTINGS = "settings"
    const val SOURCES = "sources"

    const val SEARCH = "search?sid={sid}&name={name}"
    const val DETAIL = "detail?sid={sid}&url={url}&title={title}"
    const val READER = "reader?sid={sid}&url={url}&title={title}&idx={idx}"

    fun search(sid: String, name: String) =
        "search?sid=${Uri.encode(sid)}&name=${Uri.encode(name)}"

    fun detail(sid: String, url: String, title: String) =
        "detail?sid=${Uri.encode(sid)}&url=${Uri.encode(url)}&title=${Uri.encode(title)}"

    fun reader(sid: String, url: String, title: String, idx: Int) =
        "reader?sid=${Uri.encode(sid)}&url=${Uri.encode(url)}&title=${Uri.encode(title)}&idx=$idx"
}

@Composable
fun MoDuApp() {
    val nav = rememberNavController()
    val dark = SettingsStore.themeIndex == ReaderPalette.NIGHT.ordinal

    MoDuTheme(dark = dark) {
        val view = LocalView.current
        val ctx = LocalContext.current
        SideEffect {
            val act = ctx.findActivity() ?: return@SideEffect
            val c = WindowCompat.getInsetsController(act.window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }

        val backStack by nav.currentBackStackEntryAsState()
        val route = backStack?.destination?.route
        val showBar = route == Routes.SHELF || route == Routes.BROWSE || route == Routes.SETTINGS

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBar) {
                    BottomBar(nav, route)
                }
            }
        ) { pad ->
            NavHost(
                navController = nav,
                startDestination = Routes.SHELF,
                modifier = Modifier.fillMaxSize().padding(bottom = pad.calculateBottomPadding())
            ) {
                composable(Routes.SHELF) { ShelfScreen(nav) }
                composable(Routes.BROWSE) { BrowseScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(Routes.SOURCES) { SourceScreen(nav) }

                composable(
                    route = Routes.SEARCH,
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType; defaultValue = "" },
                        navArgument("name") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    SearchScreen(
                        nav = nav,
                        sourceId = entry.arguments?.getString("sid") ?: "",
                        sourceName = entry.arguments?.getString("name") ?: ""
                    )
                }

                composable(
                    route = Routes.DETAIL,
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType; defaultValue = "" },
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { entry ->
                    DetailScreen(
                        nav = nav,
                        sourceId = entry.arguments?.getString("sid") ?: "",
                        bookUrl = entry.arguments?.getString("url") ?: "",
                        fallbackTitle = entry.arguments?.getString("title") ?: ""
                    )
                }

                composable(
                    route = Routes.READER,
                    arguments = listOf(
                        navArgument("sid") { type = NavType.StringType; defaultValue = "" },
                        navArgument("url") { type = NavType.StringType; defaultValue = "" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                        navArgument("idx") { type = NavType.IntType; defaultValue = -1 }
                    )
                ) { entry ->
                    ReaderScreen(
                        nav = nav,
                        sourceId = entry.arguments?.getString("sid") ?: "",
                        bookUrl = entry.arguments?.getString("url") ?: "",
                        bookTitle = entry.arguments?.getString("title") ?: "",
                        startIndex = entry.arguments?.getInt("idx") ?: -1
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    val items = listOf(
        Triple(Routes.SHELF, "书架", R.drawable.ic_book),
        Triple(Routes.BROWSE, "书城", R.drawable.ic_list),
        Triple(Routes.SETTINGS, "设置", R.drawable.ic_settings)
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        items.forEach { (r, label, icon) ->
            NavigationBarItem(
                selected = route == r,
                onClick = {
                    if (route != r) {
                        nav.navigate(r) {
                            popUpTo(Routes.SHELF) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = {
                    VIcon(
                        id = icon,
                        contentDescription = label,
                        tint = if (route == r) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
