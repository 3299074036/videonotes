package com.zola.videonotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** 字幕分段 DAO：按视频整体替换写入，按 idx 排序读取。 */
@Dao
interface TranscriptSegmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_segments WHERE videoId = :videoId ORDER BY idx ASC")
    suspend fun getByVideoId(videoId: String): List<TranscriptSegmentEntity>

    @Query("DELETE FROM transcript_segments WHERE videoId = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE videoId = :videoId")
    suspend fun countByVideoId(videoId: String): Int

    /** 整体替换：先删后插，保证同一视频的分段与最新一次获取一致。 */
    @Transaction
    suspend fun replaceAll(videoId: String, segments: List<TranscriptSegmentEntity>) {
        deleteByVideoId(videoId)
        if (segments.isNotEmpty()) {
            insertAll(segments)
        }
    }
}
