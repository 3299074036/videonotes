package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.work.WorkerDiagnosticLog
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.ui.components.EmptyView
import com.zola.videonotes.ui.components.ErrorView
import com.zola.videonotes.ui.components.MetadataLoadingSkeleton
import com.zola.videonotes.ui.components.PlatformBadge
import com.zola.videonotes.ui.navigation.Routes
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 元信息页（全自动工作流）：解析成功后由 ViewModel 自动编排
 * 取字幕 →（无字幕）语音转写 → 生成笔记，无页签、无逐个点击。
 * 主体按编排状态切换：处理中 / 阻塞 / 失败 → 整页工序单；
 * 完成 → 视频头 + 完成视图；已取消 → 视频头 + 重新开始。
 * 右上角：重命名 / 删除（仅有笔记时）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataScreen(
    onBackClick: () -> Unit,
    onNavigateToModels: (section: String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onViewNoteClick: (Long) -> Unit,
    onPlayClick: (storageKey: String, seconds: Long) -> Unit,
    viewModel: MetadataViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val subtitleState by viewModel.subtitleState.collectAsStateWithLifecycle()
    val transcribeState by viewModel.transcribeState.collectAsStateWithLifecycle()
    val generateState by viewModel.generateState.collectAsStateWithLifecycle()
    val existingNoteId by viewModel.existingNoteId.collectAsStateWithLifecycle()
    val pipelineState by viewModel.pipelineState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val inlinePlayerState by viewModel.inlinePlayerState.collectAsStateWithLifecycle()
    val effectiveEngineType by viewModel.effectiveEngineType.collectAsStateWithLifecycle()
    // 实时日志流（本地/云端引擎共用 LlmDiagnosticLog.log，自动推送）
    val llmLogLines by LlmDiagnosticLog.logFlow.collectAsStateWithLifecycle()
    // Worker 诊断日志流（转写 / 生成后台任务的关键步骤落点）
    val workerLogLines by WorkerDiagnosticLog.logFlow.collectAsStateWithLifecycle()
    // 本轮编排启动时刻：工序单日志预览按它过滤上一轮残留行
    val pipelineLogStartMillis by viewModel.pipelineLogStartMillis.collectAsStateWithLifecycle()
    // 管线真实启动时刻（持久化）：工序单「已用时」的计算基准，重进页面不归零
    val pipelineStartMillis by viewModel.pipelineStartMillis.collectAsStateWithLifecycle()
    val modelGuide by viewModel.modelGuide.collectAsStateWithLifecycle()
    val guideMobileConfirm by viewModel.guideMobileConfirm.collectAsStateWithLifecycle()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    // 从详情页/模型管理页返回时刷新"已生成笔记"与"LLM 配置"状态，并尝试从阻塞步续跑
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshExistingNote()
                // 设置页配好 API Key 后返回，本页编排的配置判定需要刷新
                viewModel.refreshLlmConfigured()
                viewModel.resumePipelineIfBlocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 生成成功后自动跳转到笔记详情（后台任务完成）；
    // 跳转前再回查一次数据库：Success 指向的笔记若已不存在（陈旧成功态），
    // 不跳详情页报错，而是清掉陈旧状态、停在完成视图（仅保留「重新生成」）
    LaunchedEffect(generateState) {
        val noteId = (generateState as? GenerateNoteUiState.Success)?.noteId
        if (noteId != null) {
            if (viewModel.noteExists(noteId)) {
                viewModel.consumeGenerateSuccess()
                onViewNoteClick(noteId)
            } else {
                viewModel.clearStaleGenerateSuccess()
            }
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

    // 无选择 / 选中未下载引导弹层（2026-10-24 合并包）
    modelGuide?.let { guide ->
        val anyMissing = guide.llm == GuideSideState.MISSING || guide.asr == GuideSideState.MISSING
        AlertDialog(
            onDismissRequest = { viewModel.dismissModelGuide() },
            title = { Text(if (anyMissing) "还没选模型" else "模型尚未下载") },
            text = {
                Column {
                    guide.llm?.let { side ->
                        Text(
                            when (side) {
                                GuideSideState.MISSING -> "语言模型：还没选，推荐组合为 ${guide.llmLine}"
                                GuideSideState.UNDOWNLOADED -> "语言模型：${guide.llmLine} 尚未下载"
                            },
                        )
                    }
                    guide.asr?.let { side ->
                        Text(
                            when (side) {
                                GuideSideState.MISSING -> "识别模型：还没选，推荐组合为 ${guide.asrLine}"
                                GuideSideState.UNDOWNLOADED -> "识别模型：${guide.asrLine} 尚未下载"
                            },
                        )
                    }
                    Text(
                        text = "一键下载并选用会按推荐组合补齐未选侧、下载已选未下载侧；下载完成后回到本页继续（阻塞步骤会自动续跑）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.guideDownloadAndSelect() }) {
                    Text("一键下载并选用")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissModelGuide()
                    onNavigateToModels(guide.manualSection)
                }) { Text("自己去模型管理挑") }
            },
        )
    }

    // 引导一键下载的移动网络确认步骤
    guideMobileConfirm?.let { targets ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissGuideMobileConfirm() },
            title = { Text("移动网络下载确认") },
            text = {
                Text(
                    "当前为移动网络，以下模型文件较大，确认下载吗？\n" +
                        targets.joinToString("\n") { "· ${it.displayName}" },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmGuideMobile() }) { Text("确认下载") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissGuideMobileConfirm() }) { Text("取消") }
            },
        )
    }

    Scaffold(
        topBar = {
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
        },
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
                    // 工序单推导：四步状态来自既有 UI 状态 + 编排状态（阻塞步）
                    val stepsUi = remember(
                        subtitleState, transcribeState, generateState,
                        existingNoteId, pipelineState,
                    ) {
                        deriveProcessSteps(
                            info = state.info,
                            subtitleState = subtitleState,
                            transcribeState = transcribeState,
                            generateState = generateState,
                            existingNoteId = existingNoteId,
                            pipelineState = pipelineState,
                        )
                    }
                    // 工序单当前步的日志预览：生成中取 LLM 日志、转写中取 Worker 日志，互为兜底；
                    // 只取本轮编排启动时刻之后的行，避免显示上一轮已取消任务的残留日志
                    val latestLogLine = remember(
                        llmLogLines, workerLogLines, stepsUi.running, pipelineLogStartMillis,
                    ) {
                        fun List<String>.thisRun(): List<String> =
                            if (pipelineLogStartMillis <= 0L) {
                                this
                            } else {
                                filter { line ->
                                    val t = logLineTimeMillis(line)
                                    t == null || t >= pipelineLogStartMillis
                                }
                            }
                        val llmLines = llmLogLines.thisRun()
                        val workerLines = workerLogLines.thisRun()
                        val primary =
                            if (stepsUi.running == ProcessRunning.GENERATE) {
                                llmLines
                            } else {
                                workerLines
                            }
                        val fallback =
                            if (stepsUi.running == ProcessRunning.GENERATE) {
                                workerLines
                            } else {
                                llmLines
                            }
                        primary.lastOrNull() ?: fallback.lastOrNull()
                    }
                    // 完成视图：编排到 Done 即展示；existingNoteId 为空（笔记已被删 /
                    // 陈旧成功态被清）时隐藏「查看笔记」，只保留「重新生成」
                    val showCompleted = pipelineState == PipelineUiState.Done &&
                        generateState !is GenerateNoteUiState.Working
                    when {
                        pipelineState == PipelineUiState.Cancelled -> {
                            // 已取消：视频头 + 重新开始（不自动重启）
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                MetadataHeader(
                                    info = state.info,
                                    inlineState = inlinePlayerState,
                                    onInlinePlayRequest = { viewModel.loadInlineStream(state.info) },
                                    onExpandClick = { onPlayClick(state.info.storageKey(), 0L) },
                                    downloadState = downloadState,
                                    onDownloadClick = viewModel::downloadVideo,
                                )
                                CancelledView(
                                    onRestart = viewModel::restartPipeline,
                                )
                            }
                        }
                        showCompleted -> {
                            // 完成：视频头 + 查看笔记 / 重新生成
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                MetadataHeader(
                                    info = state.info,
                                    inlineState = inlinePlayerState,
                                    onInlinePlayRequest = { viewModel.loadInlineStream(state.info) },
                                    onExpandClick = { onPlayClick(state.info.storageKey(), 0L) },
                                    downloadState = downloadState,
                                    onDownloadClick = viewModel::downloadVideo,
                                )
                                CompletedNoteView(
                                    noteId = existingNoteId,
                                    onViewNoteClick = onViewNoteClick,
                                    onRegenerate = viewModel::regenerate,
                                )
                            }
                        }
                        else -> {
                            // 处理中 / 阻塞 / 失败：整页工序单接管主体
                            ProcessStepsPage(
                                info = state.info,
                                stepsUi = stepsUi,
                                latestLogLine = latestLogLine,
                                pipelineStartMillis = pipelineStartMillis,
                                topContent = if (state.info != null) {
                                    {
                                        VideoDetailTop(
                                            info = state.info,
                                            inlineState = inlinePlayerState,
                                            onInlinePlayRequest = { viewModel.loadInlineStream(state.info) },
                                            onExpandClick = { onPlayClick(state.info.storageKey(), 0L) },
                                            downloadState = downloadState,
                                            onDownloadClick = viewModel::downloadVideo,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onCancel = viewModel::cancelCurrentTask,
                                onRetryTranscribe = viewModel::onStepRetryTranscribe,
                                onRetryGenerate = viewModel::onStepRetryGenerate,
                                onStepAction = { action ->
                                    when (action) {
                                        // 缺 ASR 模型 → 已就绪则续跑，否则弹引导（引导内可一键下载或跳模型管理）
                                        ProcessStepAction.DOWNLOAD_MODEL ->
                                            viewModel.onStepDownloadModelAction()
                                        // 引擎未配置 → 本地：已就绪则续跑，否则弹引导；
                                        // 云端：仍直达云端 API 分区（配好后返回自动续跑）
                                        ProcessStepAction.CONFIGURE_LLM ->
                                            if (effectiveEngineType == LlmEngineType.LOCAL) {
                                                viewModel.onStepConfigureLlmAction()
                                            } else {
                                                onNavigateToModels(Routes.SECTION_LLM_CLOUD)
                                            }
                                        // 本地生成熔断 → 云端 API 分区直达（切换并配好后返回自动续跑）
                                        ProcessStepAction.SWITCH_TO_CLOUD ->
                                            onNavigateToModels(Routes.SECTION_LLM_CLOUD)
                                    }
                                },
                                cancellable = pipelineState == PipelineUiState.Running ||
                                    stepsUi.running != ProcessRunning.NONE,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
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

/** 视频头：统一顶部（平台图标 + 标题 / 作者 · 时长 / 本页播放器 / 媒体行）。 */
@Composable
private fun MetadataHeader(
    info: VideoInfo,
    inlineState: InlinePlayerState,
    onInlinePlayRequest: () -> Unit,
    onExpandClick: () -> Unit,
    downloadState: VideoDownloadUiState,
    onDownloadClick: () -> Unit,
) {
    VideoDetailTop(
        info = info,
        inlineState = inlineState,
        onInlinePlayRequest = onInlinePlayRequest,
        onExpandClick = onExpandClick,
        downloadState = downloadState,
        onDownloadClick = onDownloadClick,
    )
}

/**
 * 完成视图：处理已完成。noteId 非空时提供「查看笔记 / 重新生成」；
 * 为空（笔记已被删除、仅剩陈旧完成态）时只保留「重新生成」，不给死入口。
 */
@Composable
private fun CompletedNoteView(
    noteId: Long?,
    onViewNoteClick: (Long) -> Unit,
    onRegenerate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (noteId != null) "笔记已生成" else "处理已完成",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
        Text(
            text = if (noteId != null) {
                "课堂笔记已保存到知识库。重新生成会按当前引擎再跑一次生成任务。"
            } else {
                "这篇笔记已不存在。可以重新生成一份。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        if (noteId != null) {
            Button(
                onClick = { onViewNoteClick(noteId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("查看笔记")
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        }
        OutlinedButton(
            onClick = onRegenerate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新生成")
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space24))
    }
}

/** 已取消视图：处理被用户中止，提供手动重新开始（不自动重启）。 */
@Composable
private fun CancelledView(
    onRestart: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "已取消处理",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
        Text(
            text = "这次处理已停止。重新开始后将自动按顺序处理，已获取的文稿会直接复用。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Button(
            onClick = onRestart,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("重新开始")
        }
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
