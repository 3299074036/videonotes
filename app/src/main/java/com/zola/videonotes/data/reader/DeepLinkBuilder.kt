package com.zola.videonotes.data.reader

import com.zola.videonotes.domain.model.Platform

/**
 * 时间戳 pill 跳转链接构造器。
 *
 * pill 点击后按平台拼 deep link（带时间参数），用外部浏览器 / Custom Tab 打开：
 * - YouTube：`https://www.youtube.com/watch?v={id}&t={s}s`
 * - B站：`https://www.bilibili.com/video/{bvid}?t={s}`
 *
 * videoStorageKey 格式为 `"${platform.name}:${videoId}"`（见 `storageKey()` 扩展），
 * 其中 B站的 videoId 即 bvid（M2 实物确认）。
 *
 * pill 的秒数已在 M4 经质量门禁校验，此处直接跳转，不再二次校验。
 * M6 播放器接入后可升级为站内跳转。
 */
object DeepLinkBuilder {

    /** 构造带时间参数的视频链接；平台不支持或 key 非法时返回 null。 */
    fun build(videoStorageKey: String, seconds: Long): String? {
        val videoId = videoIdOf(videoStorageKey)
        if (videoId.isEmpty()) return null
        val t = seconds.coerceAtLeast(0)
        return when (platformOf(videoStorageKey)) {
            Platform.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId&t=${t}s"
            Platform.BILIBILI -> "https://www.bilibili.com/video/$videoId?t=$t"
            else -> null
        }
    }

    /** 从存储键反解析平台；非法返回 null。 */
    fun platformOf(videoStorageKey: String): Platform? =
        runCatching { Platform.valueOf(videoStorageKey.substringBefore(":")) }.getOrNull()

    /** 从存储键反解析视频 id；非法返回空字符串。 */
    fun videoIdOf(videoStorageKey: String): String =
        videoStorageKey.substringAfter(":", "")
}
