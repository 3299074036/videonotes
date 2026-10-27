package com.zola.videonotes.data.parser

import com.zola.videonotes.data.auth.XhsAuthStore
import com.zola.videonotes.data.auth.XhsAuthStore.Companion.XHS_DESKTOP_UA
import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小红书解析器（YunX 方案移植，视记接口适配）。
 *
 * ★ 必须登录：小红书网页端笔记页对未登录会话一律拦截 —— explore 302 到 /login、
 *   discovery 302 到 /website-login/captcha，两种页面里都没有 `__INITIAL_STATE__`。
 *   登录凭证（web_session）来自设置页「小红书账号」的内嵌官方登录页。
 * ★ 分享链接里的 `xsec_token` 必须原样带上：小红书按请求校验这个令牌，缺了或过期都会落到登录墙。
 * ★ 两条路径：先 explore（桌面 UA）、失败再 discovery（iOS UA + XHR 头）；要求登录时不再重试第二条
 *   （同一道门，换路径也过不去），直接给出可操作的提示。
 * ★ 视频直链：`<note>.video.media.stream` 按 h265 → h264 → av1 → h266 取第一条 masterUrl；
 *   h265 无水印、h264 有水印。图文笔记明确提示不支持（视记只做视频转写）。
 * ★ 内嵌 JSON 里混有裸的 `undefined`（JS 字面量，不是合法 JSON），解析前先把裸值位置的
 *   undefined 换成 null（只换 `:undefined` / `,undefined` / `[undefined`，不碰引号内的文本）。
 */
@Singleton
class XiaohongshuParser @Inject constructor(
    private val redirects: RedirectResolver,
    private val client: OkHttpClient,
    private val authStore: XhsAuthStore,
) : VideoParser {

    override val platform: Platform = Platform.XIAOHONGSHU

    private val noteIdPattern = Regex("""/(?:explore|discovery/item)/([a-zA-Z0-9]+)""")

    override fun matches(url: String): Boolean =
        url.urlHostMatches(*XHS_HOSTS.toTypedArray())

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            // 已是完整笔记地址（知识库/笔记跳转带过来的库链接）就不再走跳转：
            // 跳转请求不带登录 Cookie，小红书可能把它重定向到登录/风控页，反而丢了
            // 地址里的 noteId 和 xsec_token。短链（不含笔记地址）才需要跳转展开。
            val finalUrl = if (noteIdPattern.containsMatchIn(cleanUrl)) {
                cleanUrl
            } else {
                redirects.resolve(cleanUrl)
            }
            val noteId = noteIdPattern.find(finalUrl)?.groupValues?.get(1)
                ?: throw ParseException(ParseErrorKind.INVALID_LINK, "未能从小红书链接中识别笔记 ID：请使用 App 内「分享 → 复制链接」得到的完整链接（含 xsec_token）", appendHint = false)
            // 原分享链接的查询串（含 xsec_token）：重建两条路径的地址时都带上
            val query = finalUrl.substringAfter('?', "").takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
            val cookie = authStore.webSession()?.let { "web_session=$it" }.orEmpty()

            // 先 explore；要求登录时直接抛（不再试 discovery，同一道门）
            val exploreResult = runCatching { parseExplore(noteId, query, cookie, finalUrl) }
            exploreResult.getOrNull()?.let { return Result.success(it) }
            val exploreError = exploreResult.exceptionOrNull()
            if (exploreError is XhsLoginRequiredException) throw exploreError

            // 再 discovery
            val discoveryResult = runCatching { parseDiscovery(noteId, query, cookie, finalUrl) }
            discoveryResult.getOrNull()?.let { return Result.success(it) }
            throw discoveryResult.exceptionOrNull()
                ?: exploreError
                ?: IOException("小红书解析失败")
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            Result.failure(ParseException(ParseErrorKind.UNKNOWN, "小红书解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /** explore 形态：`/explore/<id>?<原查询串>`（桌面 UA）。 */
    private suspend fun parseExplore(noteId: String, query: String, cookie: String, sourceUrl: String): VideoInfo {
        val html = fetchPage(
            url = "$XHS_HOME/explore/$noteId$query",
            headers = exploreHeaders(cookie),
        )
        val state = extractInitialState(html)
        val note = state.optJSONObject("note")
            ?.optJSONObject("noteDetailMap")
            ?.optJSONObject(noteId)
            ?.optJSONObject("note")
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面里没有该笔记数据")
        return buildVideoInfo(
            note = note,
            noteId = noteId,
            cookie = cookie,
            authorKey = "nickname",
            coverUrls = imageUrls(note.optJSONArray("imageList")) { it.str("urlDefault").ifBlank { it.str("url") } },
            sourceUrl = sourceUrl,
        )
    }

    /** discovery 形态：`/discovery/item/<id>?<原查询串>`（iOS UA + XHR 头）。 */
    private suspend fun parseDiscovery(noteId: String, query: String, cookie: String, sourceUrl: String): VideoInfo {
        val html = fetchPage(
            url = "$XHS_HOME/discovery/item/$noteId$query",
            headers = discoveryHeaders(cookie),
        )
        val state = extractInitialState(html)
        val noteRoot = state.optJSONObject("noteData")
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书分享链接失效或内容已删除")
        val note = noteRoot.optJSONObject("data")?.optJSONObject("noteData")
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面里没有笔记数据（内容可能已删除）")
        val preloadCover = imageUrls(noteRoot.optJSONObject("normalNotePreloadData")?.optJSONArray("imagesList")) {
            it.str("urlSizeLarge").ifBlank { it.str("url") }
        }
        val rawCover = imageUrls(note.optJSONArray("imageList")) { it.str("url").ifBlank { it.str("urlDefault") } }
        return buildVideoInfo(
            note = note,
            noteId = noteId,
            cookie = cookie,
            authorKey = "nickName",
            coverUrls = preloadCover.ifEmpty { rawCover },
            sourceUrl = sourceUrl,
        )
    }

    /**
     * 取分享页并先把「被登录墙拦住」识别出来。
     * 未登录时小红书会把请求 302 到 /login 或 /website-login/captcha，页面里没有
     * `__INITIAL_STATE__` —— 这种情况必须报「请先登录」，而不是「内容不存在」。
     */
    private suspend fun fetchPage(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ParseException(ParseErrorClassifier.http(response.code), "小红书分享页请求失败（HTTP ${response.code}），可能触发平台验证码风控")
                }
                val finalUrl = response.request.url.toString()
                if (isLoginWallUrl(finalUrl)) {
                    // 已登录（请求带了 Cookie）还撞墙，说明链接缺有效的 xsec_token，
                    // 不是没登录——报错要指对方向（重新粘贴原链接），别误导用户去登录。
                    throw XhsLoginRequiredException(tokenMissing = headers["Cookie"]?.isNotBlank() == true)
                }
                response.body?.stringCapped().orEmpty()
            }
        }

    /** 抠出 `window.__INITIAL_STATE__` 并解析成 JSON（裸 undefined 先换成 null）。 */
    private fun extractInitialState(html: String): JSONObject {
        val marker = "window.__INITIAL_STATE__"
        val markerIndex = html.indexOf(marker)
            .takeIf { it >= 0 }
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面里没有笔记数据（可能被风控拦截或页面改版）")
        val braceStart = html.indexOf('{', markerIndex)
            .takeIf { it >= 0 }
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面里没有笔记数据（可能被风控拦截或页面改版）")
        val jsonText = extractBalancedJson(html, braceStart)
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面数据不完整")
        val cleaned = jsonText
            .replace(":undefined", ":null")
            .replace(",undefined", ",null")
            .replace("[undefined", "[null")
        return runCatching { JSONObject(cleaned) }.getOrElse {
            throw ParseException(ParseErrorKind.UNKNOWN, "小红书页面数据解析失败")
        }
    }

    /** 从 start 起按大括号配对抠出完整 JSON（跳过字符串内的括号，避免标题里的括号干扰计数）。 */
    private fun extractBalancedJson(html: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, i + 1)
                }
            }
        }
        return null
    }

    /** 由 note JSON 组装 VideoInfo：视频笔记取直链，图文笔记明确报错。 */
    private fun buildVideoInfo(
        note: JSONObject,
        noteId: String,
        cookie: String,
        authorKey: String,
        coverUrls: List<String>,
        sourceUrl: String,
    ): VideoInfo {
        val videoObj = note.optJSONObject("video")
            ?: throw ParseException(ParseErrorKind.UNSUPPORTED, "小红书图文笔记暂不支持转写，仅支持视频笔记")
        val stream = pickVideo(videoObj)
            ?: throw ParseException(ParseErrorKind.UNKNOWN, "小红书视频笔记没有取到视频直链（笔记里有 video 字段，但 media.stream 里没有可用地址）")
        val title = note.str("title").ifBlank { note.str("desc") }.ifBlank { "小红书视频 $noteId" }
        val author = note.optJSONObject("user")?.str(authorKey).orEmpty()
        val cover = coverUrls.firstOrNull()?.let { watermarkFreeOrRaw(it) }.orEmpty()
        return VideoInfo(
            platform = Platform.XIAOHONGSHU,
            videoId = noteId,
            title = title,
            coverUrl = cover.ensureHttps(),
            durationSec = 0,
            author = author,
            // ★ 必须存原始完整链接（含 xsec_token）：内联/二级播放器对小红书会用 sourceUrl
            //   重解析取新鲜直链，token 一丢就会撞登录墙、重解析失败（播不了的根因）。
            sourceUrl = sourceUrl,
            mediaUrl = stream.url.ensureHttps(),
            mediaHeaders = mediaHeaders(cookie, stream.url),
        )
    }

    /** 取视频直链：`<note>.video.media.stream` 按 h265 → h264 → av1 → h266 取第一条 masterUrl。 */
    private fun pickVideo(videoObj: JSONObject): VideoPick? {
        val stream = videoObj.optJSONObject("media")?.optJSONObject("stream") ?: return null
        for ((key, label) in STREAM_ORDER) {
            val first = stream.optJSONArray(key)?.optJSONObject(0) ?: continue
            val url = first.str("masterUrl").ifBlank { first.str("master_url") }
            if (url.isNotBlank()) return VideoPick(url, label)
        }
        return null
    }

    private data class VideoPick(val url: String, val quality: String)

    private fun imageUrls(array: org.json.JSONArray?, pick: (JSONObject) -> String): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val url = pick(array.optJSONObject(i) ?: continue)
            if (url.isNotBlank()) result += url
        }
        return result
    }

    /**
     * 把带处理规格的图片地址换算成去水印原图；取不到图片 ID 时原样返回。
     * "host/时间戳桶/散列/<图片ID>!<处理规格>" → 丢掉前两段路径，再截掉 "!" 之后。
     */
    private fun watermarkFreeOrRaw(raw: String): String {
        if (raw.isBlank()) return raw
        val id = raw.substringAfter("://", "")
            .substringAfter('/', "")
            .split('/')
            .drop(2)
            .joinToString("/")
            .substringBefore('!')
        return if (id.isBlank()) raw else "$XHS_IMAGE_CDN/$id"
    }

    /** explore 路径的请求头（桌面 UA + accept + Referer + Cookie）。 */
    private fun exploreHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", XHS_DESKTOP_UA)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** discovery 路径的请求头（iOS UA + XHR 头）。 */
    private fun discoveryHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", UA_IPHONE)
        put("accept", ACCEPT)
        put("Referer", REFERER)
        put("origin", XHS_HOME)
        put("x-requested-with", "XMLHttpRequest")
        put("sec-fetch-site", "same-origin")
        put("sec-fetch-mode", "cors")
        put("sec-fetch-dest", "empty")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 媒体请求头（直链播放/下载用：桌面 UA + Referer + Cookie）。 */
    private fun mediaHeaders(cookie: String, mediaUrl: String): Map<String, String> = buildMap {
        put("User-Agent", XHS_DESKTOP_UA)
        put("Referer", REFERER)
        // M-15：Cookie 只发往白名单 host；直链 host 不在白名单时不带凭证（解析异常或
        // 被劫持的重定向目标），避免 web_session 外泄到 CDN 之外的域及其重定向目标。
        if (cookie.isNotBlank() && isMediaHost(mediaUrl)) put("Cookie", cookie)
    }

    /** 被登录墙拦下：调用方不再重试另一条路径，直接报可操作的提示。 */
    class XhsLoginRequiredException(tokenMissing: Boolean = false) : ParseException(
        if (tokenMissing) ParseErrorKind.INVALID_LINK else ParseErrorKind.NEED_LOGIN,
        if (tokenMissing) {
            "该小红书链接已失效（缺少 xsec_token），请重新粘贴小红书原链接解析"
        } else {
            "小红书要求登录后才能查看笔记内容，请先到设置 → 小红书账号登录"
        },
        appendHint = false,
    )

    companion object {
        private const val XHS_HOME = "https://www.xiaohongshu.com"
        private const val REFERER = "$XHS_HOME/"
        private const val ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8," +
                "application/signed-exchange;v=b3;q=0.7"

        /** 原图 CDN：直接返回原始图（无水印、不二次压成 webp）。 */
        private const val XHS_IMAGE_CDN = "https://sns-img-bd.xhscdn.com"

        /** 清晰度优先级：h265 无水印，h264 有水印。 */
        private val STREAM_ORDER = listOf(
            "h265" to "H.265",
            "h264" to "H.264",
            "av1" to "AV1",
            "h266" to "H.266",
        )

        private const val UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

        private val XHS_HOSTS = listOf("xiaohongshu.com", "xhslink.com", "xhslink.cn")

        /**
         * M-15：小红书媒体直链 host 白名单。web_session 只发往小红书自家域与 CDN 域；
         * 解析失败返回 false（fail-closed：不带 Cookie，播放/下载方再报可操作的错）。
         */
        fun isMediaHost(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
            return host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com") ||
                host == "xhscdn.com" || host.endsWith(".xhscdn.com")
        }
    }
}

/**
 * `JSONObject.optString` 遇到 JSON 里的显式 `null` 会返回字符串 "null"，直接当字段值用会把
 * "null" 当成有效内容，这里统一过滤。
 */
private fun JSONObject.str(key: String): String =
    optString(key).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
