package com.zola.videonotes.data.llm

import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 引擎提供器（M7a）：按有效引擎（[LlmEngineResolver]）返回云端或本地引擎。
 * 存储值选了云端但未配置齐全时回落本地引擎，与界面/编排口径一致。
 */
@Singleton
class LlmEngineProvider @Inject constructor(
    private val engineResolver: LlmEngineResolver,
    private val cloudLlmEngine: CloudLlmEngine,
    private val localLlmEngine: LocalLlmEngine,
) {

    suspend fun current(): LlmEngine =
        when (engineResolver.effectiveEngineTypeNow()) {
            LlmEngineType.LOCAL -> localLlmEngine
            LlmEngineType.CLOUD -> cloudLlmEngine
        }
}
