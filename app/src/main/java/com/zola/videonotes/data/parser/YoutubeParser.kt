package com.zola.videonotes.data.parser

import com.zola.videonotes.data.asr.executeCancellable
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import com.zola.videonotes.data.auth.YoutubeCookieStore
import com.zola.videonotes.data.network.newpipe.NewPipeSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * YouTube 解析器（P0）：基于 NewPipeExtractor 获取标题 / 封面 / 时长 / 作者。
 *
 * 已知限制：NewPipeExtractor 内部个别 URL 解码路径调用了 API 33 才有的
 * URLDecoder.decode(String, Charset)，在 Android 8~12 设备上可能抛
 * NoSuchMethodError；此处统一捕获为友好错误，M2 视情况跟进上游修复。
 *
 * P4：NewPipe 报 NEED_LOGIN / IP_BLOCKED（风控）时，退到 watch 页 HTML 抢一次
 * 元数据（og: + JSON-LD），结果以 isMetadataOnly 标记——仅元数据，无播放地址，
 * 抢不到仍返回原错误，不掩盖真实失败。
 */
@Singleton
class YoutubeParser @Inject constructor(
    private val newPipeSetup: NewPipeSetup,
    private val youtubeCookieStore: YoutubeCookieStore,
    // 默认客户端（带 YoutubeCookieInterceptor）：兜底 HTML 请求同样带已导入的 cookie。
    private val okHttpClient: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.YOUTUBE

    /**
     * 覆盖 watch / youtu.be / shorts / live / embed / music 等常见形态，
     * 要求 11 位视频 ID，避免把播放列表等链接误判。
     * L-12：nocookie embed 形态并入本正则（此前纯 host 回退会放行非视频链接）。
     */
    private val idPattern = Regex(
        """(?i)(?:youtube\.com/(?:watch\?[^#\s]*v=|shorts/|live/|embed/|v/)|youtu\.be/|youtube-nocookie\.com/embed/)([A-Za-z0-9_-]{11})""",
    )

    override fun matches(url: String): Boolean {
        // M-14：先过 host 门禁，再跑路径正则；否则 evil.com/?x=youtube.com/watch?v=... 会被误认。
        // L-12：去掉 nocookie 纯 host 回退（频道/播放列表等非视频链接曾因此误判进解析）。
        if (!url.urlHostMatches(*YOUTUBE_HOSTS.toTypedArray())) return false
        return idPattern.containsMatchIn(url)
    }

    override suspend fun parse(url: String): Result<VideoInfo> {
        // 分享文本可能夹带标题杂质，先提取干净链接（catch 里的兜底也要用它取视频 ID）。
        val cleanUrl = extractFirstUrl(url) ?: url.trim()
        return try {
            newPipeSetup.ensureInit()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            // 含 NoSuchMethodError 在内的所有异常统一转为中文友好错误；
            // P0：按 NewPipe 原文分类，NEED_LOGIN 时区分“未导入”与“已导入但过期”，
            // 让用户知道该重导 cookie 还是换网，而非笼统的“检查链接”。
            val rawMsg = t.message ?: t.javaClass.simpleName
            val kind = ParseErrorClassifier.youtube(rawMsg)
            val hint = if (kind == ParseErrorKind.NEED_LOGIN) {
                if (youtubeCookieStore.isImported.value) {
                    "登录态可能已过期，请重新导入 cookie；仍失败请换网络重试"
                } else {
                    "请导入 cookie 后重试；仍失败请换网络重试"
                }
            } else {
                null
            }
            // P4：仅风控类失败才走网页兜底（删除 / 私享等失败走兜底会拿到错误页，误导更大）。
            if (kind == ParseErrorKind.NEED_LOGIN || kind == ParseErrorKind.IP_BLOCKED) {
                val fallback = tryMetadataFallback(cleanUrl, url)
                if (fallback != null) return Result.success(fallback)
            }
            Result.failure(ParseException(kind, "YouTube 解析失败：$rawMsg", hintOverride = hint))
        }
    }

    /**
     * 网页兜底：取 watch 页 HTML → 解析 og: / JSON-LD → 仅元数据的 VideoInfo。
     * 任一步失败（风控页、无 og: 标签、网络异常）都返回 null，由调用方保留原错误；
     * 取消原样抛出（M-19），不被吞成“兜底失败”。
     */
    private suspend fun tryMetadataFallback(cleanUrl: String, originalUrl: String): VideoInfo? =
        withContext(Dispatchers.IO) {
            try {
                val videoId = idPattern.find(cleanUrl)?.groupValues?.getOrNull(1) ?: return@withContext null
                val request = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId")
                    .header("User-Agent", FALLBACK_UA)
                    .get()
                    .build()
                val html = okHttpClient.executeCancellable(request).use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string() ?: return@withContext null
                }
                val meta = parseYoutubeWatchMetadata(html) ?: return@withContext null
                VideoInfo(
                    platform = Platform.YOUTUBE,
                    videoId = videoId,
                    title = meta.title,
                    coverUrl = meta.coverUrl,
                    durationSec = meta.durationSec,
                    author = meta.author,
                    sourceUrl = originalUrl.trim(),
                    isMetadataOnly = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
        }

    /** YouTube 在 NewPipe 内置服务中的 ID，固定为 0。 */
    private companion object {
        const val YOUTUBE_SERVICE_ID = 0
        val YOUTUBE_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
        const val FALLBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
