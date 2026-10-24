package com.zola.videonotes.di

import com.zola.videonotes.BuildConfig
import com.zola.videonotes.data.auth.BiliCookieInterceptor
import com.zola.videonotes.data.auth.YoutubeCookieInterceptor
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
    fun provideOkHttpClient(
        youtubeCookieInterceptor: YoutubeCookieInterceptor,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .addInterceptor(loggingInterceptor())
            // 放日志拦截器之后：BASIC 级本就不打 header，顺序上再保一层（日志看不到 Cookie）。
            // host 门控在拦截器内部：只影响 YouTube 相关域，其他请求原样放行。
            .addInterceptor(youtubeCookieInterceptor)
            .build()
    }

    /**
     * 模型下载专用客户端（2026-10-24 定版包后修复）：
     * 全局客户端的 callTimeout（90 秒）是整次调用的总墙钟预算，
     * GB 级模型下载必然在尾巴被掐断；下载客户端禁用 callTimeout，
     * 只保留建连 15 秒与真断流 30 秒的保护。RetryInterceptor 仍挂载：
     * 它只在响应头到达前生效（建连/网关重试），响应体流式读取中的
     * 超时发生在拦截器之外，不会被它重试，失败后由内核 Range 续传接手。
     */
    @Provides
    @Singleton
    @Named("download")
    fun provideDownloadOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 2))
            .addInterceptor(loggingInterceptor())
            .build()
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
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
