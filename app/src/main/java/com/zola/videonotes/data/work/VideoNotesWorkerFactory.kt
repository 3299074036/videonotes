package com.zola.videonotes.data.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.zola.videonotes.data.asr.TranscribeAudioUseCase
import com.zola.videonotes.data.llm.LlmEngineResolver
import com.zola.videonotes.data.llm.NoteGenerator
import com.zola.videonotes.data.pipeline.GenerateCrashGuard
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 手动 WorkerFactory（替代 @HiltWorker）。
 *
 * 背景：Hilt 2.57.2 + KSP 下 @HiltWorker 的绑定代码未生成（build/generated
 * 中无对应产物），HiltWorkerFactory 根本不认识这两个 Worker。任务入队后
 * doWork 从未被调用，WorkManager 直接标 FAILED 且无任何错误数据——
 * 这正是真机上"转写失败：请重试"空错误的根因。
 *
 * 改为手动工厂，直接调用 Worker 构造函数，不依赖 Hilt 的 Worker codegen。
 * 依赖用 Provider 延迟获取，避免 Application 启动时就实例化重型依赖。
 * 创建过程写诊断日志，可在设置页导出查看。
 */
@Singleton
class VideoNotesWorkerFactory @Inject constructor(
    private val transcribeAudioUseCase: Provider<TranscribeAudioUseCase>,
    private val noteGenerator: Provider<NoteGenerator>,
    private val engineResolver: Provider<LlmEngineResolver>,
    private val crashGuard: Provider<GenerateCrashGuard>,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        WorkerDiagnosticLog.log(appContext, "WorkerFactory", "createWorker: $workerClassName")
        return try {
            val worker = when (workerClassName) {
                TranscribeWorker::class.java.name ->
                    TranscribeWorker(appContext, workerParameters, transcribeAudioUseCase.get())
                GenerateNoteWorker::class.java.name ->
                    GenerateNoteWorker(
                        appContext,
                        workerParameters,
                        noteGenerator.get(),
                        engineResolver.get(),
                        crashGuard.get(),
                    )
                else -> null
            }
            WorkerDiagnosticLog.log(
                appContext, "WorkerFactory",
                "created: ${worker?.javaClass?.simpleName ?: "null (not ours)"}",
            )
            worker
        } catch (t: Throwable) {
            WorkerDiagnosticLog.log(
                appContext, "WorkerFactory",
                "create failed: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        }
    }
}
