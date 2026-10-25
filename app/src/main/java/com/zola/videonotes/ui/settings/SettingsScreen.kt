package com.zola.videonotes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.theme.ThemeMode
import com.zola.videonotes.ui.components.PaperSegmentedRow
import com.zola.videonotes.ui.navigation.Routes
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 设置页（纸上课堂）：衬线大字标题 + 纸面直铺分组清单——
 * 分组小标题 + 发丝线分隔的文字行（无卡片、无行图标），右侧放状态值 / 箭头 / 开关。
 * 生成笔记：生成引擎分段（行内右侧，本地在前，选中态为有效引擎）+
 * 与有效引擎联动的单行（大语言模型 / 云端 API 二选一显示，带真实状态值）+
 * 笔记提示词行（自定义 system 提示词），三个模型行各直达模型管理页对应分区；
 * 外观与关于：主题四档分段（标题行下紧接）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToModels: (section: String) -> Unit,
    onLicensesClick: () -> Unit,
    onBiliLoginClick: () -> Unit,
    onXhsLoginClick: () -> Unit,
    onNavigateToOps: () -> Unit,
    onNavigateToPromptEditor: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val cacheSizeText by viewModel.cacheSizeText.collectAsStateWithLifecycle()
    val biliLoggedIn by viewModel.biliLoggedIn.collectAsStateWithLifecycle()
    val biliUid by viewModel.biliUid.collectAsStateWithLifecycle()
    val xhsLoggedIn by viewModel.xhsLoggedIn.collectAsStateWithLifecycle()
    val youtubeImported by viewModel.youtubeImported.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val effectiveEngineType by viewModel.effectiveEngineType.collectAsStateWithLifecycle()
    val localModelText by viewModel.localModelText.collectAsStateWithLifecycle()
    val cloudApiText by viewModel.cloudApiText.collectAsStateWithLifecycle()
    val asrModelText by viewModel.asrModelText.collectAsStateWithLifecycle()
    val promptOverrideText by viewModel.promptOverrideText.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showXhsLogoutConfirm by remember { mutableStateOf(false) }
    var showYoutubeImport by remember { mutableStateOf(false) }
    var showYoutubeClearConfirm by remember { mutableStateOf(false) }
    var youtubeCookieInput by remember { mutableStateOf("") }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出 B站登录") },
            text = { Text("将清除本机保存的 B站登录凭证，之后需登录的视频将无法获取字幕。确定退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logoutBili()
                    },
                ) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showXhsLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showXhsLogoutConfirm = false },
            title = { Text("退出小红书登录") },
            text = { Text("将清除本机保存的小红书登录凭证，之后小红书视频将无法解析。确定退出吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showXhsLogoutConfirm = false
                        viewModel.logoutXhs()
                    },
                ) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { showXhsLogoutConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showYoutubeImport) {
        AlertDialog(
            onDismissRequest = { showYoutubeImport = false },
            title = { Text("导入 YouTube cookie") },
            text = {
                Column {
                    if (youtubeImported) {
                        Text(
                            text = "已导入，重新导入会覆盖旧值。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    }
                    OutlinedTextField(
                        value = youtubeCookieInput,
                        onValueChange = { youtubeCookieInput = it },
                        placeholder = {
                            Text("粘贴从浏览器复制的 cookie 字符串，如：SID=…; HSID=…; SSID=…")
                        },
                        minLines = 4,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    Text(
                        text = "电脑浏览器（已登录 YouTube）导出整串 cookie，发到手机后粘贴。Cookie 只存本机加密存储，不会上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.importYoutubeCookie(youtubeCookieInput)) {
                            showYoutubeImport = false
                            youtubeCookieInput = ""
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (youtubeImported) {
                        TextButton(
                            onClick = {
                                showYoutubeImport = false
                                showYoutubeClearConfirm = true
                            },
                        ) { Text("清除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { showYoutubeImport = false }) { Text("取消") }
                }
            },
        )
    }

    if (showYoutubeClearConfirm) {
        AlertDialog(
            onDismissRequest = { showYoutubeClearConfirm = false },
            title = { Text("清除 YouTube cookie") },
            text = { Text("将清除本机保存的 YouTube cookie，之后需登录的视频可能无法解析。确定清除吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showYoutubeClearConfirm = false
                        viewModel.clearYoutubeCookie()
                    },
                ) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showYoutubeClearConfirm = false }) { Text("取消") }
            },
        )
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VideoNotesTokens.Space16),
        ) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
            // 页头：内容区衬线大字（不再用 TopAppBar 小标题）
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // ---------- 生成笔记（2026-10-24 合并包由「大语言模型」改名） ----------
            SettingsGroup(title = "生成笔记") {
                // 生成引擎：分段控件在行右侧同行呈现（本地在前，选中态 = 有效引擎）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = VideoNotesTokens.Space12),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "生成引擎",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "本地模型完全离线，云端速度更快",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PaperSegmentedRow(
                        labels = listOf("本地模型", "云端 API"),
                        selectedIndex = if (effectiveEngineType == LlmEngineType.LOCAL) 0 else 1,
                        onSelect = { index ->
                            if (index == 0) {
                                viewModel.setEngineType(LlmEngineType.LOCAL)
                            } else {
                                // 存存储值后顺势去云端直达页补齐配置；
                                // 配齐返回后有效引擎自动变云端，没配齐仍回落本地
                                viewModel.setEngineType(LlmEngineType.CLOUD)
                                onNavigateToModels(Routes.SECTION_LLM_CLOUD)
                            }
                        },
                    )
                }
                SettingsDivider()
                // 联动行：只显示有效引擎对应的一行
                if (effectiveEngineType == LlmEngineType.LOCAL) {
                    SettingsRow(
                        title = "大语言模型",
                        subtitle = localModelText.ifBlank { "读取中…" },
                        onClick = { onNavigateToModels(Routes.SECTION_LLM_LOCAL) },
                        trailing = { SettingsChevron() },
                    )
                } else {
                    SettingsRow(
                        title = "云端 API",
                        subtitle = cloudApiText.ifBlank { "读取中…" },
                        onClick = { onNavigateToModels(Routes.SECTION_LLM_CLOUD) },
                        trailing = { SettingsChevron() },
                    )
                }
                SettingsDivider()
                // 笔记提示词：自定义 system 提示词（本地 / 云端各存各的，一期只开放 system）
                SettingsRow(
                    title = "笔记提示词",
                    subtitle = promptOverrideText.ifBlank { "读取中…" },
                    onClick = onNavigateToPromptEditor,
                    trailing = { SettingsChevron() },
                )
            }

            // ---------- 语音识别 ----------
            SettingsGroup(title = "语音识别") {
                SettingsRow(
                    title = "识别模型",
                    subtitle = asrModelText.ifBlank { "读取中…" },
                    onClick = { onNavigateToModels(Routes.SECTION_ASR) },
                    trailing = { SettingsChevron() },
                )
            }

            // ---------- 账号与存储 ----------
            SettingsGroup(title = "账号与存储") {
                if (biliLoggedIn) {
                    SettingsRow(
                        title = "B站账号",
                        subtitle = if (biliUid != null) "已登录（UID $biliUid）" else "已登录",
                        trailing = {
                            TextButton(onClick = { showLogoutConfirm = true }) {
                                Text("退出登录")
                            }
                        },
                    )
                } else {
                    SettingsRow(
                        title = "B站账号",
                        subtitle = "未登录，登录后可获取需登录的字幕",
                        trailing = {
                            Button(onClick = onBiliLoginClick) {
                                Text("登录")
                            }
                        },
                    )
                }
                SettingsDivider()
                if (xhsLoggedIn) {
                    SettingsRow(
                        title = "小红书账号",
                        subtitle = "已登录",
                        trailing = {
                            TextButton(onClick = { showXhsLogoutConfirm = true }) {
                                Text("退出登录")
                            }
                        },
                    )
                } else {
                    SettingsRow(
                        title = "小红书账号",
                        subtitle = "未登录，登录后可解析小红书视频",
                        trailing = {
                            Button(onClick = onXhsLoginClick) {
                                Text("登录")
                            }
                        },
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "YouTube cookie",
                    subtitle = if (youtubeImported) "已导入" else "未导入，导入后可解析需登录的视频",
                    onClick = {
                        youtubeCookieInput = ""
                        showYoutubeImport = true
                    },
                    trailing = { SettingsChevron() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "缓存清理",
                    subtitle = "音频 / 图片等临时文件，共 $cacheSizeText",
                    trailing = {
                        TextButton(onClick = { viewModel.clearAudioCache() }) {
                            Text("清理", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }

            // ---------- 外观与关于 ----------
            SettingsGroup(title = "外观与关于") {
                // 主题：四段较宽，分段控件紧接标题行之下（同一条目内，不套卡片）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = VideoNotesTokens.Space12),
                ) {
                    Text(
                        text = "主题",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "纸上课堂浅色 / 夜纸深色 / 纯黑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    val themeOptions = listOf(
                        ThemeMode.SYSTEM to "跟随系统",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.DARK to "深色",
                        ThemeMode.PURE_BLACK to "纯黑",
                    )
                    PaperSegmentedRow(
                        labels = themeOptions.map { it.second },
                        selectedIndex = themeOptions.indexOfFirst { it.first == themeMode }
                            .coerceAtLeast(0),
                        onSelect = { index -> viewModel.setThemeMode(themeOptions[index].first) },
                        spread = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SettingsDivider()
                SettingsRow(
                    title = "运维诊断",
                    subtitle = "LLM 诊断、日志与设备信息",
                    onClick = onNavigateToOps,
                    trailing = { SettingsChevron() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "开源许可",
                    subtitle = "第三方组件及其许可证列表",
                    onClick = onLicensesClick,
                    trailing = { SettingsChevron() },
                )
                SettingsDivider()
                SettingsRow(
                    title = "版本",
                    trailing = {
                        Text(
                            text = versionName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}

/** 纸面分组：小标题 + 直铺行（无卡片容器）。 */
@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = VideoNotesTokens.Space4),
    )
    content()
}

/** 纸面行：标题 + 副文案（左）与状态值 / 箭头 / 开关（右），无图标。 */
@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = VideoNotesTokens.Space12),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** 行间发丝线。 */
@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 可导航行的右侧箭头。 */
@Composable
private fun SettingsChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
