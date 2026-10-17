package com.zola.videonotes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 视记主题：Material 3 Expressive + Material You 动态取色。
 *
 * @param darkTheme 深色模式（默认跟随系统）
 * @param dynamicColor 动态取色开关（默认开启，仅 Android 12+ 生效）
 * @param pureBlack OLED 纯黑开关（M6 设置页接入，默认关闭）
 */
@Composable
fun VideoNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
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
