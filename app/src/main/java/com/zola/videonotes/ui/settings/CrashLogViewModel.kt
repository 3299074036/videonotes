package com.zola.videonotes.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.llm.local.LlmCrashBreadcrumb
import com.zola.videonotes.data.work.WorkerDiagnosticLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** 一条崩溃记录：头行（时间 + 异常类 + message 首行）+ 完整原文（含堆栈与 cause 链）。 */
data class CrashEntry(
    val time: String,
    val exceptionClass: String,
    val message: String,
    val fullText: String,
)

/**
 * 崩溃日志 ViewModel（2026-10-24 合并包）：
 * 解析 worker.log：以含 [CRASH] 的行起条，续行直到下一条匹配新日志行格式
 * `^[MM-dd HH:mm:ss.SSS][` 的行；堆栈行与 "Caused by:" 块都归属本条。
 * 列表倒序（最新在前）。不改 CrashHandler 写入路径与详细日志页。
 */
@HiltViewModel
class CrashLogViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _entries = MutableStateFlow<List<CrashEntry>>(emptyList())
    val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    /** 最近一次原生面包屑原文（若有）：仅供对照，不一定属于当前选中条目。 */
    private val _breadcrumb = MutableStateFlow<String?>(null)
    val breadcrumb: StateFlow<String?> = _breadcrumb.asStateFlow()

    init {
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { parseCrashLog(WorkerDiagnosticLog.logFile(context)) }
            _entries.value = parsed
            val crumb = withContext(Dispatchers.IO) {
                val live = LlmCrashBreadcrumb.breadcrumbFile(context)
                val text = live.takeIf { it.exists() }?.readText()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    text
                } else {
                    // 已上报的面包屑只保留最近一份（见 LlmCrashBreadcrumb）
                    File(context.filesDir, "diagnostic/native_breadcrumb.reported.txt")
                        .takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
            _breadcrumb.value = crumb
        }
    }

    companion object {
        /** 新日志行格式：[MM-dd HH:mm:ss.SSS][tag] … */
        private val NEW_LOG_LINE = Regex("^\\[\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\]\\[")

        /** 崩溃头行：[MM-dd HH:mm:ss.SSS][CRASH][线程] 异常类: message */
        private val CRASH_HEAD = Regex("^\\[(\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]\\[CRASH\\]\\[.*?\\] (.*)$")

        fun parseCrashLog(file: File): List<CrashEntry> {
            if (!file.exists()) return emptyList()
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            val entries = mutableListOf<CrashEntry>()
            var current: MutableList<String>? = null
            fun flush() {
                val block = current ?: return
                current = null
                if (block.isEmpty()) return
                val head = CRASH_HEAD.find(block[0]) ?: return
                val time = head.groupValues[1]
                val rest = head.groupValues[2]
                val exceptionClass = rest.substringBefore(": ").substringBefore(":")
                val message = rest.substringAfter(": ", "")
                entries += CrashEntry(
                    time = time,
                    exceptionClass = exceptionClass,
                    message = message,
                    fullText = block.joinToString("\n"),
                )
            }
            for (line in lines) {
                if ("[CRASH]" in line) {
                    flush()
                    current = mutableListOf(line)
                } else if (current != null) {
                    if (NEW_LOG_LINE.containsMatchIn(line)) {
                        flush()
                    } else {
                        current!!.add(line)
                    }
                }
            }
            flush()
            return entries.reversed()
        }
    }
}
