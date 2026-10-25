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
        val input = url.trim()
        // 先从分享文本里提链接再匹配：用户常直接粘贴整段分享文案（带标题、作者等杂质），
        // 匹配与解析都应以提到的第一条链接为准，而不是整段文本。
        val extracted = extractFirstUrl(input)
        val target = extracted ?: input
        val parser = parserFor(target)
            ?: return Result.failure(
                IllegalArgumentException(
                    if (extracted == null)
                        "没有识别到视频链接：请粘贴包含 https 链接的分享文本（小红书分享口令暂不支持解析）"
                    else
                        "暂不支持的链接（${hostOf(target)}）：请粘贴 YouTube / B站 / 抖音 / 小红书的视频链接",
                ),
            )
        return parser.parse(target)
    }

    /** 从链接里取域名（诊断用，取不到时回退前 60 字）。 */
    private fun hostOf(url: String): String =
        Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1)
            ?: url.take(60)
}
