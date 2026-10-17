package com.zola.videonotes.data.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    /** 引擎类型：云端 BYOK 或本地 llama.cpp，默认云端。 */
    val engineType: Flow<LlmEngineType> = context.llmDataStore.data
        .map { prefs ->
            when (prefs[Keys.ENGINE_TYPE]) {
                LlmEngineType.LOCAL.name -> LlmEngineType.LOCAL
                else -> LlmEngineType.CLOUD
            }
        }

    suspend fun setBaseUrl(url: String) {
        context.llmDataStore.edit { it[Keys.BASE_URL] = url.trim() }
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
