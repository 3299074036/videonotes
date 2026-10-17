package com.zola.videonotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 视频 DAO：知识库条目来源。同一视频重复新增时忽略（保留最早的 createdAt）。 */
@Dao
interface VideoDao {

    /** 插入；storageKey 已存在则忽略，返回 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(video: VideoEntity): Long

    @Query("SELECT * FROM videos ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE storageKey = :key LIMIT 1")
    suspend fun getByStorageKey(key: String): VideoEntity?

    @Query("UPDATE videos SET title = :title WHERE storageKey = :key")
    suspend fun updateTitle(key: String, title: String)

    @Query("DELETE FROM videos WHERE storageKey = :key")
    suspend fun deleteByStorageKey(key: String)
}
