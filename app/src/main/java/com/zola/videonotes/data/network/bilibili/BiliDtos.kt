package com.zola.videonotes.data.network.bilibili

import com.google.gson.annotations.SerializedName

/** B站 x/web-interface/view 接口响应。code=0 为成功。 */
data class BiliViewResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("message") val message: String = "",
    @SerializedName("data") val data: BiliVideoData? = null,
)

/** 视频信息主体。duration 单位为秒。 */
data class BiliVideoData(
    @SerializedName("bvid") val bvid: String = "",
    @SerializedName("aid") val aid: Long = 0,
    @SerializedName("title") val title: String = "",
    @SerializedName("pic") val pic: String = "",
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("owner") val owner: BiliOwner? = null,
)

/** UP 主信息。 */
data class BiliOwner(
    @SerializedName("name") val name: String = "",
)

// ---------- M2：字幕链路 ----------

/** x/web-interface/nav 响应：data.wbi_img 提供 WBI 签名所需的 img_key / sub_key。 */
data class BiliNavResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("message") val message: String = "",
    @SerializedName("data") val data: BiliNavData? = null,
)

data class BiliNavData(
    @SerializedName("wbi_img") val wbiImg: BiliWbiImg? = null,
)

data class BiliWbiImg(
    @SerializedName("img_url") val imgUrl: String = "",
    @SerializedName("sub_url") val subUrl: String = "",
)

/** x/player/pagelist 响应：取 data[0].cid。优先用它取 cid，因为 view 接口在某些出口 IP 会被 -412 ban。 */
data class BiliPagelistResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("message") val message: String = "",
    @SerializedName("data") val data: List<BiliPage>? = null,
)

data class BiliPage(
    @SerializedName("cid") val cid: Long = 0,
    @SerializedName("page") val page: Int = 0,
    @SerializedName("part") val part: String = "",
)

/** x/player/wbi/v2 响应：data.subtitle.subtitles 为字幕轨道列表。 */
data class BiliPlayerResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("message") val message: String = "",
    @SerializedName("data") val data: BiliPlayerData? = null,
)

data class BiliPlayerData(
    @SerializedName("subtitle") val subtitle: BiliSubtitleWrap? = null,
    /** 为 true 时字幕需要登录后才能获取（code 仍为 0）。 */
    @SerializedName("need_login_subtitle") val needLoginSubtitle: Boolean = false,
)

data class BiliSubtitleWrap(
    @SerializedName("subtitles") val subtitles: List<BiliSubtitleTrack> = emptyList(),
)

data class BiliSubtitleTrack(
    /** 语言代码，如 zh-CN、ai-zh */
    @SerializedName("lan") val lan: String = "",
    /** 语言展示名，如 "中文（中国）" */
    @SerializedName("lan_doc") val lanDoc: String = "",
    /** 字幕文件 URL，可能以 // 开头 */
    @SerializedName("subtitle_url") val subtitleUrl: String = "",
)

/** 字幕文件 JSON：{"body": [{"from": 秒(double), "to": 秒(double), "content": "文本"}]} */
data class BiliSubtitleFile(
    @SerializedName("body") val body: List<BiliSubtitleLine>? = null,
)

data class BiliSubtitleLine(
    @SerializedName("from") val from: Double = 0.0,
    @SerializedName("to") val to: Double = 0.0,
    @SerializedName("content") val content: String? = null,
)

// ---------- M3：音频下载（playurl） ----------

/** x/player/playurl 响应：data.dash.audio 为音频流列表。code != 0 或 dash 为空多为付费/大会员内容。 */
data class BiliPlayurlResponse(
    @SerializedName("code") val code: Int = -1,
    @SerializedName("message") val message: String = "",
    @SerializedName("data") val data: BiliPlayurlData? = null,
)

data class BiliPlayurlData(
    @SerializedName("dash") val dash: BiliDash? = null,
)

data class BiliDash(
    @SerializedName("audio") val audio: List<BiliAudioStream>? = null,
    /** M6：站内播放器用，视频流列表（dash，清晰度降序）。 */
    @SerializedName("video") val video: List<BiliVideoStream>? = null,
)

data class BiliAudioStream(
    /** 音频直链（m4a/aac） */
    @SerializedName("baseUrl") val baseUrl: String = "",
    /** 码率，选最高者 */
    @SerializedName("bandwidth") val bandwidth: Long = 0,
)

/** M6：playurl dash.video 条目。 */
data class BiliVideoStream(
    /** 视频直链（mp4） */
    @SerializedName("baseUrl") val baseUrl: String = "",
    /** 码率 */
    @SerializedName("bandwidth") val bandwidth: Long = 0,
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
)
