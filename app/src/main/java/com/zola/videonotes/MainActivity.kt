package com.zola.videonotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zola.videonotes.ui.navigation.Routes
import com.zola.videonotes.ui.navigation.VideoNotesNavGraph
import com.zola.videonotes.ui.theme.VideoNotesTheme
import dagger.hilt.android.AndroidEntryPoint

/** 主 Activity：承载全 App 的 Compose 导航。 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 冷启动时若携带系统分享的文本，直接进入元信息解析页
        val sharedText = intent.extractSharedText()
        setContent {
            VideoNotesTheme {
                VideoNotesNavGraph(
                    startDestination = sharedText?.let { Routes.metadata(it) } ?: Routes.HOME,
                )
            }
        }
    }

    /**
     * 从 ACTION_SEND 分享意图中提取文本。
     * 说明：launchMode 保持默认 standard，App 已在前台时分享会创建新实例走 onCreate，
     * 因此 onNewIntent 暂不处理。
     */
    private fun Intent.extractSharedText(): String? {
        if (action != Intent.ACTION_SEND) return null
        if (type != "text/plain") return null
        return getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }
}
