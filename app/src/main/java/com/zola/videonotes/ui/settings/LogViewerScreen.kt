package com.zola.videonotes.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * 统一日志查看页（深色终端风，单页混排）。
 *
 * llm.log（LLM 生成）+ worker.log（转写/Worker）按行内时间戳 `[MM-dd HH:mm:ss.SSS]`
 * 混排，实时更新（合并两个 logFlow），自动滚到底。
 * 标题栏：导出（按显示顺序合并文本分享）+ 清空（两个文件）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val llmFlowLines by LlmDiagnosticLog.logFlow.collectAsStateWithLifecycle()
    val workerFlowLines by WorkerDiagnosticLog.logFlow.collectAsStateWithLifecycle()

    // 文件全文（冷启动时读一次；实时增量走 flow）
    var llmFileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var workerFileLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        llmFileLines = readLogFile(LlmDiagnosticLog.logFile(context))
        workerFileLines = readLogFile(WorkerDiagnosticLog.logFile(context))
    }

    // 混排：文件 + flow 去重，按时间戳排序，取最近 800 行
    val displayLines = remember(llmFileLines, workerFileLines, llmFlowLines, workerFlowLines) {
        (llmFileLines + workerFileLines + llmFlowLines + workerFlowLines)
            .distinct()
            .sortedWith(compareBy { extractTimestampMs(it) })
            .takeLast(800)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(displayLines.size) {
        if (displayLines.isNotEmpty()) {
            listState.scrollToItem(displayLines.size - 1)
        }
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
                    IconButton(
                        onClick = {
                            val text = displayLines.joinToString("\n")
                                .ifBlank { "暂无日志。" }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                                putExtra(Intent.EXTRA_SUBJECT, "视记详细日志")
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "导出详细日志"))
                        },
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "导出日志")
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
            Card(
                modifier = Modifier
                    .fillMaxSize()
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
                            text = "暂无日志",
                            color = Color(0xFF9A9AA2),
                            fontSize = 14.sp,
                        )
                    }
                } else {
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

private fun readLogFile(file: File): List<String> {
    return try {
        if (file.exists() && file.length() > 0) {
            file.readLines().takeLast(800)
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
