package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 字幕卡片：展示字幕获取的四种状态 + 语音转写区（M3）。
 * 未获取 / 加载中（含进度文案） / 已获取（段数 · 来源标签 · 语言 · 前 3 段预览） / 失败（中文原因 + 重试）。
 * 无官方字幕时提供"语音转写"入口：转写中显示阶段进度（不白屏干等），失败给中文原因 + 重试。
 */
@Composable
fun SubtitleCard(
    state: SubtitleUiState,
    transcribeState: TranscribeUiState,
    onFetchClick: () -> Unit,
    onRetryClick: () -> Unit,
    onRefreshClick: () -> Unit,
    onTranscribeClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(VideoNotesTokens.Space16)) {
            when (state) {
                is SubtitleUiState.NotFetched -> NotFetchedContent(
                    hint = state.hint,
                    onFetchClick = onFetchClick,
                    transcribeState = transcribeState,
                    onTranscribeClick = onTranscribeClick,
                    onTranscribeRetry = onTranscribeRetry,
                    onTranscribeCancel = onTranscribeCancel,
                )
                is SubtitleUiState.Loading -> LoadingContent(message = state.message)
                is SubtitleUiState.Fetched -> FetchedContent(
                    result = state.result,
                    onRefreshClick = onRefreshClick,
                    transcribeState = transcribeState,
                    onTranscribeClick = onTranscribeClick,
                    onTranscribeRetry = onTranscribeRetry,
                    onTranscribeCancel = onTranscribeCancel,
                )
                is SubtitleUiState.Failed -> FailedContent(
                    message = state.message,
                    onRetryClick = onRetryClick,
                    transcribeState = transcribeState,
                    onTranscribeClick = onTranscribeClick,
                    onTranscribeRetry = onTranscribeRetry,
                    onTranscribeCancel = onTranscribeCancel,
                )
            }
        }
    }
}

@Composable
private fun CardTitleRow(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.ClosedCaption,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(VideoNotesTokens.Space20),
        )
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun NotFetchedContent(
    hint: String?,
    onFetchClick: () -> Unit,
    transcribeState: TranscribeUiState,
    onTranscribeClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
) {
    CardTitleRow(title = "字幕")
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
    Text(
        text = hint ?: "获取官方字幕，用于生成带时间索引的课堂笔记",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
        Button(onClick = onFetchClick) {
            Text(text = "获取字幕")
        }
        if (transcribeState is TranscribeUiState.Idle) {
            OutlinedButton(onClick = onTranscribeClick) {
                Text(text = "语音转写")
            }
        }
    }
    TranscribeStatus(
        transcribeState = transcribeState,
        onTranscribeRetry = onTranscribeRetry,
        onTranscribeCancel = onTranscribeCancel,
    )
}

/** 转写中 / 转写失败的状态区（引导弹层与二次确认由上层对话框处理）。 */
@Composable
private fun TranscribeStatus(
    transcribeState: TranscribeUiState,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
) {
    when (transcribeState) {
        is TranscribeUiState.Working -> {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            Text(
                text = transcribeState.stageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            if (transcribeState.progress >= 0) {
                LinearProgressIndicator(
                    progress = { transcribeState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            TextButton(onClick = onTranscribeCancel) {
                Text(text = "取消")
            }
        }
        is TranscribeUiState.Failed -> {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            Text(
                text = "转写失败：${transcribeState.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            TextButton(onClick = onTranscribeRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(VideoNotesTokens.Space16),
                )
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space4))
                Text(text = "重试转写")
            }
        }
        else -> {
            // Idle / Confirm：不在卡片内展示
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(VideoNotesTokens.Space20),
            strokeWidth = VideoNotesTokens.Space2,
        )
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FetchedContent(
    result: SubtitleFetchResult,
    onRefreshClick: () -> Unit,
    transcribeState: TranscribeUiState,
    onTranscribeClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardTitleRow(title = "字幕 · ${result.segments.size} 段")
        Spacer(modifier = Modifier.weight(1f))
        // 用户诉求：重进页面时可手动重新拉取，而不是只看缓存
        IconButton(onClick = onRefreshClick) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "重新获取字幕",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
        SourceChip(text = result.source.displayName)
        if (result.languageLabel.isNotBlank()) {
            SourceChip(text = result.languageLabel)
        }
    }
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    result.segments.take(3).forEach { segment ->
        Row(modifier = Modifier.padding(vertical = VideoNotesTokens.Space4)) {
            Text(
                text = formatTimestamp(segment.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
            Text(
                text = segment.text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    // 已有字幕时也常驻语音转写入口（用户诉求）：可强制重跑转写做对比
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
    if (transcribeState is TranscribeUiState.Idle) {
        OutlinedButton(onClick = onTranscribeClick) {
            Text(text = "语音转写")
        }
    }
    TranscribeStatus(
        transcribeState = transcribeState,
        onTranscribeRetry = onTranscribeRetry,
        onTranscribeCancel = onTranscribeCancel,
    )
}

/** 来源 / 语言的小徽标。 */
@Composable
private fun SourceChip(text: String) {
    Surface(
        shape = RoundedCornerShape(VideoNotesTokens.CornerFull),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(
                horizontal = VideoNotesTokens.Space8,
                vertical = VideoNotesTokens.Space4,
            ),
        )
    }
}

@Composable
private fun FailedContent(
    message: String,
    onRetryClick: () -> Unit,
    transcribeState: TranscribeUiState,
    onTranscribeClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(VideoNotesTokens.Space20),
        )
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
        Text(
            text = "字幕获取失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
    Row(horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8)) {
        TextButton(onClick = onRetryClick) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(VideoNotesTokens.Space16),
            )
            Spacer(modifier = Modifier.width(VideoNotesTokens.Space4))
            Text(text = "重试")
        }
        if (transcribeState is TranscribeUiState.Idle) {
            OutlinedButton(onClick = onTranscribeClick) {
                Text(text = "改用语音转写")
            }
        }
    }
    TranscribeStatus(
        transcribeState = transcribeState,
        onTranscribeRetry = onTranscribeRetry,
        onTranscribeCancel = onTranscribeCancel,
    )
}
