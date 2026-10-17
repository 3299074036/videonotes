package com.zola.videonotes.data.network.bilibili

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap

/**
 * B站公开视频信息接口（无需登录）。
 * 字幕接口（x/player/wbi/v2，需 WBI 签名）在 M2 落地。
 */
interface BiliApiService {

    /**
     * 获取视频元信息。bvid 与 aid 二选一，Retrofit 会自动忽略 null 参数。
     */
    @GET("x/web-interface/view")
    suspend fun view(
        @Query("bvid") bvid: String? = null,
        @Query("aid") aid: Long? = null,
    ): BiliViewResponse

    /**
     * 获取 WBI 签名密钥。无需登录。
     */
    @GET("x/web-interface/nav")
    suspend fun nav(): BiliNavResponse

    /**
     * 获取分P 列表，取首个分P 的 cid。bvid 与 aid 二选一。
     * 优先用它取 cid（而非 view 接口），因为 view 在某些出口 IP 会被 -412 ban。
     */
    @GET("x/player/pagelist")
    suspend fun pagelist(
        @Query("bvid") bvid: String? = null,
        @Query("aid") aid: Long? = null,
    ): BiliPagelistResponse

    /**
     * 播放器信息（含字幕列表）。params 必须经过 [WbiSigner.sign] 签名，
     * 包含 bvid（或 aid）、cid、wts、w_rid。
     */
    @GET("x/player/wbi/v2")
    suspend fun playerWbiV2(
        @QueryMap params: Map<String, String>,
    ): BiliPlayerResponse

    /**
     * 取播放地址（M3：ASR 音频下载用）。fnval=16 返回 dash 格式。
     * 公开视频免 WBI；大会员/付费视频会返回 code != 0 或空 dash。
     */
    @GET("x/player/playurl")
    suspend fun playurl(
        @Query("bvid") bvid: String? = null,
        @Query("aid") aid: Long? = null,
        @Query("cid") cid: Long,
        @Query("fnval") fnval: Int = 16,
    ): BiliPlayurlResponse
}
