package com.zola.videonotes.data.parser

import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import com.zola.videonotes.data.auth.YoutubeCookieStore
import com.zola.videonotes.data.network.newpipe.NewPipeSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube 解析器（P0）：基于 NewPipeExtractor 获取标题 / 封面 / 时长 / 作者。
 *
 * 已知限制：NewPipeExtractor 内部个别 URL 解码路径调用了 API 33 才有的
 * URLDecoder.decode(String, Charset)，在 Android 8~12 设备上可能抛
 * NoSuchMethodError；此处统一捕获为友好错误，M2 视情况跟进上游修复。
 */
@Singleton
class YoutubeParser @Inject constructor(
    private val newPipeSetup: NewPipeSetup,
    private val youtubeCookieStore: YoutubeCookieStore,
) : VideoParser {

    override val platform: Platform = Platform.YOUTUBE

    /**
     * 覆盖 watch / youtu.be / shorts / live / embed / music 等常见形态，
     * 要求 11 位视频 ID，避免把播放列表等链接误判。
     */
    private val idPattern = Regex(
        """(?i)(?:youtube\.com/(?:watch\?[^#\s]*v=|shorts/|live/|embed/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
    )

    override fun matches(url: String): Boolean =
        idPattern.containsMatchIn(url) || YOUTUBE_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            newPipeSetup.ensureInit()
            // 分享文本可能夹带标题杂质，先提取干净链接
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            val service = NewPipe.getService(YOUTUBE_SERVICE_ID)
            val linkHandler = service.streamLHFactory.fromUrl(cleanUrl)
            val info = withContext(Dispatchers.IO) {
                // v0.26.x 移除了 (StreamingService, LinkHandler) 重载，改传 URL 字符串
                StreamInfo.getInfo(service, cleanUrl)
            }
            val cover = info.thumbnails
                .maxByOrNull { it.height * it.width }
                ?.url.orEmpty()
            Result.success(
                VideoInfo(
                    platform = Platform.YOUTUBE,
                    videoId = linkHandler.id,
                    title = info.name.orEmpty(),
                    coverUrl = cover,
                    durationSec = info.duration.coerceAtLeast(0),
                    author = info.uploaderName.orEmpty(),
                    sourceUrl = url.trim(),
                ),
            )
        } catch (t: Throwable) {
            // 含 NoSuchMethodError 在内的所有异常统一转为中文友好错误；
            // 已导入 cookie 仍报 LOGIN_REQUIRED 时，提示登录态可能已过期（让用户知道该重导，而非链接问题）
            val rawMsg = t.message ?: t.javaClass.simpleName
            val expiredHint =
                if (rawMsg.contains("LOGIN_REQUIRED") && youtubeCookieStore.isImported.value) {
                    "（登录态可能已过期，请重新导入 cookie）"
                } else {
                    ""
                }
            Result.failure(IOException("YouTube 解析失败：$rawMsg$expiredHint，请检查链接或稍后重试"))
        }
    }

    /** YouTube 在 NewPipe 内置服务中的 ID，固定为 0。 */
    private companion object {
        const val YOUTUBE_SERVICE_ID = 0
        val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    }
}
