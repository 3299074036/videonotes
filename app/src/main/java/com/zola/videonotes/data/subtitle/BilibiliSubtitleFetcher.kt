package com.zola.videonotes.data.subtitle

import com.google.gson.Gson
import com.zola.videonotes.data.network.bilibili.BiliApiService
import com.zola.videonotes.data.network.bilibili.BiliSubtitleFile
import com.zola.videonotes.data.network.bilibili.WbiSigner
import com.zola.videonotes.data.network.bilibili.extractBiliIds
import com.zola.videonotes.data.parser.ensureHttpScheme
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import com.zola.videonotes.domain.subtitle.SubtitleFetcher
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

private val biliGson = Gson()

/**
 * B站字幕获取器（P0）：nav 取 WBI 密钥 → pagelist 取 cid → wbi/v2 取字幕轨道 → 下载字幕 JSON 解析为分段。
 *
 * 选轨道策略：lan 以 zh 开头或 lan_doc 含"中文"优先，否则取第一条。
 * 无字幕轨道时返回空结果（上层走 ASR 兜底），不抛异常。
 * 明确错误：-412（请求被限制）、need_login_subtitle（需登录）。
 */
@Singleton
class BilibiliSubtitleFetcher @Inject constructor(
    private val api: BiliApiService,
    private val okHttpClient: OkHttpClient,
) : SubtitleFetcher {

    override suspend fun fetch(video: VideoInfo): Result<SubtitleFetchResult> {
        require(video.platform == Platform.BILIBILI) { "BilibiliSubtitleFetcher 仅处理 B站视频" }
        return try {
            val (bvid, aid) = extractBiliIds(video)
                ?: throw IOException("未能识别 BV 号或 AV 号")

            // 1. nav 取 WBI 密钥
            // 注意：B站 nav 对未登录请求返回 code=-101（"账号未登录"），但 data.wbi_img
            // 依然有效；只有 wbi_img 缺失时（如 -412 被 ban）才报错，不以 code==0 为门槛。
            val nav = api.nav()
            if (nav.code == -412) throw IOException("B站暂时限制了请求，请稍后重试")
            val wbiImg = nav.data?.wbiImg
            if (wbiImg == null) {
                throw IOException("B站接口异常（密钥），请稍后重试")
            }
            val (imgKey, subKey) = WbiSigner.parseKeys(wbiImg.imgUrl, wbiImg.subUrl)
                ?: throw IOException("B站接口异常（密钥解析失败），请稍后重试")
            val mixinKey = WbiSigner.getMixinKey(imgKey, subKey)

            // 2. pagelist 取 cid（优先于 view 接口，后者在某些出口 IP 会被 -412 ban）
            val pagelist = if (bvid != null) api.pagelist(bvid = bvid) else api.pagelist(aid = aid)
            if (pagelist.code == -412) throw IOException("B站暂时限制了请求，请稍后重试")
            val cid = pagelist.data?.firstOrNull()?.cid?.takeIf { it > 0 }
                ?: throw IOException("B站接口异常（分P），请稍后重试")

            // 3. wbi/v2 取字幕轨道（WBI 签名）
            val baseParams = mutableMapOf("cid" to cid.toString())
            if (bvid != null) baseParams["bvid"] = bvid else baseParams["aid"] = aid.toString()
            val player = api.playerWbiV2(WbiSigner.sign(baseParams, mixinKey))
            if (player.code == -412) throw IOException("B站暂时限制了请求，请稍后重试")
            val playerData = player.data
            if (player.code != 0 || playerData == null) {
                throw IOException("B站接口返回异常（code=${player.code}）")
            }
            if (playerData.needLoginSubtitle) {
                throw IOException("该视频字幕需要登录 B站账号后获取")
            }
            val tracks = playerData.subtitle?.subtitles.orEmpty()
            if (tracks.isEmpty()) {
                // 无字幕：返回空结果，上层走 ASR 兜底
                return Result.success(SubtitleFetchResult(emptyList(), SubtitleSource.OFFICIAL, ""))
            }
            val track = tracks.firstOrNull {
                it.lan.startsWith("zh", ignoreCase = true) || it.lanDoc.contains("中文")
            } ?: tracks.first()

            // 4. 下载字幕 JSON 并解析
            val raw = okHttpClient.downloadText(track.subtitleUrl.ensureHttpScheme())
            val segments = parseBilibiliSubtitleJson(raw, video.storageKey(), SubtitleSource.OFFICIAL)
            Result.success(
                SubtitleFetchResult(
                    segments = segments,
                    source = SubtitleSource.OFFICIAL,
                    languageLabel = track.lanDoc.ifBlank { track.lan },
                ),
            )
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("B站字幕获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

}
/**
 * 解析 B站字幕文件 JSON：{"body": [{"from": 秒, "to": 秒, "content": "文本"}]}。
 * 秒（double）转毫秒用四舍五入；content 为空的分段跳过；idx 按输出顺序紧凑编号。
 */
internal fun parseBilibiliSubtitleJson(
    json: String,
    videoId: String,
    source: SubtitleSource,
): List<TranscriptSegment> {
    val root = biliGson.fromJson(json, BiliSubtitleFile::class.java)
    val out = mutableListOf<TranscriptSegment>()
    for (line in root.body.orEmpty()) {
        val text = line.content?.trim().orEmpty()
        if (text.isEmpty()) continue
        val startMs = (line.from * 1000).roundToLong()
        val endMs = (line.to * 1000).roundToLong()
        out.add(TranscriptSegment(videoId, out.size, startMs, endMs, text, source))
    }
    return out
}
