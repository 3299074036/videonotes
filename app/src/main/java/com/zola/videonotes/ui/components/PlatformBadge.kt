package com.zola.videonotes.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 平台徽标：胶囊样式，展示视频来源平台。
 */
@Composable
fun PlatformBadge(
    platform: Platform,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(VideoNotesTokens.CornerFull),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = platform.displayName,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(
                horizontal = VideoNotesTokens.Space12,
                vertical = VideoNotesTokens.Space4,
            ),
        )
    }
}
