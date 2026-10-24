package com.zola.videonotes.data.llm.local

import android.content.Context
import com.zola.videonotes.data.catalog.CatalogRepository
import com.zola.videonotes.data.catalog.toLocalLlmModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 存储布局迁移（WP5）：平铺 `llm_models/<fileName>` → 按 id 分目录 `llm_models/<id>/<fileName>`。
 *
 * 规则（方案 §5.1/§5.2）：
 * - 只处理白名单（正式 + 影子条目）文件名；未知文件一律不动（不移动、不删除、不报错）；
 * - 先验后移：大小达标且 sha256 一致才移动；校验不过 → 原地保留，不自动删（迁移不没收文件）；
 * - 幂等：完成写标记文件；任一步异常 → 不写标记，下次启动重试，该文件保留原地，
 *   期间 [LocalLlmPreferences.fileFor] 双路查找兜底。
 */
@Singleton
class LlmModelMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LocalLlmPreferences,
    private val catalog: CatalogRepository,
) {

    private val marker: File
        get() = File(context.filesDir, "llm_models/.layout_v2_done")

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        if (marker.exists()) return@withContext
        val entries = catalog.llmCurated() + catalog.llmShadow()
        for (entry in entries) {
            runCatching { migrateOne(entry.toLocalLlmModel()) }
            // 单个文件异常不阻断其余条目与启动；未完成者靠双路查找兜底，下次重试
        }
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("v2")
        }
    }

    private fun migrateOne(model: LocalLlmModel) {
        val legacy = prefs.legacyModelFile(model)
        if (!legacy.exists()) return
        val target = prefs.modelFile(model)
        if (target.exists()) return // 新布局已有文件，平铺旧文件保留原地（不删）
        // 先验：大小（isModelReady 的 80% 门 + 精确值优先）与 sha256
        val sizeOk = legacy.length() >= (model.sizeBytes * 0.8).toLong()
        if (!sizeOk) return
        if (!prefs.sha256Matches(legacy, model.sha256)) return
        target.parentFile?.mkdirs()
        if (legacy.renameTo(target)) {
            prefs.markFileVerified(model)
        }
        // rename 失败（跨卷等）保留原地，不拷贝不删除
    }
}
