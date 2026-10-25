package com.zola.videonotes.ui.ops

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 运维诊断页。
 * 三块：LLM 诊断（单 token / 最小化 native 测试 / 线程诊断）/ 日志管理 / 设备信息。
 * 诊断功能从模型管理页搬过来，统一收拢。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpsDiagnosticsScreen(
    onBackClick: () -> Unit,
    onNavigateToLogViewer: () -> Unit,
    onNavigateToCrashLog: () -> Unit = {},
    viewModel: OpsDiagnosticsViewModel = hiltViewModel(),
) {
    val probing by viewModel.probing.collectAsStateWithLifecycle()
    val probeResult by viewModel.probeResult.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val crashCount by viewModel.crashCount.collectAsStateWithLifecycle()
    // 诊断说明弹窗：点 ⓘ 弹出（2026-10-24 合并包，替代页面固定描述文字）
    var infoDialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("运维诊断") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // ---------- LLM 诊断 ----------
            SectionTitle("LLM 诊断")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // 单 token 诊断
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "单 token 诊断",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = {
                            infoDialog = "单 token 诊断：用已加载模型跑 1 个 token，验证引擎与模板是否通。\n\n" +
                                "生成进行中禁用：它与生成共用上下文，运行时会干扰在途生成。\n\n" +
                                "闲时失败不等于模型损坏：先以最小化 native 测试为准作干净结论。"
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "单 token 诊断说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::runSingleTokenProbe,
                            enabled = !probing && !isGenerating,
                        ) {
                            Text("运行")
                        }
                    }
                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "本地生成进行中，单 token 诊断已禁用（探针与生成共用上下文，会干扰在途生成）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 最小化 native 测试
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Science,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "最小化 native 测试",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = {
                            infoDialog = "最小化 native 测试：5 个最小用例，验证 native 层加载、模板与生成通路。\n\n" +
                                "它用独立上下文，不会破坏在途生成。"
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "最小化 native 测试说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::runMinimalProbe,
                            enabled = !probing,
                        ) {
                            Text("运行")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    // 线程诊断（方案 T）：独立上下文，不破坏在途生成，但会争 CPU/内存
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "线程诊断",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(onClick = {
                            infoDialog = "线程诊断：固定 prompt，4 / 6 / 8 线程 + 绑大核，512 token/批，全程约 3–5 分钟。\n\n" +
                                "它用独立上下文，不会破坏在途生成；生成进行中运行仅争 CPU/内存，会拖慢生成。"
                        }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "线程诊断说明",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = viewModel::runThreadSweepProbe,
                            enabled = !probing,
                        ) {
                            Text("运行")
                        }
                    }
                    // 运行中 / 结果
                    if (probing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "诊断中…（单项最长 90s，线程诊断全程约 3–5 分钟）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    probeResult?.let { result ->
                        Spacer(modifier = Modifier.height(12.dp))
                        // 结果按行拆开展示（最小化测试的 5 用例各占一行）
                        val lines = result.split("\n").filter { it.isNotBlank() }
                        if (lines.size == 1) {
                            // 单 token 诊断：单行直接显示
                            Text(
                                text = lines[0],
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            // 最小化测试：表格样式
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                                    lines.forEachIndexed { index, line ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // 格式："用例1 · 4线程/8tok · ✅ 1017ms"
                                            val parts = line.split("·").map { it.trim() }
                                            Text(
                                                text = parts.getOrNull(0) ?: line,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = parts.getOrNull(1) ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp),
                                            )
                                            Text(
                                                text = parts.getOrNull(2) ?: "",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        if (index < lines.size - 1) {
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 日志管理 ----------
            SectionTitle("日志管理")
            ListItem(
                headlineContent = { Text("详细日志") },
                supportingContent = { Text("LLM / 转写实时日志查看与导出") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onNavigateToLogViewer() },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("崩溃日志") },
                supportingContent = { Text("共 $crashCount 条 · 未捕获异常堆栈") },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onNavigateToCrashLog() },
            )
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // ---------- 设备信息 ----------
            SectionTitle("设备信息")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    viewModel.deviceInfo.forEachIndexed { index, (k, v) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = v,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (index < viewModel.deviceInfo.size - 1) {
                            HorizontalDivider()
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // 诊断说明弹窗（点 ⓘ 弹出）
    infoDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { infoDialog = null },
            confirmButton = {
                TextButton(onClick = { infoDialog = null }) { Text("知道了") }
            },
            text = { Text(text) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}
