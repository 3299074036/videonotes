package com.zola.videonotes.ui.notes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zola.videonotes.data.reader.DeepLinkBuilder
import com.zola.videonotes.domain.model.Note
import com.zola.videonotes.ui.components.ErrorView
import com.zola.videonotes.ui.components.MetadataLoadingSkeleton
import com.zola.videonotes.ui.components.PlatformBadge
import com.zola.videonotes.ui.reader.MarkdownReader
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri

/**
 * 笔记详情页（M5）：自研 Markdown 富渲染 + 时间戳 pill 跳转。
 * 顶栏操作：分享 / 导出文件（SAF）/ 复制全文 / 更多（重命名、删除）。
 *
 * M6：pill 点击改为站内播放器跳转（[onPlayClick]），不再走外部浏览器 deep link。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    onBackClick: () -> Unit,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
    viewModel: NoteDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }

    var showMenu by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val note = (uiState as? NoteDetailUiState.Ready)?.note

    // SAF 导出：ACTION_CREATE_DOCUMENT，无需存储权限
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        if (uri == null || note == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(note.markdown.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("无法打开目标文件")
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "已导出到文件" else "导出失败，请重试")
        }
    }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(note?.title ?: "课堂笔记") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    if (note != null) {
                        IconButton(
                            onClick = { shareNote(context, note) },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Share,
                                contentDescription = "分享",
                            )
                        }
                        IconButton(
                            onClick = { exportLauncher.launch(exportFileName(note.title)) },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "导出为文件",
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(
                                                note.title,
                                                note.markdown,
                                            ),
                                        ),
                                    )
                                    snackbar.showSnackbar("已复制全文")
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "复制全文",
                            )
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多操作",
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("重命名") },
                                onClick = {
                                    showMenu = false
                                    showRename = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("删除") },
                                onClick = {
                                    showMenu = false
                                    showDelete = true
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val state = uiState) {
            NoteDetailUiState.Loading -> {
                Column(modifier = Modifier.padding(padding)) {
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                    MetadataLoadingSkeleton()
                }
            }
            is NoteDetailUiState.Error -> {
                ErrorView(
                    message = state.message,
                    onRetry = onBackClick,
                    modifier = Modifier.padding(padding),
                )
            }
            is NoteDetailUiState.Ready -> {
                NoteDetailContent(
                    note = state.note,
                    onPillClick = { seconds ->
                        onPlayClick(state.note.videoStorageKey, seconds)
                    },
                    onLinkClick = { url ->
                        openUrl(context, url)?.let(::showMessage)
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }

    if (showRename && note != null) {
        RenameDialog(
            currentTitle = note.title,
            onDismiss = { showRename = false },
            onConfirm = { newTitle ->
                viewModel.rename(newTitle)
                showRename = false
                showMessage("已重命名")
            },
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("删除笔记") },
            text = { Text("确定删除这份笔记吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = viewModel.delete()
                            showDelete = false
                            if (ok) onBackClick() else showMessage("删除失败，请重试")
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 详情正文：头部元信息 + Markdown 富渲染。 */
@Composable
private fun NoteDetailContent(
    note: Note,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = VideoNotesTokens.Space20),
    ) {
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeepLinkBuilder.platformOf(note.videoStorageKey)?.let { platform ->
                PlatformBadge(platform = platform)
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
            }
            Text(
                text = formatDetailTime(note.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
            Text(
                text = "${note.markdown.length} 字",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (note.videoTitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = "来源视频：${note.videoTitle}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        MarkdownReader(
            markdown = note.markdown,
            onPillClick = onPillClick,
            onLinkClick = onLinkClick,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space32))
    }
}

/** 重命名对话框。 */
@Composable
private fun RenameDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名笔记") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("笔记标题") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 分享：ACTION_SEND 文本分享 markdown 全文（沿用 M1 分享模式）。 */
private fun shareNote(context: Context, note: Note) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, note.title)
        putExtra(Intent.EXTRA_TEXT, note.markdown)
    }
    context.startActivity(Intent.createChooser(send, "分享笔记"))
}

/** 用浏览器打开链接；返回 null 表示已打开。 */
private fun openUrl(context: Context, url: String): String? {
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        null
    } catch (e: Exception) {
        "无法打开链接"
    }
}

/** 导出文件名：过滤文件系统非法字符。 */
private fun exportFileName(title: String): String {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60)
    return "${safe.ifBlank { "笔记" }}.md"
}

private fun formatDetailTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
