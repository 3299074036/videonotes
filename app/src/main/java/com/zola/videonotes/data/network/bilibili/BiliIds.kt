package com.zola.videonotes.data.network.bilibili

import com.zola.videonotes.domain.model.VideoInfo

/**
 * 从 VideoInfo.videoId 解析 B站 BV 号 / AV 号。
 * @return Pair(bvid, aid)，二者其一为 null；都解析失败时返回 (null, null)
 */
internal fun extractBiliIds(video: VideoInfo): Pair<String?, Long?> {
    val id = video.videoId
    return when {
        id.startsWith("BV", ignoreCase = true) -> id to null
        id.startsWith("av", ignoreCase = true) -> null to id.substring(2).toLongOrNull()
        else -> null to null
    }
}
