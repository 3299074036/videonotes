package com.zola.videonotes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * 字阶：全部引用 VideoNotesTokens，页面禁止自定义字号。
 * 纸上课堂：标题类用系统衬线（FontFamily.Serif，中文回退 Noto Serif CJK，不引字体文件），
 * 正文维持无衬线。
 */
val VideoNotesTypography = Typography(
    displaySmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeDisplaySmall,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Serif,
        lineHeight = VideoNotesTokens.TextSizeDisplaySmall * 1.25,
    ),
    headlineSmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeHeadlineSmall,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Serif,
        lineHeight = VideoNotesTokens.TextSizeHeadlineSmall * 1.3,
    ),
    titleLarge = TextStyle(
        fontSize = VideoNotesTokens.TextSizeTitle,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Serif,
        lineHeight = VideoNotesTokens.LineHeightTitle,
    ),
    titleMedium = TextStyle(
        fontSize = VideoNotesTokens.TextSizeBody,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Serif,
        lineHeight = VideoNotesTokens.TextSizeBody * 1.4,
    ),
    titleSmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeTitleSmall,
        fontWeight = FontWeight.Medium,
        lineHeight = VideoNotesTokens.TextSizeTitleSmall * 1.4,
    ),
    bodyLarge = TextStyle(
        fontSize = VideoNotesTokens.TextSizeBody,
        fontWeight = FontWeight.Normal,
        lineHeight = VideoNotesTokens.LineHeightBody,
    ),
    bodySmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeBodySmall,
        fontWeight = FontWeight.Normal,
        lineHeight = VideoNotesTokens.TextSizeBodySmall * 1.5,
    ),
    labelSmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeCaption,
        fontWeight = FontWeight.Medium,
        lineHeight = VideoNotesTokens.TextSizeCaption * 1.4,
    ),
)
