package com.zola.videonotes.data.work

import android.content.Context
import androidx.work.BackoffPolicy
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.io.IOException
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

/**
 * M-12：长任务公共 tag。知识库一次观察全部长任务用（observeAllLongTasks），
 * 替代每视频 2 条 getWorkInfosForUniqueWorkFlow 的 2N 订阅。
 */
const val LONG_TASK_TAG = "videonotes-longtask"

/**
 * H-03：Worker 重试总次数上限（含首次执行）。runAttemptCount 从 0 起：
 * 首次（0）与第 1 次重试（1）失败且可重试时 Result.retry()，第 2 次重试（2）失败则
 * Result.failure 并保留 KEY_ERROR。此前无上限：持续性错误会长期反复重跑（整任务
 * 从头重来：转写重下音频、生成重跑 LLM），耗电耗费且界面长期停在“准备重试…”。
 */
const val MAX_RETRY_ATTEMPTS = 3

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
    KEY_MEDIA_HEADERS to Gson().toJson(mediaHeaders),
)

/** Worker inputData → VideoInfo；参数缺失/非法时返回 null。 */
fun Data.toVideoInfo(): VideoInfo? {
    val platform = getString(KEY_PLATFORM)
        ?.let { runCatching { Platform.valueOf(it) }.getOrNull() }
        ?: return null
    val videoId = getString(KEY_VIDEO_ID)?.takeIf { it.isNotBlank() } ?: return null
    val mediaHeaders = getString(KEY_MEDIA_HEADERS)?.let { json ->
        runCatching {
            // L-37：原 org.json 在本地 JVM 单测是 Android stub（not mocked）；改 Gson（纯 JVM），
            // 顶层纯函数恢复可测；Gson 可解析旧 JSONObject 产出的标准 JSON，存量任务兼容。
            val type = object : TypeToken<Map<String, String>>() {}.type
            Gson().fromJson<Map<String, String>>(json, type) ?: emptyMap()
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
        // M-12：公共 tag 供知识库单流观察全部长任务；单视频 tag 供内存 join 时按视频归属。
        .addTag(LONG_TASK_TAG)
        .addTag(transcribeUniqueName(video.storageKey()))
        // H-03：显式退避（指数退避、初始 30s）。此前无 setBackoffCriteria 时用默认退避
        // 且无次数上限（上限见各 Worker 的 runAttemptCount 门禁），持续性错误长期重跑。
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

/** 构造笔记生成请求（顶层纯函数，JVM 可测；不加急，原因同上）。 */
fun buildGenerateNoteRequest(video: VideoInfo): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<GenerateNoteWorker>()
        .setInputData(video.toInputData())
        // M-12：同转写，公共 tag + 单视频 tag。
        .addTag(LONG_TASK_TAG)
        .addTag(generateUniqueName(video.storageKey()))
        // H-03：同转写，显式退避。
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
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
     * L-19：入队串行锁。查活跃 + 入队必须原子，否则两路调用方同时查到空闲、先后
     * REPLACE 入队，后者打断前者刚起的任务（进度丢失、熔断记账干扰）。
     */
    private val enqueueMutex = Mutex()

    /**
     * L-18：inputData 字节估算（WorkManager Data 上限 10KB）。含 mediaHeaders JSON
     * （Cookie 可达 KB 级）、sourceUrl、标题；超限时 Data.Builder.build() 抛
     * IllegalStateException，入队前先估算，超限直接报失败（兜底 try-catch 仍在，
     * 估算与实际计量的小偏差不会漏网）。
     */
    private fun VideoInfo.estimatedInputDataBytes(): Int {
        var size = 0
        fun add(key: String, value: String) {
            size += key.toByteArray(Charsets.UTF_8).size + value.toByteArray(Charsets.UTF_8).size
        }
        add(KEY_STORAGE_KEY, storageKey())
        add(KEY_PLATFORM, platform.name)
        add(KEY_VIDEO_ID, videoId)
        add(KEY_TITLE, title)
        add(KEY_SOURCE_URL, sourceUrl)
        add(KEY_COVER_URL, coverUrl)
        add(KEY_AUTHOR, author)
        add(KEY_MEDIA_URL, mediaUrl)
        add(KEY_MEDIA_HEADERS, Gson().toJson(mediaHeaders))
        size += KEY_DURATION_SEC.toByteArray(Charsets.UTF_8).size + 8
        return size
    }

    /**
     * L-19：转写入队单点（替代调用方各自先查后入队）。
     * 同视频已有 RUNNING / ENQUEUED 任务时只返回在跑任务的 id（调用方观察它，不再入队）；
     * 否则新入队并返回新任务 id。
     * L-18：入队全程 try-catch，超限 / WorkManager 异常转 Result.failure，不抛到调用方崩溃。
     */
    suspend fun enqueueTranscribeIfIdle(video: VideoInfo): Result<UUID> =
        enqueueIfIdle(video, isGenerate = false)

    /**
     * L-19：笔记生成入队单点，语义同转写。
     * L-32：生成入队时若同视频转写在跑则拒绝入队（转写中途换文稿会让笔记版本错位）。
     * @param isCancelled 调用方取消标志（生成页的 pipelineCancelRequested）：在锁内复查，
     * 取消与入队竞速时两边都安全——先取消则此处跳过、先入队则取消方随后取消该任务，
     * 不会出现“取消后又被复活”。
     */
    suspend fun enqueueGenerateNoteIfIdle(
        video: VideoInfo,
        isCancelled: (() -> Boolean)? = null,
    ): Result<UUID> = enqueueIfIdle(video, isGenerate = true, isCancelled = isCancelled)

    private suspend fun enqueueIfIdle(
        video: VideoInfo,
        isGenerate: Boolean,
        isCancelled: (() -> Boolean)? = null,
    ): Result<UUID> {
        return try {
            enqueueMutex.withLock {
                val key = video.storageKey()
                val infos = if (isGenerate) {
                    observeGenerateNote(key).first()
                } else {
                    observeTranscribe(key).first()
                }
                val active = infos.firstOrNull {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                if (active != null) {
                    Result.success(active.id)
                } else if (isGenerate &&
                    observeTranscribe(key).first().any {
                        it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                    }
                ) {
                    // L-32：生成入队时同视频转写在跑 → 拒绝入队（L-19 只串行化同类
                    // 查 + 入队，不拦跨类并发）。生成读文稿是一次性快照，转写中途
                    // replaceAll 会让笔记与新文稿版本错位；拒绝比产出旧版笔记诚实，
                    // 用户等转写完成后再点生成即可（失败文案进 Failed 态，不崩溃）。
                    Result.failure(IOException("转写进行中，请等待转写完成后再生成笔记"))
                } else if (isCancelled?.invoke() == true) {
                    Result.failure(IOException("已取消"))
                } else if (video.estimatedInputDataBytes() > Data.MAX_DATA_BYTES) {
                    Result.failure(
                        IOException(
                            "任务参数过大（约 ${video.estimatedInputDataBytes() / 1024}KB，" +
                                "超过 10KB 上限），请缩短标题或重新粘贴链接后重试",
                        ),
                    )
                } else {
                    val id = if (isGenerate) {
                        enqueueGenerateNote(video)
                    } else {
                        enqueueTranscribe(video)
                    }
                    Result.success(id)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            WorkerDiagnosticLog.log(
                context,
                "Scheduler",
                "enqueue failed: ${t.javaClass.simpleName}: ${t.message}",
            )
            Result.failure(IOException("任务入队失败，请重试"))
        }
    }

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

    /**
     * M-12：一次观察全部长任务（按公共 tag）。此前知识库每视频订阅 2 条
     * getWorkInfosForUniqueWorkFlow，视频一多就是 2N 条观察流；单流 + 内存 join 替代。
     * 注意：升级前已入队的旧任务没有公共 tag，不会出现在本流里（重入队后恢复正常）；
     * 单视频精确观察（observeTranscribe/observeGenerateNote）不受影响，继续保留。
     */
    fun observeAllLongTasks(): Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(LONG_TASK_TAG)

    /** 观察笔记生成任务状态（含进度）。 */
    fun observeGenerateNote(storageKey: String): Flow<List<WorkInfo>> =
        workManager.getWorkInfosForUniqueWorkFlow(generateUniqueName(storageKey))
}
