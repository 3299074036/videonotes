package com.zola.videonotes.data.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * YouTube 网页元数据兜底（P4）：NewPipe 被风控时，从 watch 页 HTML 里抠标题 / 封面 / 作者 / 时长。
 *
 * 只补元数据，不给播放地址：og:video 是失效的 Flash 播放器地址，绝不能当 mediaUrl；
 * 播放 / 转写 / 下载仍需 NewPipe 取流成功，兜底结果必须以 isMetadataOnly 标记。
 *
 * 来源优先级：og: 标签优先（标题、封面），缺失时用 JSON-LD（VideoObject）补；
 * 作者与时长只取 JSON-LD（og: 没有可靠的作者与时长字段）。
 * 无标题返回 null——无法确认是哪个视频时，兜底没有意义，保留原错误。
 */
data class YoutubePageMetadata(
    val title: String,
    val coverUrl: String,
    val durationSec: Long,
    val author: String,
)

fun parseYoutubeWatchMetadata(html: String): YoutubePageMetadata? {
    val og = extractOpenGraph(html)
    val ld = extractJsonLdVideoObject(html)
    val title = (og["og:title"] ?: ld?.get("name")?.asStringOrNull()).orEmpty()
        .let { unescapeHtml(it).trim() }
    if (title.isBlank()) return null
    val cover = (og["og:image"] ?: og["og:image:secure_url"] ?: ld?.get("thumbnailUrl")?.stringOrFirst())
        .orEmpty().let { unescapeHtml(it).trim() }
    val author = ld?.get("author")?.let { authorNode ->
        when {
            authorNode.isJsonPrimitive -> authorNode.asStringOrNull()
            authorNode.isJsonObject -> authorNode.asJsonObject.get("name")?.asStringOrNull()
            authorNode.isJsonArray -> authorNode.asJsonArray.firstOrNull()
                ?.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.asStringOrNull()
            else -> null
        }
    }.orEmpty().let { unescapeHtml(it).trim() }
    val duration = ld?.get("duration")?.asStringOrNull()?.let { parseIso8601Duration(it) } ?: 0L
    return YoutubePageMetadata(
        title = title,
        coverUrl = cover,
        durationSec = duration,
        author = author,
    )
}

private fun JsonElement.asStringOrNull(): String? =
    takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonElement.stringOrFirst(): String? {
    if (isJsonPrimitive) return asStringOrNull()
    if (isJsonArray) {
        return asJsonArray.firstOrNull()?.asStringOrNull()
    }
    return null
}

/** 提取全部 <meta> 标签的 property/name → content（属性顺序不限）。 */
private fun extractOpenGraph(html: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val tagRegex = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    val attrRegex = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    for (tag in tagRegex.findAll(html)) {
        val attrs = mutableMapOf<String, String>()
        for (m in attrRegex.findAll(tag.value)) {
            attrs[m.groupValues[1].lowercase()] = m.groupValues[2].ifEmpty { m.groupValues[3] }
        }
        val key = attrs["property"] ?: attrs["name"] ?: continue
        val content = attrs["content"] ?: continue
        if (key.startsWith("og:") && key !in out) {
            out[key] = content
        }
    }
    return out
}

/** 提取首个 VideoObject（对象 / 数组 / @graph 三种写法都认）。 */
private fun extractJsonLdVideoObject(html: String): JsonObject? {
    val scriptRegex = Regex(
        """<script\b[^>]*type\s*=\s*["']application/ld\+json[^"']*["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    for (m in scriptRegex.findAll(html)) {
        val element = runCatching { JsonParser.parseString(m.groupValues[1]) }.getOrNull() ?: continue
        findVideoObject(element)?.let { return it }
    }
    return null
}

private fun findVideoObject(el: JsonElement): JsonObject? {
    if (el.isJsonObject) {
        val obj = el.asJsonObject
        if (obj.get("@type")?.asStringOrNull() == "VideoObject") return obj
        val graph = obj.get("@graph")
        if (graph != null && graph.isJsonArray) {
            for (item in graph.asJsonArray) {
                findVideoObject(item)?.let { return it }
            }
        }
        val main = obj.get("mainEntity")
        if (main != null) return findVideoObject(main)
    } else if (el.isJsonArray) {
        for (item in el.asJsonArray) {
            findVideoObject(item)?.let { return it }
        }
    }
    return null
}

/** ISO 8601 时长（PT1H2M3S / PT15M30S / PT45S / P1DT2H3M4S）转秒；解析失败返回 0。 */
internal fun parseIso8601Duration(raw: String): Long {
    val m = Regex("""P(?:(\d+)D)?T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?""").matchEntire(raw.trim())
        ?: return 0L
    val days = m.groupValues[1].toLongOrNull() ?: 0L
    val hours = m.groupValues[2].toLongOrNull() ?: 0L
    val minutes = m.groupValues[3].toLongOrNull() ?: 0L
    val seconds = m.groupValues[4].toDoubleOrNull() ?: 0.0
    return days * 86400 + hours * 3600 + minutes * 60 + seconds.toLong()
}

/** 解码 HTML 实体（&amp; 最后解，避免双重解码）。 */
private fun unescapeHtml(s: String): String {
    var out = s
    for ((entity, char) in listOf(
        "&quot;" to "\"",
        "&#34;" to "\"",
        "&#39;" to "'",
        "&#x27;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
    )) {
        out = out.replace(entity, char)
    }
    return out.replace("&amp;", "&")
}
