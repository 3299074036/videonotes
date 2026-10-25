package com.zola.videonotes.data.work

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---------- Worker inputData 键（JVM 测试覆盖键名稳定性） ----------

/** 视频存储键，格式 "${platform.name}:${videoId}" */
const val KEY_STORAGE_KEY = "storageKey"
const val KEY_PLATFORM = "platform"
const val KEY_VIDEO_ID = "videoId"
const val KEY_TITLE = "title"
const val KEY_SOURCE_URL = "sourceUrl"
const val KEY_COVER_URL = "coverUrl"
const val KEY_DURATION_SEC = "durationSec"
const val KEY_AUTHOR = "author"
const val KEY_MEDIA_URL = "mediaUrl"
const val KEY_MEDIA_HEADERS = "mediaHeaders"

// ---------- 进度 / 输出键 ----------

/** 进度：中文阶段文案 */
const val KEY_STAGE = "stage"

/** 进度：0f..1f；< 0 表示不确定进度 */
const val KEY_PROGRESS = "progress"

/** 转写成功输出：分段数 */
const val KEY_SEGMENT_COUNT = "segmentCount"

/** 笔记生成成功输出：笔记 id */
const val KEY_NOTE_ID = "noteId"

/** 失败输出：中文原因 */
const val KEY_ERROR = "error"

/** VideoInfo → Worker inputData。mediaHeaders 序列化为 JSON 字符串（Data 不支持 Map）。 */
fun VideoInfo.toInputData(): Data = workDataOf(
    KEY_STORAGE_KEY to storageKey(),
    KEY_PLATFORM to platform.name,
    KEY_VIDEO_ID to videoId,
    KEY_TITLE to title,
    KEY_SOURCE_URL to sourceUrl,
    KEY_COVER_URL to coverUrl,
    KEY_DURATION_SEC to durationSec,
    KEY_AUTHOR to author,
    KEY_MEDIA_URL to mediaUrl,
    KEY_MEDIA_HEADERS to JSONObject(mediaHeaders as Map<*, *>).toString(),
)

/** Worker inputData → VideoInfo；参数缺失/非法时返回 null。 */
fun Data.toVideoInfo(): VideoInfo? {
    val platform = getString(KEY_PLATFORM)
        ?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
        ?: return null
    val videoId = getString(KEY_VIDEO_ID)?.takeIf { it.isNotBlank() } ?: return null
    val mediaHeaders = getString(KEY_MEDIA_HEADERS)?.let { json ->
        runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key) }
            map
        }.getOrNull()
    }.orEmpty()
    return VideoInfo(
        platform = platform,
        videoId = videoId,
        title = getString(KEY_TITLE).orEmpty(),
        coverUrl = getString(KEY_COVER_URL).orEmpty(),
        durationSec = getLong(KEY_DURATION_SEC, 0),
        author = getString(KEY_AUTHOR).orEmpty(),
        sourceUrl = getString(KEY_SOURCE_URL).orEmpty(),
        mediaUrl = getString(KEY_MEDIA_URL).orEmpty(),
        mediaHeaders = mediaHeaders,
    )
}

/** 转写唯一任务名规则：transcribe_${storageKey}（顶层纯函数，JVM 可测）。 */
fun transcribeUniqueName(storageKey: String): String = "transcribe_$storageKey"

/** 笔记生成唯一任务名规则：generate_${storageKey}（顶层纯函数，JVM 可测）。 */
fun generateUniqueName(storageKey: String): String = "generate_$storageKey"

/**
 * 构造转写请求（顶层纯函数，JVM 可测）。
 *
 * 注意：不再用 setExpedited()。加急任务会让系统起前台服务并掐 10 秒表，
 * 10 秒内没调 startForeground() 就抛 ForegroundServiceDidNotStartInTimeException
 * 直接杀 App（2026-10-25 实测崩溃）。普通任务没有这个计时器，崩溃类直接消失；
 * 前台通知仍由 Worker 内的 safeSetForeground 尽力而为（失败只降级，不杀任务）。
 */
fun buildTranscribeRequest(video: VideoInfo): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<TranscribeWorker>()
        .setInputData(video.toInputData())
        .build()

/** 构造笔记生成请求（顶层纯函数，JVM 可测；不加急，原因同上）。 */
fun buildGenerateNoteRequest(video: VideoInfo): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<GenerateNoteWorker>()
        .setInputData(video.toInputData())
        .build()

/**
 * 长任务调度器（M6）：转写 / 笔记生成的统一入口。
 *
 * - 唯一任务：同一视频同时只跑一个任务，新任务 REPLACE 旧任务；
 * - 不加网络约束（用户可能用流量转写）；
 * - expedited 尽快执行，配额不足时降级为普通任务。
 */
@Singleton
class LongTaskScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    /**
     * 入队转写任务（替换同一视频的旧任务）。
     * @return 本次入队任务的 id，界面用它精确观察，避免读到旧任务的状态。
     */
    fun enqueueTranscribe(video: VideoInfo): UUID {
        val request = buildTranscribeRequest(video)
        WorkerDiagnosticLog.log(context, "Scheduler", "enqueue transcribe ${video.storageKey()} id=${request.id}")
        workManager.beginUniqueWork(
            transcribeUniqueName(video.storageKey()),
            ExistingWorkPolicy.REPLACE,
            request,
        ).enqueue()
        return request.id
    }

    /**
     * 入队笔记生成任务（替换同一视频的旧任务）。
     * @return 本次入队任务的 id，界面用它精确观察，避免读到旧任务的状态。
     */
    fun enqueueGenerateNote(video: VideoInfo): UUID {
        val request = buildGenerateNoteRequest(video)
        WorkerDiagnosticLog.log(context, "Scheduler", "enqueue generate ${video.storageKey()} id=${request.id}")
        workManager.beginUniqueWork(
            generateUniqueName(video.storageKey()),
            ExistingWorkPolicy.REPLACE,
            request,
        ).enqueue()
        return request.id
    }

    /**
     * 按任务 id 精确观察单个任务（主动发起/重试场景）。
     * 比观察整个 unique-work 列表更可靠：列表里可能残留旧任务，firstOrNull 会取到旧状态。
     */
    fun observeTranscribeById(id: UUID): Flow<WorkInfo?> =
        workManager.getWorkInfoByIdFlow(id)

    /** 按任务 id 精确观察单个笔记生成任务。 */
    fun observeGenerateNoteById(id: UUID): Flow<WorkInfo?> =
        workManager.getWorkInfoByIdFlow(id)

    /** 取消转写任务。 */
    fun cancelTranscribe(storageKey: String) {
        workManager.cancelUniqueWork(transcribeUniqueName(storageKey))
    }

    /** 取消笔记生成任务。 */
    fun cancelGenerateNote(storageKey: String) {
        workManager.cancelUniqueWork(generateUniqueName(storageKey))
    }

    /** 观察转写任务状态（含进度）。 */
    fun observeTranscribe(storageKey: String): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(transcribeUniqueName(storageKey))

    /** 观察笔记生成任务状态（含进度）。 */
    fun observeGenerateNote(storageKey: String): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(generateUniqueName(storageKey))
}
