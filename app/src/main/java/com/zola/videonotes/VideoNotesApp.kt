package com.zola.videonotes

import android.app.Application
import androidx.work.Configuration
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.llm.local.LlmCrashBreadcrumb
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.network.HeadlessWebViewTracker
import com.zola.videonotes.data.work.VideoNotesWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视记 Application：Hilt 依赖注入入口。
 *
 * M6：实现 [Configuration.Provider]，向 WorkManager 注入 [VideoNotesWorkerFactory]
 * （手动工厂）。不用 @HiltWorker：Hilt 2.57.2 + KSP 下其绑定代码未生成，
 * 导致 Worker 建不出来、任务空失败，改手动工厂更可靠。
 */
@HiltAndroidApp
class VideoNotesApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: VideoNotesWorkerFactory

    @Inject
    lateinit var localLlmPreferences: LocalLlmPreferences

    @Inject
    lateinit var asrPreferences: AsrPreferences

    override fun onCreate() {
        super.onCreate()
        // 抖音解析：无头 WebView 需挂在前台 Activity 的窗口上，这里追踪当前 resumed Activity
        HeadlessWebViewTracker.install(this)
        // 最早安装全局崩溃捕获：闪退也落盘，用户可在设置页导出
        CrashHandler.install(this)
        // 模型目录预热解析（WP1）：assets 清单解析失败自动回退种子，不阻塞启动
        com.zola.videonotes.data.catalog.CatalogStore.init(this)
        // 2026-10-24 合并包：首次启动新版时做一次选择态迁移（老用户回填默认、新装保持 null），
        // 后台一次性执行，幂等；迁移完成前读到的选择可能短暂为 null，界面按「未选择」处理。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { localLlmPreferences.migrateSelectionIfNeeded() }
            runCatching { asrPreferences.migrateSelectionIfNeeded() }
        }
        // 上报上次本地生成的原生崩溃面包屑（纯文件 IO，不加载 native 库）：
        // 把信号 + 里程碑写进诊断日志，导出日志时能带出来
        LlmCrashBreadcrumb.reportPending(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
