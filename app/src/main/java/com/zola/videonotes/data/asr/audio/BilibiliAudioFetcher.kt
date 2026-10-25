package com.zola.videonotes.data.asr.audio

import android.content.Context
import com.zola.videonotes.data.network.bilibili.BiliDashFetcher
import com.zola.videonotes.domain.asr.AudioFetcher
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站音频获取器（P0）：经 [BiliDashFetcher] 取 dash，选码率最高的音频流 URL 下载。
 * 公开视频免 WBI；大会员/付费视频无 dash 音频流时返回明确错误，不做任何绕过。
 * B站直链必须带 Referer，否则 403。
 *
 * M6 重构：pagelist/playurl 链路下沉到 BiliDashFetcher，与站内播放器复用。
 */
@Singleton
class BilibiliAudioFetcher @Inject constructor(
    private val dashFetcher: BiliDashFetcher,
    // M-16：同 VideoDownloader，走无 callTimeout 的 download 客户端。
    @javax.inject.Named("download") private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) : AudioFetcher {

    override suspend fun fetch(video: VideoInfo, onProgress: (Float) -> Unit): Result<File> {
        require(video.platform == Platform.BILIBILI) { "BilibiliAudioFetcher 仅处理 B站视频" }
        return try {
            val dash = dashFetcher.fetch(video).getOrThrow()
            val audioUrl = dash.audio
                ?.filter { it.baseUrl.isNotBlank() }
                ?.maxByOrNull { it.bandwidth }
                ?.baseUrl
                ?: throw IOException("该视频暂不支持音频获取（可能为付费内容），无法进行语音转写")

            val dest = File(
                File(context.cacheDir, "audio"),
                "${video.platform.name}_${video.videoId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.m4a",
            )
            if (dest.exists() && dest.length() > 0) {
                onProgress(1f)
                return Result.success(dest)
            }
            okHttpClient.downloadFile(
                url = audioUrl,
                dest = dest,
                // L-15：补 UA，与播放头（VideoStreamFetcher）对齐；此前只有 Referer，个别 CDN 可能 403。
                headers = mapOf(
                    "Referer" to "https://www.bilibili.com",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                ),
                onProgress = onProgress,
            )
            Result.success(dest)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            Result.failure(IOException("B站音频获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
