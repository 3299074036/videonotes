package com.zola.videonotes

import android.app.Application
import androidx.work.Configuration
import com.zola.videonotes.data.work.VideoNotesWorkerFactory
import dagger.hilt.android.HiltAndroidApp
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

    override fun onCreate() {
        super.onCreate()
        // 最早安装全局崩溃捕获：闪退也落盘，用户可在设置页导出
        CrashHandler.install(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
