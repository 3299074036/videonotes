package com.zola.videonotes.di

import android.content.Context
import androidx.room.Room
import com.zola.videonotes.data.local.AppDatabase
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.TranscriptSegmentDao
import com.zola.videonotes.data.local.VideoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** 本地数据库 DI：Room Database 与 DAO。数据全在 App 私有目录，卸载自动清理。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "videonotes.db")
            // M4：v1→v2 新增笔记表。M6 前简化处理：破坏性迁移。
            // 笔记可由文稿重新生成，不视为不可恢复数据。
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideTranscriptSegmentDao(database: AppDatabase): TranscriptSegmentDao =
        database.transcriptSegmentDao()

    @Provides
    fun provideNoteDao(database: AppDatabase): NoteDao =
        database.noteDao()

    @Provides
    fun provideVideoDao(database: AppDatabase): VideoDao =
        database.videoDao()
}
