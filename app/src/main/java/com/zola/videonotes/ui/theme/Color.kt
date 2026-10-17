package com.zola.videonotes.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 色板：静态浅色 / 深色 / OLED 纯黑三套。
 * 动态取色（Material You）开启时优先使用系统动态色板，见 Theme.kt。
 */

// 品牌色：靛蓝
private val Primary = Color(0xFF2F6FED)
private val OnPrimary = Color(0xFFFFFFFF)
private val PrimaryContainer = Color(0xFFDCE6FF)
private val OnPrimaryContainer = Color(0xFF0B2A6B)
private val Secondary = Color(0xFF5A6B8C)
private val SecondaryContainer = Color(0xFFE3E9F5)
private val Tertiary = Color(0xFF7A4FD0)

private val PrimaryDark = Color(0xFF9DBDFF)
private val OnPrimaryDark = Color(0xFF0B2A6B)
private val PrimaryContainerDark = Color(0xFF1E3F8F)
private val OnPrimaryContainerDark = Color(0xFFDCE6FF)
private val SecondaryDark = Color(0xFFA9B8D4)
private val SecondaryContainerDark = Color(0xFF2A3648)

val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = Color(0xFF1B2436),
    tertiary = Tertiary,
)

val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = Color(0xFFE3E9F5),
    tertiary = Color(0xFFC4A8F5),
)

/** OLED 纯黑：在深色色板基础上把背景/表面压到纯黑。 */
val PureBlackColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    secondaryContainer = Color(0xFF1A1A1A),
    onSecondaryContainer = Color(0xFFE8E8E8),
    tertiary = Color(0xFFC4A8F5),
    background = Color.Black,
    onBackground = Color(0xFFE8E8E8),
    surface = Color.Black,
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF121212),
    onSurfaceVariant = Color(0xFFB0B0B0),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF242424),
)
