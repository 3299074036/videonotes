package com.zola.videonotes.ui.metadata

/** 秒数格式化为 m:ss 或 h:mm:ss，用于时长展示。 */
fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

/** 毫秒时间戳格式化为 m:ss，用于字幕分段预览。 */
fun formatTimestamp(millis: Long): String = formatDuration(millis / 1000)
