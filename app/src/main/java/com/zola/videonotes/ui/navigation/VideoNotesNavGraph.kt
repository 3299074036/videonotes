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
import com.zola.videonotes.ui.auth.XhsLoginScreen
import com.zola.videonotes.ui.importer.ImportScreen
import com.zola.videonotes.ui.metadata.MetadataScreen
import com.zola.videonotes.ui.models.ModelManagementScreen
import com.zola.videonotes.ui.notes.NoteDetailScreen
import com.zola.videonotes.ui.ops.OpsDiagnosticsScreen
import com.zola.videonotes.ui.player.PlayerScreen
import com.zola.videonotes.ui.settings.CrashLogScreen
import com.zola.videonotes.ui.settings.LicensesScreen
import com.zola.videonotes.ui.settings.LogViewerScreen
import com.zola.videonotes.ui.settings.PromptEditorScreen

/**
 * 全 App 导航图（UI 重设计 v2）。
 * - HOME：底部导航框架（知识库 / 设置）；
 * - IMPORT：视频导入页（FAB 唯一导入入口），解析跳元信息页；
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
                onEntryClick = { storageKey, noteId, url ->
                    // 有笔记直接进笔记详情，否则用库里存的原始链接进元信息页
                    // （小红书原链接含 xsec_token；只有库里没有时才退回按 storageKey 反推的地址）。
                    if (noteId != null) {
                        navController.navigate(Routes.noteDetail(noteId))
                    } else {
                        val target = url.ifBlank { storageKeyToUrl(storageKey) }
                        target?.let { navController.navigate(Routes.metadata(it)) }
                    }
                },
                onAddVideoClick = { navController.navigate(Routes.IMPORT) },
                onNavigateToModels = { section ->
                    navController.navigate(Routes.modelManagement(section))
                },
                onLicensesClick = { navController.navigate(Routes.LICENSES) },
                onBiliLoginClick = { navController.navigate(Routes.BILI_LOGIN) },
                onXhsLoginClick = { navController.navigate(Routes.XHS_LOGIN) },
                onNavigateToOps = { navController.navigate(Routes.OPS_DIAGNOSTICS) },
                onNavigateToPromptEditor = { navController.navigate(Routes.PROMPT_EDITOR) },
            )
        }
        composable(Routes.IMPORT) {
            ImportScreen(
                // 解析与分享意图走同一路径：跳元信息页，解析失败由元信息页错误态呈现
                onParse = { url -> navController.navigate(Routes.metadata(url)) },
                onSettingsClick = {
                    // 设置是 HOME 内的底部 Tab：经设置路由直达设置 Tab（与元信息页同款）
                    navController.navigate(Routes.settings("kb_settings"))
                },
                onBackClick = { navController.popBackStack() },
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
                onNavigateToModels = { section ->
                    navController.navigate(Routes.modelManagement(section))
                },
                onNavigateToSettings = {
                    // 直达设置 Tab（单页设置，无 LLM 子页）
                    navController.navigate(Routes.settings("kb_settings"))
                },
                onViewNoteClick = { noteId ->
                    navController.navigate(Routes.noteDetail(noteId))
                },
                onPlayClick = { storageKey, seconds, mediaUrl, sourceUrl ->
                    navController.navigate(Routes.player(storageKey, seconds, mediaUrl, sourceUrl))
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
                onEntryClick = { _, _, _ -> },
                onAddVideoClick = {},
                onNavigateToModels = { section ->
                    navController.navigate(Routes.modelManagement(section))
                },
                onLicensesClick = { navController.navigate(Routes.LICENSES) },
                onBiliLoginClick = { navController.navigate(Routes.BILI_LOGIN) },
                onXhsLoginClick = { navController.navigate(Routes.XHS_LOGIN) },
                onNavigateToOps = { navController.navigate(Routes.OPS_DIAGNOSTICS) },
                onNavigateToPromptEditor = { navController.navigate(Routes.PROMPT_EDITOR) },
                startTab = backStackEntry.arguments?.getString(Routes.ARG_START_TAB)
                    ?: "kb_home",
            )
        }
        composable(Routes.BILI_LOGIN) {
            BiliLoginScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.XHS_LOGIN) {
            XhsLoginScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.LICENSES) {
            LicensesScreen(onBackClick = { navController.popBackStack() })
        }
        composable(
            route = Routes.MODEL_MANAGEMENT_ROUTE,
            arguments = listOf(
                navArgument(Routes.ARG_SECTION) {
                    type = NavType.StringType
                    defaultValue = Routes.SECTION_FULL
                },
            ),
        ) { backStackEntry ->
            ModelManagementScreen(
                section = backStackEntry.arguments?.getString(Routes.ARG_SECTION)
                    ?: Routes.SECTION_FULL,
                onBackClick = { navController.popBackStack() },
                onOpenSection = { section ->
                    navController.navigate(Routes.modelManagement(section))
                },
            )
        }
        composable(Routes.LOG_VIEWER) {
            LogViewerScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.OPS_DIAGNOSTICS) {
            OpsDiagnosticsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToLogViewer = { navController.navigate(Routes.LOG_VIEWER) },
                onNavigateToCrashLog = { navController.navigate(Routes.CRASH_LOG) },
            )
        }
        composable(Routes.CRASH_LOG) {
            CrashLogScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Routes.PROMPT_EDITOR) {
            PromptEditorScreen(onBackClick = { navController.popBackStack() })
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
                onNavigateHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                        launchSingleTop = true
                    }
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
                navArgument(Routes.ARG_MEDIA_URL) {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument(Routes.ARG_SOURCE_URL) {
                    type = NavType.StringType
                    defaultValue = ""
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
        Platform.DOUYIN -> "https://www.douyin.com/video/$videoId"
        Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/explore/$videoId"
        else -> null
    }
}
