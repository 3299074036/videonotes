package com.zola.videonotes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 视记主题：纸上课堂静态色板（浅色纸面 / 深色夜纸 / OLED 暖纯黑）。
 *
 * @param darkTheme 深色模式（由 MainActivity 按主题偏好四档计算后传入）
 * @param dynamicColor 动态取色开关（默认关闭：开启时 Android 12+ 的系统动态色会
 *   覆盖纸面色板；能力保留，仅作显式开启时使用）
 * @param pureBlack OLED 纯黑（主题偏好选「纯黑」时为 true）
 */
@Composable
fun VideoNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme && pureBlack -> PureBlackColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VideoNotesTypography,
        content = content,
    )
}
