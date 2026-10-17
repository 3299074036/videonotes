package com.zola.videonotes.data.llm.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.zola.videonotes.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 本地 LLM 模型下载管理器（M7a）。
 *
 * 复用 ASR [com.zola.videonotes.data.asr.ModelDownloadManager] 的模式：
 * 单个 GGUF 文件，OkHttp 流式下载 + Range 断点续传，
 * 进度经 StateFlow 对外，下载前检查 Wi-Fi（复用 ASR 的「仅 Wi-Fi」开关语义，
 * 此处独立实现，避免跨模块依赖）。
 */
@Singleton
class LocalModelDownloadManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val prefs: LocalLlmPreferences,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {

    enum class DlStatus {
        NOT_DOWNLOADED,
        DOWNLOADING,
        PAUSED,
        FAILED,
        COMPLETED,
    }

    data class ModelDlState(
        val modelId: String,
        val status: DlStatus,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val error: String? = null,
    ) {
        val progress: Float
            get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _states = MutableStateFlow<Map<String, ModelDlState>>(emptyMap())
    val states: StateFlow<Map<String, ModelDlState>> = _states.asStateFlow()

    fun stateOf(modelId: String): Flow<ModelDlState> =
        states.map { it[modelId] ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId)) }

    private val jobs = HashMap<String, Job>()

    init {
        appScope.launch { refreshAll() }
    }

    fun startDownload(modelId: String) {
        val model = LocalLlmModels.byId(modelId) ?: return
        if (jobs[modelId]?.isActive == true) return
        if (prefs.isModelReady(model)) {
            updateState(modelId) {
                it.copy(status = DlStatus.COMPLETED, downloadedBytes = it.totalBytes, error = null)
            }
            return
        }
        updateState(modelId) { it.copy(status = DlStatus.DOWNLOADING, error = null) }
        jobs[modelId] = appScope.launch {
            try {
                if (!isWifiConnected()) {
                    // 本地模型体积大（1–3GB），强制仅 Wi-Fi 下载
                    throw IOException("本地模型较大，请连接 Wi-Fi 后下载")
                }
                downloadFile(model) { done, total ->
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
                val msg = e.message ?: "网络错误，请检查网络后重试"
                updateState(modelId) { it.copy(status = DlStatus.FAILED, error = msg) }
            } finally {
                jobs.remove(modelId)
            }
        }
    }

    fun pauseDownload(modelId: String) {
        val job = jobs.remove(modelId)
        if (job?.isActive == true) {
            job.cancel()
        } else {
            updateState(modelId) { it.copy(status = DlStatus.PAUSED) }
        }
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = LocalLlmModels.byId(modelId) ?: return@withContext
        pauseDownload(modelId)
        prefs.modelFile(model).delete()
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
        for (model in LocalLlmModels.ALL) {
            val file = prefs.modelFile(model)
            val done = if (file.exists()) file.length() else 0L
            val status = if (prefs.isModelReady(model)) DlStatus.COMPLETED else DlStatus.NOT_DOWNLOADED
            updateState(model.id) { ModelDlState(model.id, status, done, model.sizeBytes) }
        }
    }

    private fun totalOf(modelId: String): Long =
        LocalLlmModels.byId(modelId)?.sizeBytes ?: 0L

    private fun updateState(modelId: String, transform: (ModelDlState) -> ModelDlState) {
        val current = _states.value[modelId]
            ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId))
        _states.value = _states.value + (modelId to transform(current))
    }

    /** 单文件下载，支持 Range 断点续传。 */
    private suspend fun downloadFile(
        model: LocalLlmModel,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val dest = prefs.modelFile(model).apply { parentFile?.mkdirs() }
        var existing = if (dest.exists()) dest.length() else 0L
        val request = Request.Builder()
            .url(model.url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .header("User-Agent", "VideoNotes/1.0")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // 本地已完整
                onProgress(dest.length(), model.sizeBytes)
                return@withContext
            }
            if (response.code == 200 && existing > 0) {
                // 服务器不支持续传，从头开始
                existing = 0
                dest.delete()
            }
            if (!response.isSuccessful) {
                throw IOException("下载失败（HTTP ${response.code}），请重试")
            }
            val total = response.header("Content-Range")
                ?.substringAfter("/")
                ?.toLongOrNull()
                ?: response.body?.contentLength()?.takeIf { it > 0 }?.plus(existing)
                ?: model.sizeBytes
            val body = response.body ?: throw IOException("响应体为空")
            // 续传时追加写入，否则覆盖
            FileOutputStream(dest, existing > 0 && response.code == 206).use { out ->
                var done = existing
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            // 简单完整性检查：大小达到标称 80% 以上
            if (dest.length() < (model.sizeBytes * 0.8).toLong()) {
                throw IOException("下载不完整，请重试")
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
