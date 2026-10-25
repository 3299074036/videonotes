package com.zola.videonotes.data.llm

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** OpenAI 兼容接口的 chat 消息。 */
data class ChatMessage(
    val role: String,
    val content: String,
)

/**
 * SSE 流式诊断信息（可选输出参数）。
 *
 * 调用方传入实例，[LlmClient.chatStream] 在失败时填充失败阶段 / HTTP 状态码 /
 * 已收字符数。只加日志，不改变任何生成逻辑。
 */
class LlmStreamDiag {
    /**
     * 失败阶段：
     * - `connect`：建连/发请求阶段失败；
     * - `http`：HTTP 状态码非 2xx；
     * - `first-packet`：响应已建立、等待首个 token 时失败；
     * - `stream`：已收到 token、流式接收中被打断；
     * - `empty`：流正常结束但返回为空。
     */
    var failurePhase: String = ""

    /** HTTP 状态码（-1 = 未收到 HTTP 响应）。 */
    var httpCode: Int = -1

    /** 失败时已收到的字符数。 */
    var receivedChars: Int = 0
}

/**
 * M-02：流式接收中被打断时附带已收文本的失败。
 * 调用方可用 [partial] 续写或保存草稿；不识别此类型的调用方仍按普通失败处理。
 */
class PartialStreamException(
    message: String,
    val partial: String,
) : IOException(message)

/**
 * OpenAI 兼容接口客户端（BYOK）。
 *
 * - `chatStream`：POST `{baseUrl}/chat/completions`，`stream:true`，手动解析 SSE；
 *   token 通过 onToken 实时回调；协程取消时关闭 call。
 * - `testConnection`：GET `{baseUrl}/models` 探活。
 * - 超时：connect 30s、read 300s（长文生成需要）；错误统一转中文。
 */
@Singleton
class LlmClient @Inject constructor(
    baseClient: OkHttpClient,
) {

    private val gson = Gson()

    /** 长时间生成专用 client：读超时放宽到 300 秒。 */
    private val streamClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(310, TimeUnit.SECONDS)
        .build()

    /** 探活用 client：短超时。 */
    private val probeClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 流式对话。成功返回完整文本；失败返回中文原因。
     * [onToken] 在 IO 线程回调，UI 层自行切回主线程。
     * [diag] 可选：传入 [LlmStreamDiag] 实例，失败时填充失败阶段与 HTTP 状态码（只加日志）。
     */
    suspend fun chatStream(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        diag: LlmStreamDiag? = null,
        // H-04：云端此前未设输出上限；与本地 MAX_OUTPUT_TOKENS 对齐为 3000，防长输出费用失控。
        maxTokens: Int = 3000,
    ): Result<String> = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trim().trimEnd('/')}/chat/completions"
        val bodyJson = gson.toJson(
            ChatRequest(
                model = model,
                messages = messages,
                temperature = LlmSettings.TEMPERATURE,
                stream = true,
                max_tokens = maxTokens,
            ),
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val call = streamClient.newCall(request)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        // 诊断用阶段跟踪（只加日志，不改变逻辑）：
        // connect = 建连/发请求；first-packet = 响应已建立、等首包；stream = 流式接收中
        var phase = "connect"
        var receivedChars = 0
        // M-02：文本累积器提至 try 外，流式超时失败时可随异常附带已收部分文本。
        val sb = StringBuilder()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    diag?.httpCode = response.code
                    diag?.failurePhase = "http"
                    return@withContext Result.failure(
                        IOException(mapHttpError(response.code)),
                    )
                }
                phase = "first-packet"
                val reader = response.body?.charStream()?.buffered()
                    ?: return@withContext Result.failure(IOException("响应体为空，请重试"))
                var badLines = 0
                reader.useLines { lines ->
                    for (line in lines) {
                        ensureActive()
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith(":")) continue
                        if (!trimmed.startsWith("data:")) {
                            // L-33：非 data 行不再静默跳过，计数并记日志（不触发失败，兼容事件行）。
                            badLines++
                            Log.w(TAG, "[llm-cloud] SSE 坏行（非 data 行）：${trimmed.take(200)}")
                            continue
                        }
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload == "[DONE]") break
                        val token = parseToken(payload)
                        if (!token.isNullOrEmpty()) {
                            if (phase == "first-packet") {
                                phase = "stream"
                                Log.d(TAG, "[llm-cloud] SSE 首包到达")
                            }
                            sb.append(token)
                            receivedChars = sb.length
                            onToken(token)
                        } else {
                            // L-33：解析失败的行计数；坏行过多说明服务端格式异常，直接失败而非缺句。
                            badLines++
                            Log.w(TAG, "[llm-cloud] SSE 坏行（解析失败）：${payload.take(200)}")
                            if (badLines >= 10) {
                                return@withContext Result.failure(
                                    IOException("服务端返回多行无法解析的数据（$badLines 行），笔记可能缺句，已停止"),
                                )
                            }
                        }
                    }
                }
                diag?.receivedChars = receivedChars
                val text = sb.toString()
                if (text.isBlank()) {
                    diag?.failurePhase = "empty"
                    Result.failure(IOException("模型返回为空，请重试"))
                } else {
                    Result.success(text)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            diag?.failurePhase = phase
            diag?.receivedChars = receivedChars
            if (phase == "stream") {
                Log.d(TAG, "[llm-cloud] SSE 流中断: 已收 $receivedChars 字符, 原因=读超时")
            }
            // M-02：流式接收中超时，已收文本不再丢弃，随异常附带，调用方可续写或存草稿。
            if (phase == "stream" && sb.isNotEmpty()) {
                Result.failure(
                    PartialStreamException("请求超时，已收到部分文本（$receivedChars 字符）", sb.toString()),
                )
            } else {
                Result.failure(IOException("请求超时，请重试"))
            }
        } catch (e: UnknownHostException) {
            diag?.failurePhase = phase
            diag?.receivedChars = receivedChars
            Result.failure(IOException("网络不可达，请检查网络后重试"))
        } catch (e: IOException) {
            if (e.message?.contains("Canceled", ignoreCase = true) == true) {
                throw CancellationException("已取消", e)
            }
            diag?.failurePhase = phase
            diag?.receivedChars = receivedChars
            if (phase == "stream") {
                Log.d(TAG, "[llm-cloud] SSE 流中断: 已收 $receivedChars 字符, 原因=${e.javaClass.simpleName}")
            }
            Result.failure(IOException("网络请求失败，请检查网络后重试"))
        }
    }

    /** 探活：GET {baseUrl}/models，成功返回"连接成功"。 */
    suspend fun testConnection(baseUrl: String, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || apiKey.isBlank()) {
                return@withContext Result.failure(IOException("请先填写接口地址和 API Key"))
            }
            val url = "${baseUrl.trim().trimEnd('/')}/models"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val call = probeClient.newCall(request)
            coroutineContext.job.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    if (response.isSuccessful) {
                        Result.success("连接成功")
                    } else {
                        Result.failure(IOException(mapHttpError(response.code)))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                Result.failure(IOException("请求超时，请重试"))
            } catch (e: UnknownHostException) {
                Result.failure(IOException("网络不可达，请检查网络后重试"))
            } catch (e: IOException) {
                Result.failure(IOException("网络请求失败，请检查网络后重试"))
            }
        }

    /** 从 SSE chunk JSON 中提取增量文本，解析失败返回 null（跳过该行）。 */
    private fun parseToken(payload: String): String? {
        return try {
            gson.fromJson(payload, StreamChunk::class.java)
                ?.choices?.firstOrNull()?.delta?.content
        } catch (_: Exception) {
            null
        }
    }

    private fun mapHttpError(code: Int): String = when (code) {
        401 -> "API Key 无效，请检查设置"
        404 -> "模型不存在或接口地址错误"
        429 -> "请求被限流，请稍后重试"
        in 500..599 -> "服务暂时不可用（$code），请稍后重试"
        else -> "请求失败（$code），请重试"
    }

    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double,
        val stream: Boolean,
        // H-04：Gson 按字段名原样序列化，即 "max_tokens"。
        val max_tokens: Int,
    )

    private data class StreamChunk(
        val choices: List<StreamChoice>?,
    )

    private data class StreamChoice(
        val delta: StreamDelta?,
    )

    private data class StreamDelta(
        val content: String?,
        @SerializedName("reasoning_content") val reasoningContent: String? = null,
    )

    companion object {
        private const val TAG = "VideoNotesLlm"
    }
}
