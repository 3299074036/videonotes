package com.zola.videonotes.domain.model

/**
 * 字幕/转写分段：一条带起止时间（毫秒）的文本。
 * 是时间戳质量门禁（M4）的依据来源：笔记中的每个时间戳都必须能在分段中 ±5 秒内找到对应文本。
 *
 * @param videoId 跨平台唯一的视频标识，格式为 "${platform.name}:${video.videoId}"，见 [storageKey]
 * @param idx 同一视频内的分段序号（从 0 开始，按时间排序）
 * @param startMs 开始时间（毫秒）
 * @param endMs 结束时间（毫秒）
 * @param text 文本内容
 * @param source 来源
 * @param estimated 时间戳是否为估算值（M-05：均匀分配而来，笔记 pill 显示“约”）
 */
data class TranscriptSegment(
    val videoId: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val source: SubtitleSource,
    val estimated: Boolean = false,
)

/**
 * 跨平台唯一的视频存储标识：YouTube 的 "abc123" 与 B站的 "abc123" 不会冲突。
 */
fun VideoInfo.storageKey(): String = "${platform.name}:$videoId"
