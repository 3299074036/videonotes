package com.zola.videonotes.data.subtitle

import com.google.gson.Gson
import com.zola.videonotes.data.network.bilibili.BiliApiService
import com.zola.videonotes.data.network.bilibili.BiliSubtitleFile
import com.zola.videonotes.data.network.bilibili.BiliSubtitleTrack
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
 * 选轨道策略（P3）：手动 zh > 手动 en > AI zh > AI en > 其余，逐条下载解析，
 * 首条非空即用；全部下载失败才报失败，全部为空才走 ASR 兜底。
 * AI 判定：lan 以 ai- 开头（如 ai-zh）或 lan_doc 含 AI 字样，来源标 AUTO 而非 OFFICIAL。
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
            // 4. 按偏好顺序逐条下载字幕 JSON 并解析（轨道级兜底链）
            val ordered = orderSubtitleTracks(
                tracks,
                isAuto = { isBiliAiTrack(it) },
                langTag = { "${it.lan} ${it.lanDoc}" },
            )
            val videoId = video.storageKey()
            when (val outcome = firstNonEmptyTrack(ordered) { track ->
                val raw = okHttpClient.downloadText(track.subtitleUrl.ensureHttpScheme())
                val source = if (isBiliAiTrack(track)) SubtitleSource.AUTO else SubtitleSource.OFFICIAL
                parseBilibiliSubtitleJson(raw, videoId, source)
            }) {
                is TrackAttemptOutcome.Hit<*> -> {
                    val track = outcome.candidate as BiliSubtitleTrack
                    val source = if (isBiliAiTrack(track)) SubtitleSource.AUTO else SubtitleSource.OFFICIAL
                    Result.success(
                        SubtitleFetchResult(
                            segments = outcome.segments,
                            source = source,
                            languageLabel = track.lanDoc.ifBlank { track.lan },
                        ),
                    )
                }
                is TrackAttemptOutcome.Empty ->
                    Result.success(SubtitleFetchResult(emptyList(), SubtitleSource.OFFICIAL, ""))
                is TrackAttemptOutcome.Failed -> {
                    val e = outcome.error
                    throw if (e is IOException) e else IOException("B站字幕获取失败：${e.message}", e)
                }
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // M-19：取消必须原样抛出；吞成失败会让“用户取消”变“XX失败”，且重试逻辑误判。
            throw e
        } catch (t: Throwable) {
            Result.failure(IOException("B站字幕获取失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

}
/** B站 AI 字幕判定：lan 以 ai- 开头（如 ai-zh）或 lan_doc 含 AI 字样。 */
internal fun isBiliAiTrack(track: BiliSubtitleTrack): Boolean =
    track.lan.startsWith("ai-", ignoreCase = true) || track.lanDoc.contains("AI", ignoreCase = true)

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
