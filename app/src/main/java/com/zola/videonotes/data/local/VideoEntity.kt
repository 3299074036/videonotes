package com.zola.videonotes.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey

/**
 * 视频表：知识库主页的条目来源。
 * 解析链接成功后立即落库一条，用户点 FAB 新增后列表立刻出现（状态"解析中"），
 * 不用等到笔记生成完。是否有字幕/笔记通过关联表推导，不设 status 字段。
 */
@Entity(
    tableName = "videos",
    indices = [Index(value = ["storageKey"], unique = true)],
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 跨平台唯一键，格式 "${platform.name}:${videoId}"，见 [storageKey]。 */
    val storageKey: String,
    val url: String,
    val title: String,
    /** 平台名（Platform.name），Room 存枚举名。 */
    val platform: String,
    val author: String,
    val durationSec: Long,
    val coverUrl: String,
    val createdAt: Long,
)

fun VideoInfo.toEntity(now: Long = System.currentTimeMillis()): VideoEntity = VideoEntity(
    storageKey = storageKey(),
    url = sourceUrl,
    title = title,
    platform = platform.name,
    author = author,
    durationSec = durationSec,
    coverUrl = coverUrl,
    createdAt = now,
)

fun VideoEntity.platformEnum(): Platform =
    runCatching { Platform.valueOf(platform) }.getOrDefault(Platform.UNKNOWN)
