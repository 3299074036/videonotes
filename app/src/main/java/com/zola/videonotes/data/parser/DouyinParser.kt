package com.zola.videonotes.data.parser

import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抖音解析器（P1，实验性）。
 *
 * 实验性说明：抖音分享接口依赖平台签名算法，极易随平台调整而失效；
 * 本实现仅做「分享链接跳转追踪 + 分享页 HTML 元信息尽力提取」，不做任何签名、
 * 反爬对抗或登录态抓取。解析失败时返回明确原因，不静默失败。
 */
@Singleton
class DouyinParser @Inject constructor(
    private val redirects: RedirectResolver,
    private val client: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.DOUYIN

    private val videoIdPattern = Regex("""/video/(\d+)""")

    override fun matches(url: String): Boolean =
        DOUYIN_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            val finalUrl = redirects.resolve(cleanUrl)
            val videoId = videoIdPattern.find(finalUrl)?.groupValues?.get(1)
                ?: throw IOException("未能从抖音链接中识别视频 ID：分享页结构可能已变化（P1 实验性功能）")
            val html = fetchHtml(finalUrl)
            // 分享页内嵌 JSON 尽力提取；字段缺失允许降级为空，全部缺失则明确报错
            val title = extractJsonField(html, "desc")?.unescapeJson().orEmpty()
            val author = extractJsonField(html, "nickname")?.unescapeJson().orEmpty()
            val cover = extractCover(html).orEmpty()
            if (title.isBlank() && author.isBlank()) {
                throw IOException("抖音分享页结构已变化，暂无法提取元信息（P1 实验性功能，可能随平台调整而失效）")
            }
            Result.success(
                VideoInfo(
                    platform = Platform.DOUYIN,
                    videoId = videoId,
                    title = title.ifBlank { "抖音视频 $videoId" },
                    coverUrl = cover.ensureHttps(),
                    durationSec = 0,
                    author = author,
                    sourceUrl = url.trim(),
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("抖音解析失败：${t.message ?: t.javaClass.simpleName}（P1 实验性功能）"))
        }
    }

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RedirectResolver.MOBILE_UA)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("抖音分享页请求失败（HTTP ${response.code}），可能触发平台风控")
            }
            response.body?.string().orEmpty()
        }
    }

    /** 从内嵌 JSON 中提取 "key":"value" 的首个 value（原始转义形态）。 */
    private fun extractJsonField(html: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)

    /** 尽力提取封面：origin_cover.url_list 首个 https 地址。 */
    private fun extractCover(html: String): String? {
        val coverBlock = Regex(""""origin_cover"\s*:\s*\{[^}]{0,2000}""").find(html)?.value
            ?: return null
        return Regex(""""url_list"\s*:\s*\[\s*"(https:[^"]+)"""").find(coverBlock)
            ?.groupValues?.get(1)?.replace("\\/", "/")
    }

    companion object {
        private val DOUYIN_HOSTS = listOf("douyin.com", "v.douyin.com", "iesdouyin.com")
    }
}
