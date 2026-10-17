package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.components.ErrorView
import com.zola.videonotes.ui.components.MetadataLoadingSkeleton
import com.zola.videonotes.ui.components.PlatformBadge
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 元信息页（UI 重设计 v2）：Tab 布局。
 * 「视频信息」Tab：封面 / 元信息 / 字幕卡片 / 播放按钮（原有内容）。
 * 「课堂笔记」Tab：有笔记 → MarkdownReader 富渲染；无笔记 → 生成卡片
 * （生成中附实时日志面板），无文稿时引导先去取字幕。
 * 右上角：重命名 / 删除（仅有笔记时）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScreen(
    onBackClick: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onViewNoteClick: (Long) -> Unit,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
    viewModel: MetadataViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subtitleState by viewModel.subtitleState.collectAsStateWithLifecycle()
    val transcribeState by viewModel.transcribeState.collectAsStateWithLifecycle()
    val generateState by viewModel.generateState.collectAsStateWithLifecycle()
    val llmConfigured by viewModel.llmConfigured.collectAsStateWithLifecycle()
    val existingNoteId by viewModel.existingNoteId.collectAsStateWithLifecycle()
    // 有字幕段（可生成笔记）时也允许切到课堂笔记 Tab
    val hasTranscriptForTab = (subtitleState as? SubtitleUiState.Fetched)
        ?.result?.segments?.isNotEmpty() == true
    // 实时日志流（本地/云端引擎共用 LlmDiagnosticLog.log，自动推送）
    val llmLogLines by LlmDiagnosticLog.logFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(0) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // 从生成页/详情页/设置页返回时刷新"已生成笔记"与"LLM 配置"状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshExistingNote()
                // 设置页配好 API Key 后返回，本页"生成笔记"按钮状态需要刷新
                viewModel.refreshLlmConfigured()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 未下载模型时弹出 Snackbar，引导去模型管理页下载
    LaunchedEffect(transcribeState) {
        if (transcribeState is TranscribeUiState.NeedModel) {
            val result = snackbarHostState.showSnackbar(
                message = "语音转写需要先下载识别模型",
                actionLabel = "去下载",
                duration = SnackbarDuration.Short,
            )
            viewModel.consumeTranscribeEvent()
            if (result == SnackbarResult.ActionPerformed) {
                onNavigateToModels()
            }
        }
    }

    val confirmInfo = (transcribeState as? TranscribeUiState.Confirm)?.info
    if (confirmInfo != null) {
        TranscribeConfirmDialog(
            info = confirmInfo,
            onDismiss = viewModel::dismissTranscribeConfirm,
            onConfirm = viewModel::confirmTranscribe,
        )
    }

    // 生成成功后自动跳转到笔记详情（后台任务完成）
    LaunchedEffect(generateState) {
        val noteId = (generateState as? GenerateNoteUiState.Success)?.noteId
        if (noteId != null) {
            viewModel.consumeGenerateSuccess()
            onViewNoteClick(noteId)
        }
    }

    // 重命名对话框
    if (showRenameDialog && existingNoteId != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
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
                        existingNoteId?.let { viewModel.renameNote(it, renameText) }
                        showRenameDialog = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }

    // 删除二次确认
    if (showDeleteDialog && existingNoteId != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除笔记") },
            text = { Text("确定删除这篇笔记吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        existingNoteId?.let { viewModel.deleteNote(it) }
                        showDeleteDialog = false
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("视频详情") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        // 仅有笔记时显示重命名/删除
                        if (existingNoteId != null) {
                            IconButton(onClick = { showRenameDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "重命名",
                                )
                            }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "删除",
                                )
                            }
                        }
                    },
                )
                // Tab：视频信息 / 课堂笔记
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("视频信息") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("课堂笔记") },
                        // 无笔记时默认禁用；有字幕/生成中/失败时可点开，看进度 + 实时日志
                        enabled = existingNoteId != null ||
                            hasTranscriptForTab ||
                            generateState is GenerateNoteUiState.Working ||
                            generateState is GenerateNoteUiState.Failed,
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = VideoNotesTokens.Space20),
        ) {
            when (val state = uiState) {
                MetadataUiState.Loading -> {
                    Column {
                        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                        MetadataLoadingSkeleton()
                    }
                }
                MetadataUiState.Empty -> {
                    EmptyView(
                        title = "还没有链接",
                        description = "请先在首页粘贴视频链接，或从其他 App 分享链接到视记",
                    )
                }
                is MetadataUiState.Success -> {
                    // 文稿就绪（字幕/转写任一存在）才展示笔记生成入口
                    val hasTranscript = (subtitleState as? SubtitleUiState.Fetched)
                        ?.result?.segments?.isNotEmpty() == true
                    if (selectedTab == 0) {
                        MetadataContent(
                            info = state.info,
                            subtitleState = subtitleState,
                            transcribeState = transcribeState,
                            onFetchSubtitles = viewModel::fetchSubtitles,
                            onTranscribeClick = viewModel::onTranscribeClick,
                            onTranscribeRetry = viewModel::retryTranscribe,
                            onTranscribeCancel = viewModel::cancelTranscribe,
                            onPlayClick = onPlayClick,
                        )
                    } else {
                        // 课堂笔记 Tab：有笔记 → 渲染；生成中/无笔记 → 生成卡片 + 实时日志
                        NoteTabScreen(
                            existingNoteId = existingNoteId,
                            generateState = generateState,
                            llmConfigured = llmConfigured,
                            hasTranscript = hasTranscript,
                            llmLogLines = llmLogLines,
                            onGenerateClick = viewModel::startGenerate,
                            onGenerateRetry = viewModel::retryGenerate,
                            onGenerateCancel = viewModel::cancelGenerate,
                            onViewNoteClick = onViewNoteClick,
                            onNavigateToSettings = onNavigateToSettings,
                            onPlayClick = onPlayClick,
                        )
                    }
                }
                is MetadataUiState.Error -> {
                    ErrorView(
                        message = state.message,
                        onRetry = viewModel::retry,
                    )
                }
            }
        }
    }
}

/** 元信息成功态：封面 + 标题 + 平台徽标 + 时长 + 作者 + 字幕卡片（含语音转写）+ 播放按钮。 */
@Composable
private fun MetadataContent(
    info: VideoInfo,
    subtitleState: SubtitleUiState,
    transcribeState: TranscribeUiState,
    onFetchSubtitles: () -> Unit,
    onTranscribeClick: () -> Unit,
    onTranscribeRetry: () -> Unit,
    onTranscribeCancel: () -> Unit,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        if (info.coverUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(info.coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "视频封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(VideoNotesTokens.CoverAspectRatio)
                    .clip(RoundedCornerShape(VideoNotesTokens.CornerLarge)),
            )
        } else {
            // 无封面时的占位
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(VideoNotesTokens.CoverAspectRatio)
                    .clip(RoundedCornerShape(VideoNotesTokens.CornerLarge)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(VideoNotesTokens.Space48),
                )
            }
        }

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Text(
            text = info.title.ifBlank { "（无标题）" },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
        ) {
            PlatformBadge(platform = info.platform)
            if (info.durationSec > 0) {
                MetaChip(
                    icon = Icons.Filled.Schedule,
                    text = formatDuration(info.durationSec),
                )
            }
        }

        if (info.author.isNotBlank()) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(VideoNotesTokens.Space16),
                )
                Text(
                    text = info.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = VideoNotesTokens.Space8),
                )
            }
        }

        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        // M6：站内播放器入口（仅 YouTube / B站有可播放流）
        if (info.platform == Platform.YOUTUBE || info.platform == Platform.BILIBILI) {
            OutlinedButton(
                onClick = { onPlayClick(info.storageKey(), 0L) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
                Spacer(modifier = Modifier.size(VideoNotesTokens.Space8))
                Text("播放视频")
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        }
        SubtitleCard(
            state = subtitleState,
            transcribeState = transcribeState,
            onFetchClick = onFetchSubtitles,
            onRetryClick = onFetchSubtitles,
            onRefreshClick = onFetchSubtitles,
            onTranscribeClick = onTranscribeClick,
            onTranscribeRetry = onTranscribeRetry,
            onTranscribeCancel = onTranscribeCancel,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
    }
}

/** 时长等辅助信息的小徽标。 */
@Composable
private fun MetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(VideoNotesTokens.Space16),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = VideoNotesTokens.Space4),
        )
    }
}

/** 课堂笔记 Tab：有笔记 → 富渲染；否则 → 生成卡片（生成中附实时日志）。 */
@Composable
private fun NoteTabScreen(
    existingNoteId: Long?,
    generateState: GenerateNoteUiState,
    llmConfigured: Boolean,
    hasTranscript: Boolean,
    llmLogLines: List<String>,
    onGenerateClick: () -> Unit,
    onGenerateRetry: () -> Unit,
    onGenerateCancel: () -> Unit,
    onViewNoteClick: (Long) -> Unit,
    onNavigateToSettings: () -> Unit,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
) {
    if (existingNoteId != null) {
        NoteTabContent(noteId = existingNoteId, onPlayClick = onPlayClick)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = VideoNotesTokens.Space8),
    ) {
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        // 生成中 / 失败 / 有文稿待生成 → 生成卡片；无文稿 → 引导先去取字幕
        if (hasTranscript || generateState !is GenerateNoteUiState.Idle) {
            NoteGenerateCard(
                llmConfigured = llmConfigured,
                existingNoteId = null,
                generateState = generateState,
                onGenerateClick = onGenerateClick,
                onGenerateRetry = onGenerateRetry,
                onGenerateCancel = onGenerateCancel,
                onViewNoteClick = onViewNoteClick,
                onSettingsClick = onNavigateToSettings,
            )
            // 实时日志：生成中显示在生成卡片下方
            if (generateState is GenerateNoteUiState.Working) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
                RealtimeLogPanel(
                    lines = llmLogLines,
                    isRunning = true,
                )
            }
        } else {
            Text(
                text = "还没有字幕文稿，请先到「视频信息」页获取字幕或完成语音转写，再回来生成笔记。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
    }
}

/** 课堂笔记 Tab：按 noteId 加载笔记，用 MarkdownReader 富渲染。 */
@Composable
private fun NoteTabContent(
    noteId: Long,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
    viewModel: NoteTabViewModel = hiltViewModel(),
) {
    val markdown by viewModel.markdown.collectAsStateWithLifecycle()

    LaunchedEffect(noteId) {
        viewModel.load(noteId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = VideoNotesTokens.Space8),
    ) {
        if (markdown.isNotBlank()) {
            com.zola.videonotes.ui.reader.MarkdownReader(
                markdown = markdown,
                onPillClick = { seconds ->
                    viewModel.storageKey?.let { key -> onPlayClick(key, seconds) }
                },
                onLinkClick = { /* 预留 */ },
            )
        } else {
            Text(
                text = "笔记加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
    }
}
