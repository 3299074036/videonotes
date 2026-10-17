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
 * 小红书解析器（P1，实验性）。
 *
 * 实验性说明：小红书分享页依赖内嵌数据结构（window.__INITIAL_STATE__），
 * 平台改版时可能临时失效；频繁请求可能触发验证码。本实现仅做「分享链接跳转追踪 +
 * 分享页元信息尽力提取」，仅支持视频笔记（图文笔记会明确提示不支持），
 * 不做任何签名、反爬对抗或登录态抓取。失败时返回明确原因，不静默失败。
 */
@Singleton
class XiaohongshuParser @Inject constructor(
    private val redirects: RedirectResolver,
    private val client: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.XIAOHONGSHU

    private val noteIdPattern = Regex("""/(?:explore|discovery/item)/([a-zA-Z0-9]+)""")

    override fun matches(url: String): Boolean =
        XHS_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            val finalUrl = redirects.resolve(cleanUrl)
            val noteId = noteIdPattern.find(finalUrl)?.groupValues?.get(1)
                ?: throw IOException("未能从小红书链接中识别笔记 ID：请使用 App 内「分享 → 复制链接」得到的完整链接（含 xsec_token）")
            val html = fetchHtml(finalUrl)
            if (isImageNote(html)) {
                throw IOException("小红书图文笔记暂不支持解析，仅支持视频笔记（P1 实验性功能）")
            }
            // 分享页内嵌 JSON 尽力提取；字段缺失允许降级为空，全部缺失则明确报错
            val title = extractJsonField(html, "title")?.unescapeJson().orEmpty()
            val desc = extractJsonField(html, "desc")?.unescapeJson().orEmpty()
            val author = extractJsonField(html, "nickname")?.unescapeJson().orEmpty()
            val cover = extractCover(html).orEmpty()
            if (title.isBlank() && desc.isBlank() && author.isBlank()) {
                throw IOException("小红书分享页结构已变化，暂无法提取元信息（P1 实验性功能，可能随平台调整而失效）")
            }
            Result.success(
                VideoInfo(
                    platform = Platform.XIAOHONGSHU,
                    videoId = noteId,
                    title = title.ifBlank { desc.take(30) }.ifBlank { "小红书视频 $noteId" },
                    coverUrl = cover.ensureHttps(),
                    durationSec = 0,
                    author = author,
                    sourceUrl = url.trim(),
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("小红书解析失败：${t.message ?: t.javaClass.simpleName}（P1 实验性功能）"))
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
                throw IOException("小红书分享页请求失败（HTTP ${response.code}），可能触发平台验证码风控")
            }
            response.body?.string().orEmpty()
        }
    }

    /** 启发式判断是否为图文笔记：内嵌数据中出现 imageList 且无 videoInfo。 */
    private fun isImageNote(html: String): Boolean =
        html.contains("\"imageList\"") && !html.contains("\"videoInfo\"")

    /** 从内嵌 JSON 中提取 "key":"value" 的首个 value（原始转义形态）。 */
    private fun extractJsonField(html: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)

    /** 尽力提取封面：cover.url 首个 https 地址。 */
    private fun extractCover(html: String): String? {
        val coverBlock = Regex(""""cover"\s*:\s*\{[^}]{0,2000}""").find(html)?.value
            ?: return null
        return Regex(""""url"\s*:\s*"(https:[^"]+)"""").find(coverBlock)
            ?.groupValues?.get(1)?.replace("\\/", "/")
    }

    companion object {
        private val XHS_HOSTS = listOf("xiaohongshu.com", "xhslink.com")
    }
}
