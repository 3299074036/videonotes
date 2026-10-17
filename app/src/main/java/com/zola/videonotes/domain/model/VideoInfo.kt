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
 */
data class VideoInfo(
    val platform: Platform,
    val videoId: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Long,
    val author: String,
    val sourceUrl: String,
)
