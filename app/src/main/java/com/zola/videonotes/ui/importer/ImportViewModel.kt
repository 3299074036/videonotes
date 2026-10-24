package com.zola.videonotes.ui.importer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.VideoDao
import com.zola.videonotes.data.local.platformEnum
import com.zola.videonotes.data.work.KEY_STAGE
import com.zola.videonotes.data.work.LongTaskScheduler
import com.zola.videonotes.domain.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 最近导入条目（导入页底部列表，状态口径与知识库一致）。 */
data class RecentImport(
    val storageKey: String,
    val url: String,
    val title: String,
    val platform: Platform?,
    val coverUrl: String,
    val statusText: String,
)

/** 视频导入页 ViewModel：链接输入 + 最近导入（videos 表最近 5 条 + 笔记/任务状态）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ImportViewModel @Inject constructor(
    private val videoDao: VideoDao,
    private val noteDao: NoteDao,
    private val longTaskScheduler: LongTaskScheduler,
) : ViewModel() {

    var input by mutableStateOf("")
        private set

    fun onInputChange(value: String) {
        input = value
    }

    /** 从剪贴板粘贴后填入输入框。 */
    fun onPasted(text: String) {
        input = text
    }

    /** 最近导入：取 videos 表最新 5 条，关联笔记存在性与后台任务状态推导状态文案。 */
    val recentImports: StateFlow<List<RecentImport>> =
        combine(videoDao.observeAll(), noteDao.observeAll()) { videos, notes ->
            videos.take(5) to notes.associateBy { it.videoStorageKey }
        }.flatMapLatest { (videos, noteByKey) ->
            if (videos.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val perVideo: List<Flow<RecentImport>> = videos.map { v ->
                combine(
                    longTaskScheduler.observeTranscribe(v.storageKey),
                    longTaskScheduler.observeGenerateNote(v.storageKey),
                ) { tInfos, gInfos ->
                    val t = pickWork(tInfos)
                    val g = pickWork(gInfos)
                    val hasNote = noteByKey.containsKey(v.storageKey)
                    val status = when {
                        g?.state == WorkInfo.State.RUNNING ||
                            g?.state == WorkInfo.State.ENQUEUED ->
                            g.progress.getString(KEY_STAGE)?.takeIf { it.isNotBlank() } ?: "生成中"
                        t?.state == WorkInfo.State.RUNNING ||
                            t?.state == WorkInfo.State.ENQUEUED ->
                            t.progress.getString(KEY_STAGE)?.takeIf { it.isNotBlank() } ?: "处理中"
                        hasNote -> "笔记完成"
                        t?.state == WorkInfo.State.SUCCEEDED -> "已生成字幕"
                        else -> "已导入"
                    }
                    RecentImport(
                        storageKey = v.storageKey,
                        url = v.url,
                        title = v.title,
                        platform = v.platformEnum(),
                        coverUrl = v.coverUrl,
                        statusText = status,
                    )
                }
            }
            combine(perVideo) { it.toList() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 取活跃任务优先，否则取最近一个（与知识库同口径）。 */
    private fun pickWork(infos: List<WorkInfo>): WorkInfo? =
        infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: infos.lastOrNull()
}
