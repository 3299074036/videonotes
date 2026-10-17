package com.zola.videonotes.data.parser

import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 解析器注册表：按 matches() 顺序分发到各平台解析器。
 *
 * P0（YouTube / B站）优先于 P1（抖音 / 小红书）匹配；P1 平台接口变化时只改对应实现，
 * 不影响主链路。
 */
@Singleton
class ParserRegistry @Inject constructor(
    private val youtubeParser: YoutubeParser,
    private val bilibiliParser: BilibiliParser,
    private val douyinParser: DouyinParser,
    private val xiaohongshuParser: XiaohongshuParser,
) {

    private val parsers: List<VideoParser>
        get() = listOf(youtubeParser, bilibiliParser, douyinParser, xiaohongshuParser)

    /** 找到能处理该链接的解析器，无匹配返回 null。 */
    fun parserFor(url: String): VideoParser? =
        parsers.firstOrNull { it.matches(url) }

    /** 解析视频元信息；无匹配平台时返回中文友好错误。 */
    suspend fun parse(url: String): Result<VideoInfo> {
        val parser = parserFor(url)
            ?: return Result.failure(
                IllegalArgumentException("暂不支持的链接：请粘贴 YouTube / B站 / 抖音 / 小红书的视频链接"),
            )
        return parser.parse(url)
    }
}
