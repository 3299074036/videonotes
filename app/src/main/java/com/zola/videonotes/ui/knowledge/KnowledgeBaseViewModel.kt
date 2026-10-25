package com.zola.videonotes.ui.knowledge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.zola.videonotes.data.knowledge.KnowledgeFilterPrefs
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.VideoDao
import com.zola.videonotes.data.local.platformEnum
import com.zola.videonotes.data.local.toEntity
import com.zola.videonotes.data.notes.NoteRepository
import com.zola.videonotes.data.parser.ParserRegistry
import com.zola.videonotes.data.subtitle.TranscriptRepository
import com.zola.videonotes.data.work.KEY_PROGRESS
import com.zola.videonotes.data.work.KEY_STAGE
import com.zola.videonotes.data.work.LongTaskScheduler
import com.zola.videonotes.domain.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 知识库条目：一条视频及其笔记/任务状态的聚合。 */
data class KnowledgeEntry(
    val storageKey: String,
    /** 库里存的原始链接（新解析的含小红书 xsec_token；无笔记时进详情靠它重解析）。 */
    val url: String,
    val title: String,
    val platform: Platform?,
    val durationSec: Long,
    val author: String,
    val coverUrl: String,
    val noteId: Long?,
    val noteTitle: String,
    val createdAt: Long,
    val hasTranscript: Boolean,
    val transcribeState: WorkInfo.State?,
    val transcribeStage: String,
    val transcribeProgress: Float,
    val generateState: WorkInfo.State?,
    val generateStage: String,
    val generateProgress: Float,
) {
    val isTranscribing: Boolean
        get() = transcribeState == WorkInfo.State.RUNNING || transcribeState == WorkInfo.State.ENQUEUED
    val isGenerating: Boolean
        get() = generateState == WorkInfo.State.RUNNING || generateState == WorkInfo.State.ENQUEUED
    /** 卡片右上角进度环：生成优先，否则转写；无任务时 null。 */
    val ringProgress: Float?
        get() = when {
            isGenerating && generateProgress >= 0 -> generateProgress
            isTranscribing && transcribeProgress >= 0 -> transcribeProgress
            // 进度未知时不显示假的 0%，只留状态 pill（生成中的字数在 generateStage 里）
            else -> null
        }
}

/** 筛选 chips：平台分组（设计稿：全部 + 6 个平台，选某平台只显示该平台条目）。 */
enum class KnowledgeFilter(val platform: Platform?) {
    ALL(null),
    BILIBILI(Platform.BILIBILI),
    YOUTUBE(Platform.YOUTUBE),
    DOUYIN(Platform.DOUYIN),
    XIAOHONGSHU(Platform.XIAOHONGSHU),
    KUAISHOU(Platform.KUAISHOU),
    WEIBO(Platform.WEIBO),
}

/**
 * 知识库 ViewModel：以 VideoDao 为条目来源（解析成功即落库，新增后列表立刻出现），
 * 每条再关联 NoteDao 的笔记与 WorkManager 任务状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KnowledgeBaseViewModel @Inject constructor(
    private val videoDao: VideoDao,
    private val noteDao: NoteDao,
    private val noteRepository: NoteRepository,
    private val transcriptRepository: TranscriptRepository,
    private val parserRegistry: ParserRegistry,
    private val longTaskScheduler: LongTaskScheduler,
    private val filterPrefs: KnowledgeFilterPrefs,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(KnowledgeFilter.ALL)
    val filter: StateFlow<KnowledgeFilter> = _filter

    fun onQueryChange(q: String) { _query.value = q }
    fun onFilterChange(f: KnowledgeFilter) { _filter.value = f }

    /**
     * 筛选 chip 顺序（持久化）：已存顺序在前，未出现的新平台按默认顺序补在末尾；
     * “全部”恒为第一位，不在该列表里。
     */
    val filterOrder: StateFlow<List<KnowledgeFilter>> = filterPrefs.platformOrder
        .map { saved ->
            val ordered = saved.mapNotNull { p ->
                KnowledgeFilter.entries.firstOrNull { it.platform == p }
            }
            val missing = KnowledgeFilter.entries.filter {
                it.platform != null && it !in ordered
            }
            (ordered + missing).distinct()
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            KnowledgeFilter.entries.filter { it.platform != null },
        )

    /** 拖动排序结束时持久化当前顺序（拖动过程中不写，只在松手时写一次）。 */
    fun saveFilterOrder(order: List<KnowledgeFilter>) {
        viewModelScope.launch {
            // 写操作不可取消：松手后立即切后台/清 ViewModel 也不应丢写。
            withContext(NonCancellable) {
                filterPrefs.savePlatformOrder(order.mapNotNull { it.platform })
            }
        }
    }

    /** 全部视频 → 条目（含笔记关联与每个视频的任务状态）。 */
    private val allEntries: Flow<List<KnowledgeEntry>> =
        combine(videoDao.observeAll(), noteDao.observeAll()) { videos, notes ->
            videos to notes.associateBy { it.videoStorageKey }
        }.flatMapLatest { (videos, noteByKey) ->
            if (videos.isEmpty()) return@flatMapLatest flowOf(emptyList())
            val perVideo: List<Flow<KnowledgeEntry>> = videos.map { v ->
                val key = v.storageKey
                combine(
                    longTaskScheduler.observeTranscribe(key),
                    longTaskScheduler.observeGenerateNote(key),
                ) { tInfos, gInfos ->
                    val t = pickWork(tInfos)
                    val g = pickWork(gInfos)
                    val n = noteByKey[key]
                    KnowledgeEntry(
                        storageKey = key,
                        url = v.url,
                        title = v.title,
                        platform = v.platformEnum(),
                        durationSec = v.durationSec,
                        author = v.author,
                        coverUrl = v.coverUrl,
                        noteId = n?.id,
                        noteTitle = n?.title.orEmpty(),
                        createdAt = v.createdAt,
                        // 有笔记即有文稿；否则看是否有转写任务在跑/跑完（简化：成功态的任务视为有文稿）
                        hasTranscript = n != null ||
                            t?.state == WorkInfo.State.SUCCEEDED,
                        transcribeState = t?.state,
                        transcribeStage = t?.progress?.getString(KEY_STAGE).orEmpty(),
                        transcribeProgress = t?.progress?.getFloat(KEY_PROGRESS, -1f) ?: -1f,
                        generateState = g?.state,
                        generateStage = g?.progress?.getString(KEY_STAGE).orEmpty(),
                        generateProgress = g?.progress?.getFloat(KEY_PROGRESS, -1f) ?: -1f,
                    )
                }
            }
            combine(perVideo) { it.toList().sortedByDescending { e -> e.createdAt } }
        }

    /** 条目总数（页头「共 N 篇笔记」用，不受搜索 / 筛选影响）。 */
    val totalCount: StateFlow<Int> = allEntries
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** 搜索 + 筛选后的条目。 */
    val entries: StateFlow<List<KnowledgeEntry>> = combine(
        allEntries, _query, _filter,
    ) { list, q, f ->
        list.filter { e ->
            val matchQuery = q.isBlank() ||
                e.title.contains(q, ignoreCase = true) ||
                e.author.contains(q, ignoreCase = true) ||
                e.noteTitle.contains(q, ignoreCase = true)
            val matchFilter = f.platform == null || e.platform == f.platform
            matchQuery && matchFilter
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 新增视频：解析链接，成功则落库（列表立刻出现），失败返回原因。
     * 调用方成功后可再导航到详情页。
     */
    suspend fun addVideo(url: String): Result<Unit> {
        val info = parserRegistry.parse(url).getOrElse {
            return Result.failure(it)
        }
        videoDao.insertOrIgnore(info.toEntity())
        return Result.success(Unit)
    }

    /** 重命名视频标题（改 VideoEntity）。 */
    suspend fun renameVideo(storageKey: String, newTitle: String) {
        videoDao.updateTitle(storageKey, newTitle)
    }

    /**
     * 删除条目：删 VideoEntity + 级联删关联笔记 + 清字幕/转写缓存 + 取消后台任务。
     */
    suspend fun deleteVideo(storageKey: String) {
        val note = noteDao.getByVideoKey(storageKey)
        if (note != null) {
            noteRepository.delete(note.id)
        }
        transcriptRepository.clearByStorageKey(storageKey)
        videoDao.deleteByStorageKey(storageKey)
        longTaskScheduler.cancelTranscribe(storageKey)
        longTaskScheduler.cancelGenerateNote(storageKey)
    }

    /** 取活跃任务优先，否则取最近一个，避免显示陈旧状态。 */
    private fun pickWork(infos: List<WorkInfo>): WorkInfo? =
        infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: infos.lastOrNull()
}
