package com.zola.videonotes.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 设置页（统一模型管理 v2）。
 * 模型相关全部收进「模型管理」单页（大语言模型/语音识别两个 Tab），
 * 本页只留一个入口 + 账号与存储分组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToModels: () -> Unit,
    onLicensesClick: () -> Unit,
    onBiliLoginClick: () -> Unit,
    onNavigateToOps: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val cacheSizeText by viewModel.cacheSizeText.collectAsStateWithLifecycle()
    val modelStorageText by viewModel.modelStorageText.collectAsStateWithLifecycle()
    val biliLoggedIn by viewModel.biliLoggedIn.collectAsStateWithLifecycle()
    val biliUid by viewModel.biliUid.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showLogoutConfirm by remember { mutableStateOf(false) }

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

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---------- 模型管理（统一入口，大语言/语音识别都在里面） ----------
            SectionTitle("模型")
            ListItem(
                headlineContent = { Text("模型管理") },
                supportingContent = { Text("已下载 $modelStorageText · 大语言 / 语音识别") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onNavigateToModels() },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            // ---------- 账号与存储 ----------
            SectionTitle("账号与存储")
            if (biliLoggedIn) {
                ListItem(
                    headlineContent = { Text("B站账号") },
                    supportingContent = {
                        Text(
                            if (biliUid != null) "已登录（UID $biliUid）"
                            else "已登录",
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showLogoutConfirm = true }) {
                            Text("退出登录")
                        }
                    },
                )
            } else {
                ListItem(
                    headlineContent = { Text("B站账号") },
                    supportingContent = { Text("未登录，登录后可获取需登录的字幕") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = null,
                        )
                    },
                    trailingContent = {
                        Button(onClick = onBiliLoginClick) {
                            Text("登录")
                        }
                    },
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("缓存清理") },
                supportingContent = { Text("音频 / 图片等临时文件，共 $cacheSizeText") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.CleaningServices,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Button(onClick = { viewModel.clearAudioCache() }) {
                        Text("清理")
                    }
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("运维诊断") },
                supportingContent = { Text("LLM 诊断、日志与设备信息") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Build,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onNavigateToOps() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("开源许可") },
                supportingContent = { Text("第三方组件及其许可证列表") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onLicensesClick() },
            )
            HorizontalDivider()

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            horizontal = VideoNotesTokens.Space16,
            vertical = VideoNotesTokens.Space8,
        ),
    )
}

/** 云端 API 配置区（自 LlmSettingsScreen 迁移）。 */
