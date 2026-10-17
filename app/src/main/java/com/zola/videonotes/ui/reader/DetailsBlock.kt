package com.zola.videonotes.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zola.videonotes.data.reader.MdBlock
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * `<details>` 折叠块（M4 自测题用）：默认收起，summary 为标题。
 * 展开后用 MarkdownReader 递归渲染内部块。
 */
@Composable
fun DetailsBlock(
    block: MdBlock.Details,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(
                    horizontal = VideoNotesTokens.Space16,
                    vertical = VideoNotesTokens.Space12,
                ),
        ) {
            Text(
                text = block.summary,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = VideoNotesTokens.Space16,
                        end = VideoNotesTokens.Space16,
                        bottom = VideoNotesTokens.Space16,
                    ),
            ) {
                // details 内部不再嵌套 details（LLM 输出不会嵌套），直接渲染
                block.blocks.forEach { inner ->
                    MdBlockView(
                        block = inner,
                        onPillClick = onPillClick,
                        onLinkClick = onLinkClick,
                    )
                }
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            }
        }
    }
}
