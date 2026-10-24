package com.zola.videonotes.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.asr.AsrModelCatalog
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.catalog.CatalogStore
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

    companion object {
        /** 置顶推荐 3 条（新人列表；已下载的自动落入已下载组，不重复出现）。 */
        val FEATURED_IDS = listOf(
            "zipformer-ctc-zh-int8", "sensevoice-int8", "whisper-small-int8",
        )
    }

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

    /** 单个模型的展示数据。 */
    data class ModelItemUi(
        val model: AsrModel,
        val state: ModelDownloadManager.ModelDlState,
        val selected: Boolean,
    )

    /** 浏览态：已下载组 + 置顶推荐组 + 更多模型组（均已按推荐序排好、用途筛选后）。 */
    data class AsrBrowseList(
        val downloaded: List<ModelItemUi> = emptyList(),
        val featured: List<ModelItemUi> = emptyList(),
        val rest: List<ModelItemUi> = emptyList(),
    )

    val browseList: StateFlow<AsrBrowseList> = combine(
        downloadManager.states,
        prefs.selectedModelId,
        _useCaseFilter,
    ) { states, selectedId, useCase ->
        val all = AsrModelCatalog.models.map { model ->
            ModelItemUi(
                model = model,
                state = states[model.id] ?: ModelDownloadManager.ModelDlState(
                    modelId = model.id,
                    status = ModelDownloadManager.DlStatus.NOT_DOWNLOADED,
                    downloadedBytes = 0,
                    totalBytes = model.totalBytes,
                ),
                selected = model.id == selectedId,
            )
        }
        val downloaded = all.filter {
            it.state.status == ModelDownloadManager.DlStatus.COMPLETED
        }.sortedWith(
            compareByDescending<ModelItemUi> { it.selected }
                .thenBy { it.model.displayName.lowercase() },
        )
        var pending = all.filter {
            it.state.status != ModelDownloadManager.DlStatus.COMPLETED
        }
        if (useCase != null) {
            pending = pending.filter {
                CatalogStore.asrById(it.model.id)?.useCases?.contains(useCase) == true
            }
        }
        // 排序功能已下架：固定按推荐序排列（推荐在前，同组内保持目录序）。
        pending = pending.sortedByDescending {
            CatalogStore.asrById(it.model.id)?.recommended == true
        }
        val featured = FEATURED_IDS.mapNotNull { id -> pending.firstOrNull { it.model.id == id } }
        val rest = pending.filter { it.model.id !in FEATURED_IDS }
        AsrBrowseList(downloaded = downloaded, featured = featured, rest = rest)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AsrBrowseList())

    /** 旧 items 流（兼容：全量平铺，仅排序后；新界面改用 [browseList]）。 */
    val items: StateFlow<List<ModelItemUi>> = browseList
        .map { it.downloaded + it.featured + it.rest }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 开始（或继续）下载。 */
    fun download(modelId: String) = downloadManager.startDownload(modelId)

    /** 暂停下载（可断点续传）。 */
    fun pause(modelId: String) = downloadManager.pauseDownload(modelId)

    /** 一次性用户提示（Toast 用）：删除回落说明。由界面层收集并展示。 */
    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    /** 删除整个模型目录。删除已选模型时自动回落，不得选中悬空；无已下载模型时回落空选中。 */
    fun delete(modelId: String) {
        viewModelScope.launch {
            val wasSelected = items.value.firstOrNull { it.selected }?.model?.id == modelId
            val deletedName = AsrModelCatalog.models
                .firstOrNull { it.id == modelId }?.displayName ?: modelId
            downloadManager.deleteModel(modelId)
            if (wasSelected) {
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
        }
    }

    /**
     * 选中模型（合并包：未下载也可勾选，选后不自动下载；识别侧无探针门/C7，
     * 选中即持久化，下载完成即生效）。
     */
    fun select(modelId: String) {
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
    }
}
