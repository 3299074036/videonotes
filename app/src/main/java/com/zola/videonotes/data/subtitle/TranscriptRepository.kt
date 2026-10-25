package com.zola.videonotes.data.subtitle

import com.zola.videonotes.data.local.TranscriptSegmentDao
import com.zola.videonotes.data.local.toDomain
import com.zola.videonotes.data.local.toEntity
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 字幕仓库：按平台选择获取器 → 拉取 → 存 Room → 返回。
 * P1 平台（抖音/小红书）与未知平台返回明确的"暂不支持"错误，不静默失败。
 */
@Singleton
class TranscriptRepository @Inject constructor(
    private val dao: TranscriptSegmentDao,
    private val youtubeFetcher: YoutubeSubtitleFetcher,
    private val bilibiliFetcher: BilibiliSubtitleFetcher,
) {

    /** 读取本地已缓存的分段（按 idx 排序），无缓存时返回空列表。 */
    suspend fun getCached(video: VideoInfo): List<TranscriptSegment> =
        dao.getByVideoId(video.storageKey()).map { it.toDomain() }

    /**
     * 拉取字幕并整体替换写入本地。
     * 无字幕时返回 segments 为空的成功结果（上层走 ASR 兜底）。
     */
    suspend fun fetchAndStore(video: VideoInfo): Result<SubtitleFetchResult> {
        val fetcher = when (video.platform) {
            Platform.YOUTUBE -> youtubeFetcher
            Platform.BILIBILI -> bilibiliFetcher
            else -> return Result.failure(
                IOException("${video.platform.displayName}暂不支持字幕获取"),
            )
        }
        return fetcher.fetch(video).onSuccess { result ->
            // L-02：空字幕成功不清空已有缓存（一次空响应不应毁掉此前可用的字幕）。
            if (result.segments.isNotEmpty()) {
                dao.replaceAll(video.storageKey(), result.segments.map { it.toEntity() })
            }
        }
    }

    /** 清除某视频的本地字幕缓存。 */
    suspend fun clear(video: VideoInfo) {
        dao.deleteByVideoId(video.storageKey())
    }

    /** 按 storageKey 清除字幕缓存（删除知识库条目时级联用）。 */
    suspend fun clearByStorageKey(storageKey: String) {
        dao.deleteByVideoId(storageKey)
    }
}
