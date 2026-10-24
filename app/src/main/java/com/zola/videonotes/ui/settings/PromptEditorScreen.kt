package com.zola.videonotes.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.ui.components.PaperSegmentedRow
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 笔记提示词编辑页（2026-10-24 合并包，一期只开放 system）：
 * 顶部分段切换「本地模型 / 云端 API」两档，各存各的；多行编辑器 + 字数 +
 * 当前生效（自定义 / 默认）与默认文本来源；保存校验（非空、≤20,000 字、
 * 不含 {xxx} 占位，拦截不写入；不含 [[mm:ss]] 只警告不拦截）；恢复默认一键清空。
 * user 模板与导入导出放二期。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditorScreen(
    onBackClick: () -> Unit,
    viewModel: PromptEditorViewModel = hiltViewModel(),
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val text by viewModel.text.collectAsStateWithLifecycle()
    val isCustom by viewModel.isCustom.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("笔记提示词") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = VideoNotesTokens.Space16),
        ) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            PaperSegmentedRow(
                labels = listOf("本地模型", "云端 API"),
                selectedIndex = tab,
                onSelect = { viewModel.selectTab(it) },
                spread = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            Text(
                text = if (isCustom) "当前生效：已自定义" else "当前生效：默认",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "默认文本来源：" + if (tab == 0) {
                    "system_note_local.txt（小模型自定义后统一用自定义，不再另分）"
                } else {
                    "system_note.txt"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "system 提示词不含变量；保存后下次生成立即生效，进行中的生成不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            OutlinedTextField(
                value = text,
                onValueChange = { viewModel.onTextChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(VideoNotesTokens.Space48 * 8),
                singleLine = false,
                minLines = 12,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${text.length} / 20,000 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.length > 20_000) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (warning != null) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                Text(
                    text = warning!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            OutlinedButton(
                onClick = { viewModel.resetToDefault() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("恢复默认")
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}
