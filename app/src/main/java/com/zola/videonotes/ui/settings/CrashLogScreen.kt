package com.zola.videonotes.ui.settings

import android.content.Intent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 崩溃日志页（2026-10-24 合并包）：
 * worker.log 中 [CRASH] 条目倒序列表（时间 + 异常类 + message 首行）；
 * 点进详情看完整堆栈（长按选区复制）+ 单条分享；空态「暂无崩溃记录」。
 * 已知限制：worker.log 超 256KB 会被清空重写，旧崩溃可能已丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(
    onBackClick: () -> Unit,
    viewModel: CrashLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<CrashEntry?>(null) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selected == null) "崩溃日志" else "崩溃详情") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected != null) selected = null else onBackClick()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (selected != null) {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, selected!!.fullText)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享崩溃记录"))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享")
                        }
                    }
                },
            )
        },
    ) { padding ->
        val selectedEntry = selected
        if (selectedEntry != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${selectedEntry.time} · ${selectedEntry.exceptionClass}",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (selectedEntry.message.isNotBlank()) {
                    Text(
                        text = selectedEntry.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = selectedEntry.fullText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 最近一次原生面包屑：仅供对照，不一定属于本条崩溃
                breadcrumb?.let { crumb ->
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "最近一次原生面包屑（仅供对照，不一定属于本条崩溃）",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Text(
                            text = crumb,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else if (entries.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "暂无崩溃记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(entries) { entry ->
                    ListItem(
                        headlineContent = { Text("${entry.time} · ${entry.exceptionClass}") },
                        supportingContent = {
                            Text(entry.message.ifBlank { "（无 message）" })
                        },
                        trailingContent = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                            )
                        },
                        modifier = Modifier.clickable { selected = entry },
                    )
                    HorizontalDivider()
                }
                item {
                    Text(
                        text = "注：日志文件超 256KB 时会被清空重写，旧崩溃可能已丢失。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}
