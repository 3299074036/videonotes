package com.zola.videonotes.data.parser

import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 快手解析器（视频；只有图集没有视频时明确报错，不静默失败）。
 *
 * 思路借鉴 YunX 的 KuaishouParser（AGPL-3.0），代码自行重写：
 * 请求作品页 SSR，从 HTML 里抠 `window.INIT_STATE`（作品 id → 作品数据的映射），
 * 取第一个含非空 `photo` 的对象。
 *
 * 快手不需要登录，但 UA 与 Referer 是硬性要求：
 * - 桌面 UA 返回的页面里根本没有 `window.INIT_STATE`，必须用 iOS UA；
 * - 直链必须带 Referer，否则 403，所以解析出的 [VideoInfo.mediaHeaders] 要原样带给
 *   播放、下载、音频三路。
 * 跳转展开那跳也要用同一套头，因此这里自己做单次不跟随展开，不复用
 * Android UA 的 RedirectResolver。
 */
@Singleton
class KuaishouParser @Inject constructor(
    private val client: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.KUAISHOU

    override fun matches(url: String): Boolean =
        KUAISHOU_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            // 入口页（短链 / short-video / f 分享码）必须先跳转到移动端作品页；
            // 长视频页结构不同，统一改按 /fw/photo/ 请求。
            val pageUrl = expandOnce(cleanUrl).replace("/fw/long-video/", "/fw/photo/")
            val photo = findPhoto(extractInitState(fetchPage(pageUrl)))
            val videoUrl = firstUrl(photo.optJSONArray("mainMvUrls"))?.ensureHttps()
            if (videoUrl.isNullOrBlank()) {
                val atlas = photo.optJSONObject("ext_params")?.optJSONObject("atlas")
                val hasAtlas = (atlas?.optJSONArray("list")?.length() ?: 0) > 0
                throw IOException(
                    if (hasAtlas) {
                        "图集暂不支持转写"
                    } else {
                        "快手页面里没有视频数据（作品可能已删除或页面改版）"
                    },
                )
            }
            val videoId = PHOTO_ID_REGEX.find(pageUrl)?.groupValues?.get(1)
                ?: photo.optStr("id").takeIf { it.isNotBlank() }
                ?: throw IOException("未能从快手链接中识别作品 ID")
            Result.success(
                VideoInfo(
                    platform = Platform.KUAISHOU,
                    videoId = videoId,
                    title = photo.optStr("caption").ifBlank { "快手视频 $videoId" },
                    coverUrl = firstUrl(photo.optJSONArray("coverUrls"))?.ensureHttps().orEmpty(),
                    durationSec = 0,
                    author = photo.optStr("userName").replace("\u3164", "").trim(),
                    sourceUrl = pageUrl,
                    mediaUrl = videoUrl,
                    mediaHeaders = mediaHeaders(),
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("快手解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /**
     * 单次不跟随展开：读 Location（相对路径按当前地址解析）；拿不到则返回原地址。
     * 展开请求同样带 iOS UA + Referer，否则快手可能不给跳转目标。
     */
    private suspend fun expandOnce(startUrl: String): String = withContext(Dispatchers.IO) {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val url = if (startUrl.startsWith("http")) startUrl else "https://$startUrl"
        val request = Request.Builder().url(url).apply {
            mediaHeaders().forEach { (k, v) -> header(k, v) }
        }.get().build()
        noRedirect.newCall(request).execute().use { response ->
            val location = response.header("Location")?.takeIf { it.isNotBlank() }
                ?: return@withContext url
            url.toHttpUrlOrNull()?.resolve(location)?.toString() ?: url
        }
    }

    private suspend fun fetchPage(pageUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(pageUrl).apply {
            mediaHeaders().forEach { (k, v) -> header(k, v) }
        }.get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("快手作品页请求失败（HTTP ${response.code}）")
            }
            response.body?.string().orEmpty()
        }
    }

    /**
     * 从 HTML 里抠 `window.INIT_STATE` 的 JSON：从标记后第一个 `{` 起按大括号配对，
     * 字符串内的大括号不计数（INIT_STATE 里嵌套深，正则截到第一个 `</script>` 不够稳）。
     */
    private fun extractInitState(html: String): JSONObject {
        val markerIndex = html.indexOf("window.INIT_STATE").takeIf { it >= 0 }
            ?: throw IOException("快手页面里没有 window.INIT_STATE（作品可能已删除或页面改版）")
        var i = html.indexOf('{', markerIndex)
        if (i < 0) {
            throw IOException("快手页面里没有 window.INIT_STATE（作品可能已删除或页面改版）")
        }
        val start = i
        var depth = 0
        var inString = false
        var escaped = false
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val json = html.substring(start, i + 1)
                            return try {
                                JSONObject(json)
                            } catch (e: Exception) {
                                throw IOException("window.INIT_STATE 不是合法 JSON")
                            }
                        }
                    }
                }
            }
            i++
        }
        throw IOException("快手页面里没有 window.INIT_STATE（作品可能已删除或页面改版）")
    }

    /**
     * INIT_STATE 以作品 id 为 key；value 的 key 并不总是链接里的 id，
     * 所以遍历全部 key，返回第一个 `photo` 非空的对象，不按 id 精确定位。
     */
    private fun findPhoto(initState: JSONObject): JSONObject {
        for (key in initState.keys()) {
            val photo = initState.optJSONObject(key)?.optJSONObject("photo")
            if (photo != null) return photo
        }
        throw IOException("window.INIT_STATE 里没有作品数据")
    }

    /** 从 CdnUrl 数组里取第一条有 url 的。 */
    private fun firstUrl(nodes: JSONArray?): String? {
        if (nodes == null) return null
        for (i in 0 until nodes.length()) {
            val url = nodes.optJSONObject(i)?.optStr("url")?.takeIf { it.isNotBlank() } ?: continue
            return url
        }
        return null
    }

    companion object {
        /** 快手作品页必须用 iOS UA：桌面 UA 返回的页面里没有 window.INIT_STATE。 */
        const val IOS_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        /** 快手直链必须带此 Referer，否则 403。 */
        const val REFERER = "https://v.kuaishou.com/"

        private val KUAISHOU_HOSTS = listOf("kuaishou.com", "chenzhongtech.com")

        private val PHOTO_ID_REGEX = Regex("""/fw/photo/([A-Za-z0-9]+)""")

        /** 播放、下载、音频三路共用的请求头（无 Cookie，快手不需要登录）。 */
        fun mediaHeaders(): Map<String, String> = mapOf(
            "User-Agent" to IOS_UA,
            "Referer" to REFERER,
        )
    }
}

/** JSON 显式 null 与缺失一律当成空串（org.json 的 optString 会把 JSON null 读成 "null" 字符串）。 */
private fun JSONObject.optStr(key: String): String =
    if (isNull(key)) "" else optString(key, "")
