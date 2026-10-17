package com.zola.videonotes.domain.subtitle

import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.VideoInfo

/**
 * 字幕获取结果。
 *
 * @param segments 按时间排序的分段；videoId 已填为 [com.zola.videonotes.domain.model.storageKey]，idx 从 0 开始
 * @param source 本次获取的字幕来源
 * @param languageLabel 字幕语言展示名（如 "中文（中国）"），未知时为空
 */
data class SubtitleFetchResult(
    val segments: List<TranscriptSegment>,
    val source: SubtitleSource,
    val languageLabel: String,
)

/**
 * 平台字幕获取器：各平台独立实现，接口变化时只改一处。
 * 无字幕时返回成功但 segments 为空（上层走 ASR 兜底），不要抛异常；
 * 只有明确失败（网络、风控、登录要求）才返回 failure，并携带面向用户的中文原因。
 */
interface SubtitleFetcher {
    suspend fun fetch(video: VideoInfo): Result<SubtitleFetchResult>
}
