package com.zola.videonotes.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    // 兜底简标：YouTube 红底 Canvas 播放三角、未知平台灰底问号。
    // YouTube 圆角按尺寸比例（约 22% 边长）：旧的文字 ▶ + 固定 6dp 圆角缩到 16–17dp
    // 时圆角占比过大、三角糊成红坨；未知平台的灰底问号保持原样不动。
    val background = when (platform) {
        Platform.YOUTUBE -> Color(0xFFFF0000)
        else -> Color(0xFF9E9E9E)
    }
    val corner = if (platform == Platform.YOUTUBE) {
        RoundedCornerShape(size * 0.22f)
    } else {
        RoundedCornerShape(6.dp)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(corner)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        if (platform == Platform.YOUTUBE) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // 注意：外层参数 size: Dp 会遮蔽 DrawScope.size，此处用 this.size 取绘制尺寸
                val w = this.size.width
                val h = this.size.height
                // 播放三角：高为图标高度的 52%，按比例缩放，任何尺寸下都不糊；
                // 包围盒中心略右于几何中心，抵消三角左侧底边的视觉重量。
                val triH = h * 0.52f
                val triW = triH * 0.85f
                val cx = w / 2
                val cy = h / 2
                val path = Path().apply {
                    moveTo(cx - triW * 0.4f, cy - triH / 2)
                    lineTo(cx - triW * 0.4f, cy + triH / 2)
                    lineTo(cx + triW * 0.6f, cy)
                    close()
                }
                drawPath(path, Color.White)
            }
        } else {
            Text(
                text = "?",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.5f).sp,
            )
        }
    }
}
