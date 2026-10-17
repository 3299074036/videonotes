package com.zola.videonotes.data.asr.audio

import android.content.Context
import com.zola.videonotes.data.network.newpipe.NewPipeSetup
import com.zola.videonotes.data.parser.extractFirstUrl
import com.zola.videonotes.domain.asr.AudioFetcher
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 下载用的浏览器 UA：YouTube 音频直链（googlevideo）对空 UA 不友好。 */
private const val AUDIO_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/**
 * YouTube 音频获取器（P0）：NewPipeExtractor 取 audioStreams 中平均码率最高的音频流 URL 下载。
 * 下载到 cacheDir/audio/，文件名 "${platform}_${videoId}.m4a"。
 */
@Singleton
class YoutubeAudioFetcher @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val newPipeSetup: NewPipeSetup,
    @ApplicationContext private val context: Context,
) : AudioFetcher {

    override suspend fun fetch(video: VideoInfo, onProgress: (Float) -> Unit): Result<File> {
        require(video.platform == Platform.YOUTUBE) { "YoutubeAudioFetcher 仅处理 YouTube 视频" }
        return try {
            newPipeSetup.ensureInit()
            val cleanUrl = extractFirstUrl(video.sourceUrl) ?: video.sourceUrl.trim()
            val audioUrl = withContext(Dispatchers.IO) {
                val service = NewPipe.getService(0)
                val info = StreamInfo.getInfo(service, cleanUrl)
                info.audioStreams
                    .filter { it.content.isNotBlank() }
                    .maxByOrNull { it.averageBitrate }
                    ?.content
                    ?: throw IOException("未找到可下载的音频流")
            }
            val dest = File(
                File(context.cacheDir, "audio"),
                "${video.platform.name}_${video.videoId}.m4a",
            )
            if (dest.exists() && dest.length() > 0) {
                onProgress(1f)
                return Result.success(dest)
            }
            okHttpClient.downloadFile(
                url = audioUrl,
                dest = dest,
                headers = mapOf(
                    "User-Agent" to AUDIO_UA,
                    "Referer" to "https://www.youtube.com/",
                ),
                onProgress = onProgress,
            )
            Result.success(dest)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("YouTube 音频获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
