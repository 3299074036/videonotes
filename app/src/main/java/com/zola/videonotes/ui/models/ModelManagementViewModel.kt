package com.zola.videonotes.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.asr.AsrModelCatalog
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.catalog.filesChanged
import com.zola.videonotes.data.catalog.mergeUserCatalog
import com.zola.videonotes.data.catalog.toAsrModel
import com.zola.videonotes.domain.asr.AsrModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模型管理页 ViewModel：ASR 模型列表状态、下载/暂停/删除/选择（合并包：未下载可先选，初次不默认勾选；
 * 第二轮修订：排序功能与实测功能整体下架，列表固定按推荐序排列）。 */
@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val prefs: AsrPreferences,
) : ViewModel() {

    /** 等待「移动网络大文件下载」一次性确认的条目 id。 */
    val pendingMobileConfirm: StateFlow<Set<String>> = downloadManager.pendingMobileConfirm

    /** 用途筛选：null = 全部，否则为 zh / en / dialect / light（仅内存态，不过持久化）。 */
    private val _useCaseFilter = MutableStateFlow<String?>(null)
    val useCaseFilter: StateFlow<String?> = _useCaseFilter.asStateFlow()

    fun setUseCaseFilter(filter: String?) {
        _useCaseFilter.value = filter
    }

    fun confirmMobile(modelId: String) = downloadManager.confirmMobileDownload(modelId)

    fun dismissMobile(modelId: String) = downloadManager.dismissMobileConfirm(modelId)

    /** 单个模型的展示数据（entry 为生效条目：精选原值/改动覆盖/自定义，标签与副行取它）。 */
    data class ModelItemUi(
        val model: AsrModel,
        val entry: CatalogEntry,
        val state: ModelDownloadManager.ModelDlState,
        val selected: Boolean,
    )

    /** 浏览态：单列（无分组），已按推荐→已下载→目录序排好、用途筛选后。 */
    data class AsrBrowseList(
        val ordered: List<ModelItemUi> = emptyList(),
    )

    /** 保存后待定位的条目 id（列表展开并滚动到它一次，界面消费后清空）。 */
    private val _highlightId = MutableStateFlow<String?>(null)
    val highlightId: StateFlow<String?> = _highlightId.asStateFlow()

    fun consumeHighlight() {
        _highlightId.value = null
    }

    val browseList: StateFlow<AsrBrowseList> = combine(
        downloadManager.states,
        prefs.selectedModelId,
        _useCaseFilter,
        UserCatalog.version,
    ) { states, selectedId, useCase, _ ->
        val effective = mergeUserCatalog(CatalogStore.asrCurated(), UserCatalog.snapshot())
        val all = effective.map { entry ->
            val model = entry.toAsrModel()
            ModelItemUi(
                model = model,
                entry = entry,
                state = states[model.id] ?: ModelDownloadManager.ModelDlState(
                    modelId = model.id,
                    status = ModelDownloadManager.DlStatus.NOT_DOWNLOADED,
                    downloadedBytes = 0,
                    totalBytes = model.totalBytes,
                ),
                selected = model.id == selectedId,
            )
        }
        // 单列：已下载（不过滤）＋未下载（用途筛选后），按推荐→已下载→目录序排。
        val downloaded = all.filter {
            it.state.status == ModelDownloadManager.DlStatus.COMPLETED
        }
        var pending = all.filter {
            it.state.status != ModelDownloadManager.DlStatus.COMPLETED
        }
        if (useCase != null) {
            pending = pending.filter { it.entry.useCases.contains(useCase) }
        }
        val catalogOrder = all.withIndex().associate { it.value.model.id to it.index }
        val ordered = (downloaded + pending).sortedWith(
            compareByDescending<ModelItemUi> { it.entry.recommended }
                .thenByDescending { it.state.status == ModelDownloadManager.DlStatus.COMPLETED }
                .thenBy { catalogOrder[it.model.id] ?: Int.MAX_VALUE },
        )
        AsrBrowseList(ordered = ordered)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AsrBrowseList())

    /** 旧 items 流（兼容：全量平铺，仅排序后；新界面改用 [browseList]）。 */
    val items: StateFlow<List<ModelItemUi>> = browseList
        .map { it.ordered }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 开始（或继续）下载。 */
    fun download(modelId: String) = downloadManager.startDownload(modelId)

    /** 暂停下载（可断点续传）。 */
    fun pause(modelId: String) = downloadManager.pauseDownload(modelId)

    /** 一次性用户提示（Toast 用）：删除回落说明。由界面层收集并展示。 */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** 手动“重新校验全部”进行中（按钮校验中禁用态用）。 */
    private val _revalidatingAsr = MutableStateFlow(false)
    val revalidatingAsr: StateFlow<Boolean> = _revalidatingAsr.asStateFlow()

    /**
     * 手动重新校验全部已下载识别模型：逐文件复验大小 + SHA256（与启动时
     * 全量复验同一套 refreshOne；指纹缓存命中时不重算哈希）。下载中的条目
     * 跳过。跑完按前后状态给回执：不符文件已被 refreshOne 删除并置错误，
     * 该行变回未下载；语言模型页的 X/Y 计数口径这里没有（入口本身不计数），
     * 故回执只报完好数与需重下数，不硬凑探针数。
     */
    fun revalidateAllAsr() {
        if (_revalidatingAsr.value) return
        viewModelScope.launch {
            _revalidatingAsr.value = true
            try {
                val before = downloadManager.states.value.values
                    .filter { it.status == ModelDownloadManager.DlStatus.COMPLETED }
                    .map { it.modelId }
                    .toSet()
                if (before.isEmpty()) {
                    _userMessage.tryEmit("没有可校验的已下载模型")
                    return@launch
                }
                downloadManager.refreshUserModelsNow()
                val after = downloadManager.states.value
                val failed = before.filter { id ->
                    after[id]?.status != ModelDownloadManager.DlStatus.COMPLETED
                }
                val ok = before.size - failed.size
                if (failed.isEmpty()) {
                    _userMessage.tryEmit("已重新校验 ${before.size} 个模型，文件全部完好")
                } else {
                    val deletedNote = if (failed.any { after[it]?.error != null }) "（不符文件已删除）" else ""
                    _userMessage.tryEmit("已重新校验：$ok 个完好，${failed.size} 个需重新下载$deletedNote")
                }
            } finally {
                _revalidatingAsr.value = false
            }
        }
    }

    /** 删除整个模型目录。删除已选模型时自动回落，不得选中悬空；无已下载模型时回落空选中。 */
    fun delete(modelId: String) {
        viewModelScope.launch {
            val wasSelected = items.value.firstOrNull { it.selected }?.model?.id == modelId
            val deletedName = AsrModelCatalog.models
                .firstOrNull { it.id == modelId }?.displayName ?: modelId
            downloadManager.deleteModel(modelId)
            if (wasSelected) {
                fallbackAfterRemoval(deletedName)
            }
        }
    }

    /** 删除/移除已选模型后的回落（与 delete 共用）。 */
    private suspend fun fallbackAfterRemoval(deletedName: String) {
        val downloadedIds = downloadManager.states.value.values
            .filter { it.status == ModelDownloadManager.DlStatus.COMPLETED }
            .map { it.modelId }
            .toSet()
        val orderedIds = AsrModelCatalog.models.map { it.id }
        val defaultId = AsrModelCatalog.DEFAULT_MODEL_ID
        val fallbackId: String? = when {
            defaultId in downloadedIds -> defaultId
            else -> orderedIds.firstOrNull { it in downloadedIds }
        }
        if (fallbackId != null) {
            prefs.setSelectedModelId(fallbackId)
            val fallbackName = AsrModelCatalog.models
                .firstOrNull { it.id == fallbackId }?.displayName ?: fallbackId
            _userMessage.tryEmit("已删除 $deletedName，已回落到 $fallbackName")
        } else {
            prefs.setSelectedModelId(null)
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
     * 修改条目：只改名称/描述/用途/内存不碰下载态；改了文件则清掉已下载。
     * 自定义条目原地更新；精选池条目走改动覆盖（改后去推荐）。
     */
    fun saveEditedEntry(old: CatalogEntry, new: CatalogEntry) {
        viewModelScope.launch {
            // 表单不改的 curated 属性沿用旧值（来源档位、下载量）。
            val preserved = new.copy(
                smallModelPrompt = old.smallModelPrompt,
                origin = old.origin,
                downloadCount = old.downloadCount,
            )
            if (filesChanged(old, preserved)) {
                downloadManager.deleteModel(old.id)
                downloadManager.forgetState(old.id)
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
            val wasSelected = items.value.firstOrNull { it.selected }?.model?.id == entry.id
            val removedName = entry.displayName
            if (deleteFiles) {
                downloadManager.deleteModel(entry.id)
            } else {
                downloadManager.pauseDownload(entry.id)
                downloadManager.forgetState(entry.id)
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
        downloadManager.refreshUserModels()
        _highlightId.value = id
    }

    /**
     * 选中模型（合并包：未下载也可勾选，选后不自动下载；识别侧无探针门/C7，
     * 选中即持久化，下载完成即生效）。
     */
    fun select(modelId: String) {
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
    }
}
