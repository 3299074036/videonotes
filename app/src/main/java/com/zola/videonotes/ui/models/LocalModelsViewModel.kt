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

    /** 正在手动校验的条目 id（单条与批量共用，UI 据此显“校验中…”并禁用重复点）。 */
    private val _probingIds = MutableStateFlow<Set<String>>(emptySet())
    val probingIds: StateFlow<Set<String>> = _probingIds.asStateFlow()

    /** 批量校验进行中（列表底部按钮据此显“校验中…”并禁用）。 */
    private val _revalidatingAll = MutableStateFlow(false)
    val revalidatingAll: StateFlow<Boolean> = _revalidatingAll.asStateFlow()

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

    /**
     * 探针结论（指纹失效返回 null）。老精选条目手动探针后也读无门结论
     * （未探过为 null，副行不显示）；选用门已扩到全部模型，requestSelect/init
     * 按模板类型用本函数结论判定（设门条目必须 OK，老精选只拦 LOAD_FAILED）。
     */
    fun probeStateOf(entry: CatalogEntry): TemplateProbeState? = when {
        entry.source == CatalogSource.DYNAMIC -> registry.validProbe(entry)
        entry.requiresProbe -> llmPrefs.curatedProbeState(entry.toLocalLlmModel())
        else -> llmPrefs.curatedProbeStateForDisplay(entry.toLocalLlmModel())
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
        // 已下载：探针门扩到全部模型（白名单取消）——按模板类型定通过标准：
        // 设门条目（动态/设门精选）必须 OK，否则不可选；
        // 老精选/影子（内置模板）：内嵌探针不过不等于不能用，只拦真加载失败；
        // 从未探过（null）：先去探，探完再选（ⓘ 已对全部已下载模型开放）。
        val probe = probeStateOf(entry)
        val gated = entry.source == CatalogSource.DYNAMIC || entry.requiresProbe
        when {
            gated && probe != TemplateProbeState.OK -> {
                _userMessage.tryEmit("模板校验未通过，暂不可切换")
                return
            }
            !gated && probe == TemplateProbeState.LOAD_FAILED -> {
                _userMessage.tryEmit("模型文件加载失败，暂不可切换，请重新下载或重新校验")
                return
            }
            !gated && probe == null -> {
                _userMessage.tryEmit("该模型尚未完成模板校验，请先点 ⓘ 重新校验")
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

    // ---------- L-30 手动重新校验（A：单条；B：全部） ----------

    /**
     * 探针入口是否对该条目有意义：已下载即有资格（扩围后，老精选也算）。
     * 老精选的手动探针结论供行副行展示与选用门判定（只拦 LOAD_FAILED，未探过先探）。
     */
    fun canRevalidate(entry: CatalogEntry): Boolean {
        return dlStates.value[entry.id]?.status == LocalModelDownloadManager.DlStatus.COMPLETED
    }

    /** 单条校验：跑完刷 registryTick，副行结论即时刷新（不等启动补探的延迟重扫）。 */
    fun revalidateProbe(id: String) {
        val entry = resolveEntry(id) ?: return
        if (!canRevalidate(entry) || id in _probingIds.value) return
        viewModelScope.launch {
            _probingIds.value += id
            try {
                dlManager.revalidateProbe(id)
            } finally {
                _probingIds.value -= id
                registryTick.value++
            }
            // 之前这里只刷副行、不发 Toast，点了等于没反馈（ASR 单条四分支全发提示）。
            // 探针结论跑完已落盘，直接读最新结论发提示；null = 没留下结论，报未完成。
            val name = entry.displayName
            val message = when (probeStateOf(entry)) {
                TemplateProbeState.OK -> "已重新校验 $name，模板校验通过"
                TemplateProbeState.NO_TEMPLATE -> "已重新校验 $name：无可用模板"
                TemplateProbeState.UNSUPPORTED -> "已重新校验 $name：模板暂不支持"
                TemplateProbeState.LOAD_FAILED -> "已重新校验 $name：模型加载失败，请重新下载"
                null -> "已重新校验 $name，校验未完成，请重试"
            }
            _userMessage.tryEmit(message)
        }
    }

    /** 全部校验：按列表顺序逐个跑（探针本身单线程，串行最稳），单个失败不中断其余。 */
    fun revalidateAll() {
        if (_revalidatingAll.value) return
        viewModelScope.launch {
            _revalidatingAll.value = true
            try {
                val targets = browseList.value.ordered.filter { canRevalidate(it) }
                // LLM 探针要加载模型文件跑模板，单模型也要数十秒；先给一句开始提示，
                // 否则等待期间看起来像没反应（结束提示本来就有，见下面）。
                if (targets.isNotEmpty()) {
                    _userMessage.tryEmit("正在校验已下载模型…")
                }
                var done = 0
                for (entry in targets) {
                    _probingIds.value += entry.id
                    try {
                        if (dlManager.revalidateProbe(entry.id)) done++
                    } finally {
                        _probingIds.value -= entry.id
                    }
                    registryTick.value++
                }
                if (targets.isEmpty()) {
                    _userMessage.tryEmit("没有可校验的已下载模型")
                } else {
                    _userMessage.tryEmit("已重新校验 $done/${targets.size} 个模型")
                }
            } finally {
                _revalidatingAll.value = false
                registryTick.value++
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
                    // 顺延探针门扩到全部模型：OK / 未探过（null）保留选中——null 对设门条目是
                    // 探针进行中，对老精选是祖父级已选（新选已由 requestSelect 把关），都不在此处回落；
                    // 真加载失败全部回落；设门条目其他未通过也回落，老精选内嵌探针不过保留（内置模板）。
                    val isGated = entry.source == CatalogSource.DYNAMIC || entry.requiresProbe
                    val probe = probeStateOf(entry)
                    val shouldFallback = when (probe) {
                        TemplateProbeState.OK, null -> false
                        TemplateProbeState.LOAD_FAILED -> true
                        else -> isGated
                    }
                    if (shouldFallback && sel !in handledProbeFailures) {
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
