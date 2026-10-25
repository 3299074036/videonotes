package com.zola.videonotes.domain.model

/**
 * 视频元信息：各平台解析器统一产出的数据结构。
 *
 * @param platform 平台
 * @param videoId 平台内的视频唯一标识（如 YouTube 的 11 位 ID、B站 BV 号）
 * @param title 标题
 * @param coverUrl 封面图 URL（可能为空）
 * @param durationSec 时长（秒，未知时为 0）
 * @param author 作者/UP 主（可能为空）
 * @param sourceUrl 用户输入的原始链接
 * @param mediaUrl 可直接下载/播放的媒体直链（解析器已拿到时填写，如抖音 WebView 提取的 playAddr；
 *   为空表示需由 VideoStreamFetcher/AudioFetcher 按平台另行取流）
 * @param mediaHeaders 请求 mediaUrl 时需带的请求头（如 Referer、User-Agent）
 */
data class VideoInfo(
    val platform: Platform,
    val videoId: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Long,
    val author: String,
    val sourceUrl: String,
    val mediaUrl: String = "",
    val mediaHeaders: Map<String, String> = emptyMap(),
    /** 分P数（仅 B站解析时填充，>1 表示多分P视频；其余平台恒为 1）。 */
    val partCount: Int = 1,
)
