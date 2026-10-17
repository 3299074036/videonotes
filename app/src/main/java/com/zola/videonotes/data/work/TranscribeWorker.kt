package com.zola.videonotes.data.work

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zola.videonotes.data.asr.TranscribeAudioUseCase
import com.zola.videonotes.data.asr.TranscribeStage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 语音转写 Worker（M6）：复用 [TranscribeAudioUseCase] 全链路。
 *
 * - 前台服务 + 进度通知，切后台 / 杀进程后由 WorkManager 继续调度；
 * - 进度经 setProgress 上报（stage 中文文案 + 0f..1f，<0 为不确定）；
 * - 成功输出 [KEY_SEGMENT_COUNT]；可重试错误（网络超时 / B站临时限流）走
 *   Result.retry()，其余失败输出 [KEY_ERROR] 中文原因；
 * - 用户取消（cancelUniqueWork）时协程取消，原样抛出。
 */
class TranscribeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val transcribeAudioUseCase: TranscribeAudioUseCase,
) : LongTaskWorker(appContext, params) {

    override fun notificationTitle(): String = "语音转写"

    override suspend fun doDiagnosedWork(): Result = coroutineScope {
        diag("parse inputData")
        val video = inputData.toVideoInfo()
            ?: return@coroutineScope Result.failure(workDataOf(KEY_ERROR to "任务参数缺失，请重试"))
        diag("video parsed: ${video.platform.name}:${video.videoId}")

        diag("safeSetForeground(准备中)")
        safeSetForeground("准备中…", -1f)
        diag("safeSetForeground done")

        // useCase 的进度回调非 suspend，用 StateFlow 转接为 setProgress；
        // 阶段文案变化时刷新前台通知，细粒度进度只走 setProgress（避免通知刷屏）。
        val progressState = MutableStateFlow(Triple("", -1f, 0L))
        var lastStage = ""
        var seq = 0L
        val reporter = launch {
            progressState.collect { (stage, progress, _) ->
                if (stage.isBlank()) return@collect
                // setProgress 在 try 之外：异常会直接杀死整个 doWork，此处单独捕获并记日志
                try {
                    setProgress(workDataOf(KEY_STAGE to stage, KEY_PROGRESS to progress))
                } catch (t: Throwable) {
                    diag("reporter setProgress threw: ${t.javaClass.simpleName}: ${t.message}")
                }
                if (stage != lastStage) {
                    lastStage = stage
                    safeSetForeground(stage, progress)
                }
            }
        }
        diag("reporter launched")
        try {
            diag("execute started")
            val result = transcribeAudioUseCase.execute(video) { stage, progress ->
                val text = if (stage == TranscribeStage.TRANSCRIBING && progress >= 0) {
                    "正在转写 ${(progress * 100).toInt()}%…"
                } else {
                    stage.displayText
                }
                progressState.value = Triple(text, progress, seq++)
            }
            diag("execute returned: success=${result.isSuccess}")
            result.fold(
                onSuccess = { segments ->
                    diag("success: ${segments.size} segments")
                    Result.success(workDataOf(KEY_SEGMENT_COUNT to segments.size))
                },
                onFailure = { e ->
                    // SubtitleCard 显示时会加"转写失败："前缀，此处兜底不重复加前缀；
                    // message 为空时带上异常类名，避免下次只看到"请重试"无法定位。
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: "${e.javaClass.simpleName}（无详细信息），请重试"
                    diag("failure: $msg")
                    if (isRetryable(msg)) {
                        // 注意：重试次数耗尽后 WorkManager 标记 FAILED，但 outputData 为空，
                        // 原始错误会丢失。先把错误暂存到 progress，界面可从 progress 读回，
                        // 避免用户只看到兜底文案无法定位问题。
                        setProgress(workDataOf(KEY_STAGE to "准备重试…", KEY_PROGRESS to -1f, KEY_ERROR to msg))
                        Result.retry()
                    } else {
                        Result.failure(workDataOf(KEY_ERROR to msg))
                    }
                },
            )
        } catch (e: CancellationException) {
            // 用户取消或系统回收：原样抛出，WorkManager 标记为 CANCELLED
            diag("cancelled")
            throw e
        } catch (t: Throwable) {
            diag("caught: ${t.javaClass.simpleName}: ${t.message}")
            Result.failure(workDataOf(KEY_ERROR to "转写失败：${t.message ?: t.javaClass.simpleName}"))
        } finally {
            reporter.cancel()
        }
    }

    /** 可重试错误：网络超时 / B站临时限流（-412），退避后重试可能成功。 */
    private fun isRetryable(message: String): Boolean =
        message.contains("超时") || message.contains("暂时限制") || message.contains("稍后重试")
}
