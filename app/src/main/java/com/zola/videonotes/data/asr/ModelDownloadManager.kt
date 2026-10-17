package com.zola.videonotes.data.asr

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zola.videonotes.di.ApplicationScope
import com.zola.videonotes.domain.asr.AsrModel
import com.zola.videonotes.domain.asr.AsrModelFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ASR 模型下载管理器：HuggingFace 直链下载到应用私有目录（随卸载清除）。
 *
 * - OkHttp 流式下载，支持 Range 断点续传；暂停 = 取消协程，下次继续从本地长度续传
 * - 进度通过 StateFlow 对外，UI 展示百分比/已下载字节
 * - 下载前检查 Wi-Fi（模型固定仅 Wi-Fi 下载）
 * - 协程取消会同步 cancel 底层的 OkHttp Call，避免卡在阻塞 read()
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
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

    /** 某个模型的下载状态流（UI 层订阅）。 */
    fun stateOf(modelId: String): Flow<ModelDlState> =
        states.map { it[modelId] ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId)) }

    private val jobs = ConcurrentHashMap<String, Job>()

    init {
        // 启动时扫描本地已下载的模型（IO 线程，不阻塞注入）
        appScope.launch { refreshAll() }
    }

    /** 模型根目录：filesDir/models/<modelId>/。 */
    fun modelDir(modelId: String): File = File(File(context.filesDir, "models"), modelId)

    /** 完整性判断：全部文件存在且大小 > 0。可在 JVM 单元测试中用伴生函数验证逻辑。 */
    fun isDownloaded(modelId: String): Boolean =
        isModelComplete(modelDir(modelId), AsrModelCatalog.get(modelId))

    /** 开始（或继续）下载。重复调用正在下载的模型会被忽略。 */
    fun startDownload(modelId: String) {
        val model = AsrModelCatalog.get(modelId)
        if (jobs[modelId]?.isActive == true) return
        if (isDownloaded(modelId)) {
            updateState(modelId) {
                it.copy(status = DlStatus.COMPLETED, downloadedBytes = it.totalBytes, error = null)
            }
            return
        }
        updateState(modelId) { it.copy(status = DlStatus.DOWNLOADING, error = null) }
        jobs[modelId] = appScope.launch {
            try {
                // 模型固定仅 Wi-Fi 下载（设置页开关已移除）
                if (!isWifiConnected()) {
                    throw IOException("当前为移动数据网络，模型仅支持 Wi-Fi 下载，请连接 Wi-Fi 后重试")
                }
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
                val msg = if (e is IOException) {
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
        for (model in AsrModelCatalog.models) {
            val dir = modelDir(model.id)
            val done = model.files.sumOf { File(dir, it.name).takeIf { f -> f.exists() }?.length() ?: 0L }
            val status = if (isModelComplete(dir, model)) DlStatus.COMPLETED else DlStatus.NOT_DOWNLOADED
            updateState(model.id) { ModelDlState(model.id, status, done, model.totalBytes) }
        }
    }

    private suspend fun downloadAllFiles(
        model: AsrModel,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dir = modelDir(model.id).apply { mkdirs() }
        // 各文件的实际总大小（从 HTTP 头解析，目录值仅为估算）
        val fileTotals = mutableMapOf<String, Long>()
        fun currentDone(): Long =
            model.files.sumOf { f ->
                val total = fileTotals[f.name] ?: f.sizeBytes
                File(dir, f.name).takeIf { it.exists() }?.length()?.coerceAtMost(total) ?: 0L
            }
        fun currentTotal(): Long =
            model.files.sumOf { fileTotals[it.name] ?: it.sizeBytes }

        for (file in model.files) {
            downloadOneFile(
                dir = dir,
                file = file,
                onFileProgress = { _, fileTotal ->
                    fileTotals[file.name] = fileTotal
                    onProgress(currentDone(), currentTotal())
                },
            )
        }
    }

    /**
     * 下载单个文件，支持 Range 断点续传。
     * 语义：本地已有部分 → 带 Range 请求；服务器回 206 续传、200 则重下、416 表示本地已完整。
     */
    private suspend fun downloadOneFile(
        dir: File,
        file: AsrModelFile,
        onFileProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val dest = File(dir, file.name)
        var existing = if (dest.exists()) dest.length() else 0L
        val request = Request.Builder()
            .url(file.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .get()
            .build()

        val response = okHttpClient.executeCancellable(request)
        response.use { resp ->
            val total: Long
            val append: Boolean
            when (resp.code) {
                416 -> {
                    // 本地文件已完整（Range 起点超出），无需下载
                    onFileProgress(existing, existing)
                    return
                }
                206 -> {
                    append = true
                    total = resp.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: (existing + (resp.body?.contentLength() ?: 0L))
                }
                200 -> {
                    if (existing > 0) {
                        // 服务器不支持断点续传，删掉重下
                        dest.delete()
                        existing = 0
                    }
                    append = false
                    total = resp.body?.contentLength()?.takeIf { it > 0 } ?: file.sizeBytes
                }
                else -> throw IOException("下载失败（HTTP ${resp.code}）")
            }
            val body = resp.body ?: throw IOException("下载内容为空")
            body.byteStream().use { input ->
                FileOutputStream(dest, append).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = existing
                    onFileProgress(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onFileProgress(done, total)
                    }
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun totalOf(modelId: String): Long = AsrModelCatalog.get(modelId).totalBytes

    private fun updateState(modelId: String, transform: (ModelDlState) -> ModelDlState) {
        val current = _states.value[modelId]
            ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId))
        _states.value = _states.value + (modelId to transform(current))
    }

    companion object {
        /**
         * 模型目录完整性判断（纯函数，可在 JVM 单元测试）。
         * 完成条件：全部文件存在且大小 > 0。
         */
        fun isModelComplete(
            dir: File,
            model: com.zola.videonotes.domain.asr.AsrModel,
        ): Boolean = model.files.all { f ->
            val file = File(dir, f.name)
            file.exists() && file.length() > 0
        }
    }
}
