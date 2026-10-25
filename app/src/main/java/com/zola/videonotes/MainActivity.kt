package com.zola.videonotes

import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.theme.ThemeMode
import com.zola.videonotes.data.theme.ThemePreferences
import com.zola.videonotes.ui.navigation.Routes
import com.zola.videonotes.ui.navigation.VideoNotesNavGraph
import com.zola.videonotes.ui.theme.VideoNotesTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 主 Activity：承载全 App 的 Compose 导航，并按主题偏好四档应用色板。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var themePreferences: ThemePreferences

    /**
     * L-22：前台分享到达时的待处理文本。singleTop 复用实例时分享走 onNewIntent
     * （此前注释称暂不处理，实则丢链接），由导航图消费后清零。
     */
    private val pendingSharedText = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.extractSharedText()?.let { pendingSharedText.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动时若携带系统分享的文本，直接进入元信息解析页
        val sharedText = intent.extractSharedText()
        setContent {
            val themeMode by themePreferences.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.PURE_BLACK -> true
            }
            VideoNotesTheme(
                darkTheme = darkTheme,
                pureBlack = themeMode == ThemeMode.PURE_BLACK,
            ) {
                VideoNotesNavGraph(
                    startDestination = sharedText?.let { Routes.metadata(it) } ?: Routes.HOME,
                    pendingSharedText = pendingSharedText,
                    onSharedTextConsumed = { pendingSharedText.value = null },
                )
            }
        }
    }

    /**
     * 从 ACTION_SEND 分享意图中提取文本。
     * L-22：launchMode 已改 singleTop（单 Activity 应用，MainActivity 恒为栈顶，
     * 前台分享必复用实例走 onNewIntent）；冷启动仍走 onCreate 的 startDestination。
     */
    private fun Intent.extractSharedText(): String? {
        if (action != Intent.ACTION_SEND) return null
        if (type != "text/plain") return null
        return getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }
}
