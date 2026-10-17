package com.zola.videonotes.data.llm

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * LLM 引擎提供器（M7a）：按 [LlmSettings.engineType] 返回云端或本地引擎。
 */
@Singleton
class LlmEngineProvider @Inject constructor(
    private val llmSettings: LlmSettings,
    private val cloudLlmEngine: CloudLlmEngine,
    private val localLlmEngine: LocalLlmEngine,
) {

    suspend fun current(): LlmEngine =
        when (llmSettings.engineType.first()) {
            LlmEngineType.LOCAL -> localLlmEngine
            LlmEngineType.CLOUD -> cloudLlmEngine
        }
}
