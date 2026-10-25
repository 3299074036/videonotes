package com.zola.videonotes.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户目录合并、表单校验与序列化往返测试（纯逻辑，不碰文件与 DataStore）。 */
class UserCatalogTest {

    private fun entry(
        id: String,
        kind: CatalogKind = CatalogKind.LLM,
        recommended: Boolean = false,
    ): CatalogEntry = CatalogEntry(
        id = id,
        kind = kind,
        displayName = id,
        description = "",
        templateId = if (kind == CatalogKind.LLM) "gguf-qwen3-chat" else "sherpa-whisper",
        files = listOf(CatalogFile("$id.gguf", "https://example.com/$id.gguf", 100L, null)),
        recommended = recommended,
    )

    @Test
    fun `merge keeps pool order and appends custom`() {
        val pool = listOf(entry("a"), entry("b"), entry("c"))
        val merged = mergeUserCatalog(pool, UserCatalogState(custom = listOf(entry("x"))))
        assertEquals(listOf("a", "b", "c", "x"), merged.map { it.id })
    }

    @Test
    fun `merge drops removed and replaces edited in place`() {
        val pool = listOf(entry("a"), entry("b", recommended = true), entry("c"))
        val editedB = entry("b").copy(displayName = "B-edited")
        val state = UserCatalogState(
            removedIds = setOf("a"),
            edited = mapOf("b" to editedB),
        )
        val merged = mergeUserCatalog(pool, state)
        assertEquals(listOf("b", "c"), merged.map { it.id })
        assertEquals("B-edited", merged.first { it.id == "b" }.displayName)
    }

    @Test
    fun `merge with empty state returns pool unchanged`() {
        val pool = listOf(entry("a"), entry("b"))
        assertEquals(pool, mergeUserCatalog(pool, UserCatalogState()))
    }

    @Test
    fun `edited entry keeps its recommended flag from the form`() {
        // 表单「是否推荐」决定推荐位：改后为是则保留标签，改后为否则去掉标签。
        val pool = listOf(entry("a", recommended = true), entry("b"))
        val editedA = entry("a", recommended = true)
        val editedB = entry("b", recommended = true)
        val merged = mergeUserCatalog(
            pool,
            UserCatalogState(edited = mapOf("a" to editedA, "b" to editedB)),
        )
        assertTrue(merged.first { it.id == "a" }.recommended)
        assertTrue(merged.first { it.id == "b" }.recommended)
        val mergedOff = mergeUserCatalog(
            pool,
            UserCatalogState(edited = mapOf("a" to entry("a", recommended = false))),
        )
        assertFalse(mergedOff.first { it.id == "a" }.recommended)
    }

    @Test
    fun `validation rejects blank name bad id and duplicate id on add`() {
        val draft = EntryDraft(
            displayName = "",
            id = "Bad ID!",
            originalName = "",
            kind = CatalogKind.LLM,
            templateId = "gguf-qwen3-chat",
            useCases = emptyList(),
            minRamGB = null,
            description = "",
            files = listOf(EntryFileDraft("a.gguf", "https://example.com/a.gguf", 10L, "")),
        )
        val errors = validateEntryDraft(draft, existingIds = setOf("a"), isNew = true)
        val fields = errors.map { it.field }.toSet()
        assertTrue("displayName" in fields)
        assertTrue("id" in fields)

        val dup = draft.copy(displayName = "ok", id = "a")
        assertTrue(validateEntryDraft(dup, existingIds = setOf("a"), isNew = true)
            .any { it.field == "id" })
        // 编辑时标识不可改，不判重。
        assertFalse(validateEntryDraft(dup, existingIds = setOf("a"), isNew = false)
            .any { it.field == "id" })
    }

    @Test
    fun `validation enforces llm single file and asr template file sets`() {
        val llmTwoFiles = EntryDraft(
            displayName = "ok", id = "x", originalName = "",
            kind = CatalogKind.LLM, templateId = "gguf-qwen3-chat",
            useCases = emptyList(), minRamGB = null, description = "",
            files = listOf(
                EntryFileDraft("a.gguf", "https://example.com/a", 10L, ""),
                EntryFileDraft("b.gguf", "https://example.com/b", 10L, ""),
            ),
        )
        assertTrue(validateEntryDraft(llmTwoFiles, emptySet(), true)
            .any { it.field == "files" })

        val whisperMissingDecoder = EntryDraft(
            displayName = "ok", id = "y", originalName = "",
            kind = CatalogKind.ASR, templateId = "sherpa-whisper",
            useCases = emptyList(), minRamGB = null, description = "",
            files = listOf(
                EntryFileDraft("tiny-encoder.int8.onnx", "https://example.com/e", 10L, ""),
                EntryFileDraft("tiny-tokens.txt", "https://example.com/t", 10L, ""),
            ),
        )
        assertTrue(validateEntryDraft(whisperMissingDecoder, emptySet(), true)
            .any { it.field == "files" })
    }

    @Test
    fun `validation rejects bad url size and sha`() {
        val draft = EntryDraft(
            displayName = "ok", id = "x", originalName = "",
            kind = CatalogKind.LLM, templateId = "gguf-qwen3-chat",
            useCases = emptyList(), minRamGB = null, description = "",
            files = listOf(EntryFileDraft("a.gguf", "ftp://example.com/a", 0L, "xyz")),
        )
        val fields = validateEntryDraft(draft, emptySet(), true).map { it.field }.toSet()
        assertTrue("file_0_url" in fields)
        assertTrue("file_0_size" in fields)
        assertTrue("file_0_sha" in fields)
    }

    @Test
    fun `validation accepts a clean draft`() {
        val draft = EntryDraft(
            displayName = "ok", id = "x", originalName = "",
            kind = CatalogKind.LLM, templateId = "gguf-qwen3-chat",
            useCases = listOf("zh"), minRamGB = 6.0, description = "",
            files = listOf(
                EntryFileDraft(
                    "a.gguf", "https://example.com/a", 10L,
                    "a".repeat(64),
                ),
            ),
        )
        assertTrue(validateEntryDraft(draft, existingIds = setOf("a"), isNew = true).isEmpty())
    }

    @Test
    fun `filesChanged detects file edits only`() {
        val old = entry("a")
        assertFalse(filesChanged(old, old.copy(displayName = "renamed")))
        assertTrue(filesChanged(old, old.copy(files = listOf(
            CatalogFile("a.gguf", "https://example.com/other.gguf", 100L, null),
        ))))
    }
}
