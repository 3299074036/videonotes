package com.zola.videonotes.data.notes

import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.toDomain
import com.zola.videonotes.domain.model.Note
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 笔记仓库（M5）：笔记库 / 详情页的数据入口。
 * 同一视频重新生成时整体替换的语义由 DAO 的 REPLACE 策略保证。
 */
@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
) {

    /** 观察全部笔记（创建时间倒序），供笔记库列表使用。 */
    fun observeAll(): Flow<List<Note>> =
        noteDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    suspend fun getById(id: Long): Note? =
        noteDao.getById(id)?.toDomain()

    suspend fun getByVideoKey(videoStorageKey: String): Note? =
        noteDao.getByVideoKey(videoStorageKey)?.toDomain()

    suspend fun rename(id: Long, title: String) {
        noteDao.updateTitle(id, title)
    }

    suspend fun delete(id: Long) {
        noteDao.deleteById(id)
    }
}
