package com.zola.videonotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App 本地数据库（version 4）。
 * v1：字幕/转写分段表；v2：笔记表；v3：笔记表新增 videoTitle 列；v4：视频表（知识库条目来源）。
 * M6 前简化处理：破坏性迁移（fallbackToDestructiveMigration，见 DatabaseModule）。
 */
@Database(
    entities = [TranscriptSegmentEntity::class, NoteEntity::class, VideoEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun noteDao(): NoteDao
    abstract fun videoDao(): VideoDao
}
