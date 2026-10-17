package com.zola.videonotes.data.asr.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SentenceSegmenter 分句逻辑测试（纯 Kotlin，无需设备）。 */
class SentenceSegmenterTest {

    /** 按中英文标点分句：2 个句子 → 2 段。 */
    @Test
    fun `splits on punctuation`() {
        val tokens = listOf("你", "好", "世", "界", "。", "今", "天", "很", "好", "。")
        val ts = FloatArray(tokens.size) { it * 0.5f }
        val dur = FloatArray(tokens.size) { 0.5f }
        val segs = SentenceSegmenter.toSegments(tokens, ts, dur)
        assertEquals(2, segs.size)
        assertEquals("你好世界。", segs[0].text)
        assertEquals("今天很好。", segs[1].text)
        assertEquals(0L, segs[0].startMs)
        // 第一句 5 个 token × 0.5s = 2.5s
        assertEquals(2500L, segs[0].endMs)
        assertEquals(2500L, segs[1].startMs)
    }

    /** 英文标点也分句。 */
    @Test
    fun `splits on english punctuation`() {
        val tokens = listOf("hello", "!", "how", "are", "you", "?")
        val ts = FloatArray(tokens.size) { it * 1f }
        val dur = FloatArray(tokens.size) { 1f }
        val segs = SentenceSegmenter.toSegments(tokens, ts, dur)
        assertEquals(2, segs.size)
        assertEquals("hello!", segs[0].text)
    }

    /** 尾部没有标点也能成段（最后 flush）。 */
    @Test
    fun `trailing tokens without punctuation form a segment`() {
        val tokens = listOf("你好", "。", "再", "见")
        val ts = FloatArray(tokens.size) { it * 1f }
        val dur = FloatArray(tokens.size) { 1f }
        val segs = SentenceSegmenter.toSegments(tokens, ts, dur)
        assertEquals(2, segs.size)
        assertEquals("再见", segs[1].text)
    }

    /** 单段超过 30 秒强制截断。 */
    @Test
    fun `long segment is force split at 30s`() {
        // 每个 token 5 秒，无标点：70 个 token = 350 秒 → 至少 12 段
        val tokens = List(70) { "词" }
        val ts = FloatArray(tokens.size) { it * 5f }
        val dur = FloatArray(tokens.size) { 5f }
        val segs = SentenceSegmenter.toSegments(tokens, ts, dur)
        assertTrue(segs.size >= 12)
        for (s in segs) {
            // 每段至多 30 秒 + 最后一个 token 的时长（5s）
            assertTrue(s.endMs - s.startMs <= 35_000L)
        }
    }

    /** 空白 token 与 <|...|> 特殊 token 被跳过。 */
    @Test
    fun `blank and special tokens are skipped`() {
        val tokens = listOf("  ", "<|startoftranscript|>", "你", "好", "。", "<|endoftext|>")
        val ts = FloatArray(tokens.size) { it * 1f }
        val dur = FloatArray(tokens.size) { 1f }
        val segs = SentenceSegmenter.toSegments(tokens, ts, dur)
        assertEquals(1, segs.size)
        assertEquals("你好。", segs[0].text)
    }

    /** 无时间戳时按块时长均匀分配。 */
    @Test
    fun `missing timestamps fall back to even allocation`() {
        val tokens = listOf("你", "好", "。")
        val ts = FloatArray(tokens.size) // 全 0
        val dur = FloatArray(tokens.size)
        val segs = SentenceSegmenter.toSegments(
            tokens, ts, dur,
            chunkOffsetMs = 60_000L,
            chunkDurationMs = 30_000L,
        )
        assertEquals(1, segs.size)
        // 块偏移 60s + 均匀分配
        assertEquals(60_000L, segs[0].startMs)
        assertEquals(90_000L, segs[0].endMs)
    }

    /** 空 token 列表返回空。 */
    @Test
    fun `empty tokens return empty list`() {
        val segs = SentenceSegmenter.toSegments(
            emptyList(), FloatArray(0), FloatArray(0),
        )
        assertTrue(segs.isEmpty())
    }
}
