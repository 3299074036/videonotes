package com.zola.videonotes.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 色板（方向一 · 纸上课堂）：静态浅色「纸面」/ 深色「夜纸」/ OLED 纯黑（暖黑）三套。
 * 动态取色默认关闭（见 Theme.kt），否则系统动态色会盖掉纸面色板。
 * 蓝色 #2F6FED 只留给时间索引与主操作，其余界面保持纸面安静。
 */

// 品牌色：靛蓝（浅/深共用主色锚点）
private val Primary = Color(0xFF2F6FED)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFDCE6FF)
private val OnPrimaryContainer = Color(0xFF0B2A6B)

// 纸面（浅色）：暖纸底、墨字、暖灰次级
private val PaperBackground = Color(0xFFFAF8F4)
private val PaperInk = Color(0xFF1C1B1A)
private val PaperSecondary = Color(0xFF6E6759)
private val PaperSurfaceVariant = Color(0xFFF1EDE2)
private val PaperCard = Color(0xFFFFFFFF)
private val PaperContainer = Color(0xFFF7F3EA)
private val PaperContainerHigh = Color(0xFFF1EDE2)
private val PaperContainerHighest = Color(0xFFEDE8DB)
private val PaperOutline = Color(0xFFD8D2C2)
private val PaperOutlineVariant = Color(0xFFE7E2D6)
private val PaperError = Color(0xFFC8402A)

// 夜纸（深色）：深暖炭底、纸感表面
private val PrimaryDark = Color(0xFF9DBDFF)
private val OnPrimaryDark = Color(0xFF0B2A6B)
private val PrimaryContainerDark = Color(0xFF1E3F8F)
private val OnPrimaryContainerDark = Color(0xFFDCE6FF)
private val NightBackground = Color(0xFF171512)
private val NightInk = Color(0xFFEDE8DB)
private val NightSecondary = Color(0xFFA79E8C)
private val NightSurfaceVariant = Color(0xFF26231E)
private val NightContainerLowest = Color(0xFF100E0B)
private val NightContainerLow = Color(0xFF1E1B16)
private val NightContainer = Color(0xFF23201A)
private val NightContainerHigh = Color(0xFF2B2721)
private val NightContainerHighest = Color(0xFF332E26)
private val NightOutline = Color(0xFF4A4436)
private val NightOutlineVariant = Color(0xFF353126)
private val NightError = Color(0xFFE89A85)

val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = PaperSecondary,
    secondaryContainer = PaperSurfaceVariant,
    onSecondaryContainer = Color(0xFF3A382F),
    tertiary = Color(0xFF7A4FD0),
    error = PaperError,
    onError = OnPrimary,
    background = PaperBackground,
    onBackground = PaperInk,
    surface = PaperBackground,
    onSurface = PaperInk,
    surfaceVariant = PaperSurfaceVariant,
    onSurfaceVariant = PaperSecondary,
    surfaceContainerLowest = PaperCard,
    surfaceContainerLow = PaperCard,
    surfaceContainer = PaperContainer,
    surfaceContainerHigh = PaperContainerHigh,
    surfaceContainerHighest = PaperContainerHighest,
    outline = PaperOutline,
    outlineVariant = PaperOutlineVariant,
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = NightSecondary,
    secondaryContainer = NightContainerHigh,
    onSecondaryContainer = NightInk,
    tertiary = Color(0xFFC4A8F5),
    error = NightError,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightSecondary,
    surfaceContainerLowest = NightContainerLowest,
    surfaceContainerLow = NightContainerLow,
    surfaceContainer = NightContainer,
    surfaceContainerHigh = NightContainerHigh,
    surfaceContainerHighest = NightContainerHighest,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
)

/** OLED 纯黑：在夜纸色板基础上把背景/表面压到纯黑，容器取暖黑。 */
val PureBlackColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = NightSecondary,
    secondaryContainer = Color(0xFF1A1815),
    onSecondaryContainer = NightInk,
    tertiary = Color(0xFFC4A8F5),
    error = NightError,
    background = Color.Black,
    onBackground = NightInk,
    surface = Color.Black,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF14120E),
    onSurfaceVariant = NightSecondary,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0C0A),
    surfaceContainer = Color(0xFF121110),
    surfaceContainerHigh = Color(0xFF1A1815),
    surfaceContainerHighest = Color(0xFF242220),
    outline = Color(0xFF3A372F),
    outlineVariant = Color(0xFF26241F),
)
