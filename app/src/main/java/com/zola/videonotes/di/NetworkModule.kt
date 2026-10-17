package com.zola.videonotes.di

import com.zola.videonotes.BuildConfig
import com.zola.videonotes.data.auth.BiliCookieInterceptor
import com.zola.videonotes.data.network.RetryInterceptor
import com.zola.videonotes.data.network.bilibili.BiliApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

/** 网络层 DI：全局 OkHttp（含超时/重试/日志）与各平台 Retrofit。 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** B站 API 需要的请求头：无登录态也可访问公开视频信息接口。 */
    private const val BILIBILI_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    private const val BILIBILI_REFERER = "https://www.bilibili.com"

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    @Named("bilibili")
    fun provideBilibiliRetrofit(
        client: OkHttpClient,
        cookieInterceptor: BiliCookieInterceptor,
    ): Retrofit {
        // B站 API 对默认 Java UA 不友好，统一注入浏览器 UA 与 Referer；
        // 已登录时再带上 SESSDATA Cookie（需登录的接口如部分字幕可用）。
        val biliClient = client.newBuilder()
            .addInterceptor(cookieInterceptor)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", BILIBILI_UA)
                    .header("Referer", BILIBILI_REFERER)
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.bilibili.com/")
            .client(biliClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideBiliApiService(@Named("bilibili") retrofit: Retrofit): BiliApiService =
        retrofit.create(BiliApiService::class.java)
}
