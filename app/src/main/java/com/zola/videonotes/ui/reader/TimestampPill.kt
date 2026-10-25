package com.zola.videonotes.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zola.videonotes.ui.theme.VideoNotesTokens

/** 秒数 → pill 显示文本：mm:ss 或 h:mm:ss。 */
internal fun formatPillSeconds(seconds: Long): String {
    val total = seconds.coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/**
 * 时间戳 pill：可点击的小芯片（主色容器）。
 * 点击后按平台拼 deep link 跳转对应时间点（见 DeepLinkBuilder）。
 * M-05：估算时间戳显示“约 mm:ss”，提示用户跳转位置为近似值。
 */
@Composable
fun TimestampPill(
    seconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    estimated: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(VideoNotesTokens.CornerSmall),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = if (estimated) "约${formatPillSeconds(seconds)}" else formatPillSeconds(seconds),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 时间轨 pill（纸上课堂）：填充蓝、▶ 与时间上下两行，是每节的锚点。
 * 挂在笔记详情左侧时间轨的竖线上，点击行为与 [TimestampPill] 一致（跳回视频对应位置）。
 */
@Composable
fun RailTimestampPill(
    seconds: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "跳到 ${formatPillSeconds(seconds)}",
                modifier = Modifier.size(13.dp),
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = formatPillSeconds(seconds),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
