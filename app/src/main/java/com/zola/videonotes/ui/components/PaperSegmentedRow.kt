package com.zola.videonotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 纸面文字分段控件（方向一）：无边框、无胶囊底，一行纯文字选项。
 *
 * - 选中项：品牌蓝（取主题 `primary`——浅色 #2F6FED / 深色 #9DBDFF）加粗，
 *   文字正下方一个小蓝点；未选中：次级灰文字，下方蓝点位置留空保持基线齐平。
 * - 每项触摸区高 44dp（spread 时整行等分列宽），视觉文字不随之放大。
 * - [spread] = true 时各选项等分整行宽度（主题四档 / 页内 Tab 用）；
 *   false 时按内容紧凑排布（行右侧二选一用）。
 */
@Composable
fun PaperSegmentedRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    spread: Boolean = false,
) {
    val accent = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .then(if (spread) Modifier.weight(1f) else Modifier)
                    .height(44.dp)
                    .clip(RoundedCornerShape(VideoNotesTokens.CornerFull))
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = if (spread) VideoNotesTokens.Space4 else VideoNotesTokens.Space12),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) accent else idleColor,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(3.dp))
                // 蓝点槽位恒在（未选中透明），保证各选项文字基线齐平
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (selected) accent else Color.Transparent),
                )
            }
        }
    }
}
