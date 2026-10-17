package com.zola.videonotes

import android.content.Context
import com.zola.videonotes.data.work.WorkerDiagnosticLog
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：兜住所有未捕获异常（含主线程），写进诊断日志文件。
 *
 * 背景：用户反馈"闪退"且无任何错误提示，无法提供定位信息。Android 默认的
 * 崩溃直接杀进程，logcat 普通用户拿不到。此处在 Application.onCreate 最早
 * 安装 UncaughtExceptionHandler，把堆栈落盘到 filesDir/diagnostic/worker.log，
 * 用户下次进设置页"导出诊断日志"即可发给开发者。
 *
 * 注意：记录后仍交由系统默认处理器走正常崩溃流程（弹"应用已停止"），
 * 不吞异常、不掩盖问题。
 */
object CrashHandler {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
                val head = "[$timestamp][CRASH][${thread.name}] " +
                    "${throwable.javaClass.simpleName}: ${throwable.message}\n"
                // 直接追加到诊断日志文件（不走 WorkerDiagnosticLog，避免其内部再抛异常）
                val dir = appContext.filesDir.resolve("diagnostic").apply { mkdirs() }
                val file = dir.resolve("worker.log")
                if (file.exists() && file.length() > 256 * 1024L) {
                    file.writeText("")
                }
                file.appendText(head + sw.toString() + "\n")
                // 尝试把 cause 链也记下来
                var cause = throwable.cause
                var depth = 0
                while (cause != null && depth < 3) {
                    val csw = StringWriter()
                    cause.printStackTrace(PrintWriter(csw))
                    file.appendText("Caused by: $csw\n")
                    cause = cause.cause
                    depth++
                }
            } catch (_: Exception) {
                // 崩溃记录本身绝不能再抛异常
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
