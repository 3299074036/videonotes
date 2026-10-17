package com.zola.videonotes.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.getValue
import com.zola.videonotes.ui.knowledge.KnowledgeBaseScreen
import com.zola.videonotes.ui.settings.SettingsScreen

/** 底部导航 Tab。 */
private enum class MainTab(val route: String, val label: String) {
    KNOWLEDGE("kb_home", "知识库"),
    SETTINGS("kb_settings", "设置"),
}

/**
 * 主框架（UI 重设计 v2）：底部只剩 知识库 / 设置 两个 Tab。
 * 深层页面（详情/播放器/各设置子页）由外层 NavHost 承载，覆盖在底部导航之上。
 */
@Composable
fun MainScreen(
    onEntryClick: (storageKey: String, noteId: Long?) -> Unit,
    onAddVideo: (String) -> Unit,
    onNavigateToModels: () -> Unit,
    onLicensesClick: () -> Unit,
    onBiliLoginClick: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    startTab: String = MainTab.KNOWLEDGE.route,
) {
    val tabNavController = rememberNavController()
    val backStackEntry by tabNavController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            tabNavController.navigate(tab.route) {
                                popUpTo(tabNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = when (tab) {
                                    MainTab.KNOWLEDGE -> Icons.Filled.Book
                                    MainTab.SETTINGS -> Icons.Filled.Settings
                                },
                                contentDescription = tab.label,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = tabNavController,
            startDestination = startTab,
            modifier = Modifier.padding(padding),
        ) {
            composable(MainTab.KNOWLEDGE.route) {
                KnowledgeBaseScreen(
                    onEntryClick = { entry -> onEntryClick(entry.storageKey, entry.noteId) },
                    onAddVideo = onAddVideo,
                )
            }
            composable(MainTab.SETTINGS.route) {
                SettingsScreen(
                    onNavigateToModels = onNavigateToModels,
                    onLicensesClick = onLicensesClick,
                    onBiliLoginClick = onBiliLoginClick,
                    onNavigateToLogViewer = onNavigateToLogViewer,
                )
            }
        }
    }
}
