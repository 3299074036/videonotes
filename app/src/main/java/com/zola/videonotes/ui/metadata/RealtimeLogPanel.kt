package com.zola.videonotes.ui.metadata

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 详情页实时日志面板（深色终端风）。
 *
 * 放在"视频信息"Tab 的生成卡片下方，只在生成中显示。
 * 订阅 [com.zola.videonotes.data.llm.local.LlmDiagnosticLog.logFlow]，
 * 显示最近 50 行，自动滚到底，标题栏可折叠。
 */
@Composable
fun RealtimeLogPanel(
    lines: List<String>,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    val displayLines = remember(lines) { lines.takeLast(50) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    // 新行到来时自动滚到底
    LaunchedEffect(displayLines.size) {
        if (displayLines.isNotEmpty()) {
            listState.scrollToItem(displayLines.size - 1)
        }
    }

    // 绿点呼吸灯（进行中）
    val infiniteTransition = rememberInfiniteTransition(label = "logPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E22),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(
                    horizontal = VideoNotesTokens.Space16,
                    vertical = VideoNotesTokens.Space12,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (isRunning) dotAlpha else 0.35f)
                    .background(Color(0xFF4ADE80), CircleShape),
            )
            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
            Text(
                text = "实时日志",
                color = Color(0xFFE8E8EC),
                fontSize = 14.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            // 导出日志：分享 llm.log 全文（设置页的导出入口已移除，统一收在这里）
            IconButton(
                onClick = {
                    val file = LlmDiagnosticLog.logFile(context)
                    val logText = if (file.exists() && file.length() > 0) {
                        runCatching { file.readText() }.getOrDefault("LLM 日志读取失败")
                    } else {
                        "暂无 LLM 日志。请先生成一次笔记，再导出。"
                    }
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, logText)
                        putExtra(Intent.EXTRA_SUBJECT, "视记 LLM 日志")
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "导出 LLM 日志"))
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "导出日志",
                    tint = Color(0xFF9A9AA2),
                )
            }
            Icon(
                imageVector = if (expanded) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = if (expanded) "折叠" else "展开",
                tint = Color(0xFF9A9AA2),
            )
        }

        if (expanded) {
            if (displayLines.isEmpty()) {
                Text(
                    text = "等待日志…",
                    color = Color(0xFF6A6A74),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(
                        horizontal = VideoNotesTokens.Space16,
                        vertical = VideoNotesTokens.Space8,
                    ),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .padding(
                            horizontal = VideoNotesTokens.Space16,
                            vertical = VideoNotesTokens.Space4,
                        ),
                ) {
                    items(
                        items = displayLines,
                        key = { it.hashCode().toString() + it.length },
                    ) { line ->
                        LogLine(line = line)
                    }
                }
            }
            Spacer(modifier = Modifier.size(VideoNotesTokens.Space8))
        }
    }
}

/** 单行日志：时间戳灰色，正文按内容着色。 */
@Composable
private fun LogLine(line: String) {
    val bodyColor = when {
        line.contains("失败") || line.contains("错误") ||
            (line.contains("rc=") && !line.contains("rc=0")) -> Color(0xFFF87171)
        line.contains("完成") || line.contains("rc=0") || line.contains("✓") -> Color(0xFF4ADE80)
        line.contains("TTFT") || line.contains("开始生成") -> Color(0xFFB8C4E8)
        line.contains("…") -> Color(0xFFFBBF24)
        else -> Color(0xFF9A9AA2)
    }
    val annotated = remember(line) {
        buildAnnotatedString {
            // 格式：[MM-dd HH:mm:ss.SSS][tag] message —— 时间戳部分灰色
            val firstClose = line.indexOf(']')
            val secondClose = if (firstClose >= 0) line.indexOf(']', firstClose + 1) else -1
            if (firstClose > 0 && secondClose > firstClose) {
                withStyle(SpanStyle(color = Color(0xFF7A7A84))) {
                    append(line.substring(0, secondClose + 1))
                }
                withStyle(SpanStyle(color = bodyColor)) {
                    append(line.substring(secondClose + 1))
                }
            } else {
                withStyle(SpanStyle(color = bodyColor)) {
                    append(line)
                }
            }
        }
    }
    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
