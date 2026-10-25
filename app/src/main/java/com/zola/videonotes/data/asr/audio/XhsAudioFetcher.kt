package com.zola.videonotes.data.asr.audio

import android.content.Context
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
 * 小红书音频获取器：直接下载解析器已拿到的视频直链（mp4，自带音频轨）。
 *
 * 与抖音同一路数：小红书视频是完整 mp4（音视频合一，h265/h264 masterUrl），
 * 不像 B站 DASH 那样音视频分离，所以直接下载整个 mp4 文件即可；后续 PcmDecoder
 * 用 MediaExtractor 从 mp4 里提取音频轨解码，无需事先转码。
 * 已下载过的文件直接复用（按平台 + 视频 ID 缓存）。
 */
@Singleton
class XhsAudioFetcher @Inject constructor(
    // M-16：同 VideoDownloader，走无 callTimeout 的 download 客户端。
    @javax.inject.Named("download") private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) : AudioFetcher {

    override suspend fun fetch(video: VideoInfo, onProgress: (Float) -> Unit): Result<File> {
        require(video.platform == Platform.XIAOHONGSHU) { "XhsAudioFetcher 仅处理小红书视频" }
        return try {
            val mediaUrl = video.mediaUrl.takeIf { it.isNotBlank() }
                ?: throw IOException("该小红书视频未解析到可下载的直链，请重新解析（可能触发平台风控）")
            val dest = File(
                File(context.cacheDir, "audio"),
                "${video.platform.name}_${video.videoId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.mp4",
            )
            if (dest.exists() && dest.length() > 0) {
                onProgress(1f)
                return Result.success(dest)
            }
            okHttpClient.downloadFile(
                url = mediaUrl,
                dest = dest,
                headers = video.mediaHeaders,
                onProgress = onProgress,
            )
            Result.success(dest)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            Result.failure(IOException("小红书音频获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
