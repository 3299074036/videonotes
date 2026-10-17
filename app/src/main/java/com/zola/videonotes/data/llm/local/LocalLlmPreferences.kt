package com.zola.videonotes.data.llm.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.localLlmDataStore by preferencesDataStore(name = "local_llm_prefs")

/**
 * 本地 LLM 偏好与模型文件位置（M7a）。
 *
 * 模型文件存 `filesDir/llm_models/<fileName>`，随卸载清除。
 */
@Singleton
class LocalLlmPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val MODEL_ID = stringPreferencesKey("model_id")
    }

    /** 当前选择的本地模型 ID，默认 Qwen3-4B。 */
    val modelId: Flow<String> = context.localLlmDataStore.data
        .map { it[Keys.MODEL_ID] ?: LocalLlmModels.QWEN3_4B.id }

    suspend fun setModelId(id: String) {
        context.localLlmDataStore.edit { it[Keys.MODEL_ID] = id }
    }

    /** 当前选择模型的 GGUF 文件。 */
    suspend fun currentModelFile(): File {
        val id = modelId.first()
        val model = LocalLlmModels.byId(id) ?: LocalLlmModels.QWEN3_4B
        return modelFile(model)
    }

    /** 当前选择的模型定义。 */
    suspend fun currentModel(): LocalLlmModel =
        LocalLlmModels.byId(modelId.first()) ?: LocalLlmModels.QWEN3_4B

    fun modelFile(model: LocalLlmModel): File =
        File(context.filesDir, "llm_models/${model.fileName}")

    /** 模型文件是否存在且大小合理（≥ 80% 标称值，防下载中断的半截文件）。 */
    fun isModelReady(model: LocalLlmModel): Boolean {
        val f = modelFile(model)
        return f.exists() && f.length() >= (model.sizeBytes * 0.8).toLong()
    }
}
