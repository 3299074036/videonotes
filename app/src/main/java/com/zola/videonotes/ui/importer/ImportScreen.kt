package com.zola.videonotes.ui.importer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.launch

/**
 * 视频导入页（纸上课堂）：一句说明 → 链接输入卡（粘贴 / 清空）→ 解析按钮 →
 * 页脚两行小字（平台支持 + 授权声明）→ 最近导入列表。
 * 解析成功跳元信息页（走 onParse）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onParse: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onBackClick: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val input = viewModel.input
    val recentImports by viewModel.recentImports.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入视频") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "设置",
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
                .padding(horizontal = VideoNotesTokens.Space20)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            Text(
                text = "粘贴视频链接，视记会自动获取字幕；没有字幕时用本机语音转写，" +
                    "再生成带时间索引的笔记。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            // 链接输入卡
            Surface(
                shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
                    ),
            ) {
                Column(modifier = Modifier.padding(VideoNotesTokens.Space12)) {
                    Text(
                        text = "视频链接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                    OutlinedTextField(
                        value = input,
                        onValueChange = viewModel::onInputChange,
                        placeholder = { Text("https://…") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Filled.Link, contentDescription = null)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    // 读取剪贴板文本并填入；读取失败则忽略，由用户手动粘贴
                                    runCatching {
                                        val entry = clipboard.getClipEntry()
                                        entry?.clipData?.getItemAt(0)?.text?.toString()
                                    }.getOrNull()
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let(viewModel::onPasted)
                                }
                            },
                        ) {
                            Text("从剪贴板粘贴")
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { viewModel.onInputChange("") },
                            enabled = input.isNotBlank(),
                        ) {
                            Text("清空")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))

            Button(
                onClick = { onParse(input.trim()) },
                enabled = input.isNotBlank(),
                shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text("解析视频")
            }

            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
            // 页脚：平台支持与授权声明收敛为两行小字
            //（原顶部横幅 / 平台 chips / 底部大标题区三处重复提示已合并至此）
            Text(
                text = "支持哔哩哔哩 / YouTube，抖音、小红书为实验性",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "仅处理您拥有权利或已获授权的视频内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // 最近导入
            if (recentImports.isNotEmpty()) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
                Text(
                    text = "最近导入",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                recentImports.forEach { item ->
                    RecentImportRow(
                        item = item,
                        onClick = { onParse(item.url) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
        }
    }
}

/** 最近导入行：小封面 + 标题 + 平台 · 状态（点击进元信息页）。 */
@Composable
private fun RecentImportRow(
    item: RecentImport,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = VideoNotesTokens.Space8),
    ) {
        Box(
            modifier = Modifier
                .size(width = 64.dp, height = 40.dp)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.coverUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(item.platform?.displayName, item.statusText)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
