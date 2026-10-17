package com.zola.videonotes.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.hilt.navigation.compose.hiltViewModel
import com.zola.videonotes.ui.components.ComplianceBanner
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.launch

/**
 * 视频导入页（M5：由原首页 HomeScreen 搬迁改名，内容不变）。
 * 链接输入 + 粘贴按钮 + 合规提示条，解析按钮跳转到元信息页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onParse: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val input = viewModel.input

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入视频") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = VideoNotesTokens.Space20),
        ) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            // 合规提示：导入页显著位置
            ComplianceBanner()
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            OutlinedTextField(
                value = input,
                onValueChange = viewModel::onInputChange,
                label = { Text("粘贴视频链接") },
                placeholder = { Text("https://…") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                // 读取剪贴板文本并填入；读取失败则忽略，由用户手动粘贴
                                runCatching {
                                    val entry = clipboard.getClipEntry()
                                    entry?.clipData?.getItemAt(0)?.text?.toString()
                                }.getOrNull()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(viewModel::onPasted)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = "从剪贴板粘贴",
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))

            Button(
                onClick = { onParse(input.trim()) },
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("解析视频")
            }

            // 空状态：尚未输入链接时的引导
            if (input.isBlank()) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
                EmptyView(
                    title = "把视频变成课堂笔记",
                    description = "粘贴 B站 / YouTube / 抖音 / 小红书的视频链接，" +
                        "一键生成带时间索引的结构化 Markdown 笔记",
                    icon = Icons.Filled.Movie,
                )
            } else {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
                Text(
                    text = "支持平台：YouTube、B站（P0）｜抖音、小红书（P1 实验性，可能随平台调整而失效）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
