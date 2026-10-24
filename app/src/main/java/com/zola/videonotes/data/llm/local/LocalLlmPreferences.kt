package com.zola.videonotes.data.llm.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zola.videonotes.data.catalog.CatalogRepository
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.CuratedProbeStore
import com.zola.videonotes.data.catalog.DynamicModelRegistry
import com.zola.videonotes.data.catalog.TemplateProbeState
import com.zola.videonotes.data.catalog.toLocalLlmModel
import com.zola.videonotes.data.download.Sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localLlmDataStore by preferencesDataStore(name = "local_llm_prefs")

/**
 * 本地 LLM 偏好与模型文件位置（M7a；一期 WP1/WP3/WP5 改造）。
 *
 * 存储布局：`filesDir/llm_models/<id>/<fileName>`（按 id 分目录，WP5）；
 * 迁移完成前保留平铺旧路径兜底查找（见 [fileFor]），兜底在下一版本移除。
 *
 * id 解析顺序（方案 §3.2/§3.3.5）：精选正式条目 → 影子条目 → 动态注册表 → 回退默认 4B。
 */
@Singleton
class LocalLlmPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val catalog: CatalogRepository,
    private val dynamicRegistry: DynamicModelRegistry,
    private val curatedProbeStore: CuratedProbeStore,
) {

    private object Keys {
        val MODEL_ID = stringPreferencesKey("model_id")
        val SELECTION_MIGRATED_V1 = booleanPreferencesKey("selection_migrated_v1")
    }

    /**
     * 当前选择的本地模型 ID，可空：缺 key = 从未选择（2026-10-24 合并包「初次不默认勾选」）。
     * 老用户迁移见 [migrateSelectionIfNeeded]：磁盘上有任一模型文件即回填默认 id，否则保持 null。
     */
    val modelId: Flow<String?> = context.localLlmDataStore.data
        .map { it[Keys.MODEL_ID] }

    suspend fun setModelId(id: String) {
        context.localLlmDataStore.edit { it[Keys.MODEL_ID] = id }
    }

    /** 清空选择（删除回落到无已下载模型时用）。 */
    suspend fun clearModelId() {
        context.localLlmDataStore.edit { it.remove(Keys.MODEL_ID) }
    }

    /**
     * 首次启动新版时的一次性迁移（幂等）：仅当 key 缺失时执行；磁盘 llm_models 树下
     * 有任一文件 → 视为老用户，回填默认 id（行为与今日一致）；否则全新安装 → 保持 null。
     * 无模型文件但有视频/笔记的老用户被判为新装也无实际损失（本就无可用模型，走引导即可）。
     */
    suspend fun migrateSelectionIfNeeded() {
        val prefs = context.localLlmDataStore.data.first()
        if (prefs[Keys.SELECTION_MIGRATED_V1] == true) return
        if (prefs[Keys.MODEL_ID] == null) {
            val hasFile = dynamicRegistry.modelsRoot().walkTopDown().any { it.isFile }
            if (hasFile) {
                context.localLlmDataStore.edit { it[Keys.MODEL_ID] = LocalLlmModels.DEFAULT_ID }
            }
        }
        context.localLlmDataStore.edit { it[Keys.SELECTION_MIGRATED_V1] = true }
    }

    /** 按解析链取模型定义（动态条目读本地注册表），未知 id 返回 null。 */
    fun resolveModel(id: String): LocalLlmModel? =
        catalog.llmById(id)?.toLocalLlmModel()
            ?: dynamicRegistry.entryFor(id)?.toLocalLlmModel()

    /** 解析链全集（正式 + 影子 + 已下载动态），供按文件名反查等场景使用。 */
    fun resolveAllModels(): List<LocalLlmModel> {
        val base = LocalLlmModels.ALL
        val known = base.map { it.id }.toSet()
        return base + dynamicRegistry.entries()
            .filter { it.id !in known }
            .map { it.toLocalLlmModel() }
    }

    /** 当前选择的模型定义（可空：未选择或解析失败时返回 null，不再静默兜底默认条目）。 */
    suspend fun currentModel(): LocalLlmModel? =
        modelId.first()?.let { resolveModel(it) }

    /** 当前选择模型的 GGUF 文件（双路查找，见 [fileFor]；未选时 null）。 */
    suspend fun currentModelFile(): File? = currentModel()?.let { fileFor(it) }

    /** 新布局路径：llm_models/<id>/<fileName>。 */
    fun modelFile(model: LocalLlmModel): File =
        File(dynamicRegistry.dir(model.id), model.fileName)

    /** 旧布局平铺路径：llm_models/<fileName>（迁移与兜底查找用，下一版本移除）。 */
    fun legacyModelFile(model: LocalLlmModel): File =
        File(dynamicRegistry.modelsRoot(), model.fileName)

    /**
     * 实际文件定位：新布局优先；新布局不存在而旧平铺存在时用旧路径
     * （迁移未完成期的兜底，见方案 §5.2）；都不存在时返回新布局路径（下载目标）。
     */
    fun fileFor(model: LocalLlmModel): File {
        val primary = modelFile(model)
        if (primary.exists()) return primary
        val legacy = legacyModelFile(model)
        return if (legacy.exists()) legacy else primary
    }

    /** 模型文件是否存在且大小合理（≥ 80% 标称值，防下载中断的半截文件）。同步快速预检，不含哈希。注意：该判定只作快速预检，绝不作为删除触发条件——删除只看「长度完整且哈希不符」（见 LocalModelDownloadManager）。 */
    fun isModelReady(model: LocalLlmModel): Boolean {
        val f = fileFor(model)
        return f.exists() && f.length() >= (model.sizeBytes * 0.8).toLong()
    }

    /**
     * 已通过 sha256 校验的文件指纹缓存：modelId → (文件长度, 修改时间)。
     * 指纹不变视为同一文件已验，避免每次判定都重算 GB 级全量哈希（进程内有效）。
     */
    private val verifiedFingerprints = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** 流式计算文件 SHA-256（实现已抽到 [Sha256] 共用，本方法保留转发，语义不变）。 */
    fun sha256Hex(file: File): String = Sha256.hex(file)

    /** 计算并比对模型当前文件 sha256 是否与目录值一致（目录 sha256 为 null 时视为通过）。须在 IO 上下文调用。 */
    fun matchesCatalogSha256(model: LocalLlmModel): Boolean {
        val expected = model.sha256 ?: return true
        val f = fileFor(model)
        if (!f.exists()) return false
        return Sha256.matches(f, expected)
    }

    /** 任意文件与期望哈希的比对（迁移先验后移用）。须在 IO 上下文调用。 */
    fun sha256Matches(file: File, expected: String?): Boolean = Sha256.matches(file, expected)

    /** 记下「当前文件已通过 sha256 校验」的指纹（下载后校验通过时调用）。 */
    fun markFileVerified(model: LocalLlmModel) {
        val f = fileFor(model)
        if (f.exists()) {
            verifiedFingerprints[model.id] = f.length() to f.lastModified()
        }
    }

    /**
     * 强校验版「已下载」判定（suspend，哈希在 IO 线程算）：
     * 先过 [isModelReady] 大小预检；目录带 sha256 的模型还必须全量哈希一致才可用——
     * 曾有损坏的 GGUF 靠大小门槛蒙混过关、加载后算出 NaN 导致 native 崩溃，故哈希为硬门槛。
     * 目录 sha256 为 null 的模型维持原大小判定。校验通过的文件按指纹缓存，不重复算。
     */
    suspend fun isModelReadyVerified(model: LocalLlmModel): Boolean {
        if (!isModelReady(model)) return false
        if (model.sha256 == null) return true
        val f = fileFor(model)
        val fingerprint = f.length() to f.lastModified()
        if (verifiedFingerprints[model.id] == fingerprint) return true
        val ok = withContext(Dispatchers.IO) {
            runCatching { matchesCatalogSha256(model) }.getOrDefault(false)
        }
        if (ok) {
            verifiedFingerprints[model.id] = fingerprint
        } else {
            verifiedFingerprints.remove(model.id)
        }
        return ok
    }

    /** 条目是否使用小模型简化 prompt（动态条目恒 false，见方案 D12）。 */
    fun smallModelPrompt(id: String): Boolean =
        catalog.llmById(id)?.smallModelPrompt ?: false

    /** 按文件名反查期望大小（引擎完整性预检用，覆盖精选/影子/动态全解析域）。 */
    fun expectedSizeForFileName(fileName: String): Long? =
        resolveAllModels().firstOrNull { it.fileName == fileName }?.sizeBytes

    /**
     * 按完整路径反查期望大小：新布局路径含条目 id 目录，优先按 id 解析——
     * 国内名单里 GLM-Edge 4B/1.5B 文件名相同（ggml-model-Q4_K_M.gguf）但体积不同，
     * 只按文件名反查会串位；旧平铺路径无 id 目录时回退文件名反查。
     */
    fun expectedSizeForPath(path: String): Long? {
        val parentName = path.substringBeforeLast('/').substringAfterLast('/')
        resolveModel(parentName)?.let { return it.sizeBytes }
        return expectedSizeForFileName(path.substringAfterLast('/'))
    }

    /** 动态条目当前有效的探针结论（指纹一致才算数）；非动态条目返回 null。 */
    fun dynamicProbeState(model: LocalLlmModel): TemplateProbeState? {
        if (model.source != CatalogSource.DYNAMIC) return null
        val entry = dynamicRegistry.entryFor(model.id) ?: return null
        return dynamicRegistry.validProbe(entry)
    }

    /** 精选探针门条目当前有效的探针结论（指纹一致才算数）；非设门条目返回 null。 */
    fun curatedProbeState(model: LocalLlmModel): TemplateProbeState? {
        if (!model.requiresProbe) return null
        val f = fileFor(model)
        if (!f.exists()) return null
        return curatedProbeStore.validState(model.id, f.length() to f.lastModified())
    }

    /** 精选探针门条目探针定下的拼装模式（embedded/named），无有效记录返回 null。 */
    fun curatedProbeMode(model: LocalLlmModel): String? {
        if (!model.requiresProbe) return null
        val f = fileFor(model)
        if (!f.exists()) return null
        return curatedProbeStore.validMode(model.id, f.length() to f.lastModified())
    }

    /** 精选条目首次「设为使用」确认（C7 同口径，一次性）。 */
    fun isUseConfirmed(id: String): Boolean = curatedProbeStore.isUseConfirmed(id)

    fun markUseConfirmed(id: String) = curatedProbeStore.setUseConfirmed(id)
}
