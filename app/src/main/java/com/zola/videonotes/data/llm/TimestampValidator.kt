package com.zola.videonotes.data.llm

import com.zola.videonotes.domain.model.TranscriptSegment

/** 从 Markdown 中提取出的时间戳引用。 */
data class TimestampRef(
    /** 原始 pill 文本，如 `[[12:34]]`。 */
    val raw: String,
    /** 换算后的秒数。 */
    val seconds: Long,
)

/**
 * 时间戳质量门禁（纯逻辑）。
 *
 * - pill 格式：`[[mm:ss]]` / `[[h:mm:ss]]`（M1/M4 冻结语法，M5 阅读器按此渲染）；
 * - 每个时间戳必须在文稿分段中 ±5 秒内找到文本依据，否则判为失败；
 * - 失败的时间戳剔除并标注 `（时间点待核实）`。
 */
object TimestampValidator {

    /** pill 正则：M5 阅读器复用（internal），保持单点定义。 */
    internal val PILL_REGEX = Regex("""\[\[(\d{1,2}:)?\d{1,2}:\d{2}\]\]""")

    /** 校验容差：±5 秒。 */
    const val TOLERANCE_MS = 5000L

    /** 失败时间戳被剔除后的标注。 */
    const val UNVERIFIED_MARK = "（时间点待核实）"

    /** 从 Markdown 中提取所有时间戳（按出现顺序，保留重复）。 */
    fun extractTimestamps(markdown: String): List<TimestampRef> =
        PILL_REGEX.findAll(markdown).mapNotNull { match ->
            val raw = match.value
            val seconds = parseToSeconds(raw.removeSurrounding("[[", "]]"))
            seconds?.let { TimestampRef(raw, it) }
        }.toList()

    /**
     * 把 `mm:ss` / `h:mm:ss` 换算成秒。
     * 非法格式返回 null。
     */
    fun parseToSeconds(text: String): Long? {
        val parts = text.split(":")
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.toLongOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        // 秒与分必须 < 60
        if (numbers.last() >= 60) return null
        if (numbers.size == 3 && numbers[1] >= 60) return null
        return when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
    }

    /**
     * 校验：返回**未通过**的时间戳（去重）。
     * 通过条件：存在分段满足 `startMs - 5000 <= t <= endMs + 5000`。
     */
    fun findFailures(
        timestamps: List<TimestampRef>,
        segments: List<TranscriptSegment>,
    ): List<TimestampRef> {
        if (timestamps.isEmpty() || segments.isEmpty()) return emptyList()
        return timestamps.distinctBy { it.seconds }
            .filter { ref ->
                val tMs = ref.seconds * 1000
                segments.none { seg ->
                    seg.startMs - TOLERANCE_MS <= tMs && tMs <= seg.endMs + TOLERANCE_MS
                }
            }
    }

    /**
     * 剔除失败的时间戳：把 pill 替换为 [UNVERIFIED_MARK]。
     * 同一秒数的多个 pill 一次性全部替换。
     */
    fun stripFailures(markdown: String, failures: List<TimestampRef>): String {
        var result = markdown
        for (ref in failures.distinctBy { it.seconds }) {
            result = result.replace(ref.raw, UNVERIFIED_MARK)
        }
        return result
    }
}
