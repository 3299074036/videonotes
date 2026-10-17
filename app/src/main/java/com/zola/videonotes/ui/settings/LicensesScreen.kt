package com.zola.videonotes.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zola.videonotes.ui.theme.VideoNotesTokens

/** 开源许可条目：组件名 + 许可证 + 说明。 */
private data class LicenseEntry(
    val name: String,
    val license: String,
    val note: String,
)

/** M6：关于页展示的第三方组件许可列表（与发布检查清单保持一致）。 */
private val LICENSES = listOf(
    LicenseEntry(
        name = "sherpa-onnx",
        license = "Apache-2.0",
        note = "端侧语音识别引擎（AAR 预编译包）",
    ),
    LicenseEntry(
        name = "whisper / SenseVoice 模型权重",
        license = "MIT",
        note = "HuggingFace 下载的 ASR 模型文件",
    ),
    LicenseEntry(
        name = "mermaid.js",
        license = "MIT",
        note = "笔记知识地图渲染（本地 assets，无网络请求）",
    ),
    LicenseEntry(
        name = "NewPipeExtractor",
        license = "GPL-3.0",
        note = "YouTube 视频解析。注意：闭源发布需替换实现或取得例外，详见发布检查清单",
    ),
    LicenseEntry(
        name = "OkHttp / Retrofit / Coil",
        license = "Apache-2.0",
        note = "网络与图片加载",
    ),
    LicenseEntry(
        name = "Media3 (ExoPlayer)",
        license = "Apache-2.0",
        note = "站内视频播放器",
    ),
    LicenseEntry(
        name = "WorkManager",
        license = "Apache-2.0",
        note = "长任务后台调度",
    ),
    LicenseEntry(
        name = "Jetpack（Compose / Room / DataStore / Hilt / Navigation…）",
        license = "Apache-2.0",
        note = "UI / 存储 / 依赖注入 / 导航",
    ),
)

/**
 * 开源许可页（M6）：列出第三方组件及其许可证。
 * 完整许可文本随各组件分发（如 AAR / 模型仓库），本页仅做归属声明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源许可") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "视记使用了以下开源组件，感谢每一位贡献者。完整许可文本请见各组件官方仓库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = VideoNotesTokens.Space16,
                    vertical = VideoNotesTokens.Space8,
                ),
            )
            LICENSES.forEach { entry ->
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text(entry.note) },
                    trailingContent = {
                        Text(
                            text = entry.license,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                )
            }
            HorizontalDivider()
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        }
    }
}
