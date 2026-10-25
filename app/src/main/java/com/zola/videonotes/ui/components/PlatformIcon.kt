package com.zola.videonotes.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zola.videonotes.R
import com.zola.videonotes.domain.model.Platform

/**
 * 平台图标：优先用 YunX 的平台图标集（`res/drawable/logo_*.png`，AGPL-3.0，
 * 原样画、不裁圆、不加底色，与 YunX 的渲染约定一致）。
 * YunX 图标集里没有 YouTube，YouTube 与未知平台沿用品牌色简标兜底。
 */
@Composable
fun PlatformIcon(
    platform: Platform,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val logoRes = when (platform) {
        Platform.BILIBILI -> R.drawable.logo_bilibili
        Platform.DOUYIN -> R.drawable.logo_douyin
        Platform.XIAOHONGSHU -> R.drawable.logo_xhs
        Platform.KUAISHOU -> R.drawable.logo_kuaishou
        Platform.WEIBO -> R.drawable.logo_weibo
        else -> null
    }
    if (logoRes != null) {
        Image(
            painter = painterResource(logoRes),
            contentDescription = platform.displayName,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size),
        )
        return
    }
    // 兜底简标：YouTube 红底 ▶、未知平台灰底问号
    val (background, glyph) = when (platform) {
        Platform.YOUTUBE -> Color(0xFFFF0000) to "▶"
        else -> Color(0xFF9E9E9E) to "?"
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
