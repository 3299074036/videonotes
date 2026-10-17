package com.zola.videonotes.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 笔记 DAO：同一视频重新生成时整体替换。 */
@Dao
interface NoteDao {

    /** 插入并返回行 id；同一视频已存在则替换。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes WHERE videoStorageKey = :videoKey LIMIT 1")
    suspend fun getByVideoKey(videoKey: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    suspend fun getAll(): List<NoteEntity>

    /** 观察全部笔记（创建时间倒序），供 M5 笔记库列表使用。 */
    @Query("SELECT * FROM notes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("UPDATE notes SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)
}
