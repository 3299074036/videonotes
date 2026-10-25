package com.zola.videonotes.data.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/**
 * 长任务 Worker 基类（M6）：统一前台服务通知逻辑。
 *
 * 子类实现 [notificationTitle] 与 [doDiagnosedWork]。[doWork] 为 final，
 * 用模板方法包裹子类的真实逻辑，确保：
 * 1. doWork 进入即写诊断日志（可区分"Worker 未被实例化"与"执行中崩溃"）；
 * 2. 任何未被捕获的 Throwable（包括 try 块之外的）都会落盘并转为带类名的
 *    中文错误输出，不再出现无任何错误数据的 FAILED。
 *
 * FOREGROUND_SERVICE_TYPE_DATA_SYNC 需要 API 29+，低版本传 0
 * （该常量为编译期常量，低版本引用安全）。
 */
abstract class LongTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    /** 前台通知标题，如"语音转写" / "笔记生成"。 */
    protected abstract fun notificationTitle(): String

    /** 子类真实逻辑；基类负责诊断日志与兜底。 */
    protected abstract suspend fun doDiagnosedWork(): Result

    /** 诊断日志 tag，默认类名。 */
    protected open fun diagTag(): String = this::class.java.simpleName

    final override suspend fun doWork(): Result {
        val tag = diagTag()
        WorkerDiagnosticLog.log(applicationContext, tag, "doWork entered")
        // 前台通知第一时间挂上：普通任务下 setForeground 只是尽力而为的通知，
        // 失败会被 safeSetForeground 吞掉降级，不会杀任务；但越早调，通知越及时。
        safeSetForeground(notificationTitle(), -1f)
        return try {
            val result = doDiagnosedWork()
            WorkerDiagnosticLog.log(applicationContext, tag, "doWork returned $result")
            result
        } catch (e: CancellationException) {
            WorkerDiagnosticLog.log(applicationContext, tag, "doWork cancelled")
            throw e
        } catch (t: Throwable) {
            // 最后一道防线：子类 try-catch 之外抛出的任何异常都在这里被捕获，
            // 落盘 + 转为带类名的中文错误，不再产生无数据的 FAILED。
            val msg = "${t.javaClass.simpleName}：${t.message?.takeIf { it.isNotBlank() } ?: "无详细信息"}"
            WorkerDiagnosticLog.log(applicationContext, tag, "doWork threw: $msg")
            WorkerDiagnosticLog.log(
                applicationContext, tag,
                "stack: " + t.stackTrace.take(8).joinToString(" <- ") { it.toString() },
            )
            Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    /** 写诊断日志的便捷方法。 */
    protected fun diag(message: String) {
        WorkerDiagnosticLog.log(applicationContext, diagTag(), message)
    }

    /** 构造前台服务信息；progress < 0 表示不确定进度。
     * 通知 ID 按任务 storageKey 散列：多任务并发时各用各的通知，互不覆盖
     *（此前全用固定 1001，多任务同时跑时通知互相顶掉、进度串台）。 */
    protected fun foregroundInfo(stageText: String, progress: Float): ForegroundInfo {
        val notification = LongTaskNotifications.build(
            applicationContext,
            notificationTitle(),
            stageText,
            progress,
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        val storageKey = inputData.getString(KEY_STORAGE_KEY).orEmpty()
        val notificationId = if (storageKey.isNotBlank()) {
            2000 + (storageKey.hashCode().let { if (it < 0) -it else it } % 1000)
        } else {
            LONG_TASK_NOTIFICATION_ID
        }
        return ForegroundInfo(notificationId, notification, serviceType)
    }

    /**
     * 安全地刷新前台通知。
     *
     * setForeground 在 try 块之外抛异常（如 API 31+ 应用在后台时启动前台服务被系统拒绝，
     * ForegroundServiceStartNotAllowedException）会导致整个 doWork 直接崩溃，
     * WorkManager 标记 FAILED 但无任何输出数据，界面只能显示兜底文案。
     * 此处捕获后降级为无通知继续执行（界面仍可通过 WorkInfo 进度观察状态），
     * 不让一个通知问题杀死整个转写/生成任务。
     */
    protected suspend fun safeSetForeground(stageText: String, progress: Float) {
        try {
            setForeground(foregroundInfo(stageText, progress))
        } catch (_: Exception) {
            // 降级：无前台通知继续执行
        }
    }
}
