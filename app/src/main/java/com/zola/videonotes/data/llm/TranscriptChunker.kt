package com.zola.videonotes.data.llm

import com.zola.videonotes.domain.model.TranscriptSegment

/**
 * 文稿分块器（纯函数）。
 *
 * token 估算为启发式：中文按 1.5 字符/token、英文按 4 字符/token。
 * 这不是精确计数，长视频在边界处可能被截断（见已知问题），
 * 因此按 segment 边界切分，宁可少装一段也不截断一句话。
 */
object TranscriptChunker {

    /** 单次请求上限：估算 token 数。 */
    const val MAX_SINGLE_TOKENS = 12000

    /** 超限时每个 chunk 的目标 token 数。 */
    const val CHUNK_TOKENS = 8000

    /**
     * 估算文本 token 数（启发式）。
     * 中文字符（含 CJK 标点）按 1.5 字符/token，其余按 4 字符/token。
     */
    fun estimateTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if (isCjk(c)) cjk++ else other++
        }
        return (cjk / 1.5 + other / 4.0).toInt() + 1
    }

    /** 估算一批分段的总 token 数（含每段的时间戳标记开销）。 */
    fun estimateSegmentsTokens(segments: List<TranscriptSegment>): Int =
        segments.sumOf { estimateTokens(it.text) + TIMESTAMP_OVERHEAD }

    /**
     * 按 segment 边界切分。
     * - 总量 ≤ [maxSingleTokens]：返回单个 chunk（单遍生成）；
     * - 否则按 [chunkTokens] 切成多个 chunk（map-reduce），绝不拆散单个 segment。
     */
    fun chunk(
        segments: List<TranscriptSegment>,
        maxSingleTokens: Int = MAX_SINGLE_TOKENS,
        chunkTokens: Int = CHUNK_TOKENS,
    ): List<List<TranscriptSegment>> {
        if (segments.isEmpty()) return emptyList()
        if (estimateSegmentsTokens(segments) <= maxSingleTokens) {
            return listOf(segments)
        }
        val chunks = mutableListOf<List<TranscriptSegment>>()
        var current = mutableListOf<TranscriptSegment>()
        var currentTokens = 0
        for (seg in segments) {
            val segTokens = estimateTokens(seg.text) + TIMESTAMP_OVERHEAD
            if (current.isNotEmpty() && currentTokens + segTokens > chunkTokens) {
                chunks.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(seg)
            currentTokens += segTokens
        }
        if (current.isNotEmpty()) chunks.add(current)
        return chunks
    }

    private fun isCjk(c: Char): Boolean {
        val block = Character.UnicodeBlock.of(c) ?: return false
        return block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block === Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block === Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block === Character.UnicodeBlock.HIRAGANA ||
            block === Character.UnicodeBlock.KATAKANA ||
            block === Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block === Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block === Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** 每段文稿的时间戳标记（如 `[12:34]`）与换行的估算开销。 */
    private const val TIMESTAMP_OVERHEAD = 4
}
