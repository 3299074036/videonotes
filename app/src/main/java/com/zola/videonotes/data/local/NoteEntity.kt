package com.zola.videonotes.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zola.videonotes.domain.model.Note

/**
 * 笔记表：一份笔记对应一个视频（videoStorageKey 唯一）。
 * markdown 存完整笔记源码（含 [[mm:ss]] 时间戳 pill，M5 阅读器按此渲染）。
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["videoStorageKey"], unique = true)],
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoStorageKey: String,
    val title: String,
    val markdown: String,
    val createdAt: Long,
    val llmModel: String,
    /** 生成状态：DONE（M4 只有完成态，失败不落库）。 */
    val status: String = "DONE",
    /** 来源视频标题（M5 笔记库列表展示）。 */
    val videoTitle: String = "",
)

fun NoteEntity.toDomain(): Note = Note(
    id = id,
    videoStorageKey = videoStorageKey,
    title = title,
    markdown = markdown,
    createdAt = createdAt,
    llmModel = llmModel,
    videoTitle = videoTitle,
)

fun Note.toEntity(): NoteEntity = NoteEntity(
    id = id,
    videoStorageKey = videoStorageKey,
    title = title,
    markdown = markdown,
    createdAt = createdAt,
    llmModel = llmModel,
    videoTitle = videoTitle,
)
