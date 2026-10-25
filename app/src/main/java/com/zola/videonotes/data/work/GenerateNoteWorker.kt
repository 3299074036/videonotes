package com.zola.videonotes.data.work

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zola.videonotes.data.llm.GenerateStage
import com.zola.videonotes.data.llm.LlmEngineResolver
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.NoteGenerator
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.pipeline.GENERATE_CRASH_BREAKER_MESSAGE
import com.zola.videonotes.data.pipeline.GenerateCrashGuard
import com.zola.videonotes.domain.model.storageKey
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 笔记生成 Worker（M6）：复用 [NoteGenerator] 全链路
 * （取文稿 → 单遍/map-reduce 生成 → 时间戳质量门禁 → 最多 1 次修正重试 → 存 Room）。
 *
 * - 前台服务 + 进度通知，退后台后由 WorkManager 继续；
 * - 成功输出 [KEY_NOTE_ID]；限流/超时走 Result.retry()，其余失败输出 [KEY_ERROR]；
 *   retry 只对云端引擎：本地生成的失败不在 Worker 层退避重试
 *   （原生崩溃靠 [GenerateCrashGuard] 计数熔断，不靠 WorkManager 退避复活）；
 * - 本地引擎生成全程受崩溃熔断守卫：进入前置位死亡标记，正常返回后清除，
 *   连续死亡达上限时直接失败停步（[GENERATE_CRASH_BREAKER_MESSAGE]）；
 * - 用户取消时协程取消，原样抛出。
 */
class GenerateNoteWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val noteGenerator: NoteGenerator,
    private val engineResolver: LlmEngineResolver,
    private val crashGuard: GenerateCrashGuard,
) : LongTaskWorker(appContext, params) {

    override fun notificationTitle(): String = "笔记生成"

    /** Worker 阶段日志：走 LlmDiagnosticLog，实时面板可见（与 NoteGenerator 的详细日志互补）。 */
    private fun llmLog(message: String) = LlmDiagnosticLog.log(applicationContext, "llm", message)

    override suspend fun doDiagnosedWork(): Result = coroutineScope {
        diag("parse inputData")
        val video = inputData.toVideoInfo()
            ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR to "任务参数缺失，请重试"))
        diag("video parsed: ${video.platform.name}:${video.videoId}")
        llmLog("Worker：准备文稿（${video.platform.name}:${video.videoId}）")

        diag("safeSetForeground(准备中)")
        safeSetForeground("准备中…", -1f)
        diag("safeSetForeground done")

        // 崩溃熔断守卫（仅本地引擎）：登记上次死亡 → 达上限直接熔断停步；
        // 未熔断则置位死亡标记，generate() 正常返回后清除（见下方结果处理）。
        // 云端引擎不杀进程、不需要守卫。
        // 注：本段必须在 reporter 协程启动之前——熔断早退时 coroutineScope
        // 需等待子协程结束，reporter 不取消会死等。
        val storageKey = video.storageKey()
        val isLocalEngine =
            engineResolver.effectiveEngineTypeNow() == LlmEngineType.LOCAL
        var guardArmed = false
        if (isLocalEngine) {
            val deaths = crashGuard.registerStart(storageKey)
            if (deaths >= GenerateCrashGuard.MAX_CONSECUTIVE_DEATHS) {
                diag("crash breaker tripped: consecutiveDeaths=$deaths")
                llmLog("Worker：本地生成已连续 $deaths 次在生成中途中断（疑似原生崩溃），熔断停步，不再自动重试")
                return@coroutineScope Result.failure(
                    workDataOf(KEY_ERROR to GENERATE_CRASH_BREAKER_MESSAGE),
                )
            }
            crashGuard.markInProgress(storageKey)
            guardArmed = true
        }

        val progressState = MutableStateFlow(Triple("", -1f, 0L))
        var lastStage = ""
        var seq = 0L
        val reporter = launch {
            progressState.collect { (stage, progress, _) ->
                if (stage.isBlank()) return@collect
                try {
                    setProgress(workDataOf(KEY_STAGE to stage, KEY_PROGRESS to progress))
                } catch (t: Throwable) {
                    diag("reporter setProgress threw: ${t.javaClass.simpleName}: ${t.message}")
                }
                if (stage != lastStage) {
                    lastStage = stage
                    llmLog("Worker：阶段=$stage")
                    safeSetForeground(stage, progress)
                }
            }
        }
        diag("reporter launched")
        try {
            diag("generate started")
            val result = noteGenerator.generate(video) { stage, receivedChars ->
                progressState.value = Triple(formatStageText(stage, receivedChars), -1f, seq++)
            }
            diag("generate returned: success=${result.isSuccess}")
            // generate() 正常返回 = 本次进程存活：成功清零死亡计数，
            // 已处理的失败只清死亡标记（不算死亡、也不清计数）
            if (guardArmed) {
                if (result.isSuccess) {
                    crashGuard.onGenerateSuccess(storageKey)
                } else {
                    crashGuard.clearInProgress(storageKey)
                }
            }
            result.fold(
                onSuccess = { note ->
                    diag("success: noteId=${note.id}")
                    llmLog("Worker：完成，笔记 id=${note.id}")
                    Result.success(workDataOf(KEY_NOTE_ID to note.id))
                },
                onFailure = { e ->
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: "${e.javaClass.simpleName}（无详细信息），请重试"
                    diag("failure: $msg")
                    llmLog("Worker：失败，错误=$msg")
                    // H-03：次数上限见 MAX_RETRY_ATTEMPTS；耗尽后走 failure 分支，KEY_ERROR 保留。
                    if (!isLocalEngine && isRetryable(msg) && runAttemptCount + 1 < MAX_RETRY_ATTEMPTS) {
                        // 同 TranscribeWorker：重试中暂存错误到 progress，避免错误丢失。
                        setProgress(workDataOf(KEY_STAGE to "准备重试…", KEY_PROGRESS to -1f, KEY_ERROR to msg))
                        Result.retry()
                    } else {
                        Result.failure(workDataOf(KEY_ERROR to msg))
                    }
                },
            )
        } catch (e: CancellationException) {
            // 取消是已处理落点（进程存活）：清死亡标记，避免被下次误记为死亡
            if (guardArmed) runCatching { crashGuard.clearInProgress(storageKey) }
            diag("cancelled")
            throw e
        } catch (t: Throwable) {
            if (guardArmed) runCatching { crashGuard.clearInProgress(storageKey) }
            diag("caught: ${t.javaClass.simpleName}: ${t.message}")
            Result.failure(workDataOf(KEY_ERROR to "生成失败：${t.message ?: t.javaClass.simpleName}"))
        } finally {
            reporter.cancel()
        }
    }

    private fun formatStageText(stage: GenerateStage, receivedChars: Int): String =
        when {
            stage == GenerateStage.GENERATING && receivedChars > 0 ->
                "正在生成…（已接收 $receivedChars 字）"
            stage == GenerateStage.FIXING ->
                "正在修正时间戳（第 1 次）…"
            else -> stage.displayText
        }

    /** 可重试错误：LLM 限流 / 网络超时，退避后重试可能成功。 */
    private fun isRetryable(message: String): Boolean =
        message.contains("超时") || message.contains("限流") || message.contains("稍后重试")
}
