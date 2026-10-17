package com.zola.videonotes.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 元信息加载骨架屏：封面占位 + 两行文字占位，带呼吸动画。
 * 约定：任何耗时操作禁止白屏干等，必须有进度指示。
 */
@Composable
fun MetadataLoadingSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(VideoNotesTokens.CoverAspectRatio)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerLarge))
                .background(skeletonColor),
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(VideoNotesTokens.Space20)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
                .background(skeletonColor),
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(VideoNotesTokens.Space16)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
                .background(skeletonColor),
        )
    }
}
