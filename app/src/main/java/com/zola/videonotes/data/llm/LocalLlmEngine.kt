package com.zola.videonotes.data.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zola.videonotes.data.llm.local.LlamaCpp
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.job

/**
 * 本地 LLM 引擎（M7a）：llama.cpp JNI，完全离线。
 *
 * - 模型懒加载：首次生成时 load，句柄常驻内存直到进程结束；
 *   切换模型时 unload 旧句柄。
 * - [chatStream] 在 Dispatchers.Default 执行 native 生成，
 *   token 经 JNI 回调实时上报；协程取消时调 [LlamaCpp.requestCancel]。
 * - 本地上下文与速度有限，单遍上限 4000 token、chunk 3000 token，
 *   长文稿会比云端切得更碎（map-reduce 轮数更多）。
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    private val prefs: LocalLlmPreferences,
    @ApplicationContext private val context: Context,
) : LlmEngine {

    override val type: LlmEngineType = LlmEngineType.LOCAL

    override val maxSingleTokens: Int = MAX_SINGLE_TOKENS_LOCAL

    override val chunkTokens: Int = CHUNK_TOKENS_LOCAL

    override val systemPromptAsset: String = "system_note_local.txt"

    override val modelLabel: String
        get() = "本地模型"

    /** native 模型句柄（0 = 未加载），Kotlin 侧同步保护。 */
    private var handle: Long = 0L

    /** 已加载的模型文件绝对路径（用于检测切换）。 */
    private var loadedPath: String? = null

    private val lock = Any()

    override suspend fun checkReady(): Result<Unit> {
        val model = prefs.currentModel()
        return if (prefs.isModelReady(model)) {
            Result.success(Unit)
        } else {
            Result.failure(IOException("本地模型「${model.displayName}」尚未下载，请先到设置页下载"))
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.Default) {
        val model = prefs.currentModel()
        val file = prefs.modelFile(model)
        if (!prefs.isModelReady(model)) {
            return@withContext Result.failure(IOException("本地模型「${model.displayName}」尚未下载"))
        }

        // 加载模型（懒加载；切换模型时先 unload）
        val h = try {
            ensureLoaded(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IOException("本地模型加载失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }

        val prompt = buildPrompt(messages)
        // prompt 构建完成：记 system/user 字符数与估算 token
        val systemChars = messages.firstOrNull { it.role == "system" }?.content?.length ?: 0
        val userChars = messages.filter { it.role != "system" }.sumOf { it.content.length }
        val estTokens = (systemChars + userChars) / 2
        LlmDiagnosticLog.log(
            context, "llm",
            "prompt 构建完成：system=${systemChars}字符，user=${userChars}字符，合计约${estTokens} tokens（估算）",
        )
        LlmDiagnosticLog.log(
            context, "llm",
            "调用模型：engine=本地(${prefs.currentModel().displayName})，" +
                "maxTokens=$MAX_OUTPUT_TOKENS，temperature=$TEMPERATURE_LOCAL",
        )
        val sb = StringBuilder()
        // 取消时通知 native 停止采样
        coroutineContext.job.invokeOnCompletion {
            try {
                LlamaCpp.requestCancel(h)
            } catch (_: Exception) {
            }
        }
        try {
            var tokenCount = 0
            var nextLogAt = 500
            val callback = object : LlamaCpp.TokenCallback {
                override fun onToken(token: String) {
                    sb.append(token)
                    tokenCount++
                    // 每 500 token 记一行进度（避免每 token 都记太吵）
                    if (tokenCount >= nextLogAt) {
                        LlmDiagnosticLog.log(context, "llm", "已输出 $tokenCount tokens…")
                        nextLogAt += 500
                    }
                    onToken(token)
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            // prefill 可能耗时很长（大 prompt 多 batch）：轮询 native 进度并推送到日志流，
            // 卡死时实时面板能看到最后卡在第几个 batch
            val progressPoller = launch {
                var last = ""
                while (isActive) {
                    delay(5000)
                    val p = runCatching { LlamaCpp.lastProgress() }.getOrDefault("")
                    if (p.isNotBlank() && p != last) {
                        last = p
                        LlmDiagnosticLog.log(context, "llm", "生成进度：$p（进行中…）")
                    }
                }
            }
            val rc = try {
                LlamaCpp.generate(h, prompt, MAX_OUTPUT_TOKENS, TEMPERATURE_LOCAL, callback)
            } finally {
                progressPoller.cancel()
            }
            val totalMs = SystemClock.elapsedRealtime() - t0
            // native 侧性能汇总（含 tokenize/prefill/decode 的 token 数与 tok/s），失败时也有部分数据
            val perf = runCatching { LlamaCpp.lastPerfSummary() }.getOrDefault("")
            val outChars = sb.length
            val estOutTokens = outChars / 2
            val summary = "generate 结束(rc=$rc): prompt=${prompt.length}字符, " +
                "输出=${outChars}字符，约${estOutTokens} tokens（估算）, 总耗时=${totalMs}ms | $perf"
            Log.i(TAG, summary)
            LlmDiagnosticLog.log(context, "llm", summary)
            LlmDiagnosticLog.log(
                context, "llm",
                "输出完成：共 ${outChars} 字符，约 ${estOutTokens} tokens（估算），$tokenCount tokens 回调",
            )
            // 把 generate 阶段的 JNI 日志（prefill batch 进度 / llama.cpp 内部日志 / ret=）落盘
            drainJniLogsToFile()
            ensureActive()
            if (rc != 0) {
                return@withContext Result.failure(IOException(LlamaCpp.lastError()))
            }
            val text = sb.toString()
            if (text.isBlank()) {
                Result.failure(IOException("本地模型返回为空，请重试"))
            } else {
                Result.success(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IOException("本地生成失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }
    }

    /**
     * 确保模型已加载。线程安全；重复调用直接返回已加载句柄。
     * 抛 IOException（native 错误信息）。
     */
    private fun ensureLoaded(path: String): Long {
        synchronized(lock) {
            if (handle != 0L && loadedPath == path) return handle
            if (handle != 0L) {
                try {
                    LlamaCpp.unloadModel(handle)
                } catch (_: Exception) {
                }
                handle = 0L
                loadedPath = null
            }
            // 先校验文件完整性：大小与预期差 5% 以上视为下载不完整，直接报清楚，
            // 避免加载损坏文件导致难以定位的 native 失败。
            val file = java.io.File(path)
            val expected = LocalLlmModels.ALL.firstOrNull { path.endsWith(it.fileName) }?.sizeBytes
            if (expected != null && expected > 0) {
                val actual = file.length()
                // 允许 5% 误差（HF 上文件大小可能有微小出入）
                if (actual < expected * 0.95) {
                    throw IOException(
                        "模型文件不完整（实际 ${actual / 1_000_000}MB，预期约 ${expected / 1_000_000}MB），" +
                            "请到设置页删除后重新下载",
                    )
                }
            }
            // 2026-10-17: prefill 在 8 线程下 hang，降到 4 线程排查是否为线程池问题
            val nThreads = 4
            val t0 = SystemClock.elapsedRealtime()
            val h = LlamaCpp.loadModel(path, N_CTX, nThreads)
            val loadMs = SystemClock.elapsedRealtime() - t0
            if (h == 0L) {
                throw IOException(LlamaCpp.lastError())
            }
            val summary = "模型加载完成: ${loadMs}ms (threads=$nThreads, nCtx=$N_CTX, " +
                "model=${path.substringAfterLast('/')})"
            Log.i(TAG, summary)
            LlmDiagnosticLog.log(context, "llm", summary)
            // 把 loadModel 阶段的 JNI 日志（model info / ctx params / llama.cpp 内部日志）落盘
            drainJniLogsToFile()
            handle = h
            loadedPath = path
            return h
        }
    }

    /**
     * 取走 JNI 桥接日志并逐行写入 llm.log（tag `[llm-cpp]`）。
     * 失败时静默忽略（不影响主流程）。
     */
    private fun drainJniLogsToFile() {
        val raw = runCatching { LlamaCpp.drainJniLogs() }.getOrDefault("")
        if (raw.isBlank()) return
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line -> LlmDiagnosticLog.log(context, "llm-cpp", line) }
    }

    /** Qwen3 chat template 组装 prompt。 */
    private fun buildPrompt(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        for (m in messages) {
            sb.append("<|im_start|>").append(m.role).append('\n')
                .append(m.content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    companion object {
        private const val TAG = "VideoNotesLlm"

        /** 本地单遍上限：4000 token（Qwen3-4B 在手机上 KV cache 与速度的平衡点）。 */
        const val MAX_SINGLE_TOKENS_LOCAL = 4000

        /** 本地 map-reduce chunk：3000 token。 */
        const val CHUNK_TOKENS_LOCAL = 3000

        /** 上下文窗口：8192（Qwen3 原生支持 32k，手机上 8k 是内存与速度的折中）。 */
        const val N_CTX = 8192

        /** 单次生成上限：3000 token（笔记输出一般 1500–2500 token）。 */
        const val MAX_OUTPUT_TOKENS = 3000

        /** 本地温度：0.2，与云端一致，抑制幻觉。 */
        const val TEMPERATURE_LOCAL = 0.2f
    }
}
