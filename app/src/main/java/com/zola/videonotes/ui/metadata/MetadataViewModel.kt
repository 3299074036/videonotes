package com.zola.videonotes.ui.metadata

import android.content.Context
import android.os.BatteryManager
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.zola.videonotes.data.asr.AsrModelCatalog
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.download.MobileDownloadConsent
import com.zola.videonotes.data.download.NetworkGate
import com.zola.videonotes.data.download.VideoDownloader
import com.zola.videonotes.data.llm.LlmEngineResolver
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmSettings
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.VideoDao
import com.zola.videonotes.data.local.toEntity
import com.zola.videonotes.data.parser.ParserRegistry
import com.zola.videonotes.data.pipeline.GENERATE_CRASH_BREAKER_MESSAGE
import com.zola.videonotes.data.pipeline.GenerateCrashGuard
import com.zola.videonotes.data.pipeline.PipelineStartStore
import com.zola.videonotes.data.player.PlayableStream
import com.zola.videonotes.data.player.VideoStreamFetcher
import com.zola.videonotes.data.subtitle.TranscriptRepository
import com.zola.videonotes.data.work.KEY_ERROR
import com.zola.videonotes.data.work.KEY_NOTE_ID
import com.zola.videonotes.data.work.KEY_PROGRESS
import com.zola.videonotes.data.work.KEY_STAGE
import com.zola.videonotes.data.work.LongTaskScheduler
import com.zola.videonotes.domain.asr.AsrModel
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import com.zola.videonotes.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 元信息页 UI 状态。 */
sealed interface MetadataUiState {
    /** 解析中 */
    data object Loading : MetadataUiState

    /** 未传入链接 */
    data object Empty : MetadataUiState

    /** 解析成功 */
    data class Success(val info: VideoInfo) : MetadataUiState

    /** 解析失败：message 为面向用户的中文原因 */
    data class Error(val message: String) : MetadataUiState
}

/** 字幕卡片 UI 状态。 */
sealed interface SubtitleUiState {
    /** 未获取：hint 为空时显示默认引导文案 */
    data class NotFetched(val hint: String? = null) : SubtitleUiState

    /** 获取中：message 为进度文案 */
    data class Loading(val message: String = "正在获取字幕…") : SubtitleUiState

    /** 已获取 */
    data class Fetched(val result: SubtitleFetchResult) : SubtitleUiState

    /** 获取失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : SubtitleUiState
}

/** 语音转写 UI 状态（M3）。 */
sealed interface TranscribeUiState {
    /** 空闲 */
    data object Idle : TranscribeUiState

    /** 二次确认：展示耗时/流量/电量提示 */
    data class Confirm(val info: TranscribeConfirmInfo) : TranscribeUiState

    /** 转写中：progress < 0 表示不确定进度 */
    data class Working(val stageText: String, val progress: Float) : TranscribeUiState

    /** 转写失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : TranscribeUiState
}

/** 视频下载 UI 状态（解析成功后可下载无水印视频到系统下载目录）。 */
sealed interface VideoDownloadUiState {
    /** 空闲 */
    data object Idle : VideoDownloadUiState

    /** 下载中：progress 0f..1f */
    data class Working(val progress: Float) : VideoDownloadUiState

    /** 下载成功：message 为保存位置提示 */
    data class Done(val message: String) : VideoDownloadUiState

    /** 下载失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : VideoDownloadUiState
}

/** 内嵌播放器取流状态（详情页/处理中页顶部播放器共用）。 */
sealed interface InlinePlayerState {
    /** 未取流：显示封面 + ▶ */
    data object Idle : InlinePlayerState

    /** 取流中：显示加载圈（抖音/小红书重解析约 5 秒） */
    data object Loading : InlinePlayerState

    /** 已取到可播放流：显示 PlayerView */
    data class Ready(val stream: PlayableStream) : InlinePlayerState

    /** 取流失败：message 为面向用户的中文原因，点重试重新取流 */
    data class Failed(val message: String) : InlinePlayerState
}

/** 引导弹层中一侧模型的状态：未选 / 已选未下载。 */
enum class GuideSideState { MISSING, UNDOWNLOADED }

/**
 * 无选择 / 选中未下载时的引导弹层内容（2026-10-24 合并包，替代生硬错误与 NeedModel Snackbar）：
 * - 标题：任一侧 MISSING →「还没选模型」，否则「模型尚未下载」；
 * - 行：未选侧显示推荐组合模型名，已选未下载侧显示用户所选模型名；
 * - manualSection：「自己去模型管理挑」直达分区（单侧时为该侧，双侧时为语言侧）。
 */
data class ModelGuide(
    val llm: GuideSideState?,
    val asr: GuideSideState?,
    val llmLine: String,
    val asrLine: String,
    val manualSection: String,
)

/** 引导一键下载的待下载项（移动网络确认步骤用）。 */
data class GuideDownloadTarget(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val isLlm: Boolean,
)
sealed interface GenerateNoteUiState {
    /** 空闲 */
    data object Idle : GenerateNoteUiState

    /** 生成中：stageText 为阶段文案 */
    data class Working(val stageText: String) : GenerateNoteUiState

    /** 生成失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : GenerateNoteUiState

    /** 生成成功：携带笔记 id */
    data class Success(val noteId: Long) : GenerateNoteUiState
}

/**
 * 全自动编排状态：元信息加载成功后由 ViewModel 顺序推进
 * 取字幕 →（无字幕）转写 → 生成，全程无需用户逐个点击；
 * 仅在硬阻塞（缺模型 / 未配置）时停步，等用户处理后从阻塞步续跑。
 */
sealed interface PipelineUiState {
    /** 尚未启动编排（解析未成功 / 单步失败后停住，失败由步骤状态呈现）。 */
    data object Idle : PipelineUiState

    /** 编排推进中（含等待后台任务完成）。 */
    data object Running : PipelineUiState

    /** 转写步阻塞：ASR 模型未下载，步内提供「下载模型」。 */
    data object BlockedTranscribeModel : PipelineUiState

    /** 生成步阻塞：所选引擎未配置，步内提供「去配置」。 */
    data object BlockedGenerateConfig : PipelineUiState

    /** 生成步阻塞：本地生成连续原生崩溃被熔断，步内提供「手动重试」与「切换云端 API」。 */
    data object BlockedGenerateCrashed : PipelineUiState

    /** 用户取消后的落点：不自动重启，提供「重新开始」。 */
    data object Cancelled : PipelineUiState

    /** 全流程完成（已有笔记）。 */
    data object Done : PipelineUiState
}

/** 转写二次确认对话框的信息。 */
data class TranscribeConfirmInfo(
    val modelName: String,
    val durationText: String,
    val batteryText: String,
    val lowBattery: Boolean,
)

/**
 * 元信息页 ViewModel：从路由参数取链接，调用 ParserRegistry 解析；
 * 字幕走 TranscriptRepository；语音转写走 WorkManager 长任务（M6）。
 */
@HiltViewModel
class MetadataViewModel @Inject constructor(
    private val parserRegistry: ParserRegistry,
    private val transcriptRepository: TranscriptRepository,
    private val longTaskScheduler: LongTaskScheduler,
    private val asrPreferences: AsrPreferences,
    private val modelDownloadManager: ModelDownloadManager,
    private val localDownloadManager: LocalModelDownloadManager,
    private val mobileConsent: MobileDownloadConsent,
    private val llmSettings: LlmSettings,
    private val engineResolver: LlmEngineResolver,
    private val localLlmPreferences: LocalLlmPreferences,
    private val noteDao: NoteDao,
    private val videoDao: VideoDao,
    private val pipelineStartStore: PipelineStartStore,
    private val crashGuard: GenerateCrashGuard,
    private val videoDownloader: VideoDownloader,
    private val streamFetcher: VideoStreamFetcher,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val url: String = savedStateHandle.get<String>(Routes.ARG_URL).orEmpty()

    /** 路由传入的原始链接：供"生成笔记"页复用。 */
    val sourceUrl: String = url

    private val _uiState = MutableStateFlow<MetadataUiState>(
        if (url.isBlank()) MetadataUiState.Empty else MetadataUiState.Loading,
    )
    val uiState: StateFlow<MetadataUiState> = _uiState.asStateFlow()

    private val _subtitleState = MutableStateFlow<SubtitleUiState>(SubtitleUiState.NotFetched())
    val subtitleState: StateFlow<SubtitleUiState> = _subtitleState.asStateFlow()

    private val _transcribeState = MutableStateFlow<TranscribeUiState>(TranscribeUiState.Idle)
    val transcribeState: StateFlow<TranscribeUiState> = _transcribeState.asStateFlow()

    private val _downloadState = MutableStateFlow<VideoDownloadUiState>(VideoDownloadUiState.Idle)
    val downloadState: StateFlow<VideoDownloadUiState> = _downloadState.asStateFlow()

    private val _inlinePlayerState = MutableStateFlow<InlinePlayerState>(InlinePlayerState.Idle)
    val inlinePlayerState: StateFlow<InlinePlayerState> = _inlinePlayerState.asStateFlow()

    /**
     * 内嵌播放器取流（YunX 模式：解析结果就是播放源）。
     * 小红书/快手：直接播详情页解析好的 mediaUrl + mediaHeaders，不重解析——小红书重解析用的
     * sourceUrl 一旦丢了 xsec_token 就会撞登录墙，而详情页手里的直链本身就是可播的；
     * 快手重解析无 token 门槛，但直链直出同样更稳。
     * 抖音：直链时效短且重解析是通的，仍走重解析；重解析失败时退回已存直链一试。
     * 微博走抖音同款：重解析只是廉价 JSON 调用且无 token 脆弱性，不走小红书的直链直出。
     * B站/YouTube 直接取流。取流中重复调用直接忽略。
     */
    fun loadInlineStream(video: VideoInfo) {
        if (_inlinePlayerState.value is InlinePlayerState.Loading) return
        _inlinePlayerState.value = InlinePlayerState.Loading
        viewModelScope.launch {
            val playable = when (video.platform) {
                Platform.XIAOHONGSHU, Platform.KUAISHOU -> {
                    if (video.mediaUrl.isNotBlank()) {
                        video
                    } else {
                        parserRegistry.parse(video.sourceUrl).getOrNull()
                    }
                }
                Platform.DOUYIN, Platform.WEIBO -> {
                    parserRegistry.parse(video.sourceUrl).getOrNull()
                        ?: video.takeIf { it.mediaUrl.isNotBlank() }
                }
                else -> video
            }
            if (playable == null) {
                _inlinePlayerState.value = InlinePlayerState.Failed("该视频暂不支持站内播放，请重新解析")
                return@launch
            }
            streamFetcher.fetch(playable).fold(
                onSuccess = { _inlinePlayerState.value = InlinePlayerState.Ready(it) },
                onFailure = { _inlinePlayerState.value = InlinePlayerState.Failed(it.message ?: "视频加载失败，请重试") },
            )
        }
    }

    /** 下载当前解析成功的视频（无水印）到系统下载目录。下载中重复点击直接忽略。 */
    fun downloadVideo() {
        val info = (_uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_downloadState.value is VideoDownloadUiState.Working) return
        _downloadState.value = VideoDownloadUiState.Working(0f)
        viewModelScope.launch {
            videoDownloader.download(info) { p ->
                _downloadState.value = VideoDownloadUiState.Working(p)
            }.fold(
                onSuccess = {
                    _downloadState.value = VideoDownloadUiState.Done("已保存到下载/VideoNotes，可在文件管理器中查看")
                },
                onFailure = { e ->
                    _downloadState.value = VideoDownloadUiState.Failed(e.message ?: "视频下载失败")
                },
            )
        }
    }

    /** 下载提示消费后回到空闲（避免成功/失败文案常驻）。 */
    fun consumeDownloadState() {
        if (_downloadState.value !is VideoDownloadUiState.Working) {
            _downloadState.value = VideoDownloadUiState.Idle
        }
    }

    private val _generateState = MutableStateFlow<GenerateNoteUiState>(GenerateNoteUiState.Idle)
    val generateState: StateFlow<GenerateNoteUiState> = _generateState.asStateFlow()

    /**
     * 有效引擎是否已配置（本地 = 所选模型已下载；云端 = 三项齐全，谓词在 LlmEngineResolver）。
     * api_key 是同步阻塞读；初始化与每次 [refreshLlmConfigured] 时重算。
     */
    private val _llmConfigured = MutableStateFlow(false)
    val llmConfigured: StateFlow<Boolean> = _llmConfigured.asStateFlow()

    /**
     * 当前视频已生成的笔记 id（M5）：有值时元信息页显示"查看笔记"而非重新生成。
     * 从笔记详情页返回时由界面调用 [refreshExistingNote] 刷新。
     */
    private val _existingNoteId = MutableStateFlow<Long?>(null)
    val existingNoteId: StateFlow<Long?> = _existingNoteId.asStateFlow()

    private var lastVideoInfo: VideoInfo? = null

    // ---------- 全自动编排 ----------

    private val _pipelineState = MutableStateFlow<PipelineUiState>(PipelineUiState.Idle)
    val pipelineState: StateFlow<PipelineUiState> = _pipelineState.asStateFlow()

    /** 有效引擎（方案 B 单一口径）：工序单「去配置」按它选模型管理直达分区。 */
    val effectiveEngineType: StateFlow<LlmEngineType> = engineResolver.effectiveEngineType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmEngineType.LOCAL)

    /** 本轮编排启动时刻：工序单「最新日志」只展示此时刻之后的行（过滤上一轮残留）。 */
    private val _pipelineLogStartMillis = MutableStateFlow(0L)
    val pipelineLogStartMillis: StateFlow<Long> = _pipelineLogStartMillis.asStateFlow()

    /**
     * 管线启动时刻（持久化，见 [PipelineStartStore]）：工序单「已用时」的计算基准。
     * 与 [_pipelineLogStartMillis] 不同：这是任务的真实入队时刻，重进页面 / 进程重建不归零。
     */
    private val _pipelineStartMillis = MutableStateFlow<Long?>(null)
    val pipelineStartMillis: StateFlow<Long?> = _pipelineStartMillis.asStateFlow()

    // ---------- 无选择 / 选中未下载引导弹层（2026-10-24 合并包） ----------

    /** 引导弹层内容（null = 不展示）；v1 不做下载完成后自动续跑，续跑走 ON_RESUME 与步内动作。 */
    private val _modelGuide = MutableStateFlow<ModelGuide?>(null)
    val modelGuide: StateFlow<ModelGuide?> = _modelGuide.asStateFlow()

    /** 引导一键下载的移动网络确认步骤（null = 不展示）：需确认的待下载项。 */
    private val _guideMobileConfirm = MutableStateFlow<List<GuideDownloadTarget>?>(null)
    val guideMobileConfirm: StateFlow<List<GuideDownloadTarget>?> = _guideMobileConfirm.asStateFlow()

    /** 中文课程默认组合（与模型页 ModelCombos 中文课程一致）：通义千问 4B + 中文主力 Zipformer-CTC。 */
    private companion object {
        const val COMBO_LLM_ID = "qwen3-4b"
        const val COMBO_ASR_ID = "zipformer-ctc-zh-int8"
    }

    fun dismissModelGuide() {
        _modelGuide.value = null
    }

    fun dismissGuideMobileConfirm() {
        _guideMobileConfirm.value = null
    }

    /** 计算引导内容：指定侧中未就绪的侧；全部就绪返回 null。LLM 侧仅本地引擎有意义。 */
    private suspend fun buildGuide(llmSide: Boolean, asrSide: Boolean): ModelGuide? {
        var llmState: GuideSideState? = null
        var llmLine = ""
        if (llmSide && engineResolver.effectiveEngineTypeNow() == LlmEngineType.LOCAL) {
            val model = localLlmPreferences.currentModel()
            if (model == null) {
                llmState = GuideSideState.MISSING
                llmLine = CatalogStore.llmById(COMBO_LLM_ID)?.displayName ?: "通义千问 4B"
            } else if (!localLlmPreferences.isModelReadyVerified(model)) {
                llmState = GuideSideState.UNDOWNLOADED
                llmLine = model.displayName
            }
        }
        var asrState: GuideSideState? = null
        var asrLine = ""
        if (asrSide) {
            val selectedId = asrPreferences.selectedModelId.first()
            if (selectedId == null) {
                asrState = GuideSideState.MISSING
                asrLine = CatalogStore.asrById(COMBO_ASR_ID)?.displayName ?: "中文主力 Zipformer-CTC"
            } else if (!modelDownloadManager.isDownloaded(selectedId)) {
                asrState = GuideSideState.UNDOWNLOADED
                asrLine = AsrModelCatalog.get(selectedId).displayName
            }
        }
        if (llmState == null && asrState == null) return null
        val manualSection = when {
            llmState != null -> Routes.SECTION_LLM_LOCAL
            else -> Routes.SECTION_ASR
        }
        return ModelGuide(llmState, asrState, llmLine, asrLine, manualSection)
    }

    /**
     * 引导一键下载并选用：
     * - 未选侧：先设为中文课程组合（LLM→4B，ASR→中文主力）；
     * - 已选未下载侧：下载用户所选模型（不用组合顶替）；
     * - 已下载侧跳过；移动网络 + 大文件未确认时先弹确认步骤，确认后才开始下载。
     */
    fun guideDownloadAndSelect() {
        val guide = _modelGuide.value ?: return
        viewModelScope.launch {
            val targets = mutableListOf<GuideDownloadTarget>()
            if (guide.llm != null) {
                val targetId = if (guide.llm == GuideSideState.MISSING) {
                    localLlmPreferences.setModelId(COMBO_LLM_ID)
                    COMBO_LLM_ID
                } else {
                    localLlmPreferences.currentModel()?.id ?: COMBO_LLM_ID
                }
                val model = localLlmPreferences.resolveModel(targetId)
                if (model != null && !localLlmPreferences.isModelReadyVerified(model)) {
                    targets += GuideDownloadTarget(
                        id = targetId,
                        displayName = model.displayName,
                        sizeBytes = model.sizeBytes,
                        isLlm = true,
                    )
                }
            }
            if (guide.asr != null) {
                val targetId = if (guide.asr == GuideSideState.MISSING) {
                    asrPreferences.setSelectedModelId(COMBO_ASR_ID)
                    COMBO_ASR_ID
                } else {
                    asrPreferences.selectedModelId.first() ?: COMBO_ASR_ID
                }
                if (!modelDownloadManager.isDownloaded(targetId)) {
                    val model = AsrModelCatalog.get(targetId)
                    targets += GuideDownloadTarget(
                        id = targetId,
                        displayName = model.displayName,
                        sizeBytes = model.totalBytes,
                        isLlm = false,
                    )
                }
            }
            // 移动网络预检：需确认的项先弹确认步骤（模型页的确认框不在本页渲染），
            // 其余项直接开始下载；确认后 confirmGuideMobile 再启动需确认的项。
            val needConsent = targets.filter {
                !NetworkGate.isWifiConnected(appContext) &&
                    mobileConsent.needsConsent(it.id, it.sizeBytes)
            }
            val immediate = targets - needConsent.toSet()
            startGuideDownloads(immediate)
            if (needConsent.isNotEmpty()) {
                _guideMobileConfirm.value = needConsent
            } else {
                _modelGuide.value = null
            }
        }
    }

    /** 引导移动网络确认步骤「确认下载」：记下同意并开始下载需确认的项。 */
    fun confirmGuideMobile() {
        val targets = _guideMobileConfirm.value ?: return
        targets.forEach { mobileConsent.grant(it.id) }
        _guideMobileConfirm.value = null
        startGuideDownloads(targets)
        _modelGuide.value = null
    }

    private fun startGuideDownloads(targets: List<GuideDownloadTarget>) {
        targets.forEach { target ->
            if (target.isLlm) {
                localDownloadManager.startDownload(target.id)
            } else {
                modelDownloadManager.startDownload(target.id)
            }
        }
    }

    /** 工序单「下载模型」步动作：模型已就绪 → 续跑，否则弹引导。 */
    fun onStepDownloadModelAction() {
        viewModelScope.launch {
            val guide = buildGuide(llmSide = false, asrSide = true)
            if (guide == null) {
                resumePipelineIfBlocked()
            } else {
                _modelGuide.value = guide
            }
        }
    }

    /** 工序单「去配置」步动作（本地引擎）：模型已就绪 → 续跑，否则弹引导；云端仍由界面导航。 */
    fun onStepConfigureLlmAction() {
        viewModelScope.launch {
            val guide = buildGuide(llmSide = true, asrSide = false)
            if (guide == null) {
                resumePipelineIfBlocked()
            } else {
                _modelGuide.value = guide
            }
        }
    }

    /** 启动时刻存储的观察协程（按当前视频的 storageKey 订阅）。 */
    private var startStoreJob: Job? = null

    /** 生成 SUCCEEDED 的数据库校验协程：同一时间只保留一个，新的渲染会取代旧的。 */
    private var generateVerifyJob: Job? = null

    /** 最近一次渲染到的生成任务 id：校验落状态前用它确认未被新一轮任务取代。 */
    private var latestGenerateWorkId: UUID? = null

    /** 标记一轮新的编排/续跑开始（日志过滤的分界点）。 */
    private fun markPipelineRunStart() {
        _pipelineLogStartMillis.value = System.currentTimeMillis()
    }

    /** 编排守卫：同一视频只自动编排一次（重组 / 返回本页不重发）。 */
    private var pipelineKey: String? = null

    /** 编排协程：取消时整体作废，避免迟到状态继续推进。 */
    private var pipelineJob: Job? = null

    /** 用户主动取消标记：区分「取消」与任务自身失败的落点。 */
    @Volatile
    private var pipelineCancelRequested: Boolean = false

    /** 转写任务观察协程：跟随 WorkManager 的 WorkInfo 更新 UI。 */
    private var transcribeObserveJob: Job? = null

    /** 笔记生成任务观察协程。 */
    private var generateObserveJob: Job? = null

    init {
        if (url.isNotBlank()) {
            load()
        }
        // 管线启动时刻补记：只要观察到转写 / 生成处于进行中且该视频尚无记录就打点。
        // 新入队时由 startTranscribe / startGenerate 在入队点先行打点，这里主要兜底
        // 「重进页面时任务已在跑但记录缺失」（记录被清 / 跨进程）的情形——
        // 此时以首次观察时刻近似真实启动时刻。recordStartIfAbsent 幂等，不会覆盖已有记录。
        viewModelScope.launch {
            combine(_transcribeState, _generateState) { t, g -> t to g }
                .collect { (t, g) ->
                    val working = t is TranscribeUiState.Working ||
                        g is GenerateNoteUiState.Working
                    val key = lastVideoInfo?.storageKey()
                    if (working && key != null) {
                        pipelineStartStore.recordStartIfAbsent(key)
                    }
                }
        }
        // base_url / model / engine_type 变化时重算；api_key 的变化靠界面 ON_RESUME 调用 refreshLlmConfigured。
        viewModelScope.launch {
            combine(
                llmSettings.baseUrl,
                llmSettings.model,
                llmSettings.engineType,
            ) { baseUrl, model, engineType -> Triple(baseUrl, model, engineType) }
                .collect { refreshLlmConfigured() }
        }
    }

    /**
     * 重算 LLM 配置状态（按有效引擎口径，见 [isLlmConfiguredNow]）。
     *
     * API Key 是 EncryptedSharedPreferences 同步阻塞读；用户去设置页配好 Key 后返回本页，
     * 必须重算，否则"配好 LLM 回来还提示去配置"。界面在 ON_RESUME 时调用。
     */
    fun refreshLlmConfigured() {
        viewModelScope.launch {
            _llmConfigured.value = isLlmConfiguredNow()
        }
    }

    /** 错误页重试入口。 */
    fun retry() {
        load()
    }

    private fun load() {
        _uiState.value = MetadataUiState.Loading
        _subtitleState.value = SubtitleUiState.NotFetched()
        _transcribeState.value = TranscribeUiState.Idle
        _generateState.value = GenerateNoteUiState.Idle
        transcribeObserveJob?.cancel()
        generateObserveJob?.cancel()
        // 编排复位：重新解析（错误页重试）后允许重新自动编排
        pipelineJob?.cancel()
        pipelineKey = null
        pipelineCancelRequested = false
        _pipelineState.value = PipelineUiState.Idle
        viewModelScope.launch {
            parserRegistry.parse(url)
                .onSuccess {
                    lastVideoInfo = it
                    observePipelineStart(it)
                    // 落库：经导入页 / 分享进入的视频也要出现在知识库列表
                    //（insertOrIgnore 靠 storageKey 唯一索引防重复，重进不重置已有记录；
                    // 列表条目的处理进度由其自身的 WorkManager 观察推导）
                    runCatching { videoDao.insertOrIgnore(it.toEntity()) }
                    _uiState.value = MetadataUiState.Success(it)
                    refreshSubtitleFromCache(it)
                    refreshExistingNote()
                    // 重连可能正在运行的生成任务（用户离开后返回）
                    attachGenerateObserver(it)
                    // M6：重连可能存在的后台转写任务（进程被杀后返回也能看到进度）
                    attachTranscribeObserver(it)
                    // 全自动编排：解析成功即开跑（已有任务在跑时只观察不重发）
                    ensurePipeline(it)
                }
                .onFailure { _uiState.value = MetadataUiState.Error(it.message ?: "解析失败，请重试") }
        }
    }

    /** 订阅当前视频的持久化管线启动时刻（工序单「已用时」的基准）。 */
    private fun observePipelineStart(info: VideoInfo) {
        startStoreJob?.cancel()
        startStoreJob = viewModelScope.launch {
            pipelineStartStore.startMillis(info.storageKey()).collect {
                _pipelineStartMillis.value = it
            }
        }
    }

    /** 入队点打点：记录本轮管线的真实启动时刻（已有记录不覆盖）。 */
    private fun recordPipelineStart(info: VideoInfo) {
        viewModelScope.launch {
            pipelineStartStore.recordStartIfAbsent(info.storageKey())
        }
    }

    /** 终态清除启动时刻：下一轮处理重新打点，已用时不跨轮累计。 */
    private fun clearPipelineStart() {
        val key = lastVideoInfo?.storageKey() ?: return
        viewModelScope.launch {
            pipelineStartStore.clear(key)
        }
    }

    /** 字幕卡片"获取字幕"按钮与失败重试入口。 */
    fun fetchSubtitles() {
        val info = (uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_subtitleState.value is SubtitleUiState.Loading) return
        _subtitleState.value = SubtitleUiState.Loading()
        viewModelScope.launch {
            transcriptRepository.fetchAndStore(info)
                .onSuccess { result ->
                    _subtitleState.value = if (result.segments.isEmpty()) {
                        SubtitleUiState.NotFetched("该视频暂无官方字幕，可尝试下方的语音转写")
                    } else {
                        SubtitleUiState.Fetched(result)
                    }
                }
                .onFailure {
                    _subtitleState.value = SubtitleUiState.Failed(it.message ?: "字幕获取失败，请重试")
                }
        }
    }

    /** 元信息加载成功后，先看本地是否有缓存的字幕。 */
    private fun refreshSubtitleFromCache(info: VideoInfo) {
        viewModelScope.launch {
            val cached = transcriptRepository.getCached(info)
            if (cached.isNotEmpty()) {
                _subtitleState.value = SubtitleUiState.Fetched(
                    SubtitleFetchResult(
                        segments = cached,
                        source = cached.first().source,
                        languageLabel = "",
                    ),
                )
            }
        }
    }

    // ---------- 笔记入口（M5） ----------

    /** 查询当前视频是否已有笔记；从生成页/详情页返回时刷新。 */
    fun refreshExistingNote() {
        val info = lastVideoInfo ?: return
        viewModelScope.launch {
            _existingNoteId.value = runCatching {
                noteDao.getByVideoKey(info.storageKey())?.id
            }.getOrNull()
        }
    }

    /** 重命名笔记标题。 */
    fun renameNote(noteId: Long, newTitle: String) {
        viewModelScope.launch {
            runCatching { noteDao.updateTitle(noteId, newTitle) }
        }
    }

    /** 删除笔记。 */
    fun deleteNote(noteId: Long) {
        viewModelScope.launch {
            runCatching { noteDao.deleteById(noteId) }
            refreshExistingNote()
        }
    }

    // ---------- 语音转写（M3） ----------

    /** 字幕卡片"语音转写"按钮入口：模型未选或未下载 → 引导弹层，否则进二次确认。 */
    fun onTranscribeClick() {
        val info = (uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_transcribeState.value is TranscribeUiState.Working) return
        viewModelScope.launch {
            val guide = buildGuide(llmSide = false, asrSide = true)
            if (guide != null) {
                _modelGuide.value = guide
                return@launch
            }
            val model = AsrModelCatalog.get(asrPreferences.selectedModelId.first()!!)
            _transcribeState.value = TranscribeUiState.Confirm(buildConfirmInfo(info, model))
        }
    }

    /** 关闭二次确认对话框。 */
    fun dismissTranscribeConfirm() {
        if (_transcribeState.value is TranscribeUiState.Confirm) {
            _transcribeState.value = TranscribeUiState.Idle
        }
    }

    /** 确认对话框"开始转写"。 */
    fun confirmTranscribe() {
        val info = (uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_transcribeState.value !is TranscribeUiState.Confirm) return
        startTranscribe(info)
    }

    /** 转写失败后"重试"：跳过二次确认直接执行。 */
    fun retryTranscribe() {
        val info = (uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_transcribeState.value !is TranscribeUiState.Failed) return
        startTranscribe(info)
    }

    /** 转写中"取消"：取消 WorkManager 唯一任务。 */
    fun cancelTranscribe() {
        val info = lastVideoInfo ?: return
        longTaskScheduler.cancelTranscribe(info.storageKey())
    }

    /**
     * 入队转写长任务并观察进度（M6）。
     * 任务在 WorkManager 前台服务中执行，切后台 / 杀进程后由系统继续调度。
     */
    private fun startTranscribe(info: VideoInfo) {
        recordPipelineStart(info)
        _transcribeState.value = TranscribeUiState.Working("准备中…", -1f)
        // 用本次任务的 id 精确观察：unique-work 列表里可能残留旧任务，
        // 直接观察列表第一个会显示旧状态（多次重试后界面永远停在第一次失败上）。
        val workId = longTaskScheduler.enqueueTranscribe(info)
        attachTranscribeObserverById(workId, info)
    }

    /** 主动发起/重试场景：按任务 id 精确观察本次任务。 */
    private fun attachTranscribeObserverById(workId: UUID, info: VideoInfo) {
        transcribeObserveJob?.cancel()
        transcribeObserveJob = viewModelScope.launch {
            longTaskScheduler.observeTranscribeById(workId).collect { work ->
                val w = work ?: return@collect
                renderTranscribeWorkInfo(w, info)
            }
        }
    }

    /**
     * 把单个 WorkInfo 映射为界面状态。
     * - RUNNING/ENQUEUED：阶段文案 + 进度；
     * - SUCCEEDED：从 Room 刷新字幕卡片（Worker 已写入 ASR 分段）；
     * - FAILED：中文原因 + 重试；CANCELLED：回空闲。
     */
    private fun renderTranscribeWorkInfo(work: WorkInfo, info: VideoInfo) {
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                val stage = work.progress.getString(KEY_STAGE).orEmpty()
                if (stage.isNotBlank()) {
                    val progress = work.progress.getFloat(KEY_PROGRESS, -1f)
                    _transcribeState.value = TranscribeUiState.Working(stage, progress)
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                _transcribeState.value = TranscribeUiState.Idle
                refreshSubtitleFromCache(info)
            }
            WorkInfo.State.FAILED -> {
                // outputData 为空时（如重试耗尽）从 progress 读回暂存的原始错误
                val msg = work.outputData.getString(KEY_ERROR)
                    ?: work.progress.getString(KEY_ERROR)
                    ?: "请重试"
                _transcribeState.value = TranscribeUiState.Failed(msg)
            }
            WorkInfo.State.CANCELLED -> {
                _transcribeState.value = TranscribeUiState.Idle
            }
            else -> Unit
        }
    }

    /**
     * 观察转写任务的 WorkInfo，映射为界面状态（进程被杀后重连用）。
     * 列表里可能残留旧任务：优先取仍在活跃的（RUNNING/ENQUEUED），否则取最近一个已结束的，
     * 避免显示第一次失败的旧状态。
     */
    private fun attachTranscribeObserver(info: VideoInfo) {
        transcribeObserveJob?.cancel()
        transcribeObserveJob = viewModelScope.launch {
            longTaskScheduler.observeTranscribe(info.storageKey()).collect { infos ->
                val work = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.lastOrNull() ?: return@collect
                renderTranscribeWorkInfo(work, info)
            }
        }
    }

    private fun buildConfirmInfo(video: VideoInfo, model: AsrModel): TranscribeConfirmInfo {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val lowBattery = level in 1..19 && !charging
        val batteryText = when {
            level <= 0 -> "电量未知"
            charging -> "电量 $level%（充电中）"
            else -> "电量 $level%"
        }
        return TranscribeConfirmInfo(
            modelName = model.displayName,
            durationText = formatDuration(video.durationSec),
            batteryText = batteryText,
            lowBattery = lowBattery,
        )
    }

    // ---------- 笔记生成（M7a：后台任务 + 圆圈进度） ----------

    /**
     * 开始生成笔记：入队 WorkManager 长任务，本页 inline 显示圆圈进度。
     * 任务在前台服务中执行，用户可离开本页，回来后自动重连进度。
     */
    fun startGenerate() {
        val info = lastVideoInfo ?: return
        if (_generateState.value is GenerateNoteUiState.Working) return
        viewModelScope.launch {
            // 本地引擎未选或选中未下载 → 引导弹层，不入队（云端引擎不受此门影响）
            if (engineResolver.effectiveEngineTypeNow() == LlmEngineType.LOCAL) {
                buildGuide(llmSide = true, asrSide = false)?.let { guide ->
                    _modelGuide.value = guide
                    return@launch
                }
            }
            enqueueGenerate(info)
        }
    }

    /** startGenerate 的入队部分（本地门通过后与云端共用）。 */
    private fun enqueueGenerate(info: VideoInfo) {
        recordPipelineStart(info)
        _generateState.value = GenerateNoteUiState.Working("准备中…")
        viewModelScope.launch {
            // 入队前同步直查（与 syncGenerateWorkState 同款口径）：同 key 已有
            // RUNNING / ENQUEUED（退避待跑也是 ENQUEUED）的生成任务时只观察、
            // 不再 enqueue——scheduler 是 REPLACE，重复入队会打断在跑的任务，
            // 且反复重启会干扰熔断的死亡标记记账。终态旧任务不拦，正常新入队。
            val infos = runCatching {
                longTaskScheduler.observeGenerateNote(info.storageKey()).first()
            }.getOrDefault(emptyList())
            val active = infos.firstOrNull {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
            if (active != null) {
                attachGenerateObserver(info)
                return@launch
            }
            if (pipelineCancelRequested) {
                // 直查窗口内用户已取消：不把已取消的任务重新拉起
                _generateState.value = GenerateNoteUiState.Idle
                return@launch
            }
            val workId = longTaskScheduler.enqueueGenerateNote(info)
            attachGenerateObserverById(workId, info)
        }
    }

    /** 生成失败后重试。 */
    fun retryGenerate() {
        if (_generateState.value !is GenerateNoteUiState.Failed) return
        startGenerate()
    }

    /** 生成中取消：取消 WorkManager 任务。 */
    fun cancelGenerate() {
        val info = lastVideoInfo ?: return
        longTaskScheduler.cancelGenerateNote(info.storageKey())
    }

    /** 生成成功后，界面跳转到笔记详情前复位状态（避免返回本页时还显示成功态）。 */
    fun consumeGenerateSuccess() {
        if (_generateState.value is GenerateNoteUiState.Success) {
            _generateState.value = GenerateNoteUiState.Idle
            refreshExistingNote()
        }
    }

    /** 笔记是否真实存在于数据库（自动跳转前的最后一道校验，以数据库为准）。 */
    suspend fun noteExists(noteId: Long): Boolean =
        runCatching { noteDao.getById(noteId) }.getOrNull() != null

    /**
     * 跳转前校验发现 Success 指向的笔记已不存在（陈旧成功态）：清掉它，
     * 避免反复触发误跳；页面随后据 existingNoteId（数据库口径）落到完成视图。
     */
    fun clearStaleGenerateSuccess() {
        if (_generateState.value is GenerateNoteUiState.Success) {
            _generateState.value = GenerateNoteUiState.Idle
            refreshExistingNote()
        }
    }

    private fun attachGenerateObserverById(workId: UUID, info: VideoInfo) {
        generateObserveJob?.cancel()
        generateObserveJob = viewModelScope.launch {
            longTaskScheduler.observeGenerateNoteById(workId).collect { work ->
                val w = work ?: return@collect
                renderGenerateWorkInfo(w, info)
            }
        }
    }

    /**
     * 重连生成任务（返回本页 / 进程重建后）：优先取活跃任务，否则取最近一个已结束的。
     */
    private fun attachGenerateObserver(info: VideoInfo) {
        generateObserveJob?.cancel()
        generateObserveJob = viewModelScope.launch {
            longTaskScheduler.observeGenerateNote(info.storageKey()).collect { infos ->
                val work = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                } ?: infos.lastOrNull() ?: return@collect
                renderGenerateWorkInfo(work, info)
            }
        }
    }

    private fun renderGenerateWorkInfo(work: WorkInfo, info: VideoInfo) {
        latestGenerateWorkId = work.id
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                val stage = work.progress.getString(KEY_STAGE).orEmpty()
                _generateState.value = GenerateNoteUiState.Working(
                    stage.ifBlank { "正在生成…" },
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                // 不直接采信 outputData 的 noteId：WorkManager 会保留历史任务，
                // 旧 SUCCEEDED 的笔记可能已被删除。异步回查数据库后再落状态
                //（渲染路径保持同步、不阻塞观察者）。
                generateVerifyJob?.cancel()
                generateVerifyJob = viewModelScope.launch {
                    settleGenerateSuccess(work)
                }
            }
            WorkInfo.State.FAILED -> {
                val msg = work.outputData.getString(KEY_ERROR)
                    ?: work.progress.getString(KEY_ERROR)
                    ?: "生成失败，请重试"
                _generateState.value = GenerateNoteUiState.Failed(msg)
            }
            WorkInfo.State.CANCELLED -> {
                _generateState.value = GenerateNoteUiState.Idle
            }
            else -> Unit
        }
    }

    /**
     * 生成 SUCCEEDED 的数据库落定：以 noteDao 真实行决定 Success 是否成立。
     * - 任务声称的 noteId 在库中存在 → Success(真实 id)；
     * - 声称 id ≤ 0（任务异常）→ 沿用原失败文案；
     * - id 指向的行已不存在（历史任务的笔记被删）→ 不置 Success，回 Idle，
     *   让编排按「无笔记」继续正常流程，而不是拿死 id 自动跳详情页报错。
     * 落状态前再核对 [latestGenerateWorkId]，已被新一轮任务取代则放弃本次落定。
     */
    private suspend fun settleGenerateSuccess(work: WorkInfo) {
        val claimedId = work.outputData.getLong(KEY_NOTE_ID, -1L)
        val row = if (claimedId > 0) {
            runCatching { noteDao.getById(claimedId) }.getOrNull()
        } else {
            null
        }
        if (latestGenerateWorkId != work.id) return
        when {
            row != null -> {
                _generateState.value = GenerateNoteUiState.Success(row.id)
            }
            claimedId <= 0 -> {
                _generateState.value =
                    GenerateNoteUiState.Failed("生成完成但未取到笔记，请到笔记库查看")
            }
            else -> {
                _generateState.value = GenerateNoteUiState.Idle
            }
        }
        refreshExistingNote()
    }

    // ---------- 全自动编排（解析成功后顺序推进，无需逐个点击） ----------

    /**
     * 直接判定「有效引擎」是否已配置（编排用，不依赖 [_llmConfigured] 的异步刷新）。
     *
     * - 有效引擎为本地：所选本地模型已下载且完整性强校验通过（大小 + sha256）即算已配置。
     * - 有效引擎为云端：云端三项齐全（谓词在 [LlmEngineResolver] 单一定义；
     *   有效引擎能成为云端本身就意味着已配齐，这里再核一遍保持语义显式）。
     */
    private suspend fun isLlmConfiguredNow(): Boolean =
        when (engineResolver.effectiveEngineTypeNow()) {
            LlmEngineType.LOCAL -> {
                // currentModel() 可空：未选时直接判未配置（合并包空选中，不再静默兜底 QWEN3_4B）
                val model = localLlmPreferences.currentModel() ?: return false
                localLlmPreferences.isModelReadyVerified(model)
            }
            LlmEngineType.CLOUD -> engineResolver.isCloudConfiguredNow()
        }

    /** 编排入口：同一视频只跑一次；任务已在跑（重连）时各阶段只等待不重发。 */
    private fun ensurePipeline(info: VideoInfo) {
        val key = info.storageKey()
        if (pipelineKey == key) return
        pipelineKey = key
        pipelineCancelRequested = false
        markPipelineRunStart()
        pipelineJob = viewModelScope.launch {
            runPipeline(info)
        }
    }

    private suspend fun runPipeline(info: VideoInfo) {
        _pipelineState.value = PipelineUiState.Running
        // 已有笔记 → 直接完成态，不重跑
        val existing = runCatching {
            noteDao.getByVideoKey(info.storageKey())?.id
        }.getOrNull()
        if (existing != null) {
            _existingNoteId.value = existing
            _pipelineState.value = PipelineUiState.Done
            clearPipelineStart()
            return
        }
        // 文稿阶段：缓存 / 取字幕 / 转写，任一路径拿到文稿后进生成阶段
        if (awaitTranscriptReady(info)) {
            runGenerateStage(info)
        }
    }

    /** 当前是否已有可用文稿（字幕段非空）。 */
    private fun transcriptReady(): Boolean =
        (_subtitleState.value as? SubtitleUiState.Fetched)
            ?.result?.segments?.isNotEmpty() == true

    /**
     * 文稿阶段：返回 true 表示文稿就绪、可进生成阶段；
     * 返回 false 表示已停在失败 / 阻塞 / 取消落点（由步骤状态与 [pipelineState] 呈现）。
     */
    private suspend fun awaitTranscriptReady(info: VideoInfo): Boolean {
        // 1) 本地缓存文稿（load 的 refreshSubtitleFromCache 可能尚未落盘，先直读一次保证时序）
        val cached = runCatching { transcriptRepository.getCached(info) }
            .getOrDefault(emptyList())
        if (cached.isNotEmpty()) {
            _subtitleState.value = SubtitleUiState.Fetched(
                SubtitleFetchResult(
                    segments = cached,
                    source = cached.first().source,
                    languageLabel = "",
                ),
            )
            return true
        }
        if (transcriptReady()) return true
        // 2) 直查同步转写任务状态：观察者推送是异步的，新页面刚进来时状态可能还没推达，
        //    不同步就决策会误判「未开始」而走到 startTranscribe（REPLACE 会重启在跑的任务）
        syncTranscribeWorkState(info)
        if (transcriptReady()) return true
        // 3) 转写任务已在跑（重连）：只等结果，不重发
        if (_transcribeState.value is TranscribeUiState.Working) {
            return awaitTranscribeTerminal()
        }
        // 4) 已有转写失败态（重连到已失败任务）：停在失败步，等步内重试
        if (_transcribeState.value is TranscribeUiState.Failed) return false
        // 5) 自动获取官方字幕
        fetchSubtitles()
        _subtitleState.first {
            it is SubtitleUiState.Fetched ||
                it is SubtitleUiState.NotFetched ||
                it is SubtitleUiState.Failed
        }
        if (transcriptReady()) return true
        // 6) 无官方字幕（或该平台不支持取字幕）→ 自动转写
        return runTranscribeStage(info)
    }

    /**
     * 一次性直查转写任务的当前 WorkInfo 并同步到 UI 状态（重连竞态兜底）。
     * 取值口径与 [attachTranscribeObserver] 一致：优先活跃任务，否则最近一个。
     */
    private suspend fun syncTranscribeWorkState(info: VideoInfo) {
        val infos = runCatching {
            longTaskScheduler.observeTranscribe(info.storageKey()).first()
        }.getOrDefault(emptyList())
        val work = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: infos.lastOrNull() ?: return
        renderTranscribeWorkInfo(work, info)
    }

    /** 生成任务版的同上兜底（防 startGenerate 以 REPLACE 误重启在跑的生成）。 */
    private suspend fun syncGenerateWorkState(info: VideoInfo) {
        val infos = runCatching {
            longTaskScheduler.observeGenerateNote(info.storageKey()).first()
        }.getOrDefault(emptyList())
        val work = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: infos.lastOrNull() ?: return
        renderGenerateWorkInfo(work, info)
        if (work.state == WorkInfo.State.SUCCEEDED) {
            // SUCCEEDED 的落状态是异步数据库校验：等它落定，
            // 调用方（runGenerateStage）随后读到的才是可信状态
            generateVerifyJob?.join()
        }
    }

    /** 转写阶段：模型未选或未下载 → 引导弹层 + 阻塞停步；否则跳过确认弹窗直接入队并等结果。 */
    private suspend fun runTranscribeStage(info: VideoInfo): Boolean {
        _pipelineState.value = PipelineUiState.Running
        val guide = buildGuide(llmSide = false, asrSide = true)
        if (guide != null) {
            _modelGuide.value = guide
            _pipelineState.value = PipelineUiState.BlockedTranscribeModel
            clearPipelineStart()
            return false
        }
        // 全自动：不再弹二次确认（原 Confirm 环节退役），工序单全程可见可取消
        startTranscribe(info)
        return awaitTranscribeTerminal()
    }

    /** 等转写落点：文稿就绪 → true；转写失败 → false（失败步呈现 + 步内重试续跑）。 */
    private suspend fun awaitTranscribeTerminal(): Boolean {
        val outcome = combine(_transcribeState, _subtitleState) { t, s -> t to s }
            .first { (t, s) ->
                (s is SubtitleUiState.Fetched && s.result.segments.isNotEmpty()) ||
                    t is TranscribeUiState.Failed
            }
        val ready = (outcome.second as? SubtitleUiState.Fetched)
            ?.result?.segments?.isNotEmpty() == true
        if (!ready) {
            // 停在失败步：编排不再推进，步内重试成功后由续跑入口继续生成
            _pipelineState.value = PipelineUiState.Idle
            clearPipelineStart()
        }
        return ready
    }

    /**
     * 生成阶段：引擎按设置直接执行；未配置 → 阻塞停步；已在跑 → 只等结果。
     * [force] 为 true（完成态「重新生成」）时跳过「已有笔记直接完成」的短路。
     */
    private suspend fun runGenerateStage(info: VideoInfo, force: Boolean = false) {
        _pipelineState.value = PipelineUiState.Running
        if (!force) {
            // 完成短路以数据库为准：回查真实行，不信 existingNoteId 的缓存值，
            // 更不信 WorkManager 历史 SUCCEEDED（其笔记可能已被删除）
            val dbNoteId = runCatching {
                noteDao.getByVideoKey(info.storageKey())?.id
            }.getOrNull()
            if (dbNoteId != null) {
                _existingNoteId.value = dbNoteId
                _pipelineState.value = PipelineUiState.Done
                clearPipelineStart()
                return
            }
        }
        // 重连竞态兜底：先直查同步生成任务状态，下面的 when 守卫才确定成立
        //（含 SUCCEEDED 的数据库校验落定，见 syncGenerateWorkState）
        syncGenerateWorkState(info)
        when (_generateState.value) {
            is GenerateNoteUiState.Working -> {
                awaitGenerateTerminal()
                return
            }
            is GenerateNoteUiState.Success -> {
                // 此处的 Success 已由 settleGenerateSuccess 回查数据库落定，可信
                _pipelineState.value = PipelineUiState.Done
                clearPipelineStart()
                return
            }
            is GenerateNoteUiState.Failed -> {
                val failedMsg = (_generateState.value as GenerateNoteUiState.Failed).message
                when {
                    // 熔断失败但引擎已切到云端：旧的本地失败作废，
                    // 不落阻塞、继续往下走云端生成路径
                    failedMsg == GENERATE_CRASH_BREAKER_MESSAGE &&
                        engineResolver.effectiveEngineTypeNow() != LlmEngineType.LOCAL ->
                        Unit
                    // 熔断失败（本地连续原生崩溃）：进熔断阻塞态，
                    // 步内提供「手动重试 / 切换云端 API」，不作普通失败停步
                    failedMsg == GENERATE_CRASH_BREAKER_MESSAGE -> {
                        _pipelineState.value = PipelineUiState.BlockedGenerateCrashed
                        clearPipelineStart()
                        return
                    }
                    // 重连到已失败的生成任务：停在失败步，等步内重试
                    else -> {
                        _pipelineState.value = PipelineUiState.Idle
                        clearPipelineStart()
                        return
                    }
                }
            }
            else -> Unit
        }
        if (!isLlmConfiguredNow()) {
            // 本地引擎未配置 → 引导弹层（未选或已选未下载）+ 阻塞停步；云端仍走步内「去配置」
            if (engineResolver.effectiveEngineTypeNow() == LlmEngineType.LOCAL) {
                buildGuide(llmSide = true, asrSide = false)?.let { _modelGuide.value = it }
            }
            _pipelineState.value = PipelineUiState.BlockedGenerateConfig
            clearPipelineStart()
            return
        }
        startGenerate()
        awaitGenerateTerminal()
    }

    /** 等生成落点：成功 → Done（界面据 generateState.Success 自动跳详情）；失败 → 停步。 */
    private suspend fun awaitGenerateTerminal() {
        val state = _generateState.first {
            it is GenerateNoteUiState.Success || it is GenerateNoteUiState.Failed
        }
        _pipelineState.value = when {
            state is GenerateNoteUiState.Success -> PipelineUiState.Done
            // 本地生成熔断（Worker 守卫输出的固定文案）：进熔断阻塞态，
            // 步内给「手动重试 / 切换云端 API」，不当普通失败处理
            state is GenerateNoteUiState.Failed &&
                state.message == GENERATE_CRASH_BREAKER_MESSAGE ->
                PipelineUiState.BlockedGenerateCrashed
            else -> PipelineUiState.Idle
        }
        clearPipelineStart()
    }

    // ---------- 编排的用户动作（工序单按钮 → ViewModel） ----------

    /**
     * 工序单「取消任务」：作废编排并取消当前后台任务，落到已取消态，不自动重启。
     */
    fun cancelCurrentTask() {
        pipelineCancelRequested = true
        pipelineJob?.cancel()
        val info = lastVideoInfo
        if (info != null) {
            when {
                _generateState.value is GenerateNoteUiState.Working ->
                    longTaskScheduler.cancelGenerateNote(info.storageKey())
                _transcribeState.value is TranscribeUiState.Working ->
                    longTaskScheduler.cancelTranscribe(info.storageKey())
            }
        }
        _pipelineState.value = PipelineUiState.Cancelled
        clearPipelineStart()
    }

    /** 已取消态「重新开始」：重置守卫后重新编排（已完成的步会被跳过）。 */
    fun restartPipeline() {
        val info = lastVideoInfo ?: return
        pipelineKey = null
        ensurePipeline(info)
    }

    /** 完成态「重新生成」：只重跑生成阶段（配置失效时转阻塞态）。 */
    fun regenerate() {
        val info = lastVideoInfo ?: return
        pipelineCancelRequested = false
        markPipelineRunStart()
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            runGenerateStage(info, force = true)
        }
    }

    /** 工序单转写步「重试」：重试成功后自动续跑生成阶段。 */
    fun onStepRetryTranscribe() {
        val info = lastVideoInfo ?: return
        if (_transcribeState.value !is TranscribeUiState.Failed) return
        markPipelineRunStart()
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _pipelineState.value = PipelineUiState.Running
            retryTranscribe()
            if (awaitTranscribeTerminal()) {
                runGenerateStage(info)
            }
        }
    }

    /** 工序单生成步「重试」：重试后等待落点（成功由界面自动跳详情）。 */
    fun onStepRetryGenerate() {
        if (_generateState.value !is GenerateNoteUiState.Failed) return
        markPipelineRunStart()
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            _pipelineState.value = PipelineUiState.Running
            // 步内重试是用户的手动动作：先清零熔断计数与死亡标记
            // （人工重试永远允许，熔断只拦自动复活）
            lastVideoInfo?.let { crashGuard.reset(it.storageKey()) }
            retryGenerate()
            awaitGenerateTerminal()
        }
    }

    /**
     * 从模型管理 / 设置页返回时调用：若停在阻塞态，重新检查阻塞条件，
     * 已解除则从阻塞步续跑；仍阻塞则保持原态。
     */
    fun resumePipelineIfBlocked() {
        val info = lastVideoInfo ?: return
        when (_pipelineState.value) {
            PipelineUiState.BlockedTranscribeModel -> {
                markPipelineRunStart()
                pipelineJob?.cancel()
                pipelineJob = viewModelScope.launch {
                    if (runTranscribeStage(info)) {
                        runGenerateStage(info)
                    }
                }
            }
            PipelineUiState.BlockedGenerateConfig -> {
                markPipelineRunStart()
                pipelineJob?.cancel()
                pipelineJob = viewModelScope.launch {
                    runGenerateStage(info)
                }
            }
            PipelineUiState.BlockedGenerateCrashed -> {
                // 用户可能已在设置里切换引擎（或配好云端）：重跑生成阶段——
                // 引擎仍为本地且计数未清时 Worker 会再次熔断、回到本阻塞态；
                // 已切云端时 runGenerateStage 内的熔断失败分支会放行云端路径
                markPipelineRunStart()
                pipelineJob?.cancel()
                pipelineJob = viewModelScope.launch {
                    runGenerateStage(info)
                }
            }
            else -> Unit
        }
    }
}