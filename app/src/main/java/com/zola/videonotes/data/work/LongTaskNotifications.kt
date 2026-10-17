package com.zola.videonotes.data.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/** 长任务通知渠道 id。 */
internal const val LONG_TASK_CHANNEL_ID = "long_tasks"

/** 长任务前台通知 id（转写与笔记生成共用一个槽位，同时只跑一个长任务时不会打架）。 */
internal const val LONG_TASK_NOTIFICATION_ID = 1001

/**
 * 长任务通知帮助（M6）。
 *
 * 渠道重要性 LOW：只在通知栏显示进度，不打扰用户。
 * smallIcon 使用系统下载图标（项目无自定义通知图标，避免新增资源）。
 */
internal object LongTaskNotifications {

    /** 幂等创建通知渠道（API 26+ 需要）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(LONG_TASK_CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                LONG_TASK_CHANNEL_ID,
                "长任务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "语音转写与笔记生成的后台进度通知"
            },
        )
    }

    /** 构造前台服务通知；progress < 0 表示不确定进度。 */
    fun build(context: Context, title: String, text: String, progress: Float): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, LONG_TASK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progress in 0f..1f) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }
}
