package com.zola.videonotes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zola.videonotes.data.reader.DeepLinkBuilder
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.ui.auth.BiliLoginScreen
import com.zola.videonotes.ui.metadata.MetadataScreen
import com.zola.videonotes.ui.models.ModelManagementScreen
import com.zola.videonotes.ui.notes.NoteDetailScreen
import com.zola.videonotes.ui.player.PlayerScreen
import com.zola.videonotes.ui.settings.LicensesScreen
import com.zola.videonotes.ui.settings.LogViewerScreen

/**
 * 全 App 导航图（UI 重设计 v2）。
 * - HOME：底部导航框架（知识库 / 设置）；
 * - 导入页已移除，FAB 直接粘贴链接进元信息页；
 * - 全屏笔记生成页已移除（M7a 后台任务化）。
 */
@Composable
fun VideoNotesNavGraph(
    startDestination: String = Routes.HOME,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            MainScreen(
                onEntryClick = { storageKey, noteId ->
                    // 有笔记直接进笔记详情，否则按 storageKey 反推 URL 进元信息页
                    if (noteId != null) {
                        navController.navigate(Routes.noteDetail(noteId))
                    } else {
                        storageKeyToUrl(storageKey)?.let { url ->
                            navController.navigate(Routes.metadata(url))
                        }
                    }
                },
                onAddVideo = { url -> navController.navigate(Routes.metadata(url)) },
                onNavigateToModels = { navController.navigate(Routes.MODEL_MANAGEMENT) },
                onLicensesClick = { navController.navigate(Routes.LICENSES) },
                onBiliLoginClick = { navController.navigate(Routes.BILI_LOGIN) },
                onNavigateToLogViewer = { navController.navigate(Routes.LOG_VIEWER) },
            )
        }
        composable(
            route = Routes.METADATA_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_URL) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            MetadataScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToModels = { navController.navigate(Routes.MODEL_MANAGEMENT) },
                onNavigateToSettings = {
                    // 直达设置 Tab（单页设置，无 LLM 子页）
                    navController.navigate(Routes.settings("kb_settings"))
                },
                onViewNoteClick = { noteId ->
                    navController.navigate(Routes.noteDetail(noteId))
                },
                onPlayClick = { storageKey, seconds ->
                    navController.navigate(Routes.player(storageKey, seconds))
                },
            )
        }
        composable(
            route = "${Routes.SETTINGS}?${Routes.ARG_START_TAB}={${Routes.ARG_START_TAB}}",
            arguments = listOf(
                navArgument(Routes.ARG_START_TAB) {
                    type = NavType.StringType
                    defaultValue = "kb_home"
                },
            ),
        ) { backStackEntry ->
            // 设置页路由（兼容旧入口）：经底部导航框架打开，可指定初始 Tab
            MainScreen(
                onEntryClick = { _, _ -> },
                onAddVideo = {},
                onNavigateToModels = { navController.navigate(Routes.MODEL_MANAGEMENT) },
                onLicensesClick = { navController.navigate(Routes.LICENSES) },
                onBiliLoginClick = { navController.navigate(Routes.BILI_LOGIN) },
                onNavigateToLogViewer = { navController.navigate(Routes.LOG_VIEWER) },
                startTab = backStackEntry.arguments?.getString(Routes.ARG_START_TAB)
                    ?: "kb_home",
            )
        }
        composable(Routes.BILI_LOGIN) {
            BiliLoginScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LICENSES) {
            LicensesScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.MODEL_MANAGEMENT) {
            ModelManagementScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.LOG_VIEWER) {
            LogViewerScreen(onBackClick = { navController.popBackStack() })
        }
        composable(
            route = Routes.NOTE_DETAIL_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_NOTE_ID) {
                    type = NavType.LongType
                    defaultValue = -1L
                },
            ),
        ) {
            NoteDetailScreen(
                onBackClick = { navController.popBackStack() },
                onPlayClick = { storageKey, seconds ->
                    navController.navigate(Routes.player(storageKey, seconds))
                },
            )
        }
        composable(
            route = Routes.PLAYER_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_STORAGE_KEY) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_START_SECONDS) {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) {
            PlayerScreen(onBackClick = { navController.popBackStack() })
        }
    }
}

/** 从 storageKey 反推可解析的视频 URL（B站 BV / YouTube ID）。 */
private fun storageKeyToUrl(storageKey: String): String? {
    val platform = DeepLinkBuilder.platformOf(storageKey) ?: return null
    val videoId = DeepLinkBuilder.videoIdOf(storageKey)
    return when (platform) {
        Platform.BILIBILI -> "https://www.bilibili.com/video/$videoId"
        Platform.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId"
        else -> null
    }
}
