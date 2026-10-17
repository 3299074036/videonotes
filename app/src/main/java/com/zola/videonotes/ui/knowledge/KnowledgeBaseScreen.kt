package com.zola.videonotes.ui.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.components.PlatformBadge
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.launch

/**
 * 知识库主页（UI 重设计 v2）：替代旧笔记库首页。
 * 搜索 + 筛选 chips + 条目卡片（含后台任务圆圈进度）；FAB 新增视频；长按重命名/删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeBaseScreen(
    onEntryClick: (KnowledgeEntry) -> Unit,
    onAddVideo: (String) -> Unit,
    viewModel: KnowledgeBaseViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf("") }
    var menuEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var renameEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<KnowledgeEntry?>(null) }
    var renameText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // 新增视频对话框
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增视频") },
            text = {
                OutlinedTextField(
                    value = addUrl,
                    onValueChange = { addUrl = it },
                    label = { Text("粘贴视频链接") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (addUrl.isNotBlank()) {
                            val url = addUrl.trim()
                            scope.launch {
                                // 先解析并落库（列表立刻出现），失败则提示原因
                                val result = viewModel.addVideo(url)
                                if (result.isSuccess) {
                                    addUrl = ""
                                    showAddDialog = false
                                    onAddVideo(url)
                                } else {
                                    snackbarHostState.showSnackbar(
                                        result.exceptionOrNull()?.message ?: "解析失败，请检查链接",
                                    )
                                }
                            }
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("取消") }
            },
        )
    }

    // 长按菜单
    menuEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { menuEntry = null },
            title = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = { Text("选择操作") },
            confirmButton = {
                TextButton(
                    onClick = {
                        renameText = entry.noteTitle
                        renameEntry = entry
                        menuEntry = null
                    },
                ) { Text("重命名") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        deleteEntry = entry
                        menuEntry = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
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
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "新增视频")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 搜索框
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = "搜索")
                },
                placeholder = { Text("搜索标题 / 作者") },
                singleLine = true,
                shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VideoNotesTokens.Space16)
                    .padding(top = VideoNotesTokens.Space8),
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            // 筛选 chips
            Row(
                horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = Modifier.padding(horizontal = VideoNotesTokens.Space16),
            ) {
                KnowledgeFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { viewModel.onFilterChange(f) },
                        label = {
                            Text(
                                when (f) {
                                    KnowledgeFilter.ALL -> "全部"
                                    KnowledgeFilter.HAS_NOTE -> "有笔记"
                                    KnowledgeFilter.GENERATING -> "生成中"
                                    KnowledgeFilter.TRANSCRIBING -> "转写中"
                                },
                            )
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))

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
                    verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = VideoNotesTokens.Space16),
                ) {
                    items(entries, key = { it.storageKey }) { entry ->
                        KnowledgeEntryCard(
                            entry = entry,
                            onClick = { onEntryClick(entry) },
                            onLongClick = { menuEntry = entry },
                        )
                    }
                    item { Spacer(modifier = Modifier.height(VideoNotesTokens.Space48)) }
                }
            }
        }
    }
}

/** 知识库条目卡片：封面 / 标题 / 平台徽标 / 时长 / 状态徽标 / 进度环。 */
@Composable
private fun KnowledgeEntryCard(
    entry: KnowledgeEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(VideoNotesTokens.CornerLarge),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(VideoNotesTokens.Space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面占位（渐变方块；有真实封面 URL 时可换 AsyncImage）
            Box(
                modifier = Modifier
                    .size(width = VideoNotesTokens.Space48, height = VideoNotesTokens.Space32)
                    .clip(RoundedCornerShape(VideoNotesTokens.CornerMedium))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.title.take(4),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                        PlatformBadge(platform = it)
                        Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
                    }
                    if (entry.durationSec > 0) {
                        Text(
                            text = formatDuration(entry.durationSec),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry.hasTranscript) {
                        StatusPill(text = "✓ 字幕", done = true)
                    }
                    when {
                        entry.noteId != null -> StatusPill(text = "✓ 笔记", done = true)
                        entry.isGenerating -> StatusPill(text = "⟳ 笔记生成中", done = false)
                        entry.isTranscribing -> StatusPill(text = "⟳ 语音转写中", done = false)
                        else -> StatusPill(text = "笔记未生成", done = false, idle = true)
                    }
                }
            }
            // 后台任务进度环
            entry.ringProgress?.let { p ->
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { p.coerceIn(0f, 1f) },
                        modifier = Modifier.size(VideoNotesTokens.Space48),
                        strokeWidth = VideoNotesTokens.Space4,
                    )
                    Text(
                        text = "${(p * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 状态徽标。 */
@Composable
private fun StatusPill(text: String, done: Boolean, idle: Boolean = false) {
    val container = when {
        done -> MaterialTheme.colorScheme.primaryContainer
        idle -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = when {
        done -> MaterialTheme.colorScheme.onPrimaryContainer
        idle -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
            .background(container),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(
                horizontal = VideoNotesTokens.Space8,
                vertical = VideoNotesTokens.Space4,
            ),
        )
    }
}

private fun formatDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
