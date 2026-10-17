package com.zola.videonotes.data.llm

import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TranscriptChunker 测试：token 启发式估算、单遍/多遍判定、切分不跨 segment。 */
class TranscriptChunkerTest {

    private fun segment(index: Int, text: String) = TranscriptSegment(
        videoId = "v1",
        idx = index,
        startMs = index * 10_000L,
        endMs = index * 10_000L + 9_000,
        text = text,
        source = SubtitleSource.ASR,
    )

    /** 短文稿：总量 ≤ 上限时返回单个 chunk（单遍生成）。 */
    @Test
    fun `short transcript yields single chunk`() {
        val segments = listOf(segment(0, "这是开头"), segment(1, "这是结尾"))
        val chunks = TranscriptChunker.chunk(segments)
        assertEquals(1, chunks.size)
        assertEquals(segments, chunks.first())
    }

    /** token 启发式：中文按 1.5 字符/token、英文按 4 字符/token（实现末尾 +1 向上取整）。 */
    @Test
    fun `estimateTokens follows heuristic`() {
        val chinese = "中".repeat(30) // 30 字符 → 20 + 1
        val english = "a".repeat(40) // 40 字符 → 10 + 1
        assertEquals(21, TranscriptChunker.estimateTokens(chinese))
        assertEquals(11, TranscriptChunker.estimateTokens(english))
        assertEquals(31, TranscriptChunker.estimateTokens(chinese + english))
    }

    /** 长文稿：切成多个 chunk，每 chunk ≤ 单遍上限，且所有 segment 按序完整覆盖。 */
    @Test
    fun `long transcript splits into multiple chunks without breaking segments`() {
        val longText = "这是一个很长的字幕句子，用于填充 token 数量，字幕内容需要足够长才能触发切分逻辑。".repeat(6)
        val segments = (0 until 100).map { segment(it, longText) }
        val chunks = TranscriptChunker.chunk(segments)
        assertTrue("chunks=${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            val tokens = TranscriptChunker.estimateSegmentsTokens(chunk)
            assertTrue(
                "chunk tokens=$tokens 超过单遍上限",
                tokens <= TranscriptChunker.MAX_SINGLE_TOKENS,
            )
        }
        // segment 按顺序完整覆盖：无丢失、无拆分、无重叠
        assertEquals(segments, chunks.flatten())
    }

    /** 空文稿返回空列表，不抛异常。 */
    @Test
    fun `empty transcript yields no chunks`() {
        assertTrue(TranscriptChunker.chunk(emptyList()).isEmpty())
    }
}
