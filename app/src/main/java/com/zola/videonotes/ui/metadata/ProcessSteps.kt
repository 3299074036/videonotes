package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.delay

/**
 * 工序单（纸上课堂 · 处理进度整页）：把分散在字幕卡 / 生成卡里的处理状态，
 * 按 解析 → 提取音频 → 语音转写 → 生成笔记 四步统一呈现；
 * 处理进行中 / 失败时由 [ProcessStepsPage] 接管元信息页主体（隐藏 Tab 内容）。
 * 本文件只做呈现层推导：状态全部来自既有 UI 状态，不改任务与调度。
 */

enum class ProcessStepStatus { DONE, ACTIVE, PENDING, FAILED, BLOCKED }

enum class ProcessRunning { NONE, TRANSCRIBE, GENERATE }

/** 失败步的重试动作：由页面映射到 ViewModel 的既有回调。 */
enum class ProcessStepRetry { TRANSCRIBE, GENERATE }

/** 阻塞步的步内动作：由页面映射到导航回调（下载模型 / 去配置 / 切换云端）。 */
enum class ProcessStepAction { DOWNLOAD_MODEL, CONFIGURE_LLM, SWITCH_TO_CLOUD }

/**
 * 解析诊断日志行的时间戳前缀为 epoch 毫秒（行格式 `[MM-dd HH:mm:ss.SSS][tag] …`，
 * 年份按当前年补）。解析失败返回 null（调用方据此不过滤该行）。
 * 供「最新日志」预览按本轮编排启动时刻过滤上一轮残留行。
 */
internal fun logLineTimeMillis(line: String): Long? {
    if (!line.startsWith("[")) return null
    val stamp = line.substring(1, minOf(line.length, 19))
    if (stamp.length < 18) return null
    return runCatching {
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        val parsed = format.parse(stamp) ?: return null
        val cal = java.util.Calendar.getInstance()
        val nowYear = cal.get(java.util.Calendar.YEAR)
        cal.time = parsed
        cal.set(java.util.Calendar.YEAR, nowYear)
        cal.timeInMillis
    }.getOrNull()
}

data class ProcessStepUi(
    val title: String,
    val status: ProcessStepStatus,
    val detail: String? = null,
    /** 分项进度：0f..1f；null 表示无确定进度。 */
    val progress: Float? = null,
    /** 失败时的重试动作；null 表示该步不提供重试。 */
    val retry: ProcessStepRetry? = null,
    /** 阻塞时的步内动作；null 表示该步未阻塞。 */
    val action: ProcessStepAction? = null,
)

data class ProcessStepsUi(
    val steps: List<ProcessStepUi>,
    /** 当前步（1 起）；无进行中 / 失败步骤时为 0。 */
    val currentStep: Int,
    /** 总进度条：null 表示不确定型。 */
    val overallProgress: Float?,
    /** 标题区大号百分比：null 时改显阶段文案。 */
    val headlinePercent: Int?,
    val activeStageText: String?,
    val running: ProcessRunning,
    /** 是否有需要用户关注的失败。 */
    val hasFailure: Boolean,
)

/** 转写阶段文案 → 工序步：下载/解码归「提取音频」，转写/保存归「语音转写」。 */
private fun transcribePhase(stageText: String): Int = when {
    stageText.contains("下载") || stageText.contains("解码") || stageText.contains("准备中") -> 2
    else -> 3
}

/**
 * 由既有 UI 状态推导四步工序。
 * 官方字幕路径下「提取音频 / 语音转写」两步以字幕获取结果直接结清（文案注明来源）。
 */
fun deriveProcessSteps(
    info: VideoInfo?,
    subtitleState: SubtitleUiState,
    transcribeState: TranscribeUiState,
    generateState: GenerateNoteUiState,
    existingNoteId: Long?,
    pipelineState: PipelineUiState = PipelineUiState.Idle,
): ProcessStepsUi {
    val fetched = subtitleState as? SubtitleUiState.Fetched
    val hasTranscript = (fetched?.result?.segments?.isNotEmpty() == true)
    val fromAsr = fetched?.result?.source == SubtitleSource.ASR
    val transcribe = transcribeState as? TranscribeUiState.Working
    val transcribeFailed = transcribeState as? TranscribeUiState.Failed
    val generate = generateState as? GenerateNoteUiState.Working
    val generateFailed = generateState as? GenerateNoteUiState.Failed

    // 步骤 2/3（音频与转写）
    val step2: ProcessStepUi
    val step3: ProcessStepUi
    when {
        transcribe != null && transcribePhase(transcribe.stageText) == 2 -> {
            step2 = ProcessStepUi(
                title = "提取音频",
                status = ProcessStepStatus.ACTIVE,
                detail = transcribe.stageText,
                progress = transcribe.progress.takeIf { it >= 0 },
            )
            step3 = ProcessStepUi("语音转写", ProcessStepStatus.PENDING)
        }
        transcribe != null -> {
            step2 = ProcessStepUi("提取音频", ProcessStepStatus.DONE, detail = "已提取音频")
            step3 = ProcessStepUi(
                title = "语音转写",
                status = ProcessStepStatus.ACTIVE,
                detail = transcribe.stageText,
                progress = transcribe.progress.takeIf { it >= 0 },
            )
        }
        transcribeFailed != null -> {
            step2 = ProcessStepUi("提取音频", ProcessStepStatus.DONE, detail = "已提取音频")
            step3 = ProcessStepUi(
                title = "语音转写",
                status = ProcessStepStatus.FAILED,
                detail = "转写失败：${transcribeFailed.message}",
                retry = ProcessStepRetry.TRANSCRIBE,
            )
        }
        hasTranscript && fromAsr -> {
            step2 = ProcessStepUi("提取音频", ProcessStepStatus.DONE, detail = "已提取音频")
            step3 = ProcessStepUi(
                "语音转写",
                ProcessStepStatus.DONE,
                detail = "本机转写 · 共 ${fetched!!.result.segments.size} 段",
            )
        }
        hasTranscript -> {
            step2 = ProcessStepUi(
                "提取音频",
                ProcessStepStatus.DONE,
                detail = "已获取${fetched!!.result.source.displayName}，无需提取音频",
            )
            step3 = ProcessStepUi(
                "语音转写",
                ProcessStepStatus.DONE,
                detail = "文稿就绪 · 共 ${fetched.result.segments.size} 段",
            )
        }
        pipelineState == PipelineUiState.BlockedTranscribeModel -> {
            // 编排停在转写步：ASR 模型未下载，步内提供「下载模型」
            step2 = ProcessStepUi("提取音频", ProcessStepStatus.PENDING)
            step3 = ProcessStepUi(
                title = "语音转写",
                status = ProcessStepStatus.BLOCKED,
                detail = "需要先下载语音识别模型，下载后自动继续",
                action = ProcessStepAction.DOWNLOAD_MODEL,
            )
        }
        subtitleState is SubtitleUiState.Loading -> {
            step2 = ProcessStepUi(
                "提取音频",
                ProcessStepStatus.ACTIVE,
                detail = subtitleState.message,
            )
            step3 = ProcessStepUi("语音转写", ProcessStepStatus.PENDING)
        }
        else -> {
            step2 = ProcessStepUi("提取音频", ProcessStepStatus.PENDING)
            step3 = ProcessStepUi(
                "语音转写",
                ProcessStepStatus.PENDING,
                detail = "无官方字幕时用本机转写",
            )
        }
    }

    // 步骤 4（生成笔记）
    val step4 = when {
        generate != null -> ProcessStepUi(
            "生成笔记",
            ProcessStepStatus.ACTIVE,
            detail = generate.stageText,
        )
        pipelineState == PipelineUiState.BlockedGenerateCrashed -> ProcessStepUi(
            title = "生成笔记",
            status = ProcessStepStatus.BLOCKED,
            detail = "本地生成已连续崩溃，已停止自动重试。可手动重试，或切换到云端 API 生成",
            retry = ProcessStepRetry.GENERATE,
            action = ProcessStepAction.SWITCH_TO_CLOUD,
        )
        generateFailed != null -> ProcessStepUi(
            "生成笔记",
            ProcessStepStatus.FAILED,
            detail = "生成失败：${generateFailed.message}",
            retry = ProcessStepRetry.GENERATE,
        )
        existingNoteId != null || generateState is GenerateNoteUiState.Success ->
            ProcessStepUi("生成笔记", ProcessStepStatus.DONE, detail = "笔记已生成")
        pipelineState == PipelineUiState.BlockedGenerateConfig -> ProcessStepUi(
            title = "生成笔记",
            status = ProcessStepStatus.BLOCKED,
            detail = "所选生成引擎尚未配置，配置后自动继续",
            action = ProcessStepAction.CONFIGURE_LLM,
        )
        hasTranscript -> ProcessStepUi(
            "生成笔记",
            ProcessStepStatus.PENDING,
            detail = "文稿就绪，可开始生成",
        )
        else -> ProcessStepUi("生成笔记", ProcessStepStatus.PENDING)
    }

    val steps = listOf(
        ProcessStepUi(
            "解析视频",
            if (info != null) ProcessStepStatus.DONE else ProcessStepStatus.ACTIVE,
            detail = if (info != null) "已获取标题、作者与时长" else null,
        ),
        step2,
        step3,
        step4,
    )

    val currentIndex = steps.indexOfFirst {
        it.status == ProcessStepStatus.ACTIVE || it.status == ProcessStepStatus.FAILED ||
            it.status == ProcessStepStatus.BLOCKED
    }
    val running = when {
        generate != null -> ProcessRunning.GENERATE
        transcribe != null -> ProcessRunning.TRANSCRIBE
        else -> ProcessRunning.NONE
    }
    val activeProgress = steps.getOrNull(currentIndex)?.progress
    return ProcessStepsUi(
        steps = steps,
        currentStep = if (currentIndex >= 0) currentIndex + 1 else 0,
        overallProgress = activeProgress,
        headlinePercent = activeProgress?.let { (it * 100).toInt() },
        activeStageText = when {
            generate != null -> generate.stageText
            transcribe != null -> transcribe.stageText
            subtitleState is SubtitleUiState.Loading -> subtitleState.message
            else -> null
        },
        running = running,
        hasFailure = steps.any { it.status == ProcessStepStatus.FAILED },
    )
}

/** 工序单是否需要展示：任一步骤在进行中、失败或阻塞时展示（纯待办状态不打扰）。 */
fun ProcessStepsUi.shouldShow(): Boolean =
    running != ProcessRunning.NONE || hasFailure ||
        steps.any {
            it.status == ProcessStepStatus.ACTIVE || it.status == ProcessStepStatus.BLOCKED
        }

/**
 * 工序单整页（纸上课堂 · 处理进度）：处理进行中 / 失败时接管元信息页主体。
 * 版面：视频行（封面 + 标题 + 作者·平台·时长）→ 总进度（衬线大数字 + 总进度条 +
 * 步数 / 已用时）→ 纵向四步（失败步带重试、当前步带最新日志预览）→
 * 底部常驻后台提示 + 单一取消按钮。
 * 已用时按持久化的管线启动时刻计算（now − 入队时刻，重进页面不归零；
 * 仍不显示预计剩余，无可靠数据源）。
 */
@Composable
fun ProcessStepsPage(
    info: VideoInfo?,
    stepsUi: ProcessStepsUi,
    latestLogLine: String?,
    onCancel: () -> Unit,
    onRetryTranscribe: () -> Unit,
    onRetryGenerate: () -> Unit,
    onStepAction: (ProcessStepAction) -> Unit = {},
    cancellable: Boolean = stepsUi.running != ProcessRunning.NONE,
    modifier: Modifier = Modifier,
    /** 管线真实启动时刻（持久化记录，来自 ViewModel）；null 时不显示已用时。 */
    pipelineStartMillis: Long? = null,
    /** 顶部内容：与详情页完全一致的统一顶部（平台图标 + 标题 / 作者 · 时长 / 本页播放器 / 媒体行）；null 时不显示。 */
    topContent: (@Composable () -> Unit)? = null,
) {
    // 已用时：now − 持久化启动时刻；运行中每秒滚动刷新（重进页面不归零）
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stepsUi.running) {
        if (stepsUi.running != ProcessRunning.NONE) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val elapsedSec = pipelineStartMillis?.let { start ->
        ((nowMillis - start) / 1000).coerceAtLeast(0L)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶部：与详情页完全一致的统一顶部（图标 + 标题 / 作者 · 时长 / 播放器 / 媒体行）
            topContent?.invoke()

            // 总进度：大数字 + 阶段文案
            Row(verticalAlignment = Alignment.Bottom) {
                if (stepsUi.headlinePercent != null) {
                    Text(
                        text = "${stepsUi.headlinePercent}%",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stepsUi.activeStageText ?: when {
                            stepsUi.hasFailure -> "处理失败，请查看下方步骤"
                            stepsUi.steps.any { it.status == ProcessStepStatus.BLOCKED } ->
                                "已暂停，完成这一步后自动继续"
                            else -> "处理中"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val meta = buildString {
                        if (stepsUi.currentStep > 0) {
                            append("第 ${stepsUi.currentStep} 步 / 共 4 步")
                        }
                        if (stepsUi.running != ProcessRunning.NONE && elapsedSec != null) {
                            if (isNotEmpty()) append(" · ")
                            append("已用 ${formatStepsDuration(elapsedSec)}")
                        }
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            if (stepsUi.overallProgress != null) {
                LinearProgressIndicator(
                    progress = { stepsUi.overallProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(VideoNotesTokens.CornerFull)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(VideoNotesTokens.CornerFull)),
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space20))

            // 纵向四步（当前步附最新日志预览，失败步附重试）
            stepsUi.steps.forEachIndexed { index, step ->
                ProcessStepRow(
                    step = step,
                    index = index,
                    isLast = index == stepsUi.steps.lastIndex,
                    logLine = latestLogLine.takeIf { index == stepsUi.currentStep - 1 },
                    onRetry = when (step.retry) {
                        ProcessStepRetry.TRANSCRIBE -> onRetryTranscribe
                        ProcessStepRetry.GENERATE -> onRetryGenerate
                        null -> null
                    },
                    onAction = step.action?.let { action -> { onStepAction(action) } },
                )
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        }

        // 底部常驻：后台提示 + 单一取消入口（编排推进中出现，含取字幕等无后台任务的阶段）
        if (cancellable) {
            Text(
                text = "任务在后台运行，可离开本页，进度会同步到通知栏",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("取消任务", color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        }
    }
}

/** 工序单单步：状态节点（✓ / 步号 / !）+ 竖线 + 标题与子文案 + 分项进度 / 日志预览 / 重试。 */
@Composable
private fun ProcessStepRow(
    step: ProcessStepUi,
    index: Int,
    isLast: Boolean,
    logLine: String? = null,
    onRetry: (() -> Unit)? = null,
    onAction: (() -> Unit)? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val doneGreen = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF3E7C4F)
    } else {
        Color(0xFF8FCB9B)
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        // 节点列（节点 + 连接竖线）
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        when (step.status) {
                            ProcessStepStatus.DONE -> doneGreen
                            ProcessStepStatus.ACTIVE -> primary
                            ProcessStepStatus.BLOCKED -> primary
                            ProcessStepStatus.FAILED -> MaterialTheme.colorScheme.error
                            ProcessStepStatus.PENDING ->
                                MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when (step.status) {
                    ProcessStepStatus.DONE -> Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "已完成",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    ProcessStepStatus.FAILED -> Text(
                        text = "!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                    )
                    ProcessStepStatus.BLOCKED -> Text(
                        text = "!",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    else -> Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (step.status == ProcessStepStatus.ACTIVE) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(30.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else VideoNotesTokens.Space12),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = step.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when (step.status) {
                        ProcessStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = when (step.status) {
                        ProcessStepStatus.DONE -> "已完成"
                        ProcessStepStatus.ACTIVE ->
                            step.progress?.let { "进行中 ${(it * 100).toInt()}%" } ?: "进行中"
                        ProcessStepStatus.FAILED -> "失败"
                        ProcessStepStatus.BLOCKED -> "已暂停"
                        ProcessStepStatus.PENDING -> "等待中"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (step.status) {
                        ProcessStepStatus.DONE -> doneGreen
                        ProcessStepStatus.ACTIVE -> primary
                        ProcessStepStatus.BLOCKED -> primary
                        ProcessStepStatus.FAILED -> MaterialTheme.colorScheme.error
                        ProcessStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (step.detail != null) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == ProcessStepStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (step.status == ProcessStepStatus.ACTIVE && step.progress != null) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
                LinearProgressIndicator(
                    progress = { step.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(VideoNotesTokens.CornerFull)),
                )
            }
            // 当前步的实时内容：最新一行日志（如实命名，不冒充转写句子流）
            if (logLine != null) {
                Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
                Surface(
                    shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(VideoNotesTokens.Space12)) {
                        Text(
                            text = "最新日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.height(VideoNotesTokens.Space2))
                        Text(
                            text = logLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 失败步内重试（接 ViewModel 既有回调）；熔断阻塞步同样提供，
            // 文案为「手动重试」（会先清零熔断计数，见 ViewModel 步内重试入口）
            if ((step.status == ProcessStepStatus.FAILED ||
                    step.status == ProcessStepStatus.BLOCKED) && onRetry != null
            ) {
                TextButton(onClick = onRetry) {
                    Text(
                        if (step.status == ProcessStepStatus.BLOCKED) "手动重试" else "重试",
                    )
                }
            }
            // 阻塞步内动作（下载模型 / 去配置 / 切换云端，跳现有页面后返回自动续跑）
            if (step.status == ProcessStepStatus.BLOCKED && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        when (step.action) {
                            ProcessStepAction.DOWNLOAD_MODEL -> "下载模型"
                            ProcessStepAction.CONFIGURE_LLM -> "去配置"
                            ProcessStepAction.SWITCH_TO_CLOUD -> "切换云端 API"
                            null -> ""
                        },
                    )
                }
            }
        }
    }
}

private fun formatStepsDuration(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}
