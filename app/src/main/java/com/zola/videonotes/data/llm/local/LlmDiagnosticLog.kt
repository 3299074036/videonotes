package com.zola.videonotes.data.llm.local

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地 LLM 诊断日志：写到应用私有目录 `filesDir/diagnostic/llm.log`。
 *
 * 背景：用户反馈本地模型生成太慢，需要可分析的性能数据。
 * native 侧（llama_jni.cpp）用 `__android_log_print` 打出 tokenize / prefill / decode
 * 的耗时与 tok/s（tag `VideoNotesLlm`）；Kotlin 侧（LocalLlmEngine）在模型加载完成
 * 与每次 generate 结束时把汇总（含 prompt token、生成 token、prefill/decode tok/s、
 * 总耗时）落盘，用户在设置页导出后即可分析瓶颈。
 *
 * - 实现照抄 [com.zola.videonotes.data.work.WorkerDiagnosticLog]（256KB 轮转）；
 * - 全部 try-catch，日志本身永不抛异常。
 * - 另带内存环形缓冲（最近 [MAX_MEMORY_LINES] 条）经 [logFlow] 对外推送，
 *   供详情页实时日志面板订阅；本地/云端引擎都走 [log]，自动获得推送。
 */
object LlmDiagnosticLog {

    private const val DIR_NAME = "diagnostic"
    private const val FILE_NAME = "llm.log"
    private const val MAX_SIZE_BYTES = 256 * 1024L

    /** 内存环形缓冲上限（行数）。 */
    private const val MAX_MEMORY_LINES = 100

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val _logFlow = MutableStateFlow<List<String>>(emptyList())

    /** 实时日志流（最近 MAX_MEMORY_LINES 条，时间升序）。线程安全。 */
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()

    fun log(context: Context, tag: String, message: String) {
        try {
            val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            if (file.exists() && file.length() > MAX_SIZE_BYTES) {
                file.writeText("")
            }
            val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
            val line = "[$timestamp][$tag] $message"
            file.appendText("$line\n")
            // 内存环形缓冲：供 UI 实时订阅（log 可能来自任意线程，StateFlow 赋值本身线程安全）
            synchronized(_logFlow) {
                _logFlow.value = (_logFlow.value + line).takeLast(MAX_MEMORY_LINES)
            }
        } catch (_: Exception) {
            // 日志本身永不影响业务
        }
    }

    fun logFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, FILE_NAME)
    }

    fun clear(context: Context) {
        try {
            logFile(context).takeIf { it.exists() }?.writeText("")
            // 内存环形缓冲同步清空：否则日志页（文件 + flow 合并显示）点清空后旧行仍在
            synchronized(_logFlow) {
                _logFlow.value = emptyList()
            }
        } catch (_: Exception) {
        }
    }
}
