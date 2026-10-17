package com.zola.videonotes.data.work

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Worker 诊断日志：写到应用私有目录 `filesDir/diagnostic/worker.log`。
 *
 * 背景：转写/生成 Worker 在用户真机上失败时 WorkInfo 里没有任何错误数据
 * （outputData 与 progress 均为 null），静态分析无法定位。改为文件级埋点：
 * 入队、doWork 进入、每个关键步骤、异常都落盘，用户在设置页导出日志后
 * 即可精确定位死在哪一步（尤其能区分"Worker 未被实例化"与"执行中崩溃"）。
 *
 * - 全部 try-catch，日志本身永不抛异常；
 * - 超过 256KB 自动清空重写，避免无限膨胀。
 */
object WorkerDiagnosticLog {

    private const val DIR_NAME = "diagnostic"
    private const val FILE_NAME = "worker.log"
    private const val MAX_SIZE_BYTES = 256 * 1024L
    private const val MAX_MEMORY_LINES = 100

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val _logFlow = MutableStateFlow<List<String>>(emptyList())

    /** 内存环形缓冲（最近 100 条），供日志查看页实时订阅。 */
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
