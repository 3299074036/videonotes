package com.zola.videonotes.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zola.videonotes.R
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 合规提示条：首页显著位置展示，提醒用户仅处理拥有权利或已获授权的内容。
 */
@Composable
fun ComplianceBanner(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = VideoNotesTokens.Space16,
                vertical = VideoNotesTokens.Space12,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(VideoNotesTokens.Space20),
            )
            Text(
                text = stringResource(R.string.compliance_banner),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = VideoNotesTokens.Space12),
            )
        }
    }
}
