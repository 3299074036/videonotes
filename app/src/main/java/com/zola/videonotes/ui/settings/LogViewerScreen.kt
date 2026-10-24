package com.zola.videonotes.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.work.WorkerDiagnosticLog
import com.zola.videonotes.ui.theme.VideoNotesTokens
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 统一日志查看页（深色终端风，单页混排；2026-10-24 §20 重设计）。
 *
 * llm.log（LLM 生成）+ worker.log（转写/Worker）按行内时间戳 `[MM-dd HH:mm:ss.SSS]`
 * 混排，实时更新（合并两个 logFlow），无搜索时自动滚到底。
 *
 * 筛选（2026-10-24 合并包：FilterList 图标 + 下拉菜单，菜单顺序 全部、关键、性能；
 * LogTier 枚举不动，默认档仍为关键）：
 * - 关键：阶段行 + 错误 + 汇总结论；
 * - 性能：prefill/decode 汇总与逐批耗时（含 TTFT）；
 * - 全部：含 llama-cpp 内部行与采样行。
 *
 * 长按选区复制：日志体包 SelectionContainer；选区激活期间暂停自动滚动
 * （仅当已在底部时才随新行滚到底）。
 *
 * grep：终端卡上方搜索框，忽略大小写 contains；检索覆盖全部历史（搜索激活时
 * 不限 800 行），显示匹配数、上/下一个跳转、命中行主色底高亮；导出默认导出
 * 当前过滤结果，另有「导出全部」选项。日志体保留黑底终端，不纸面化。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val llmFlowLines by LlmDiagnosticLog.logFlow.collectAsStateWithLifecycle()
    val workerFlowLines by WorkerDiagnosticLog.logFlow.collectAsStateWithLifecycle()

    var tier by remember { mutableStateOf(LogTier.KEY) }
    var query by remember { mutableStateOf("") }
    var showShareMenu by remember { mutableStateOf(false) }
    var showTierMenu by remember { mutableStateOf(false) }

    // 文件全文（冷启动时读一次；实时增量走 flow）
    var llmFileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var workerFileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        llmFileLines = readLogFile(LlmDiagnosticLog.logFile(context))
        workerFileLines = readLogFile(WorkerDiagnosticLog.logFile(context))
    }

    // 混排：文件 + flow 去重，按时间戳排序（全量，不设上限）
    val mergedLines = remember(llmFileLines, workerFileLines, llmFlowLines, workerFlowLines) {
        (llmFileLines + workerFileLines + llmFlowLines + workerFlowLines)
            .distinct()
            .sortedWith(compareBy { extractTimestampMs(it) })
    }
    // 先按档位过滤；无搜索时取最近 800 行，搜索时不过滤行数（全量 grep）
    val displayLines = remember(mergedLines, tier, query) {
        val tiered = when (tier) {
            LogTier.KEY -> mergedLines.filter(::isKeyLine)
            LogTier.PERF -> mergedLines.filter(::isPerfLine)
            LogTier.ALL -> mergedLines
        }
        if (query.isBlank()) {
            tiered.takeLast(800)
        } else {
            tiered.filter { it.contains(query, ignoreCase = true) }
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var currentMatch by remember { mutableIntStateOf(0) }

    // 无搜索时自动滚到底——仅当用户已在底部时（长按选区复制期间不抢滚）
    LaunchedEffect(displayLines.size) {
        if (query.isBlank() && displayLines.isNotEmpty()) {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: (displayLines.size - 1)
            if (lastVisible >= displayLines.size - 2) {
                listState.scrollToItem(displayLines.size - 1)
            }
        }
    }
    // 搜索词变化时回到首个匹配
    LaunchedEffect(query) {
        currentMatch = 0
        if (query.isNotBlank() && displayLines.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    fun jumpToMatch(index: Int) {
        if (displayLines.isEmpty()) return
        val target = ((index % displayLines.size) + displayLines.size) % displayLines.size
        currentMatch = target
        scope.launch { listState.scrollToItem(target) }
    }

    fun shareText(text: String, subject: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text.ifBlank { "暂无日志。" })
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        context.startActivity(Intent.createChooser(sendIntent, "导出详细日志"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("详细日志") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 筛选（第二轮修订：从搜索框上方移到标题栏右上角，与分享、删除并列；
                    // 菜单顺序 全部、关键、性能仅为展示顺序，LogTier 枚举不动；默认档仍为关键）
                    Box {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showTierMenu = true }) {
                                Icon(Icons.Filled.FilterList, contentDescription = "筛选")
                            }
                            Text(
                                text = when (tier) {
                                    LogTier.ALL -> "全部"
                                    LogTier.KEY -> "关键"
                                    LogTier.PERF -> "性能"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = showTierMenu,
                            onDismissRequest = { showTierMenu = false },
                        ) {
                            // 展示顺序：全部、关键、性能
                            listOf(LogTier.ALL, LogTier.KEY, LogTier.PERF).forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (item) {
                                                LogTier.ALL -> "全部"
                                                LogTier.KEY -> "关键"
                                                LogTier.PERF -> "性能"
                                            },
                                        )
                                    },
                                    trailingIcon = if (item == tier) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        tier = item
                                        showTierMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Filled.Share, contentDescription = "导出日志")
                        }
                        DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出过滤结果") },
                                onClick = {
                                    showShareMenu = false
                                    shareText(displayLines.joinToString("\n"), "视记详细日志（过滤结果）")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("导出全部") },
                                onClick = {
                                    showShareMenu = false
                                    shareText(mergedLines.joinToString("\n"), "视记详细日志")
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            LlmDiagnosticLog.clear(context)
                            WorkerDiagnosticLog.clear(context)
                            llmFileLines = emptyList()
                            workerFileLines = emptyList()
                        },
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空日志")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 筛选按钮已移到标题栏右上角（第二轮修订）；此处仅留顶部间距。
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            // grep 搜索框（胶囊形）
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索日志") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(VideoNotesTokens.CornerFull),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            )
            if (query.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VideoNotesTokens.Space16)
                        .padding(top = VideoNotesTokens.Space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (displayLines.isEmpty()) {
                            "0 条匹配"
                        } else {
                            "${displayLines.size} 条匹配 · 第 ${currentMatch + 1} 条"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { jumpToMatch(currentMatch - 1) },
                        enabled = displayLines.isNotEmpty(),
                    ) { Text("上一个") }
                    TextButton(
                        onClick = { jumpToMatch(currentMatch + 1) },
                        enabled = displayLines.isNotEmpty(),
                    ) { Text("下一个") }
                }
                Text(
                    text = "检索覆盖全部历史，不限当前 800 行",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16),
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16)
                    .padding(bottom = VideoNotesTokens.Space16),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
            ) {
                if (displayLines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (query.isBlank()) "暂无日志" else "没有匹配的日志",
                            color = Color(0xFF9A9AA2),
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    // 长按选区复制：整列包 SelectionContainer（命中高亮与选区颜色可能重叠，属已知外观细节）
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        ) {
                            items(
                                displayLines,
                                key = { it.hashCode().toString() + "_" + it.length },
                            ) { line ->
                                val highlighted = query.isNotBlank() &&
                                    line.contains(query, ignoreCase = true)
                                if (highlighted) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                            )
                                            .padding(horizontal = 4.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = line,
                                            color = logLineColor(line),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 15.sp,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = line,
                                        color = logLineColor(line),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 日志档位：关键（阶段 + 错误 + 汇总）/ 性能（prefill/decode/TTFT）/ 全部。 */
private enum class LogTier { KEY, PERF, ALL }

/** 是否为性能行：逐批耗时、独立性能汇总（含 prefill 与 decode 的 tok in 数据）、TTFT。 */
private fun isPerfLine(line: String): Boolean {
    if (line.contains("prefill batch") || line.contains("TTFT")) return true
    // generate 结束行虽含 prefill/decode 数据，但它是汇总结论，归关键档
    return line.contains("prefill") && line.contains("decode") && line.contains("tok in") &&
        !line.contains("结束") && !line.contains("完成")
}

/** 是否为关键行：阶段行 + 错误 + 汇总结论（性能行已先行分流，不重叠）。 */
private fun isKeyLine(line: String): Boolean {
    if (isPerfLine(line)) return false
    return KEY_MARKERS.any { line.contains(it, ignoreCase = true) }
}

private val KEY_MARKERS = listOf(
    "开始", "结束", "完成", "失败", "错误", "failed", "异常",
    "阶段=", "诊断结论", "全部通过", "检查",
)

private fun readLogFile(file: File): List<String> {
    return try {
        if (file.exists() && file.length() > 0) {
            file.readLines()
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private val tsFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

/** 从行首 `[MM-dd HH:mm:ss.SSS]` 解析时间戳毫秒；解析失败返回 Long.MAX_VALUE（排最后）。 */
private fun extractTimestampMs(line: String): Long {
    return try {
        if (line.length > 21 && line[0] == '[') {
            val ts = line.substring(1, 21)
            tsFormat.parse(ts)?.time ?: Long.MAX_VALUE
        } else {
            Long.MAX_VALUE
        }
    } catch (_: Exception) {
        Long.MAX_VALUE
    }
}

/** 日志行着色：与 RealtimeLogPanel 一致。 */
private fun logLineColor(line: String): Color {
    return when {
        line.contains("失败") || line.contains("错误") || line.contains("failed", ignoreCase = true) ->
            Color(0xFFF87171)
        line.contains("完成") || line.contains("✓") || line.contains("成功") ->
            Color(0xFF4ADE80)
        line.contains("开始") || line.contains("TTFT") ->
            Color(0xFF93C5FD)
        line.contains("进行中") || line.contains("...") ->
            Color(0xFFFBBF24)
        else -> Color(0xFFB8C4E8)
    }
}
