package com.zola.videonotes.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全 App 唯一的设计 Token：圆角 / 间距 / 字阶集中定义于此。
 * 约定：各页面禁止自定义圆角、间距、字阶，必须引用本文件的 Token。
 */
object VideoNotesTokens {

    // 圆角
    val CornerSmall = 8.dp
    val CornerMedium = 12.dp
    val CornerLarge = 16.dp
    val CornerExtraLarge = 24.dp
    /** 胶囊形（时间索引 pill、徽标等） */
    val CornerFull = 999.dp

    // 间距
    val Space2 = 2.dp
    val Space4 = 4.dp
    val Space8 = 8.dp
    val Space12 = 12.dp
    val Space16 = 16.dp
    val Space20 = 20.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space48 = 48.dp

    // 字阶（Type.kt 中的 Typography 引用这些字号）
    val TextSizeCaption = 12.sp
    val TextSizeBodySmall = 14.sp
    val TextSizeBody = 16.sp
    val TextSizeTitleSmall = 16.sp
    val TextSizeTitle = 20.sp
    val TextSizeHeadlineSmall = 24.sp
    val TextSizeDisplaySmall = 32.sp

    // 行高
    val LineHeightBody = 24.sp
    val LineHeightTitle = 28.sp

    // 封面图比例
    const val CoverAspectRatio = 16f / 9f

    // 骨架屏 / 空状态插画尺寸
    val IllustrationSize = 96.dp
}
