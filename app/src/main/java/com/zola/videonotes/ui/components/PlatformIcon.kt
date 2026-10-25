package com.zola.videonotes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zola.videonotes.domain.model.Platform

/**
 * 平台图标：品牌色小方块 + 辨识字形（B站粉底"哔"、抖音黑底音符、
 * 小红书红底"红"、YouTube 红底 ▶），用在详情页标题左侧，对齐 YunX 解析页的平台图标样式。
 */
@Composable
fun PlatformIcon(
    platform: Platform,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val (background, glyph) = when (platform) {
        Platform.BILIBILI -> Color(0xFFFB7299) to "哔"
        Platform.DOUYIN -> Color(0xFF000000) to "♪"
        Platform.XIAOHONGSHU -> Color(0xFFFF2442) to "红"
        Platform.YOUTUBE -> Color(0xFFFF0000) to "▶"
        Platform.UNKNOWN -> Color(0xFF9E9E9E) to "?"
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.5f).sp,
        )
    }
}
