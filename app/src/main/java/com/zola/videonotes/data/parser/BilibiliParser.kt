package com.zola.videonotes.data.parser

import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.data.network.bilibili.BiliApiService
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站解析器（P0）：Retrofit 直调官方公开接口 x/web-interface/view。
 *
 * 支持 b23.tv 短链（302 追踪还原）、BV 号与 AV 号。无需登录即可访问公开视频；
 * 大会员 / 付费视频接口会返回非 0 code，此处直接透出原因，不做任何绕过。
 */
@Singleton
class BilibiliParser @Inject constructor(
    private val api: BiliApiService,
    private val redirects: RedirectResolver,
) : VideoParser {

    override val platform: Platform = Platform.BILIBILI

    private val bvPattern = Regex("""(?i)(BV[a-zA-Z0-9]{10})""")
    private val avPattern = Regex("""(?i)av(\d+)""")

    override fun matches(url: String): Boolean =
        url.urlHostMatches("bilibili.com", "b23.tv")

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            // b23.tv 短链先追踪还原，拿到真实 BV/AV 地址
            val finalUrl = if (cleanUrl.contains("b23.tv", ignoreCase = true)) {
                redirects.resolve(cleanUrl)
            } else {
                cleanUrl
            }
            val bvid = bvPattern.find(finalUrl)?.groupValues?.get(1)
            val aid = avPattern.find(finalUrl)?.groupValues?.get(1)?.toLongOrNull()
            if (bvid == null && aid == null) {
                throw ParseException(ParseErrorKind.INVALID_LINK, "未能从链接中识别出 BV 号或 AV 号，请确认是有效的 B站视频链接", appendHint = false)
            }
            val response = if (bvid != null) api.view(bvid = bvid) else api.view(aid = aid)
            val data = response.data
            if (response.code != 0 || data == null) {
                throw ParseException(ParseErrorClassifier.bilibili(response.code, response.message), "B站接口返回异常（code=${response.code}）：${response.message.ifBlank { "视频可能不存在、无权访问或为付费内容" }}")
            }
            Result.success(
                VideoInfo(
                    platform = Platform.BILIBILI,
                    videoId = data.bvid.ifBlank { "av${data.aid}" },
                    title = data.title,
                    coverUrl = data.pic.ensureHttps(),
                    durationSec = data.duration.coerceAtLeast(0),
                    author = data.owner?.name.orEmpty(),
                    sourceUrl = url.trim(),
                    // M-17：多分P只取 P1（取流/字幕/音频三处均 firstOrNull cid）；
                    // 分P数随解析结果带到详情页提示，不进库（详情页每次现解析）。
                    partCount = data.videos.coerceAtLeast(1),
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            Result.failure(ParseException(ParseErrorKind.UNKNOWN, "B站解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
