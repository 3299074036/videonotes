package com.zola.videonotes.data.subtitle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import kotlin.math.roundToLong

private val gson = Gson()

/** YouTube timedtext json3 格式。 */
private data class TimedtextJson3(
    @SerializedName("events") val events: List<TimedtextEvent>? = null,
)

private data class TimedtextEvent(
    @SerializedName("tStartMs") val tStartMs: Long? = null,
    @SerializedName("dDurationMs") val dDurationMs: Long? = null,
    @SerializedName("segs") val segs: List<TimedtextSeg>? = null,
)

private data class TimedtextSeg(
    @SerializedName("utf8") val utf8: String? = null,
)

/**
 * 解析 YouTube timedtext json3 字幕。
 * 跳过无 segs 的 event 与拼接后为空的文本；idx 按输出顺序紧凑编号。
 */
internal fun parseTimedtextJson3(
    json: String,
    videoId: String,
    source: SubtitleSource,
): List<TranscriptSegment> {
    val root = gson.fromJson(json, TimedtextJson3::class.java)
    val out = mutableListOf<TranscriptSegment>()
    for (event in root.events.orEmpty()) {
        val text = event.segs.orEmpty().mapNotNull { it.utf8 }.joinToString("").trim()
        if (text.isEmpty()) continue
        val startMs = event.tStartMs ?: continue
        val endMs = startMs + (event.dDurationMs ?: 0L)
        out.add(TranscriptSegment(videoId, out.size, startMs, endMs, text, source))
    }
    return out
}

/**
 * 解析 YouTube timedtext srv3（XML）字幕，作为 json3 解析失败时的降级。
 * 格式示例：<text start="1.23" dur="2.50">文本</text>
 */
internal fun parseTimedtextSrv3(
    xml: String,
    videoId: String,
    source: SubtitleSource,
): List<TranscriptSegment> {
    val out = mutableListOf<TranscriptSegment>()
    val pattern = Regex(
        """<text\s+start="([\d.]+)"\s+dur="([\d.]+)"[^>]*>(.*?)</text>""",
        RegexOption.DOT_MATCHES_ALL,
    )
    for (match in pattern.findAll(xml)) {
        val startSec = match.groupValues[1].toDoubleOrNull() ?: continue
        val durSec = match.groupValues[2].toDoubleOrNull() ?: continue
        val text = unescapeXml(match.groupValues[3]).trim()
        if (text.isEmpty()) continue
        val startMs = (startSec * 1000).roundToLong()
        out.add(TranscriptSegment(videoId, out.size, startMs, startMs + (durSec * 1000).roundToLong(), text, source))
    }
    return out
}

private fun unescapeXml(s: String): String {
    var out = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
    // 数字字符引用 &#123; / &#x1F;
    out = Regex("&#(\\d+);").replace(out) { m ->
        m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
    }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
        m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
    }
    return out.replace("&amp;", "&")
}
