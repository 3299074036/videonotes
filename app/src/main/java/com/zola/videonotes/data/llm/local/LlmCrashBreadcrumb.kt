package com.zola.videonotes.data.llm.local

import android.content.Context
import java.io.File

/**
 * 原生崩溃面包屑的 Kotlin 对接。
 *
 * native 侧（llama_jni.cpp）在 JNI_OnLoad 安装信号处理：本地生成类调用
 * （loadModel / generate / 探针）执行线程发生原生崩溃（SIGSEGV 等）时，
 * 把一行 `CRASH ts=… sig=… (…) milestone=… prefill_tok=… decode_tok=…`
 * 追加写入 [breadcrumbFile]，随后链回旧 handler 让进程按原样崩溃。
 * 另：ggml 的 assert/abort 类日志行会在 abort 前被即时捕获为
 * `LOG ts=… <断言原文>` 行写入同一文件（断言文本捕获），上报时附到
 * 紧随其后的 CRASH 记录文案里。
 * 第三阶段：native 还注册了 ggml 全局 abort 回调——ggml_abort 在 abort
 * 前把完整的「<file>:<line>: <reason>」以 `ABORT ts=… <message>` 行
 * 写入同一文件，上报时以「原因：」附到 CRASH 上（优先级高于 LOG 行的
 * 「断言：」）；同时 stderr 被重定向到同目录的 native_stderr.txt 兜底，
 * 其尾部内容一并上报。
 *
 * - [install]：把文件路径交给 native，须在引擎首次使用前调用
 *   （由 LocalLlmEngine 的 init 触发，会连带加载 native 库）；
 * - [reportPending]：App 启动时调用（纯文件 IO，不加载 native 库）：
 *   上个进程留下的未上报记录逐行写进 [LlmDiagnosticLog]（详细日志 /
 *   导出日志可见），随后把文件转存为已上报（只保留最近一份）。
 */
object LlmCrashBreadcrumb {

    private const val DIR_NAME = "diagnostic"
    private const val FILE_NAME = "native_breadcrumb.txt"
    private const val REPORTED_NAME = "native_breadcrumb.reported.txt"
    private const val STDERR_NAME = "native_stderr.txt"
    private const val STDERR_REPORTED_NAME = "native_stderr.reported.txt"

    fun breadcrumbFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, FILE_NAME)
    }

    private fun stderrFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return File(dir, STDERR_NAME)
    }

    /** 把面包屑路径交给 native（引擎首次使用前调用；重复调用无副作用）。 */
    fun install(context: Context) {
        runCatching {
            LlamaCpp.setBreadcrumbPath(breadcrumbFile(context).absolutePath)
        }
    }

    /** 上报上个进程遗留的崩溃记录（App 启动时调用）。日志本身永不抛异常。 */
    fun reportPending(context: Context) {
        try {
            reportBreadcrumb(context)
            reportStderr(context)
        } catch (_: Exception) {
            // 上报本身永不影响启动
        }
    }

    private fun reportBreadcrumb(context: Context) {
        val file = breadcrumbFile(context)
        if (!file.exists() || file.length() == 0L) return
        val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        // ABORT 行是 ggml_abort 的真原因原文，LOG 行是日志捕获的断言文本：
        // 两者都不单独上报，附到紧随其后的第一条 CRASH 记录上（附过即
        // 消费，避免跨崩溃回合串味）；ABORT 优先级高于 LOG——两者都有
        // 时只附「原因：」，避免已被证伪的日志通道盖过回调原文。
        // 文件末尾没等到 CRASH 的行各自单独带出，不丢信息。
        var pendingReason: String? = null
        var pendingAssert: String? = null
        for (line in lines) {
            when {
                line.startsWith("ABORT ") -> pendingReason = extractAbortContent(line)
                line.startsWith("LOG ") -> pendingAssert = extractLogContent(line)
                line.startsWith("CRASH ") -> {
                    LlmDiagnosticLog.log(
                        context, "llm", formatReport(line, pendingReason, pendingAssert),
                    )
                    pendingReason = null
                    pendingAssert = null
                }
                else -> LlmDiagnosticLog.log(context, "llm", formatReport(line, null, null))
            }
        }
        pendingReason?.let {
            LlmDiagnosticLog.log(context, "llm", "上次本地生成 ggml abort（未伴随崩溃记录）：$it")
        }
        pendingAssert?.let {
            LlmDiagnosticLog.log(context, "llm", "上次本地生成断言（未伴随崩溃记录）：$it")
        }
        rotateToReported(file, File(file.parentFile, REPORTED_NAME))
    }

    /**
     * stderr 兜底上报：native 把 stderr 重定向到 native_stderr.txt
     * （无缓冲，abort 时不丢）。取最后 ≤10 行逐行写进诊断日志，随后与
     * 面包屑同样的轮换（转存 .reported，只留最近一份），不重复上报。
     */
    private fun reportStderr(context: Context) {
        val file = stderrFile(context)
        if (!file.exists() || file.length() == 0L) return
        val tail = file.readLines().map { it.trimEnd() }.filter { it.isNotEmpty() }
            .takeLast(10)
        for (line in tail) {
            LlmDiagnosticLog.log(context, "llm", "stderr：${line.take(300)}")
        }
        rotateToReported(file, File(file.parentFile, STDERR_REPORTED_NAME))
    }

    /** 转存为已上报（覆盖旧的一份）：同一记录不重复上报，磁盘只留最近一份。 */
    private fun rotateToReported(file: File, reported: File) {
        reported.delete()
        if (!file.renameTo(reported)) {
            runCatching {
                reported.writeText(file.readText())
                file.delete()
            }
        }
    }

    private val lineRegex = Regex(
        """CRASH ts=(\d+) sig=(\d+) \((\w+)\) milestone=(.*) prefill_tok=(\d+) decode_tok=(\d+)""",
    )

    private val logLineRegex = Regex("""LOG ts=\d+ (.*)""")

    private val abortLineRegex = Regex("""ABORT ts=\d+ (.*)""")

    /** LOG 行 → 断言原文（去掉 `LOG ts=… ` 前缀）；解析不了就整行返回。 */
    private fun extractLogContent(line: String): String =
        logLineRegex.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: line

    /** ABORT 行 → ggml abort 原因原文（去掉 `ABORT ts=… ` 前缀）；解析不了就整行返回。 */
    private fun extractAbortContent(line: String): String =
        abortLineRegex.matchEntire(line)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: line

    /** 原生行 → 面向用户的诊断文案；解析不了就原样带出，不丢信息。 */
    private fun formatReport(line: String, reasonText: String?, assertText: String?): String {
        val m = lineRegex.matchEntire(line)
            ?: return "上次本地生成崩溃（原始记录）：$line"
        val (ts, sig, sigName, milestone, prefill, decode) = m.destructured
        val whenText = runCatching {
            val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            format.format(java.util.Date(ts.toLong() * 1000))
        }.getOrDefault(ts)
        val base = "上次本地生成崩溃：$whenText，信号 $sig（$sigName），" +
            "位置「$milestone」，prefill $prefill tok / decode $decode tok"
        // 原因（ggml 回调原文）优先；没有原因时才附断言（日志捕获）
        return when {
            !reasonText.isNullOrBlank() -> "$base，原因：$reasonText"
            !assertText.isNullOrBlank() -> "$base，断言：$assertText"
            else -> base
        }
    }
}
