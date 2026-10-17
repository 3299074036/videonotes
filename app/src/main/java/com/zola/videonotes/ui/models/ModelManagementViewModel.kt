package com.zola.videonotes.ui.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.asr.AsrModelCatalog
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.domain.asr.AsrModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 模型管理页 ViewModel：模型列表状态、下载/暂停/删除/选择（固定仅 Wi-Fi 下载）。 */
@HiltViewModel
class ModelManagementViewModel @Inject constructor(
    private val downloadManager: ModelDownloadManager,
    private val prefs: AsrPreferences,
) : ViewModel() {

    /** 单个模型的展示数据。 */
    data class ModelItemUi(
        val model: AsrModel,
        val state: ModelDownloadManager.ModelDlState,
        val selected: Boolean,
    )

    val items: StateFlow<List<ModelItemUi>> = combine(
        downloadManager.states,
        prefs.selectedModelId,
    ) { states, selectedId ->
        AsrModelCatalog.models.map { model ->
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 开始（或继续）下载。 */
    fun download(modelId: String) = downloadManager.startDownload(modelId)

    /** 暂停下载（可断点续传）。 */
    fun pause(modelId: String) = downloadManager.pauseDownload(modelId)

    /** 删除整个模型目录。 */
    fun delete(modelId: String) {
        viewModelScope.launch { downloadManager.deleteModel(modelId) }
    }

    /** 选中模型（未下载也可选中，转写时会提示先下载）。 */
    fun select(modelId: String) {
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
    }
}
