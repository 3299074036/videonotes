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
        // S-03（2026-10-25）：删除 fallbackToDestructiveMigration。升版本必须手写 Migration；
        // 无 Migration 时 Room 会在启动时直接报错（fail-fast），而不是静默清空用户全部数据。
        // 历史背景：M4 v1→v2 时曾用破坏性迁移，笔记可重生成；但 v4 已含视频表与文稿，
        // 且小红书旧条目 token 丢失后不可恢复，清库代价已不可接受。
        Room.databaseBuilder(context, AppDatabase::class.java, "videonotes.db")
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
