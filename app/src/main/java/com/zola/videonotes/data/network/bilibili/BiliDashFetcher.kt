package com.zola.videonotes.data.network.bilibili

import com.zola.videonotes.domain.model.VideoInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站 dash 数据获取器（M6）：pagelist 取 cid → playurl（fnval=16，dash）。
 *
 * 从 M3 的 BilibiliAudioFetcher 抽取，音频下载与站内播放器复用同一链路，
 * 行为（错误文案）保持一致。公开视频免 WBI；大会员/付费视频无 dash 流时
 * 返回明确错误，不做任何绕过。
 */
@Singleton
class BiliDashFetcher @Inject constructor(
    private val api: BiliApiService,
) {

    /** 取视频的 dash 数据（audio / video 流列表）。 */
    suspend fun fetch(video: VideoInfo): Result<BiliDash> {
        return try {
            val (bvid, aid) = extractBiliIds(video)
                ?: throw IOException("未能识别 BV 号或 AV 号")

            val pagelist = if (bvid != null) api.pagelist(bvid = bvid) else api.pagelist(aid = aid)
            if (pagelist.code == -412) throw IOException("B站暂时限制了请求，请稍后重试")
            val cid = pagelist.data?.firstOrNull()?.cid?.takeIf { it > 0 }
                ?: throw IOException("B站接口异常（分P），请稍后重试")

            val playurl = if (bvid != null) {
                api.playurl(bvid = bvid, cid = cid)
            } else {
                api.playurl(aid = aid, cid = cid)
            }
            if (playurl.code != 0) {
                throw IOException("B站接口返回异常（code=${playurl.code}），该视频可能为付费内容")
            }
            val dash = playurl.data?.dash
                ?: throw IOException("该视频暂不支持（可能为付费内容）")
            Result.success(dash)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("B站接口请求失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
