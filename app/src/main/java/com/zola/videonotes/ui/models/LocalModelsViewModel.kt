package com.zola.videonotes.ui.models

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogRepository
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.DynamicModelRegistry
import com.zola.videonotes.data.catalog.TemplateProbeState
import com.zola.videonotes.data.catalog.toLocalLlmModel
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 本地模型页 ViewModel（国内名单定版，2026-10-24 §17 改判；2026-10-24 合并包；
 * 2026-10-24 第二轮修订：排序功能与实测功能整体下架，C7 下架）：
 *
 * 一条列表 = 已下载（含使用中/影子/存量动态条目）→ 置顶推荐 → 更多模型；搜索框已下架。
 * HF 动态层已整体下线（浏览/搜索/文件层/流式判定全撤），
 * 已下载的存量动态条目仍由注册表枚举呈现，可选用/删除，仅不再有发现新模型的通路。
 * 选择机制（合并包，推翻 §20 先下后选）：未下载行也可勾选（选后不自动下载），
 * 探针门顺延至下载完成时（C7 首次确认已随实测功能下架）；初次不默认勾选（选择可空，见偏好迁移）。
 * 列表固定按推荐序排列，不再有排序维度。
 */
@HiltViewModel
class LocalModelsViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val registry: DynamicModelRegistry,
    private val llmPrefs: LocalLlmPreferences,
    private val dlManager: LocalModelDownloadManager,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        /** 置顶推荐 3 条（新人列表；已下载的自动落入已下载组，不重复出现）。 */
        val FEATURED_IDS = listOf("qwen3-4b", "qwen3-1_7b", "qwen3-8b")
    }

    /** 浏览态列表数据：已下载组 + 置顶推荐组 + 更多模型组（均已按推荐序排好）。 */
    data class BrowseList(
        val downloaded: List<CatalogEntry> = emptyList(),
        val featured: List<CatalogEntry> = emptyList(),
        val rest: List<CatalogEntry> = emptyList(),
        val downloadedCount: Int = 0,
        val downloadedBytes: Long = 0,
    )

    val dlStates: StateFlow<Map<String, LocalModelDownloadManager.ModelDlState>> = dlManager.states
    val pendingMobileConfirm: StateFlow<Set<String>> = dlManager.pendingMobileConfirm

    /** 当前选择，可空：null = 从未选择（合并包初次不默认勾选）。 */
    val selectedId: StateFlow<String?> = llmPrefs.modelId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 用途筛选：null = 全部，否则为 zh / en / dialect / light（仅内存态，不过持久化）。 */
    private val _useCaseFilter = MutableStateFlow<String?>(null)
    val useCaseFilter: StateFlow<String?> = _useCaseFilter.asStateFlow()

    fun setUseCaseFilter(filter: String?) {
        _useCaseFilter.value = filter
    }

    /**
     * 一次性用户提示（Toast 用）：探针拦截原因、删除回落说明。
     * 由界面层收集并展示，ViewModel 不直接碰 UI。
     */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** 整机总内存（GB），C6 禁用判定用。 */
    val deviceRamGB: Double by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.totalMem / (1024.0 * 1024 * 1024)
    }

    /** 注册表重扫触发器（下载/删除/探针后自增）。 */
    private val registryTick = MutableStateFlow(0)

    val browseList: StateFlow<BrowseList> = combine(
        dlStates, selectedId, registryTick, _useCaseFilter,
    ) { states, currentId, _, useCase ->
        buildBrowseList(states, currentId, useCase)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseList())

    private fun buildBrowseList(
        states: Map<String, LocalModelDownloadManager.ModelDlState>,
        currentId: String?,
        useCase: String?,
    ): BrowseList {
        val all = llmPrefs.resolveAllModels().map { m ->
            // LocalLlmModel → CatalogEntry 视图（UI 只需要条目形态）
            resolveEntry(m.id) ?: error("解析域条目缺失：${m.id}")
        }
        fun isCompleted(e: CatalogEntry) =
            states[e.id]?.status == LocalModelDownloadManager.DlStatus.COMPLETED
        val downloaded = all.filter { isCompleted(it) }
            .sortedWith(
                compareByDescending<CatalogEntry> { it.id == currentId }
                    .thenBy { it.source.ordinal }
                    .thenBy { it.displayName.lowercase() },
            )
        var pending = all.filter { it.source == CatalogSource.CURATED && !isCompleted(it) }
        if (useCase != null) {
            pending = pending.filter { it.useCases.contains(useCase) }
        }
        // 排序功能已下架：固定按推荐序排列（推荐在前，同组内保持目录序）。
        pending = pending.sortedByDescending { it.recommended }
        val featured = FEATURED_IDS.mapNotNull { id -> pending.firstOrNull { it.id == id } }
        val rest = pending.filter { it.id !in FEATURED_IDS }
        val completedStates = states.values
            .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
        return BrowseList(
            downloaded = downloaded,
            featured = featured,
            rest = rest,
            downloadedCount = completedStates.size,
            downloadedBytes = completedStates.sumOf { it.downloadedBytes },
        )
    }

    fun resolveEntry(id: String): CatalogEntry? =
        catalog.llmById(id) ?: registry.entryFor(id)

    /** 探针结论（动态已下载行与设探针门精选行展示用，指纹失效返回 null）。 */
    fun probeStateOf(entry: CatalogEntry): TemplateProbeState? = when {
        entry.source == CatalogSource.DYNAMIC -> registry.validProbe(entry)
        entry.requiresProbe -> llmPrefs.curatedProbeState(entry.toLocalLlmModel())
        else -> null
    }

    /** C6：条目内存需求超过整机内存 → 禁用下载。 */
    fun isRamBlocked(entry: CatalogEntry): Boolean {
        val need = entry.minRamGB ?: return false
        return deviceRamGB < need
    }

    // ---------- 选用（合并包：未下载可先选，探针门顺延至下载完成；C7 已随实测功能下架） ----------

    /** 已处理过的下载完成探针失败 id（防重复回落 Toast；选择变化时清空）。 */
    private val handledProbeFailures = mutableSetOf<String>()

    fun requestSelect(id: String) {
        val entry = resolveEntry(id) ?: return
        val completed = dlStates.value[id]?.status == LocalModelDownloadManager.DlStatus.COMPLETED
        if (!completed) {
            // 未下载：直接持久化选中，不自动开始下载；探针门顺延至下载完成时。
            handledProbeFailures.clear()
            viewModelScope.launch { llmPrefs.setModelId(id) }
            return
        }
        // 已下载：探针门（模板校验，不是评测，保留）——未通过的不可选，给原因不静默；
        // 通过的直接落选，不再有 C7 首次确认。
        if (entry.source == CatalogSource.DYNAMIC || entry.requiresProbe) {
            if (probeStateOf(entry) != TemplateProbeState.OK) {
                _userMessage.tryEmit("模板校验未通过，暂不可切换")
                return
            }
        }
        handledProbeFailures.clear()
        viewModelScope.launch { llmPrefs.setModelId(id) }
    }

    // ---------- 下载动作 ----------

    fun download(id: String) = dlManager.startDownload(id)
    fun pause(id: String) = dlManager.pauseDownload(id)

    fun delete(id: String) {
        viewModelScope.launch {
            val wasSelected = selectedId.value == id
            val deletedName = resolveEntry(id)?.displayName ?: id
            dlManager.deleteModel(id)
            registryTick.value++
            if (wasSelected) {
                // 删除已选模型不得选中悬空（合并包口径）：已下载的默认模型优先，
                // 否则第一个已下载模型；均无则回落空选中（条显「未选择」），不得自动勾回默认。
                val downloadedIds = dlManager.states.value.values
                    .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
                    .map { it.modelId }
                    .toSet()
                val orderedIds = llmPrefs.resolveAllModels().map { it.id }
                val fallbackId: String? = when {
                    LocalLlmModels.DEFAULT_ID in downloadedIds -> LocalLlmModels.DEFAULT_ID
                    else -> orderedIds.firstOrNull { it in downloadedIds }
                }
                if (fallbackId != null) {
                    llmPrefs.setModelId(fallbackId)
                    val fallbackName = resolveEntry(fallbackId)?.displayName ?: fallbackId
                    _userMessage.tryEmit("已删除 $deletedName，已回落到 $fallbackName")
                } else {
                    llmPrefs.clearModelId()
                    _userMessage.tryEmit("已删除 $deletedName，暂无已下载模型")
                }
            }
        }
    }

    fun confirmMobile(id: String) = dlManager.confirmMobileDownload(id)
    fun dismissMobile(id: String) = dlManager.dismissMobileConfirm(id)

    // 注意：init 必须位于全部属性声明之后（曾有 init 顺序读到未初始化 StateFlow 导致进页必崩）。
    init {
        // 探针在管理器内异步执行（动态/设门精选），完成后重扫注册表与探针存储以刷新结论展示
        viewModelScope.launch {
            dlStates.collect { states ->
                val anyProbedCompleted = states.any { (id, st) ->
                    st.status == LocalModelDownloadManager.DlStatus.COMPLETED &&
                        (id.startsWith("hf--") || resolveEntry(id)?.requiresProbe == true)
                }
                if (anyProbedCompleted) {
                    delay(3_000)
                    registryTick.value++
                    delay(9_000)
                    registryTick.value++
                }
            }
        }
        // 合并包顺延探针门（C7 已下架）：选中条目下载完成时补跑点选时跳过的探针门；
        // 探针通过即保留选中，不再弹确认。探针结论为 null 说明探针仍在跑，
        // 等 registryTick 刷新后再判定；失败只处理一次。
        viewModelScope.launch {
            combine(dlStates, selectedId, registryTick) { states, sel, _ -> states to sel }
                .collect { (states, sel) ->
                    if (sel == null) return@collect
                    val entry = resolveEntry(sel) ?: return@collect
                    val completed = states[sel]?.status ==
                        LocalModelDownloadManager.DlStatus.COMPLETED
                    if (!completed) return@collect
                    val needsGate = entry.source == CatalogSource.DYNAMIC || entry.requiresProbe
                    if (!needsGate) return@collect
                    when (val probe = probeStateOf(entry)) {
                        TemplateProbeState.OK -> Unit // 探针通过，保留选中
                        null -> Unit // 探针进行中，等下一次刷新
                        else -> {
                            if (sel !in handledProbeFailures) {
                                handledProbeFailures += sel
                                val downloadedIds = states.values
                                    .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
                                    .map { it.modelId }
                                    .toSet()
                                val fallbackId = when {
                                    LocalLlmModels.DEFAULT_ID in downloadedIds -> LocalLlmModels.DEFAULT_ID
                                    else -> llmPrefs.resolveAllModels().map { it.id }
                                        .firstOrNull { it in downloadedIds }
                                }
                                if (fallbackId != null) {
                                    llmPrefs.setModelId(fallbackId)
                                } else {
                                    llmPrefs.clearModelId()
                                }
                                _userMessage.tryEmit(
                                    "模板校验未通过，已取消选用 ${entry.displayName}",
                                )
                            }
                        }
                    }
                }
        }
    }
}
