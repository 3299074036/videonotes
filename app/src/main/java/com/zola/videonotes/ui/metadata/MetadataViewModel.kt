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
import com.zola.videonotes.data.llm.LlmCredentialStore
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmSettings
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.parser.ParserRegistry
import com.zola.videonotes.data.subtitle.TranscriptRepository
import com.zola.videonotes.data.work.KEY_ERROR
import com.zola.videonotes.data.work.KEY_NOTE_ID
import com.zola.videonotes.data.work.KEY_PROGRESS
import com.zola.videonotes.data.work.KEY_STAGE
import com.zola.videonotes.data.work.LongTaskScheduler
import com.zola.videonotes.domain.asr.AsrModel
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import com.zola.videonotes.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * 需要先下载模型：一次性事件，界面弹出 Snackbar 引导去模型管理页，
     * 消费后调 [MetadataViewModel.consumeTranscribeEvent] 复位。
     */
    data object NeedModel : TranscribeUiState

    /** 二次确认：展示耗时/流量/电量提示 */
    data class Confirm(val info: TranscribeConfirmInfo) : TranscribeUiState

    /** 转写中：progress < 0 表示不确定进度 */
    data class Working(val stageText: String, val progress: Float) : TranscribeUiState

    /** 转写失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : TranscribeUiState
}

/** 笔记生成 UI 状态（M7a：后台任务 + 圆圈进度，不再跳全屏页）。 */
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
    private val llmSettings: LlmSettings,
    private val credentialStore: LlmCredentialStore,
    private val localLlmPreferences: LocalLlmPreferences,
    private val noteDao: NoteDao,
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

    private val _generateState = MutableStateFlow<GenerateNoteUiState>(GenerateNoteUiState.Idle)
    val generateState: StateFlow<GenerateNoteUiState> = _generateState.asStateFlow()

    /**
     * LLM 是否已配置（base_url / model / api_key 均非空）。
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

    /** 转写任务观察协程：跟随 WorkManager 的 WorkInfo 更新 UI。 */
    private var transcribeObserveJob: Job? = null

    /** 笔记生成任务观察协程。 */
    private var generateObserveJob: Job? = null

    init {
        if (url.isNotBlank()) {
            load()
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
     * 重算 LLM 配置状态。
     *
     * - 云端模式：base_url / model / api_key 均非空才算已配置。
     * - 本地模式：所选模型已下载（文件存在且大小合理）即算已配置，无需 API Key。
     *
     * API Key 是 EncryptedSharedPreferences 同步阻塞读；用户去设置页配好 Key 后返回本页，
     * 必须重算，否则"配好 LLM 回来还提示去配置"。界面在 ON_RESUME 时调用。
     */
    fun refreshLlmConfigured() {
        viewModelScope.launch {
            val engineType = llmSettings.engineType.first()
            _llmConfigured.value = if (engineType == LlmEngineType.LOCAL) {
                val model = localLlmPreferences.currentModel()
                localLlmPreferences.isModelReady(model)
            } else {
                val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }
                llmSettings.baseUrl.first().isNotBlank() &&
                    llmSettings.model.first().isNotBlank() &&
                    !apiKey.isNullOrBlank()
            }
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
        viewModelScope.launch {
            parserRegistry.parse(url)
                .onSuccess {
                    lastVideoInfo = it
                    _uiState.value = MetadataUiState.Success(it)
                    refreshSubtitleFromCache(it)
                    refreshExistingNote()
                    // 重连可能正在运行的生成任务（用户离开后返回）
                    attachGenerateObserver(it)
                    // M6：重连可能存在的后台转写任务（进程被杀后返回也能看到进度）
                    attachTranscribeObserver(it)
                }
                .onFailure { _uiState.value = MetadataUiState.Error(it.message ?: "解析失败，请重试") }
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

    /** 字幕卡片"语音转写"按钮入口：检查模型是否已下载。 */
    fun onTranscribeClick() {
        val info = (uiState.value as? MetadataUiState.Success)?.info ?: return
        if (_transcribeState.value is TranscribeUiState.Working) return
        viewModelScope.launch {
            val model = AsrModelCatalog.get(asrPreferences.selectedModelId.first())
            if (!modelDownloadManager.isDownloaded(model.id)) {
                _transcribeState.value = TranscribeUiState.NeedModel
                return@launch
            }
            _transcribeState.value = TranscribeUiState.Confirm(buildConfirmInfo(info, model))
        }
    }

    /** 界面消费完 NeedModel 事件（Snackbar 已展示）后调用。 */
    fun consumeTranscribeEvent() {
        if (_transcribeState.value is TranscribeUiState.NeedModel) {
            _transcribeState.value = TranscribeUiState.Idle
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
        _generateState.value = GenerateNoteUiState.Working("准备中…")
        val workId = longTaskScheduler.enqueueGenerateNote(info)
        attachGenerateObserverById(workId, info)
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
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                val stage = work.progress.getString(KEY_STAGE).orEmpty()
                _generateState.value = GenerateNoteUiState.Working(
                    stage.ifBlank { "正在生成…" },
                )
            }
            WorkInfo.State.SUCCEEDED -> {
                val noteId = work.outputData.getLong(KEY_NOTE_ID, -1L)
                _generateState.value = if (noteId > 0) {
                    GenerateNoteUiState.Success(noteId)
                } else {
                    GenerateNoteUiState.Failed("生成完成但未取到笔记，请到笔记库查看")
                }
                refreshExistingNote()
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
}