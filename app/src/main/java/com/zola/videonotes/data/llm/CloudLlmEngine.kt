package com.zola.videonotes.data.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.catalog.isLoopbackUrl
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 云端 LLM 引擎（M4 原有逻辑的封装）。
 *
 * 配置来自 [LlmSettings] + [LlmCredentialStore]（BYOK）。
 *
 * 可观测性（只加日志，不改生成逻辑）：
 * - 每次 [chatStream] 结束（无论成功失败）记一条汇总：logcat（tag `VideoNotesLlm`）
 *   + 落盘 `filesDir/diagnostic/llm.log`（tag `[llm-cloud]`，与本地 `[llm]` 区分，
 *   设置页「导出 LLM 日志」可一并导出）；
 * - 成功：TTFT（首 token 耗时）、总耗时、输出 token 估算、有效 tok/s；
 * - 失败：失败阶段（connect/http/first-packet/stream/empty，见 [LlmStreamDiag]）、
 *   HTTP 状态码、已收字符数、错误信息；
 * - 绝不记录：API Key、Base URL 全路径（只记 host）、prompt/输出正文（只记长度）。
 */
@Singleton
class CloudLlmEngine @Inject constructor(
    private val llmSettings: LlmSettings,
    private val credentialStore: LlmCredentialStore,
    private val llmClient: LlmClient,
    @ApplicationContext private val context: Context,
) : LlmEngine {

    override val type: LlmEngineType = LlmEngineType.CLOUD

    override val maxSingleTokens: Int = TranscriptChunker.MAX_SINGLE_TOKENS

    override val chunkTokens: Int = TranscriptChunker.CHUNK_TOKENS

    override val systemPromptAsset: String = "system_note.txt"

    override val modelLabel: String
        get() = "云端模型"

    override suspend fun checkReady(): Result<Unit> {
        val (baseUrl, model) = llmSettings.snapshot()
        val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }.orEmpty()
        return if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            Result.failure(IOException("请先到设置页配置大语言模型"))
        } else {
            Result.success(Unit)
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
    ): Result<String> {
        val (baseUrl, model) = llmSettings.snapshot()
        val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }.orEmpty()
        if (baseUrl.isBlank() || model.isBlank() || apiKey.isBlank()) {
            return Result.failure(IOException("请先到设置页配置大语言模型"))
        }
        // M-08：历史已存的 http 地址同样不能拿 Key 去调（保存态拦截管不到存量）。
        if (baseUrl.trim().startsWith("http://") && !isLoopbackUrl(baseUrl.trim())) {
            return Result.failure(IOException("云端地址须为 https（http 明文会泄露 API Key），请到设置页修改"))
        }
        // 只记 host：完整 URL 可能含路径与查询参数，不进日志
        val host = runCatching { java.net.URI(baseUrl.trim().trimEnd('/')).host }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "?"
        // 只记长度，不记正文
        val promptChars = messages.sumOf { it.content.length }
        val systemChars = messages.firstOrNull { it.role == "system" }?.content?.length ?: 0
        val userChars = messages.filter { it.role != "system" }.sumOf { it.content.length }
        LlmDiagnosticLog.log(
            context, "llm-cloud",
            "prompt 构建完成：system=${systemChars}字符，user=${userChars}字符，合计约${promptChars / 2} tokens（估算）",
        )
        LlmDiagnosticLog.log(
            context, "llm-cloud",
            "调用模型：engine=云端($model)，host=$host",
        )
        val diag = LlmStreamDiag()
        val t0 = SystemClock.elapsedRealtime()
        var ttftMs = -1L
        var receivedChars = 0
        var tokenCount = 0
        var nextLogAt = 500
        val result = llmClient.chatStream(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            messages = messages,
            onToken = { token ->
                if (ttftMs < 0) ttftMs = SystemClock.elapsedRealtime() - t0
                receivedChars += token.length
                tokenCount++
                if (tokenCount >= nextLogAt) {
                    LlmDiagnosticLog.log(context, "llm-cloud", "已输出 $tokenCount tokens…")
                    nextLogAt += 500
                }
                onToken(token)
            },
            diag = diag,
            // H-04：与本地 MAX_OUTPUT_TOKENS 对齐，云端单次输出同样封顶 3000 token。
            maxTokens = 3000,
        )
        val totalMs = SystemClock.elapsedRealtime() - t0
        result.fold(
            onSuccess = {
                // 输出 token 数为估算：中文约 1.5 字符/token，英文约 4 字符/token，
                // 笔记以中文为主，按总字符/2 粗估并注明
                val estTokens = receivedChars / 2
                val tps = if (totalMs > 0) estTokens * 1000.0 / totalMs else 0.0
                val summary = "generate 结束(rc=0): model=$model, host=$host, " +
                    "prompt=${promptChars}字符, " +
                    "TTFT=${if (ttftMs >= 0) "${ttftMs}ms" else "N/A"}, " +
                    "总耗时=${totalMs}ms, 输出约 ${estTokens} tokens(估算), " +
                    String.format(Locale.US, "%.1f", tps) + " tok/s"
                Log.i(TAG, summary)
                LlmDiagnosticLog.log(context, "llm-cloud", summary)
                LlmDiagnosticLog.log(
                    context, "llm-cloud",
                    "输出完成：共 ${receivedChars} 字符，约 ${estTokens} tokens（估算），$tokenCount tokens 回调",
                )
            },
            onFailure = { e ->
                val summary = "generate 失败: model=$model, host=$host, " +
                    "prompt=${promptChars}字符, " +
                    "阶段=${diag.failurePhase.ifBlank { "unknown" }}, " +
                    "http=${diag.httpCode}, 已收=${diag.receivedChars}字符, " +
                    "总耗时=${totalMs}ms, 错误=${e.message ?: e.javaClass.simpleName}"
                Log.i(TAG, summary)
                LlmDiagnosticLog.log(context, "llm-cloud", summary)
            },
        )
        return result
    }

    companion object {
        private const val TAG = "VideoNotesLlm"
    }
}
