package com.zola.videonotes.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 协程作用域限定符：应用级后台协程（如模型下载）使用，不随界面销毁。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * ASR 相关 DI：应用级协程作用域。
 * 其余 ASR 类（ModelDownloadManager、PcmDecoder、SherpaAsrEngine、TranscribeAudioUseCase、
 * 各 AudioFetcher、AsrPreferences）均为 @Singleton + @Inject 构造，无需在此声明。
 */
@Module
@InstallIn(SingletonComponent::class)
object AsrModule {

    @ApplicationScope
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
