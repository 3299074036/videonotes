package com.zola.videonotes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** 字阶：全部引用 VideoNotesTokens，页面禁止自定义字号。 */
val VideoNotesTypography = Typography(
    displaySmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeDisplaySmall,
        fontWeight = FontWeight.Bold,
        lineHeight = VideoNotesTokens.TextSizeDisplaySmall * 1.25,
    ),
    headlineSmall = TextStyle(
        fontSize = VideoNotesTokens.TextSizeHeadlineSmall,
        fontWeight = FontWeight.SemiBold,
        lineHeight = VideoNotesTokens.TextSizeHeadlineSmall * 1.3,
    ),
    titleLarge = TextStyle(
        fontSize = VideoNotesTokens.TextSizeTitle,
        fontWeight = FontWeight.SemiBold,
        lineHeight = VideoNotesTokens.LineHeightTitle,
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
