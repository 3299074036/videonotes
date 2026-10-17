package com.zola.videonotes.data.llm

/** LLM 引擎类型：云端 BYOK 或本地 llama.cpp。 */
enum class LlmEngineType {
    CLOUD,
    LOCAL,
}

/**
 * LLM 引擎接口（M7a）。
 *
 * 云端（[CloudLlmEngine]）与本地（[LocalLlmEngine]）实现同一接口，
 * [NoteGenerator] 只依赖本接口，不感知具体引擎。
 *
 * - [checkReady]：检查引擎是否就绪（云端：配置完整；本地：模型已下载），
 *   未就绪返回中文原因。
 * - [chatStream]：流式对话，token 经 onToken 实时回调（IO 线程），
 *   成功返回完整文本；协程取消时抛 CancellationException。
 * - [maxSingleTokens]/[chunkTokens]：各引擎的输入上限，
 *   [NoteGenerator] 据此决定单遍还是 map-reduce。
 * - [systemPromptAsset]：各引擎的 system prompt 文件名
 *   （本地用简化版，不含 mermaid）。
 */
interface LlmEngine {

    val type: LlmEngineType

    val maxSingleTokens: Int

    val chunkTokens: Int

    val systemPromptAsset: String

    /** 存档用模型标识（如 "deepseek-chat" 或 "Qwen3-4B (本地)"）。 */
    val modelLabel: String

    suspend fun checkReady(): Result<Unit>

    suspend fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
    ): Result<String>
}
