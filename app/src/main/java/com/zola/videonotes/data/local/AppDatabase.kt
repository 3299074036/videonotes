package com.zola.videonotes.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * App 本地数据库（version 5）。
 * v1：字幕/转写分段表；v2：笔记表；v3：笔记表新增 videoTitle 列；v4：视频表（知识库条目来源）。
 * v5：分段表新增 estimated 列（M-05：均匀分配时间戳标记为估算）。
 * S-03（2026-10-25）：禁止破坏性迁移。升版本必须手写 Migration（见 DatabaseModule），
 * schema 由 exportSchema + ksp room.schemaLocation 导出到 app/schemas/，迁移测试发版前必跑。
 */
@Database(
    entities = [TranscriptSegmentEntity::class, NoteEntity::class, VideoEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao
    abstract fun noteDao(): NoteDao
    abstract fun videoDao(): VideoDao
}
