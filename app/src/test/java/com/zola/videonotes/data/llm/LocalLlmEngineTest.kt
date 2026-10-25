package com.zola.videonotes.data.llm

import com.zola.videonotes.data.llm.LocalLlmEngine
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M7a 本地引擎参数测试：小 chunk 切分、模型目录 sanity。 */
class LocalLlmEngineTest {

    private fun segment(index: Int, text: String) = TranscriptSegment(
        videoId = "v1",
        idx = index,
        startMs = index * 10_000L,
        endMs = index * 10_000L + 9_000,
        text = text,
        source = SubtitleSource.ASR,
    )

    /** 本地小上限：同样文稿本地切得比云端更碎。 */
    @Test
    fun `local chunking splits finer than cloud`() {
        val longText = "这是一个很长的字幕句子，用于填充 token 数量，字幕内容需要足够长才能触发切分逻辑。".repeat(6)
        val segments = (0 until 100).map { segment(it, longText) }
        val cloudChunks = TranscriptChunker.chunk(segments)
        val localChunks = TranscriptChunker.chunk(
            segments,
            maxSingleTokens = LocalLlmEngine.MAX_SINGLE_TOKENS_LOCAL,
            chunkTokens = LocalLlmEngine.CHUNK_TOKENS_LOCAL,
        )
        assertTrue("local=${localChunks.size} cloud=${cloudChunks.size}", localChunks.size >= cloudChunks.size)
        for (chunk in localChunks) {
            val tokens = TranscriptChunker.estimateSegmentsTokens(chunk)
            assertTrue(
                "chunk tokens=$tokens 超过本地单遍上限",
                tokens <= LocalLlmEngine.CHUNK_TOKENS_LOCAL + TranscriptChunker.estimateSegmentsTokens(chunk.take(1)),
            )
        }
        // 所有 segment 按序完整覆盖
        assertEquals(segments, localChunks.flatten())
    }

    /** 本地引擎常量 sanity：chunk < 单遍上限 < 上下文。 */
    @Test
    fun `local engine constants are consistent`() {
        assertTrue(LocalLlmEngine.CHUNK_TOKENS_LOCAL < LocalLlmEngine.MAX_SINGLE_TOKENS_LOCAL)
        assertTrue(LocalLlmEngine.MAX_SINGLE_TOKENS_LOCAL < LocalLlmEngine.N_CTX)
        assertTrue(LocalLlmEngine.MAX_OUTPUT_TOKENS > 0)
    }

    /** 模型目录：两档模型 ID 唯一、URL 为 https 直链、文件名以 .gguf 结尾
     *（2026-10-24 国内名单起部分条目走 ModelScope，不再断言 HF 前缀）。 */
    @Test
    fun `local model catalog is sane`() {
        val ids = LocalLlmModels.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (m in LocalLlmModels.ALL) {
            assertTrue(m.fileName.endsWith(".gguf"))
            assertTrue(m.url.startsWith("https://"))
            assertTrue(m.sizeBytes > 500_000_000L)
        }
        // 默认选中 4B
        assertEquals(LocalLlmModels.QWEN3_4B.id, LocalLlmModels.ALL.first().id)
    }
}
