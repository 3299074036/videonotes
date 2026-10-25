package com.zola.videonotes.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.components.PlatformIcon
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 知识库主页（纸上课堂）：衬线大标题 + 计数、纸面搜索框、筛选 chips、
 * 文档行条目（真实封面 + 状态行）；FAB 跳导入页（唯一导入入口）；长按重命名/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    onEntryClick: (KnowledgeEntry) -> Unit,
    onAddVideoClick: () -> Unit,
    viewModel: KnowledgeBaseViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var menuEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var renameEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // 长按菜单（§20：重命名在左、删除在右；正文为条目副信息，不再是空洞的「选择操作」）
    menuEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Text(
                    buildString {
                        entry.platform?.let { append(it.displayName) }
                        if (entry.durationSec > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(formatDuration(entry.durationSec))
                        }
                        val status = when {
                            entry.noteId != null -> "已有笔记"
                            entry.isGenerating -> "生成中"
                            entry.isTranscribing -> "转写中"
                            entry.hasTranscript -> "已有字幕"
                            else -> "尚未生成笔记"
                        }
                        if (isNotEmpty()) append(" · ")
                        append(status)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry = entry
                        menuEntry = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        renameText = entry.noteTitle
                        renameEntry = entry
                        menuEntry = null
                    },
                ) { Text("重命名") }
            },
        )
    }

    // 重命名对话框
    renameEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { renameEntry = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            viewModel.renameVideo(entry.storageKey, renameText.ifBlank { entry.title })
                        }
                        renameEntry = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { renameEntry = null }) { Text("取消") }
            },
        )
    }

    // 删除二次确认
    deleteEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("删除条目") },
            text = { Text("将删除「${entry.title}」及其关联笔记与字幕缓存，确定吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { viewModel.deleteVideo(entry.storageKey) }
                        deleteEntry = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteEntry = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddVideoClick,
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "新增视频")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 页头：衬线大标题 + 笔记计数
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16)
                    .padding(top = VideoNotesTokens.Space16),
            ) {
                Text(
                    text = "知识库",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "共 $totalCount 条记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            // 搜索框：纸面填充
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "搜索")
                },
                placeholder = { Text("搜索标题、作者") },
                singleLine = true,
                shape = RoundedCornerShape(VideoNotesTokens.CornerFull),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            // 筛选 chips：平台分组（设计稿：全部 + 6 个平台小图标 chip，横向滚动）
            Row(
                horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = VideoNotesTokens.Space16),
            ) {
                KnowledgeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.onFilterChange(f) },
                        label = {
                            Text(
                                when (f) {
                                    KnowledgeFilter.ALL -> "全部"
                                    KnowledgeFilter.BILIBILI -> "哔哩哔哩"
                                    else -> f.platform?.displayName.orEmpty()
                                },
                            )
                        },
                        leadingIcon = f.platform?.let { platform ->
                            { PlatformIcon(platform = platform, size = 17.dp) }
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))

            if (entries.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space48))
                    EmptyView(
                        title = "知识库是空的",
                        description = "点右下角 ＋ 粘贴视频链接开始",
                        icon = Icons.Filled.MenuBook,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = VideoNotesTokens.Space16),
                ) {
                    items(entries, key = { it.storageKey }) { entry ->
                        KnowledgeEntryRow(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { menuEntry = entry },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    item { Spacer(modifier = Modifier.height(VideoNotesTokens.Space48)) }
                }
            }
        }
    }
}

/** 知识库条目（文档行）：真实封面 / 标题 / 平台·作者·时长 / 状态行。 */
@Composable
private fun KnowledgeEntryRow(
    entry: KnowledgeEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = VideoNotesTokens.Space12),
        verticalAlignment = Alignment.Top,
    ) {
        // 真实封面（Coil）；缺失 / 加载失败时回退标题占位块
        Box(
            modifier = Modifier
                .size(
                    width = VideoNotesTokens.CoverThumbWidth,
                    height = VideoNotesTokens.CoverThumbHeight,
                )
                .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.title.take(4),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = VideoNotesTokens.Space4),
            )
            if (entry.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(entry.coverUrl)
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
                text = entry.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                entry.platform?.let {
                    PlatformIcon(platform = it, size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                val meta = buildString {
                    if (entry.author.isNotBlank()) append(entry.author)
                    if (entry.durationSec > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(formatDuration(entry.durationSec))
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            EntryStatusLine(entry = entry)
        }
    }
}

/**
 * 状态行（纸上课堂）：完成态绿字 ✓、进行中蓝字 + 细进度条、待处理灰字。
 * 绿色按当前表面明度取深绿 / 浅绿，保证浅色纸面与夜纸下都可读。
 */
@Composable
private fun EntryStatusLine(entry: KnowledgeEntry) {
    val doneGreen = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF3E7C4F)
    } else {
        Color(0xFF8FCB9B)
    }
    val activeColor = MaterialTheme.colorScheme.primary
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall

    when {
        entry.isGenerating -> {
            val pct = entry.ringProgress
            Text(
                text = "生成中 · " + entry.generateStage.ifBlank { "笔记生成中" } +
                    (pct?.let { " ${(it * 100).toInt()}%" } ?: ""),
                style = small,
                color = activeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EntryProgressBar(progress = pct)
        }
        entry.isTranscribing -> {
            val pct = entry.ringProgress
            Text(
                text = "转写中 · " + entry.transcribeStage.ifBlank { "语音转写" } +
                    (pct?.let { " ${(it * 100).toInt()}%" } ?: ""),
                style = small,
                color = activeColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            EntryProgressBar(progress = pct)
        }
        else -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.hasTranscript) {
                    Text(text = "✓ 字幕", style = small, color = doneGreen)
                }
                if (entry.noteId != null) {
                    if (entry.hasTranscript) {
                        Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
                    }
                    Text(text = "✓ 笔记", style = small, color = doneGreen)
                }
                if (entry.hasTranscript || entry.noteId != null) {
                    Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
                    Text(
                        text = formatRelativeTime(entry.createdAt),
                        style = small,
                        color = idleColor,
                    )
                } else {
                    Text(text = "尚未生成笔记", style = small, color = idleColor)
                }
            }
        }
    }
}

/** 状态行下方的细进度条（进度未知时为不确定型）。 */
@Composable
private fun EntryProgressBar(progress: Float?) {
    Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .width(140.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerFull)),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .width(140.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(VideoNotesTokens.CornerFull)),
        )
    }
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}

/** 相对时间：今天 / 昨天 / N 天前 / 上周 / N 周前 / 日期。 */
private fun formatRelativeTime(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - createdAt) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days < 7 -> "$days 天前"
        days < 14 -> "上周"
        days < 30 -> "${days / 7} 周前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(createdAt))
    }
}
