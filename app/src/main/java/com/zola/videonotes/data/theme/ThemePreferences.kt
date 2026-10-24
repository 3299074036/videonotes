package com.zola.videonotes.data.theme

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")

/** 主题模式四档：跟随系统 / 浅色（纸面）/ 深色（夜纸）/ 纯黑（OLED 暖黑）。 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK, PURE_BLACK
}

/**
 * 主题偏好（DataStore，模式与 AsrPreferences / LlmSettings 一致）。
 * MainActivity 读取后给 VideoNotesTheme 传 darkTheme / pureBlack；设置页四档分段控件写这里。
 */
@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    /** 当前主题模式，默认跟随系统。 */
    val themeMode: Flow<ThemeMode> = context.themeDataStore.data
        .map { prefs ->
            when (prefs[Keys.THEME_MODE]) {
                ThemeMode.LIGHT.name -> ThemeMode.LIGHT
                ThemeMode.DARK.name -> ThemeMode.DARK
                ThemeMode.PURE_BLACK.name -> ThemeMode.PURE_BLACK
                else -> ThemeMode.SYSTEM
            }
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }
}
