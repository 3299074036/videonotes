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
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.catalog.filesChanged
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

    /** 浏览态列表数据：单列（无分组），已按推荐→已下载→目录序排好。 */
    data class BrowseList(
        val ordered: List<CatalogEntry> = emptyList(),
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

    /** 保存后待定位的条目 id（列表展开并滚动到它一次，界面消费后清空）。 */
    private val _highlightId = MutableStateFlow<String?>(null)
    val highlightId: StateFlow<String?> = _highlightId.asStateFlow()

    fun consumeHighlight() {
        _highlightId.value = null
    }

    /** 注册表重扫触发器（下载/删除/探针后自增）。 */
    private val registryTick = MutableStateFlow(0)

    val browseList: StateFlow<BrowseList> = combine(
        dlStates, selectedId, registryTick, _useCaseFilter, UserCatalog.version,
    ) { states, currentId, _, useCase, _ ->
        buildBrowseList(states, currentId, useCase)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseList())

    private fun buildBrowseList(
        states: Map<String, LocalModelDownloadManager.ModelDlState>,
        currentId: String?,
        useCase: String?,
    ): BrowseList {
        val all = llmPrefs.resolveAllModels().mapNotNull { m ->
            // 解析不到的跳过不崩（移除/改动竞态下快照可能不一致，旧口径 error 属 111 行隐患）。
            resolveEntry(m.id)
        }
        fun isCompleted(e: CatalogEntry) =
            states[e.id]?.status == LocalModelDownloadManager.DlStatus.COMPLETED
        // 单列：已下载（不过滤）＋未下载精选（用途筛选后），按推荐→已下载→目录序排。
        val downloaded = all.filter { isCompleted(it) }
        var pending = all.filter { it.source == CatalogSource.CURATED && !isCompleted(it) }
        if (useCase != null) {
            pending = pending.filter { it.useCases.contains(useCase) }
        }
        val catalogOrder = all.withIndex().associate { it.value.id to it.index }
        val ordered = (downloaded + pending).sortedWith(
            compareByDescending<CatalogEntry> { it.recommended }
                .thenByDescending { isCompleted(it) }
                .thenBy { catalogOrder[it.id] ?: Int.MAX_VALUE },
        )
        val completedStates = states.values
            .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
        return BrowseList(
            ordered = ordered,
            downloadedCount = completedStates.size,
            downloadedBytes = completedStates.sumOf { it.downloadedBytes },
        )
    }

    fun resolveEntry(id: String): CatalogEntry? {
        if (id in UserCatalog.snapshot().removedIds) return null
        return UserCatalog.entryFor(id) ?: catalog.llmById(id) ?: registry.entryFor(id)
    }

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
                fallbackAfterRemoval(deletedName)
            }
        }
    }

    /** 删除/移除已选模型后的回落（与 delete 共用）：已下载的默认模型优先，否则第一个已下载模型；均无则回落空选中。 */
    private suspend fun fallbackAfterRemoval(deletedName: String) {
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

    // ---------- 增删改（模型配置化） ----------

    /** 新增自定义条目（表单已校验）。 */
    fun addCustomEntry(entry: CatalogEntry) {
        UserCatalog.addCustom(entry)
        _highlightId.value = entry.id
    }

    /**
     * 修改条目：只改名称/描述/用途/内存不碰下载态；改了文件则清掉已下载
     * （删文件 + 丢下载态，下次下载走新文件）。
     * 自定义条目原地更新；精选池条目走改动覆盖（改后去推荐）。
     */
    fun saveEditedEntry(old: CatalogEntry, new: CatalogEntry) {
        viewModelScope.launch {
            // 表单不改的 curated 属性沿用旧值（小模型提示、来源档位、下载量）。
            val preserved = new.copy(
                smallModelPrompt = old.smallModelPrompt,
                origin = old.origin,
                downloadCount = old.downloadCount,
            )
            if (filesChanged(old, preserved)) {
                dlManager.deleteModel(old.id)
                dlManager.forgetState(old.id)
            }
            if (UserCatalog.isCustom(old.id)) {
                UserCatalog.updateCustom(preserved)
            } else {
                UserCatalog.saveEditedPoolEntry(preserved)
            }
            _highlightId.value = preserved.id
        }
    }

    /**
     * 从列表移除条目：下载中的先取消；文件按勾选删或保留
     * （保留时丢弃下载态，恢复添加后按磁盘重算，可找回）。
     * 移除已选模型时与删除文件同口径回落。
     */
    fun removeEntry(entry: CatalogEntry, deleteFiles: Boolean) {
        viewModelScope.launch {
            val wasSelected = selectedId.value == entry.id
            val removedName = entry.displayName
            if (deleteFiles) {
                dlManager.deleteModel(entry.id)
            } else {
                dlManager.pauseDownload(entry.id)
                dlManager.forgetState(entry.id)
            }
            UserCatalog.remove(entry.id)
            if (wasSelected) {
                fallbackAfterRemoval(removedName)
            }
        }
    }

    /** 从推荐池添加/恢复：清移除标记与改动覆盖，回到精选原值；已保留文件按磁盘重算态。 */
    fun restorePoolEntry(id: String) {
        UserCatalog.restore(id)
        dlManager.refreshModel(id)
        _highlightId.value = id
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
