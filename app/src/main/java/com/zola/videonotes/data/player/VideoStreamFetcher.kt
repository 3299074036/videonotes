package com.zola.videonotes.data.player

import com.zola.videonotes.data.network.bilibili.BiliDashFetcher
import com.zola.videonotes.data.network.newpipe.NewPipeSetup
import com.zola.videonotes.data.parser.extractFirstUrl
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 播放器 UA：直链（googlevideo / B站）对空 UA 不友好。 */
internal const val PLAYER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/** 可播放流：直链 URL + 请求头（B站直链必须带 Referer，否则 403）。 */
data class PlayableStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    /**
     * 独立音频流 URL（B站 DASH 音视频分离时使用）。
     * 为 null 表示视频流自带音频（如 YouTube progressive mp4），无需合成。
     */
    val audioUrl: String? = null,
)

/**
 * progressive 候选流（纯数据类）。
 * 从 NewPipe VideoStream 映射而来，使选择逻辑成为纯函数，JVM 可测。
 */
data class ProgressiveCandidate(
    val url: String,
    val itag: Int,
    val height: Int,
    val isMp4: Boolean,
)

/**
 * 站内播放器视频流获取器（M6）。
 *
 * - YouTube：NewPipe `videoStreams`（progressive，非 DASH）中选最优；
 * - B站：playurl dash.video 首个有效直链；
 * - P1 平台（抖音/小红书）与失败返回中文错误，不做任何绕过。
 */
@Singleton
class VideoStreamFetcher @Inject constructor(
    private val newPipeSetup: NewPipeSetup,
    private val dashFetcher: BiliDashFetcher,
) {

    /** 取可播放流；失败时 Result 携带中文原因。 */
    suspend fun fetch(video: VideoInfo): Result<PlayableStream> =
        when (video.platform) {
            Platform.YOUTUBE -> fetchYoutube(video)
            Platform.BILIBILI -> fetchBilibili(video)
            // 抖音：解析器已通过 WebView 拿到直链，直接用（无需另取流）
            Platform.DOUYIN -> {
                val url = video.mediaUrl.takeIf { it.isNotBlank() }
                if (url != null) {
                    Result.success(PlayableStream(url, video.mediaHeaders))
                } else {
                    Result.failure(IOException("该抖音视频未解析到可播放的直链，请重新解析"))
                }
            }
            else -> Result.failure(IOException("${video.platform.displayName}暂不支持站内播放"))
        }

    private suspend fun fetchYoutube(video: VideoInfo): Result<PlayableStream> {
        return try {
            newPipeSetup.ensureInit()
            val cleanUrl = extractFirstUrl(video.sourceUrl) ?: video.sourceUrl.trim()
            val candidates = withContext(Dispatchers.IO) {
                val service = NewPipe.getService(0)
                val info = StreamInfo.getInfo(service, cleanUrl)
                info.videoStreams
                    .filter { !it.isVideoOnly }
                    .map {
                        ProgressiveCandidate(
                            url = it.content.orEmpty(),
                            itag = it.itag,
                            height = it.height,
                            isMp4 = it.format == MediaFormat.MPEG_4,
                        )
                    }
            }
            val best = selectProgressive(candidates)
                ?: throw IOException("未找到可播放的视频流")
            Result.success(PlayableStream(best.url, mapOf("User-Agent" to PLAYER_UA)))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("视频流获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    private suspend fun fetchBilibili(video: VideoInfo): Result<PlayableStream> {
        return try {
            val dash = dashFetcher.fetch(video).getOrThrow()
            val videoUrl = dash.video
                ?.firstOrNull { it.baseUrl.isNotBlank() }
                ?.baseUrl
                ?: throw IOException("该视频暂不支持站内播放（可能为付费内容）")
            // B站 DASH 音视频分离：视频流无音频轨，必须同时取音频流合成播放，
            // 否则有画面无声音。
            val audioUrl = dash.audio
                ?.filter { it.baseUrl.isNotBlank() }
                ?.maxByOrNull { it.bandwidth }
                ?.baseUrl
            Result.success(
                PlayableStream(
                    url = videoUrl,
                    headers = mapOf(
                        "Referer" to "https://www.bilibili.com",
                        "User-Agent" to PLAYER_UA,
                    ),
                    audioUrl = audioUrl,
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("视频流获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    companion object {
        /**
         * progressive 流选择（纯函数）：
         * mp4 优先 → itag 22（720p）→ itag 18（360p）→ 其余按高度降序。
         * 空列表 / 全无效 URL 返回 null。
         */
        fun selectProgressive(candidates: List<ProgressiveCandidate>): ProgressiveCandidate? {
            val valid = candidates.filter { it.url.isNotBlank() }
            if (valid.isEmpty()) return null
            val itagRank = mapOf(22 to 0, 18 to 1)
            return valid.sortedWith(
                compareBy<ProgressiveCandidate> { if (it.isMp4) 0 else 1 }
                    .thenBy { itagRank[it.itag] ?: 2 }
                    .thenByDescending { it.height },
            ).first()
        }
    }
}
