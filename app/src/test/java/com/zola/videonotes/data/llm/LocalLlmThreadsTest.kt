package com.zola.videonotes.data.llm

import com.zola.videonotes.data.catalog.CatalogKind
import com.zola.videonotes.data.catalog.EntryDraft
import com.zola.videonotes.data.catalog.EntryFileDraft
import com.zola.videonotes.data.catalog.validateEntryDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 每模型线程数覆盖：引擎分档解析 + 表单校验。 */
class LocalLlmThreadsTest {

    private val giB = 1024L * 1024 * 1024

    /** 自动分档边界：2GiB 封顶 4 线程，4GiB 封顶 6 线程，超过 8 线程。 */
    @Test
    fun `auto tiers follow file size boundaries`() {
        assertEquals(4, LocalLlmEngine.resolveThreads(giB, null))
        assertEquals(4, LocalLlmEngine.resolveThreads(2 * giB, null))
        assertEquals(6, LocalLlmEngine.resolveThreads(2 * giB + 1, null))
        assertEquals(6, LocalLlmEngine.resolveThreads(4 * giB, null))
        assertEquals(8, LocalLlmEngine.resolveThreads(4 * giB + 1, null))
    }

    /** 手动覆盖优先于分档：小模型也能开 8 线程，大模型也能压到 4 线程。 */
    @Test
    fun `manual override wins over size tier`() {
        assertEquals(8, LocalLlmEngine.resolveThreads(giB, 8))
        assertEquals(4, LocalLlmEngine.resolveThreads(8 * giB, 4))
        assertEquals(16, LocalLlmEngine.resolveThreads(3 * giB, 16))
        assertEquals(1, LocalLlmEngine.resolveThreads(3 * giB, 1))
    }

    /** 越界覆盖值不进引擎：回落自动分档，不会把非法线程数传给 native。 */
    @Test
    fun `out of range override falls back to tier`() {
        assertEquals(4, LocalLlmEngine.resolveThreads(giB, 0))
        assertEquals(4, LocalLlmEngine.resolveThreads(giB, 17))
        assertEquals(4, LocalLlmEngine.resolveThreads(giB, -2))
    }

    private fun draft(threadsOverride: Int?) = EntryDraft(
        displayName = "通义千问 4B",
        id = "qwen3-4b",
        originalName = "",
        kind = CatalogKind.LLM,
        templateId = "gguf-qwen3-chat",
        useCases = emptyList(),
        minRamGB = null,
        description = "",
        files = listOf(
            EntryFileDraft(
                name = "model.gguf",
                url = "https://example.com/model.gguf",
                sizeBytes = 1000L,
                sha256 = "a".repeat(64),
            ),
        ),
        threadsOverride = threadsOverride,
    )

    /** 表单校验：合法覆盖值与自动档都放行，越界值报 threads 错。 */
    @Test
    fun `draft validation accepts valid override and rejects out of range`() {
        assertTrue(validateEntryDraft(draft(8), emptySet(), true).isEmpty())
        assertTrue(validateEntryDraft(draft(null), emptySet(), true).isEmpty())
        assertTrue(validateEntryDraft(draft(0), emptySet(), true).any { it.field == "threads" })
        assertTrue(validateEntryDraft(draft(17), emptySet(), true).any { it.field == "threads" })
    }
}
