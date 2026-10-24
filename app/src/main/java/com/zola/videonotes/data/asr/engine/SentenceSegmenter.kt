package com.zola.videonotes.data.asr.engine

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * ASR 转写输出的分句器：token 级时间戳 → 句子级分段。
 *
 * 规则：
 * - 按标点 。！？；.!? 分句（token 尾字符命中即分句）
 * - 单段超过 [MAX_SEGMENT_MS]（30 秒）时强制截断，避免超长段
 * - 空白 token 与特殊 token（<|...|>，以及 Dolphin 的语言/方言标签 <...> 形）跳过
 * - bbpe 子词的 ▁ 词界标记归一为空格（Zipformer-CTC / Transducer 的 bbpe 解码用，
 *   与 Whisper BPE 处理同类；中文单字 token 不受影响）
 * - 时间戳缺失（全 0 或长度不对）时，按 token 在块内均匀分配
 * - [toSegmentsFromText]：已解码文本（如 sherpa result.text）专用——无逐 token
 *   时间戳时按句在块内均匀分配（2026-10-24 英文案：Zipformer-CTC 的 text 路径）
 *
 * 纯 Kotlin，可在 JVM 单元测试。
 */
data class RawSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

object SentenceSegmenter {

    private val SENTENCE_END = setOf('。', '！', '？', '；', '.', '!', '?', ';')

    /** 单段上限 30 秒，超时强制截断。 */
    private const val MAX_SEGMENT_MS = 30_000L

    /**
     * @param tokens 识别出的 token 文本（保留原始空格，由分句器统一 trim）
     * @param timestampsSec 每个 token 的开始时间（秒），与 tokens 等长
     * @param durationsSec 每个 token 的持续时间（秒），与 tokens 等长
     * @param chunkOffsetMs 本块在整段音频中的起始偏移（毫秒），分块转写时叠加
     * @param chunkDurationMs 本块时长（毫秒），仅时间戳缺失时用于均匀分配
     */
    fun toSegments(
        tokens: List<String>,
        timestampsSec: FloatArray,
        durationsSec: FloatArray,
        chunkOffsetMs: Long = 0L,
        chunkDurationMs: Long = 0L,
    ): List<RawSegment> {
        if (tokens.isEmpty()) return emptyList()

        val aligned = timestampsSec.size == tokens.size
        val hasTimestamps = aligned && tokens.indices.any { timestampsSec[it] > 0f }

        data class Tok(val text: String, val startMs: Long, val endMs: Long)
        val toks = mutableListOf<Tok>()
        for (i in tokens.indices) {
            // bbpe 子词标记归一：▁（U+2581，词界）转空格；中文单字 token 无此标记，不受影响
            val text = tokens[i].replace("▁", " ")
            if (text.isBlank()) continue
            if (text.startsWith("<") && text.endsWith(">")) continue
            val (startMs, endMs) = when {
                hasTimestamps -> {
                    val start = (timestampsSec[i] * 1000.0).roundToLong()
                    val dur = ((durationsSec.getOrNull(i) ?: 0f).coerceAtLeast(0f) * 1000.0)
                        .roundToLong()
                    start to start + dur
                }
                chunkDurationMs > 0 -> {
                    // 无时间戳：按 token 序号在块内均匀分配
                    val per = chunkDurationMs.toDouble() / tokens.size
                    (i * per).roundToLong() to ((i + 1) * per).roundToLong()
                }
                else -> 0L to 0L
            }
            toks.add(Tok(text, startMs + chunkOffsetMs, endMs + chunkOffsetMs))
        }
        if (toks.isEmpty()) return emptyList()

        val out = mutableListOf<RawSegment>()
        val buf = StringBuilder()
        var segStart = 0L
        var segEnd = 0L
        fun flush() {
            val text = buf.toString().trim()
            if (text.isNotEmpty()) {
                out.add(RawSegment(segStart, max(segEnd, segStart), text))
            }
            buf.clear()
        }
        for (t in toks) {
            if (buf.isEmpty()) segStart = t.startMs
            buf.append(t.text)
            segEnd = t.endMs
            val endsSentence = t.text.lastOrNull()?.let { it in SENTENCE_END } == true
            val tooLong = segEnd - segStart >= MAX_SEGMENT_MS
            if (endsSentence || tooLong) flush()
        }
        flush()
        return out
    }

    /**
     * 已解码文本 → 句子级分段（无逐 token 时间戳时用）。
     *
     * 按句末标点切句（标点保留；末尾无标点的残句自成一段），每句在块内均匀分配
     * 一段时间片。精度低于原生 token 时间戳，但文本正确性优先于时间戳精度——
     * 这是 Zipformer-CTC 改取 result.text 后的配套兜底（其 tokens 为字节碎片，
     * 不可用；text 本身不带逐 token 时间戳）。
     */
    fun toSegmentsFromText(
        text: String,
        chunkOffsetMs: Long = 0L,
        chunkDurationMs: Long = 0L,
    ): List<RawSegment> {
        if (text.isBlank()) return emptyList()
        val sentences = mutableListOf<String>()
        val buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in SENTENCE_END) {
                sentences.add(buf.toString())
                buf.clear()
            }
        }
        if (buf.isNotBlank()) sentences.add(buf.toString())
        if (sentences.isEmpty()) return emptyList()
        val per = if (chunkDurationMs > 0) chunkDurationMs.toDouble() / sentences.size else 0.0
        return sentences.mapIndexed { i, s ->
            val start = chunkOffsetMs + (i * per).roundToLong()
            val end = chunkOffsetMs + ((i + 1) * per).roundToLong()
            RawSegment(start, max(end, start), s.trim())
        }.filter { it.text.isNotEmpty() }
    }
}
