package com.zola.videonotes.data.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zola.videonotes.data.catalog.isLoopbackUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.llmDataStore by preferencesDataStore(name = "llm_prefs")

/**
 * LLM 服务配置（DataStore）：base_url 与 model。
 * api_key 涉及密钥，单独走 EncryptedSharedPreferences（[LlmCredentialStore]）。
 * temperature 固定 0.2（常量，降低幻觉），不暴露给用户。
 */
@Singleton
class LlmSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        /** 生成温度：固定低温以抑制时间戳幻觉。 */
        const val TEMPERATURE = 0.2
    }

    private object Keys {
        val BASE_URL = stringPreferencesKey("base_url")
        val MODEL = stringPreferencesKey("model")
        val ENGINE_TYPE = stringPreferencesKey("engine_type")
    }

    /** 接口地址，默认 OpenAI 官方。 */
    val baseUrl: Flow<String> = context.llmDataStore.data
        .map { it[Keys.BASE_URL] ?: LlmPresets.DEFAULT_BASE_URL }

    /** 模型名，默认空（由用户填写）。 */
    val model: Flow<String> = context.llmDataStore.data
        .map { it[Keys.MODEL] ?: "" }

    /**
     * 引擎类型（用户最后点选的存储值）：云端 BYOK 或本地 llama.cpp，默认本地。
     * 注意：实际行为走 [LlmEngineResolver.effectiveEngineType] 的有效引擎口径，
     * 存储值为云端但未配置齐全时回落本地，不要直接拿存储值做行为决策。
     */
    val engineType: Flow<LlmEngineType> = context.llmDataStore.data
        .map { prefs ->
            when (prefs[Keys.ENGINE_TYPE]) {
                LlmEngineType.CLOUD.name -> LlmEngineType.CLOUD
                else -> LlmEngineType.LOCAL
            }
        }

    suspend fun setBaseUrl(url: String) {
        val trimmed = url.trim()
        // M-08：http 明文会泄露 API Key；本机回环地址（本地自建服务）除外。
        if (trimmed.startsWith("http://") && !isLoopbackUrl(trimmed)) {
            throw IllegalArgumentException("接口地址须为 https（http 明文会泄露 API Key；本机回环地址除外）")
        }
        context.llmDataStore.edit { it[Keys.BASE_URL] = trimmed }
    }

    suspend fun setModel(model: String) {
        context.llmDataStore.edit { it[Keys.MODEL] = model.trim() }
    }

    suspend fun setEngineType(type: LlmEngineType) {
        context.llmDataStore.edit { it[Keys.ENGINE_TYPE] = type.name }
    }

    /** 生成/测试连接时的一次性读取。 */
    suspend fun snapshot(): Pair<String, String> =
        baseUrl.first().trim().trimEnd('/') to model.first().trim()
}
