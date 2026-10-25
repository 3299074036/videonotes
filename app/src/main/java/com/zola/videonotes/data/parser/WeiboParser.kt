package com.zola.videonotes.data.parser

import com.zola.videonotes.data.auth.XhsAuthStore
import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 微博解析器（视频；图文/无视频时明确报错，不静默失败）。
 *
 * 思路借鉴 YunX 的 WeiboParser（AGPL-3.0），代码自行重写：
 * - status：手机站 JSON 接口 `m.weibo.cn/statuses/show`（公开内容匿名可读），
 *   必须不跟随重定向——跟随会被 302 到登录/验证页，既拿不到 JSON，又会把
 *   Cookie 顺带发给二跳域名；
 * - tv：链接里的 mid 先 [mid2id] 转成 id，再走 status；
 * - fid：视频组件接口 `h5.video.weibo.com/api/component`（整段 JSON 塞在
 *   名为 data 的表单字段里）；
 * - t.cn：YunX 不支持，视记额外补——微博分享默认就是 t.cn 短链，matches 里
 *   接受 t.cn 域名，parse 里先经 RedirectResolver 展开再按最终地址分形态。
 *
 * 微博 v1 不做登录、不存 Cookie：下面各函数的 `cookie` 参数只是保留的可选
 * 传入位置（将来风控收紧、评估手动 Cookie 导入时再用），当前调用一律传空串，
 * 请求头里不带 Cookie。
 */
@Singleton
class WeiboParser @Inject constructor(
    private val client: OkHttpClient,
    private val redirects: RedirectResolver,
) : VideoParser {

    override val platform: Platform = Platform.WEIBO

    override fun matches(url: String): Boolean =
        TCN_REGEX.containsMatchIn(url) || WEIBO_RULES.any { it.second.containsMatchIn(url) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            // t.cn 短链先展开一次；展开本身失败（网络异常等）报链接失效，
            // 展开后仍无匹配则按不支持处理（与规格一致）。
            val target = if (TCN_REGEX.containsMatchIn(cleanUrl)) {
                runCatching { redirects.resolve(cleanUrl) }.getOrNull()
                    ?: throw IOException("分享链接已失效，请重新复制")
            } else {
                cleanUrl
            }
            val (kind, id) = classify(target)
                ?: throw IOException("暂不支持的微博链接")
            val info = when (kind) {
                "status" -> parseStatus(target, id)
                "tv" -> {
                    val weiboId = try {
                        mid2id(id)
                    } catch (e: Exception) {
                        throw IOException("不支持的 tv 链接")
                    }
                    if (weiboId.isBlank()) throw IOException("不支持的 tv 链接")
                    parseStatus(target, weiboId)
                }
                "fid" -> parseFid(target, id)
                else -> throw IOException("暂不支持的微博链接")
            }
            Result.success(info)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("微博解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /** 按 YunX 规则顺序分形态：tv → fid → status（手机站）→ status（主站）。 */
    private fun classify(url: String): Pair<String, String>? {
        for ((kind, regex) in WEIBO_RULES) {
            val id = regex.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: continue
            return kind to id
        }
        return null
    }

    /**
     * status 路径：GET 手机站 JSON 接口。
     *
     * @param rawUrl 用户原链接（t.cn 时为展开后的最终地址；非规范链接时存入 sourceUrl，
     *   知识库/播放器重解析就靠它）
     * @param queryId 查询用的 id（status 的 wid/bid，或 tv 经 mid2id 转换后的 id），
     *   同时作为 VideoInfo.videoId
     */
    private suspend fun parseStatus(
        rawUrl: String,
        queryId: String,
        cookie: String = "",
    ): VideoInfo = withContext(Dispatchers.IO) {
        val noRedirect = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val apiUrl = "https://m.weibo.cn/statuses/show?id=$queryId&_=${System.currentTimeMillis()}"
        val request = Request.Builder().url(apiUrl).apply {
            header("accept", "application/json, text/plain, */*")
            header("referer", "https://m.weibo.cn/detail/$queryId")
            header("origin", "https://m.weibo.cn")
            header("x-requested-with", "XMLHttpRequest")
            header("mweibo-pwa", "1")
            header("sec-fetch-site", "same-origin")
            header("sec-fetch-mode", "cors")
            header("sec-fetch-dest", "empty")
            header("User-Agent", XhsAuthStore.XHS_DESKTOP_UA)
            if (cookie.isNotBlank()) header("Cookie", cookie)
        }.get().build()
        val (code, contentType, body) = noRedirect.newCall(request).execute().use { response ->
            Triple(response.code, response.header("Content-Type").orEmpty(), response.body?.string().orEmpty())
        }
        when (code) {
            200 -> Unit
            403, 418, 429 -> throw IOException("微博风控拦截，请稍后重试")
            404, 410 -> throw IOException("微博内容不存在或已删除")
            else -> throw IOException("微博数据获取失败，请稍后重试")
        }
        // 200 但 content-type 不是 JSON，说明被风控换成了验证页/网关页。
        if (!contentType.contains("application/json", ignoreCase = true)) {
            throw IOException("微博风控拦截，请稍后重试")
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("微博数据获取失败，请稍后重试")
        }
        val data = json.optJSONObject("data")
            ?: throw IOException("微博内容不存在或已删除")
        buildStatusResult(rawUrl, queryId, data, cookie, allowRetweet = true)
    }

    /**
     * 由 status JSON 组装结果。转发微博只递归一层：仅当外层既无视频也无图片时，
     * 用 `retweeted_status` 再走一次；外层有图无视频时不递归（图片也算媒体）。
     * 递归结果整体替换外层（标题、作者都变成原博的），videoId 保持外层查询 id，
     * 这样重解析仍走同一条外层链接，链路稳定。
     */
    private fun buildStatusResult(
        rawUrl: String,
        queryId: String,
        data: JSONObject,
        cookie: String,
        allowRetweet: Boolean,
    ): VideoInfo {
        val pageInfo = data.optJSONObject("page_info")
        val videoUrl = weiboVideoUrl(pageInfo)
        val hasPics = (data.optJSONArray("pics")?.length() ?: 0) > 0
        if (videoUrl.isBlank() && !hasPics && allowRetweet) {
            val retweeted = data.optJSONObject("retweeted_status")
            if (retweeted != null) {
                return buildStatusResult(rawUrl, queryId, retweeted, cookie, allowRetweet = false)
            }
        }
        if (videoUrl.isBlank()) {
            throw IOException("该微博没有视频，暂不支持图文转写")
        }
        val user = data.optJSONObject("user")
        val userId = user?.optLong("id", 0L) ?: 0L
        val bid = data.optStr("bid")
        // 规范链接优先：user.id 与 bid 齐全时拼主站地址，重解析仍走 status 路径，
        // 不依赖用户原链接形态；否则用解析时实际用的链接。
        val sourceUrl = if (userId > 0 && bid.isNotBlank()) {
            "https://weibo.com/$userId/$bid"
        } else {
            rawUrl
        }
        return VideoInfo(
            platform = Platform.WEIBO,
            videoId = queryId,
            title = pageInfo?.optStr("title").orEmpty().ifBlank { "微博_$queryId" },
            coverUrl = pageInfo?.optJSONObject("page_pic")?.optStr("url").orEmpty().ensureHttps(),
            durationSec = 0,
            author = user?.optStr("screen_name").orEmpty(),
            sourceUrl = sourceUrl,
            mediaUrl = videoUrl,
            mediaHeaders = mediaHeaders(cookie),
        )
    }

    /** `page_info.urls` 里 mp4_720p → mp4_hd → mp4_ld 取第一个非空（单文件 mp4 直链）。 */
    private fun weiboVideoUrl(pageInfo: JSONObject?): String {
        val urls = pageInfo?.optJSONObject("urls") ?: return ""
        return urls.optStr("mp4_720p_mp4")
            .ifBlank { urls.optStr("mp4_hd_mp4") }
            .ifBlank { urls.optStr("mp4_ld_mp4") }
            .ensureHttps()
    }

    /** fid 路径：POST 视频组件接口，表单字段 data 里塞整段 JSON。 */
    private suspend fun parseFid(
        rawUrl: String,
        fid: String,
        cookie: String = "",
    ): VideoInfo = withContext(Dispatchers.IO) {
        val reqUrl = "https://h5.video.weibo.com/api/component?page=/show/$fid"
        val form = FormBody.Builder()
            .add("data", """{"Component_Play_Playinfo":{"oid":"$fid"}}""")
            .build()
        val request = Request.Builder().url(reqUrl).post(form).apply {
            header("Referer", "https://h5.video.weibo.com/show/$fid")
            header("User-Agent", XhsAuthStore.XHS_DESKTOP_UA)
            if (cookie.isNotBlank()) header("Cookie", cookie)
        }.build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("微博数据获取失败，请稍后重试")
            response.body?.string().orEmpty()
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("微博数据获取失败，请稍后重试")
        }
        val playInfo = json.optJSONObject("data")?.optJSONObject("Component_Play_Playinfo")
            ?: throw IOException("微博视频数据为空（作品可能已删除）")
        // urls 是「码率 → 直链」字典，第一条码率最高；为空时兜底 stream_url（最低码率）。
        val urls = playInfo.optJSONObject("urls")
        val firstKey = urls?.keys()?.asSequence()?.firstOrNull()
        val videoUrl = (firstKey?.let { urls.optStr(it) }?.takeIf { it.isNotBlank() }
            ?: playInfo.optStr("stream_url")).ensureHttps()
        if (videoUrl.isBlank()) {
            throw IOException("该微博没有视频，暂不支持图文转写")
        }
        VideoInfo(
            platform = Platform.WEIBO,
            videoId = fid,
            title = playInfo.optStr("title").ifBlank { "微博_$fid" },
            coverUrl = playInfo.optStr("cover_image").ensureHttps(),
            durationSec = 0,
            author = playInfo.optJSONObject("reward")?.optJSONObject("user")?.optStr("name").orEmpty(),
            sourceUrl = rawUrl,
            mediaUrl = videoUrl,
            mediaHeaders = mediaHeaders(cookie),
        )
    }

    /**
     * 播放、下载、音频三路共用的媒体请求头。
     * 注意与 fid 请求头不同：媒体头用主站 Referer（YunX 同款）。
     */
    private fun mediaHeaders(cookie: String): Map<String, String> = buildMap {
        put("User-Agent", XhsAuthStore.XHS_DESKTOP_UA)
        put("Referer", "https://weibo.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** base62 编码（字母表与参考实现一致：0-9 a-z A-Z）。 */
    private fun base62Encode(number: Long): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (number == 0L) return "0"
        var value = number
        val builder = StringBuilder()
        while (value > 0) {
            builder.insert(0, alphabet[(value % 62).toInt()])
            value /= 62
        }
        return builder.toString()
    }

    /**
     * 微博 mid → id：先整体反转，每 7 位切一段、段内再反转，每段按 base62 编码，
     * 非尾段不足 4 位左侧补零，最后把段序反转后拼接。
     * 非数字 mid 会在 toLong 处抛异常，由调用方转成中文失败，不裸抛。
     */
    internal fun mid2id(mid: String): String {
        val reversed = mid.reversed()
        if (reversed.isEmpty()) return ""
        val size = (reversed.length + 6) / 7
        val chunks = mutableListOf<String>()
        for (i in 0 until size) {
            val end = minOf((i + 1) * 7, reversed.length)
            val piece = reversed.substring(i * 7, end).reversed()
            var encoded = base62Encode(piece.toLong())
            if (i < size - 1 && encoded.length < 4) {
                encoded = "0".repeat(4 - encoded.length) + encoded
            }
            chunks += encoded
        }
        return chunks.reversed().joinToString("")
    }

    companion object {
        /** t.cn 短链：微博分享默认形态，YunX 规则表里没有，视记单独接受域名。 */
        private val TCN_REGEX = Regex("""https?://t\.cn(/|\?|$)""", RegexOption.IGNORE_CASE)

        /**
         * 微博识别规则（YunX VideoLinkParser 同款顺序与正则）：
         * tv → fid → status（手机站）→ status（主站）。
         */
        private val WEIBO_RULES = listOf(
            "tv" to Regex("""weibo\.com/tv/show/\d{4}:\d+\?mid=(\d+)""", RegexOption.IGNORE_CASE),
            "fid" to Regex("""video\.weibo\.com/show\?fid=(\d+:\d+)""", RegexOption.IGNORE_CASE),
            "status" to Regex("""weibo\.cn/(?:status|statuses|detail)/([0-9a-zA-Z]+)""", RegexOption.IGNORE_CASE),
            "status" to Regex("""weibo\.com/\d+/([0-9a-zA-Z]{6,})""", RegexOption.IGNORE_CASE),
        )
    }
}

/** JSON 显式 null 与缺失一律当成空串（org.json 的 optString 会把 JSON null 读成 "null" 字符串）。 */
private fun JSONObject.optStr(key: String): String =
    if (isNull(key)) "" else optString(key, "")
