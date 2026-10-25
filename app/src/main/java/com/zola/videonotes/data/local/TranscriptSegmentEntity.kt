package com.zola.videonotes.data.local

import androidx.room.Entity
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment

/**
 * 字幕/转写分段的 Room 存储实体。
 * 主键为 (videoId, idx)；videoId 为跨平台唯一标识（见 domain.storageKey）。
 * source 存枚举名，避免 TypeConverter。
 */
@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["videoId", "idx"],
)
data class TranscriptSegmentEntity(
    val videoId: String,
    val idx: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val source: String,
    /** 获取时间戳（毫秒），用于后续缓存策略。 */
    val fetchedAt: Long,
    /** M-05：时间戳是否为估算值（v5 新增列，旧数据迁移默认 false）。 */
    val estimated: Boolean = false,
)

/** domain → entity。 */
fun TranscriptSegment.toEntity(nowMs: Long = System.currentTimeMillis()): TranscriptSegmentEntity =
    TranscriptSegmentEntity(
        videoId = videoId,
        idx = idx,
        startMs = startMs,
        endMs = endMs,
        text = text,
        source = source.name,
        fetchedAt = nowMs,
        estimated = estimated,
    )

/** entity → domain。未知 source 兜底为 OFFICIAL。 */
fun TranscriptSegmentEntity.toDomain(): TranscriptSegment =
    TranscriptSegment(
        videoId = videoId,
        idx = idx,
        startMs = startMs,
        endMs = endMs,
        text = text,
        source = runCatching { SubtitleSource.valueOf(source) }.getOrDefault(SubtitleSource.OFFICIAL),
        estimated = estimated,
    )
