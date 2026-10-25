package com.zola.videonotes.data.asr

import android.content.Context
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.catalog.mergeUserCatalog
import com.zola.videonotes.data.catalog.toAsrModel
import com.zola.videonotes.data.download.MobileDownloadConsent
import com.zola.videonotes.data.download.ModelFileDownloader
import com.zola.videonotes.data.download.NetworkGate
import com.zola.videonotes.data.download.Sha256
import com.zola.videonotes.di.ApplicationScope
import com.zola.videonotes.domain.asr.AsrModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 模型下载管理器（一期 WP4/WP6 改造）：
 *
 * - 单文件下载走通用内核 [ModelFileDownloader]（断点续传、取消同步 cancel Call）；
 * - 逐文件 sha256 强校验：下载后逐个验（内核内完成）、启动 refreshAll 复验
 *   （指纹缓存避免反复重算），不符删该文件 + 校验失败文案（与 LLM 侧同口径）；
 * - 「仅 Wi-Fi 下载」开关接回（读 [AsrPreferences.wifiOnly]，即设置页同一开关）：
 *   开关开 → 非 Wi-Fi 拦截（文案不变）；开关关 → 放行移动网络，
 *   单文件 > 500 MB 首次需一次性确认（[MobileDownloadConsent]，仅内存）。
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    private val downloader: ModelFileDownloader,
    private val prefs: AsrPreferences,
    private val consent: MobileDownloadConsent,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    /** 单个模型的下载状态。 */
    enum class DlStatus {
        /** 从未下载或已删除 */
        NOT_DOWNLOADED,

        /** 下载中 */
        DOWNLOADING,

        /** 已暂停（可续传） */
        PAUSED,

        /** 下载失败，error 为中文原因 */
        FAILED,

        /** 已下载完整 */
        COMPLETED,
    }

    data class ModelDlState(
        val modelId: String,
        val status: DlStatus,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val error: String? = null,
    ) {
        /** 0..1 的进度；totalBytes 未知时为 0。 */
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _states = MutableStateFlow<Map<String, ModelDlState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDlState>> = _states.asStateFlow()

    /** 等待「移动网络大文件下载」一次性确认的条目 id（UI 弹确认框用）。 */
    private val _pendingMobileConfirm = MutableStateFlow<Set<String>>(emptySet())
    val pendingMobileConfirm: StateFlow<Set<String>> = _pendingMobileConfirm.asStateFlow()

    /** 已验文件指纹缓存：绝对路径 → (长度, 修改时间)，避免每次启动全量重算哈希。 */
    private val verifiedFingerprints = ConcurrentHashMap<String, Pair<Long, Long>>()

    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        UserCatalog.init(context)
        // 启动时扫描本地已下载模型并逐文件强校验（IO 线程，不阻塞注入）
        appScope.launch { refreshAll() }
    }

    /** 生效的 ASR 模型（精选池 + 用户改动；已移除的不在列）。 */
    fun effectiveModels(): List<AsrModel> =
        mergeUserCatalog(CatalogStore.asrCurated(), UserCatalog.snapshot()).map { it.toAsrModel() }

    /**
     * 按 id 解析模型（用户改动优先 → 精选池；已移除或未知返回 null，
     * 不像 AsrModelCatalog.get 那样回退默认模型——回退会让自定义/已移除 id 静默跑默认模型）。
     */
    fun resolveModel(modelId: String): AsrModel? {
        val userState = UserCatalog.snapshot()
        if (modelId in userState.removedIds) return null
        return UserCatalog.entryFor(modelId)?.toAsrModel()
            ?: CatalogStore.asrById(modelId)?.toAsrModel()
    }

    /** 模型根目录：filesDir/models/<modelId>/。 */
    fun modelDir(modelId: String): File = File(File(context.filesDir, "models"), modelId)

    /** 同步完整性：全部文件存在且大小与清单真值精确一致（强校验见 refreshAll 的哈希复验）。 */
    fun isDownloaded(modelId: String): Boolean =
        resolveModel(modelId)?.let { isModelComplete(modelDir(modelId), it) } ?: false

    /** 开始（或继续）下载。重复调用正在下载的模型会被忽略。 */
    fun startDownload(modelId: String) {
        val model = resolveModel(modelId) ?: return
        if (jobs[modelId]?.isActive == true) return
        if (isDownloaded(modelId)) {
            updateState(modelId) {
                it.copy(status = DlStatus.COMPLETED, downloadedBytes = it.totalBytes, error = null)
            }
            return
        }
        jobs[modelId] = appScope.launch {
            try {
                // 网络门：非 Wi-Fi 时大文件首次下载需一次性确认（仅内存）；
                // 2026-10-24 合并包删除「仅 Wi-Fi」开关后，移动网络默认放行，不再拦截。
                if (!NetworkGate.isWifiConnected(context)) {
                    val biggest = model.files.maxOfOrNull { it.sizeBytes } ?: 0L
                    if (consent.needsConsent(modelId, biggest)) {
                        _pendingMobileConfirm.value = _pendingMobileConfirm.value + modelId
                        updateState(modelId) { it.copy(status = DlStatus.PAUSED, error = null) }
                        return@launch
                    }
                }
                updateState(modelId) { it.copy(status = DlStatus.DOWNLOADING, error = null) }
                downloadAllFiles(model) { done, total ->
                    updateState(modelId) { it.copy(downloadedBytes = done, totalBytes = total) }
                }
                val total = totalOf(modelId)
                updateState(modelId) {
                    it.copy(status = DlStatus.COMPLETED, downloadedBytes = total, totalBytes = total, error = null)
                }
            } catch (e: CancellationException) {
                updateState(modelId) { it.copy(status = DlStatus.PAUSED) }
                throw e
            } catch (e: Exception) {
                val msg = if (e is UnknownHostException) {
                    "无法解析下载服务器域名，请检查网络后重试"
                } else if (e is IOException) {
                    e.message ?: "网络错误，请检查网络后重试"
                } else {
                    "下载失败：${e.message ?: e.javaClass.simpleName}"
                }
                updateState(modelId) { it.copy(status = DlStatus.FAILED, error = msg) }
            } finally {
                jobs.remove(modelId)
            }
        }
    }

    /** 移动网络下载确认（UI 确认后调用）：记下同意并重新开始下载。 */
    fun confirmMobileDownload(modelId: String) {
        consent.grant(modelId)
        _pendingMobileConfirm.value = _pendingMobileConfirm.value - modelId
        startDownload(modelId)
    }

    /** 移动网络下载确认被取消：仅清待确认标记。 */
    fun dismissMobileConfirm(modelId: String) {
        _pendingMobileConfirm.value = _pendingMobileConfirm.value - modelId
    }

    /** 暂停下载（可续传）。任务不在运行时直接标记为 PAUSED。 */
    fun pauseDownload(modelId: String) {
        val job = jobs.remove(modelId)
        if (job?.isActive == true) {
            job.cancel()
            // 状态由协程的 CancellationException 分支置为 PAUSED
        } else {
            updateState(modelId) { it.copy(status = DlStatus.PAUSED) }
        }
    }

    /** 删除整个模型目录。 */
    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        pauseDownload(modelId)
        modelDir(modelId).deleteRecursively()
        verifiedFingerprints.keys.removeAll { it.contains(modelId) }
        updateState(modelId) {
            it.copy(
                status = DlStatus.NOT_DOWNLOADED,
                downloadedBytes = 0,
                totalBytes = totalOf(modelId),
                error = null,
            )
        }
    }

    private suspend fun refreshAll() = withContext(Dispatchers.IO) {
        for (model in effectiveModels()) {
            refreshOne(model)
        }
    }

    /** 用户改动后重算生效列表的下载态（进行中的下载不碰；恢复添加后找回已保留文件）。 */
    fun refreshUserModels() {
        appScope.launch { refreshUserModelsNow() }
    }

    /** 同上、可等待完成版：供手动“重新校验全部”按钮用（校验中禁用态 + 跑完给回执）。 */
    suspend fun refreshUserModelsNow() = withContext(Dispatchers.IO) {
        for (model in effectiveModels()) {
            if (jobs[model.id]?.isActive == true) continue
            refreshOne(model)
        }
    }

    /** 列表移除时丢弃该 id 的下载态（文件是否保留由调用方在此外决定）。 */
    fun forgetState(modelId: String) {
        jobs.remove(modelId)?.cancel()
        _states.value = _states.value - modelId
    }

    private fun refreshOne(model: AsrModel) {
        val dir = modelDir(model.id)
        var done = model.files.sumOf { File(dir, it.name).takeIf { f -> f.exists() }?.length() ?: 0L }
        var error: String? = null
        val status = when {
            !isModelComplete(dir, model) -> DlStatus.NOT_DOWNLOADED
            verifyAllFiles(dir, model) -> DlStatus.COMPLETED
            else -> {
                // 大小达标但 sha256 不符：删掉不符文件（与 LLM 侧同语义）
                deleteMismatchedFiles(dir, model)
                done = model.files.sumOf { File(dir, it.name).takeIf { f -> f.exists() }?.length() ?: 0L }
                error = "模型文件校验失败（SHA-256 不匹配），已删除，请重新下载"
                DlStatus.NOT_DOWNLOADED
            }
        }
        updateState(model.id) { ModelDlState(model.id, status, done, model.totalBytes, error) }
    }

    /** 逐文件哈希复验（指纹缓存命中直接通过）。须在 IO 线程调用。 */
    private fun verifyAllFiles(dir: File, model: AsrModel): Boolean =
        model.files.all { mf ->
            val f = File(dir, mf.name)
            val expected = mf.sha256 ?: return@all true
            val fp = f.length() to f.lastModified()
            if (verifiedFingerprints[f.absolutePath] == fp) return@all true
            val ok = Sha256.matches(f, expected)
            if (ok) verifiedFingerprints[f.absolutePath] = fp
            ok
        }

    private fun deleteMismatchedFiles(dir: File, model: AsrModel) {
        for (mf in model.files) {
            val expected = mf.sha256 ?: continue
            val f = File(dir, mf.name)
            if (f.exists() && !Sha256.matches(f, expected)) {
                f.delete()
                verifiedFingerprints.remove(f.absolutePath)
            }
        }
    }

    private suspend fun downloadAllFiles(
        model: AsrModel,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(model.id).apply { mkdirs() }
        val fileTotals = mutableMapOf<String, Long>()
        fun currentDone(): Long =
            model.files.sumOf { f ->
                val total = fileTotals[f.name] ?: f.sizeBytes
                File(dir, f.name).takeIf { it.exists() }?.length()?.coerceAtMost(total) ?: 0L
            }
        fun currentTotal(): Long =
            model.files.sumOf { fileTotals[it.name] ?: it.sizeBytes }

        for (file in model.files) {
            val dest = File(dir, file.name)
            downloader.download(
                url = file.url,
                dest = dest,
                expectedSize = file.sizeBytes,
                expectedSha256 = file.sha256,
            ) { done, total ->
                fileTotals[file.name] = total
                onProgress(currentDone(), currentTotal())
            }
            // 下载内核已验过哈希，这里登记指纹供 refreshAll 复用
            if (dest.exists()) {
                verifiedFingerprints[dest.absolutePath] = dest.length() to dest.lastModified()
            }
        }
    }

    private fun totalOf(modelId: String): Long = resolveModel(modelId)?.totalBytes ?: 0L

    private fun updateState(modelId: String, transform: (ModelDlState) -> ModelDlState) {
        val current = _states.value[modelId]
            ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId))
        _states.value = _states.value + (modelId to transform(current))
    }

    companion object {
        /**
         * 模型目录完整性判断（纯函数）：全部文件存在且大小与清单真值精确一致。
         * （WP6 前为「> 0」弱判定；哈希强校验在 refreshAll/下载后逐文件执行。）
         */
        fun isModelComplete(
            dir: File,
            model: com.zola.videonotes.domain.asr.AsrModel,
        ): Boolean = model.files.all { f ->
            val file = File(dir, f.name)
            file.exists() && file.length() == f.sizeBytes
        }
    }
}
