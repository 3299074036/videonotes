package com.zola.videonotes.data.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 有效引擎推导（方案 B，全局单一口径）：
 *
 * - 存储值 [LlmSettings.engineType] 是用户最后点选的引擎；
 * - 有效引擎 = 存储值为云端「且」云端已配置齐全时才是云端，否则回落本地。
 *   这样用户选了云端但还没配好（或配到一半退出）时，界面与生成都按本地走，
 *   配齐后自动切回云端，无需再点一次。
 *
 * 云端「已配置」的谓词（baseUrl / model / 密钥三项齐全）只在此定义一处，
 * 设置页状态文案、元信息页编排判定、引擎提供器都共用，不要另处复制。
 */
@Singleton
class LlmEngineResolver @Inject constructor(
    private val llmSettings: LlmSettings,
    private val credentialStore: LlmCredentialStore,
) {

    /** 云端配置谓词（纯函数，全局唯一定义）：三项均非空才算已配置。 */
    fun isCloudConfigured(baseUrl: String, model: String, apiKey: String?): Boolean =
        baseUrl.isNotBlank() && model.isNotBlank() && !apiKey.isNullOrBlank()

    /** 云端是否配置齐全（密钥是同步阻塞读，切 IO 线程）。 */
    val cloudConfigured: Flow<Boolean> =
        combine(llmSettings.baseUrl, llmSettings.model) { url, model -> url to model }
            .map { (url, model) ->
                val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }
                isCloudConfigured(url, model, apiKey)
            }

    /** 有效引擎流：存储 CLOUD 且云端已配齐 → CLOUD，否则 LOCAL。 */
    val effectiveEngineType: Flow<LlmEngineType> =
        combine(llmSettings.engineType, cloudConfigured) { stored, configured ->
            if (stored == LlmEngineType.CLOUD && configured) {
                LlmEngineType.CLOUD
            } else {
                LlmEngineType.LOCAL
            }
        }

    /** 一次性读取：云端是否配置齐全。 */
    suspend fun isCloudConfiguredNow(): Boolean {
        val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }
        return isCloudConfigured(
            llmSettings.baseUrl.first(),
            llmSettings.model.first(),
            apiKey,
        )
    }

    /** 一次性读取：当前有效引擎。 */
    suspend fun effectiveEngineTypeNow(): LlmEngineType =
        if (llmSettings.engineType.first() == LlmEngineType.CLOUD && isCloudConfiguredNow()) {
            LlmEngineType.CLOUD
        } else {
            LlmEngineType.LOCAL
        }
}
