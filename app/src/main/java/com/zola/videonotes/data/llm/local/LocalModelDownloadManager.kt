package com.zola.videonotes.data.llm.local

import android.content.Context
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.DynamicModelRegistry
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.download.MobileDownloadConsent
import com.zola.videonotes.data.download.ModelFileDownloader
import com.zola.videonotes.data.download.NetworkGate
import com.zola.videonotes.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地 LLM 模型下载管理器（M7a；一期 WP4/WP5 改造）。
 *
 * - 单文件下载走通用内核 [ModelFileDownloader]（全 App 只此一份下载逻辑）；
 * - 网络门（2026-10-24 合并包）：「仅 Wi-Fi」开关已删除，移动网络默认放行，
 *   单文件 > 500 MB 首次需一次性确认（[MobileDownloadConsent]，仅内存）；
 * - 条目解析走 [LocalLlmPreferences] 解析链（精选/影子/动态注册表）；
 * - 强校验三处语义保留自收尾包：启动已有文件先验、下载后验、refreshAll 清洗；
 * - 动态条目下载完成后回填本地实算 sha256（无官方哈希时）并自动跑模板探针（WP2/WP3）。
 */
@Singleton
class LocalModelDownloadManager @Inject constructor(
    private val downloader: ModelFileDownloader,
    private val prefs: LocalLlmPreferences,
    private val consent: MobileDownloadConsent,
    private val migration: LlmModelMigration,
    private val templateProbe: LocalLlmTemplateProbe,
    private val dynamicRegistry: DynamicModelRegistry,
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

    /** 等待「移动网络大文件下载」一次性确认的条目 id（UI 弹确认框用）。 */
    private val _pendingMobileConfirm = MutableStateFlow<Set<String>>(emptySet())
    val pendingMobileConfirm: StateFlow<Set<String>> = _pendingMobileConfirm.asStateFlow()

    fun stateOf(modelId: String): Flow<ModelDlState> =
        states.map { it[modelId] ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId)) }

    private val jobs = HashMap<String, Job>()

    init {
        UserCatalog.init(context)
        appScope.launch {
            migration.migrateIfNeeded()
            refreshAll()
        }
    }

    fun startDownload(modelId: String) {
        val model = prefs.resolveModel(modelId) ?: return
        if (jobs[modelId]?.isActive == true) return
        jobs[modelId] = appScope.launch {
            try {
                // 已有文件先过强校验：长度完整且哈希通过才算已完成；
                // 长度完整但哈希不符 = 损坏文件，删掉后落入下面的重新下载。
                // 长度不足的残缺文件一律保留，交给内核 Range 续传——
                // 旧口径曾以 isModelReady（≥80% 门）触发删除，致大文件反复死在尾巴。
                val existingFile = prefs.fileFor(model)
                if (existingFile.exists() && existingFile.length() == model.sizeBytes) {
                    if (prefs.isModelReadyVerified(model)) {
                        val total = totalOf(modelId)
                        updateState(modelId) {
                            it.copy(status = DlStatus.COMPLETED, downloadedBytes = total, totalBytes = total, error = null)
                        }
                        ensurePostDownloadSteps(model)
                        return@launch
                    }
                    existingFile.delete()
                }
                // 网络门：非 Wi-Fi 时大文件首次下载需一次性确认（仅内存）；
                // 2026-10-24 合并包删除「仅 Wi-Fi」开关后，移动网络默认放行，不再拦截。
                if (!NetworkGate.isWifiConnected(context)) {
                    // 大文件在移动网络首次下载需一次性确认（仅内存）
                    if (consent.needsConsent(modelId, model.sizeBytes)) {
                        _pendingMobileConfirm.value = _pendingMobileConfirm.value + modelId
                        updateState(modelId) { it.copy(status = DlStatus.PAUSED, error = null) }
                        return@launch
                    }
                }
                updateState(modelId) { it.copy(status = DlStatus.DOWNLOADING, error = null) }
                val dest = prefs.modelFile(model)
                downloader.download(
                    url = model.url,
                    dest = dest,
                    expectedSize = model.sizeBytes,
                    expectedSha256 = model.sha256,
                ) { done, total ->
                    updateState(modelId) { it.copy(downloadedBytes = done, totalBytes = total) }
                }
                prefs.markFileVerified(model)
                val total = totalOf(modelId)
                updateState(modelId) {
                    it.copy(status = DlStatus.COMPLETED, downloadedBytes = total, totalBytes = total, error = null)
                }
                ensurePostDownloadSteps(model)
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

    fun pauseDownload(modelId: String) {
        val job = jobs.remove(modelId)
        if (job?.isActive == true) {
            job.cancel()
        } else {
            updateState(modelId) { it.copy(status = DlStatus.PAUSED) }
        }
    }

    suspend fun deleteModel(modelId: String) = withContext(Dispatchers.IO) {
        val model = prefs.resolveModel(modelId) ?: return@withContext
        pauseDownload(modelId)
        // 按 id 目录整体删除（动态条目的 entry.json 随目录一并清除）+ 平铺旧文件兜底
        dynamicRegistry.dir(modelId).deleteRecursively()
        prefs.legacyModelFile(model).delete()
        updateState(modelId) {
            it.copy(
                status = DlStatus.NOT_DOWNLOADED,
                downloadedBytes = 0,
                totalBytes = totalOf(modelId),
                error = null,
            )
        }
    }

    /** 列表移除时丢弃该 id 的下载态（文件是否保留由调用方在此外决定）。 */
    fun forgetState(modelId: String) {
        jobs.remove(modelId)?.cancel()
        _states.value = _states.value - modelId
    }

    /**
     * 按磁盘现状重算单个条目态（恢复添加后找回已保留的文件；进行中的下载不碰）。
     * 逻辑与 [refreshAll] 的单条目分支一致（含探针补探）。
     */
    fun refreshModel(modelId: String) {
        if (jobs[modelId]?.isActive == true) return
        val model = prefs.resolveModel(modelId) ?: return
        appScope.launch {
            withContext(Dispatchers.IO) {
                val file = prefs.fileFor(model)
                var done = if (file.exists()) file.length() else 0L
                var error: String? = null
                val status = when {
                    !file.exists() || file.length() != model.sizeBytes -> DlStatus.NOT_DOWNLOADED
                    prefs.isModelReadyVerified(model) -> DlStatus.COMPLETED
                    else -> {
                        file.delete()
                        done = 0L
                        error = "模型文件校验失败（SHA-256 不匹配），已删除，请重新下载"
                        DlStatus.NOT_DOWNLOADED
                    }
                }
                updateState(model.id) { ModelDlState(model.id, status, done, model.sizeBytes, error) }
                if (status == DlStatus.COMPLETED && model.requiresProbe &&
                    prefs.curatedProbeState(model) == null
                ) {
                    runCatching { templateProbe.probeCurated(model, prefs.fileFor(model)) }
                }
            }
        }
    }

    private suspend fun refreshAll() = withContext(Dispatchers.IO) {
        for (model in prefs.resolveAllModels()) {
            val file = prefs.fileFor(model)
            var done = if (file.exists()) file.length() else 0L
            var error: String? = null
            val status = when {
                // 长度不足 = 残缺文件，保留并显示已下字节（不删除，续传用）；
                // 只有长度完整才走哈希判定，不符者为损坏文件，删除。
                !file.exists() || file.length() != model.sizeBytes -> DlStatus.NOT_DOWNLOADED
                prefs.isModelReadyVerified(model) -> DlStatus.COMPLETED
                else -> {
                    // 长度完整但 sha256 不符：损坏文件，删除以免被当作可用模型加载
                    file.delete()
                    done = 0L
                    error = "模型文件校验失败（SHA-256 不匹配），已删除，请重新下载"
                    DlStatus.NOT_DOWNLOADED
                }
            }
            updateState(model.id) { ModelDlState(model.id, status, done, model.sizeBytes, error) }
            // 已下载的设门条目若探针结论缺失（如探针曾被进程中断），启动时补探一次
            if (status == DlStatus.COMPLETED && model.requiresProbe &&
                prefs.curatedProbeState(model) == null
            ) {
                runCatching { templateProbe.probeCurated(model, prefs.fileFor(model)) }
            }
        }
    }

    /**
     * 下载后步骤：动态条目回填本地实算哈希（无官方 sha 时）并自动探针；
     * 设探针门的精选条目（国内名单新收录）在探针无有效结论时自动探针一次。
     * 失败静默（探针结论缺失时条目按「未校验」呈现，生成入口会拦）。
     */
    private suspend fun ensurePostDownloadSteps(model: LocalLlmModel) {
        if (model.source == CatalogSource.DYNAMIC) {
            val entry = dynamicRegistry.entryFor(model.id) ?: return
            runCatching {
                if (entry.files.first().sha256 == null) {
                    val file = dynamicRegistry.modelFile(entry)
                    if (file.exists()) {
                        dynamicRegistry.setSha256(model.id, prefs.sha256Hex(file))
                    }
                }
                val fresh = dynamicRegistry.entryFor(model.id) ?: entry
                if (dynamicRegistry.validProbe(fresh) == null) {
                    templateProbe.probe(fresh)
                }
            }
        } else if (model.requiresProbe) {
            runCatching {
                if (prefs.curatedProbeState(model) == null) {
                    templateProbe.probeCurated(model, prefs.fileFor(model))
                }
            }
        }
    }

    private fun totalOf(modelId: String): Long =
        prefs.resolveModel(modelId)?.sizeBytes ?: 0L

    private fun updateState(modelId: String, transform: (ModelDlState) -> ModelDlState) {
        val current = _states.value[modelId]
            ?: ModelDlState(modelId, DlStatus.NOT_DOWNLOADED, 0, totalOf(modelId))
        _states.value = _states.value + (modelId to transform(current))
    }
}
