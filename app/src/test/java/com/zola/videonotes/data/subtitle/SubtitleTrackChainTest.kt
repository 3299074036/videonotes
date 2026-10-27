package com.zola.videonotes.data.subtitle

import com.zola.videonotes.data.network.bilibili.BiliSubtitleTrack
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** P3：字幕轨道排序、AI 判定与轨道级兜底链测试（纯逻辑，不发网络请求）。 */
class SubtitleTrackChainTest {

    private data class T(val auto: Boolean, val lang: String, val name: String)

    private fun seg(text: String) =
        TranscriptSegment("v", 0, 0L, 1000L, text, SubtitleSource.OFFICIAL)

    @Test
    fun `ordering is manual zh then manual en then auto zh then auto en then rest`() {
        val tracks = listOf(
            T(auto = true, lang = "fr", name = "auto-fr"),
            T(auto = true, lang = "en", name = "auto-en"),
            T(auto = false, lang = "en", name = "manual-en"),
            T(auto = true, lang = "zh", name = "auto-zh"),
            T(auto = false, lang = "ja", name = "manual-ja"),
            T(auto = false, lang = "zh", name = "manual-zh"),
        )
        val ordered = orderSubtitleTracks(tracks, isAuto = { it.auto }, langTag = { it.lang })
        assertEquals(
            listOf("manual-zh", "manual-en", "auto-zh", "auto-en", "auto-fr", "manual-ja"),
            ordered.map { it.name },
        )
    }

    @Test
    fun `chinese in language name counts as zh tier`() {
        val tracks = listOf(
            T(auto = true, lang = "ai-zh 中文（AI生成）", name = "ai-zh"),
            T(auto = false, lang = "zh-CN 中文（中国）", name = "manual-zh"),
        )
        val ordered = orderSubtitleTracks(tracks, isAuto = { it.auto }, langTag = { it.lang })
        assertEquals(listOf("manual-zh", "ai-zh"), ordered.map { it.name })
    }

    @Test
    fun `bili ai detection uses ai prefix and ai wording`() {
        assertTrue(isBiliAiTrack(BiliSubtitleTrack(lan = "ai-zh", lanDoc = "中文（AI生成）", subtitleUrl = "")))
        assertTrue(isBiliAiTrack(BiliSubtitleTrack(lan = "zh-CN", lanDoc = "中文（AI生成）", subtitleUrl = "")))
        assertTrue(!isBiliAiTrack(BiliSubtitleTrack(lan = "zh-CN", lanDoc = "中文（中国）", subtitleUrl = "")))
        assertTrue(!isBiliAiTrack(BiliSubtitleTrack(lan = "en", lanDoc = "英语", subtitleUrl = "")))
    }

    @Test
    fun `first hit wins`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) { listOf(seg(it)) }
        val hit = outcome as TrackAttemptOutcome.Hit<String>
        assertEquals("a", hit.candidate)
    }

    @Test
    fun `failure falls through to next track`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) {
            if (it == "a") throw IOException("dead link")
            listOf(seg(it))
        }
        val hit = outcome as TrackAttemptOutcome.Hit<String>
        assertEquals("b", hit.candidate)
    }

    @Test
    fun `empty falls through to next track`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) {
            if (it == "a") emptyList() else listOf(seg(it))
        }
        val hit = outcome as TrackAttemptOutcome.Hit<String>
        assertEquals("b", hit.candidate)
    }

    @Test
    fun `all failures yield failed with last error`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) {
            throw IOException("fail-$it")
        }
        val failed = outcome as TrackAttemptOutcome.Failed
        assertEquals("fail-b", failed.error.message)
    }

    @Test
    fun `all empty yields empty`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) { emptyList() }
        assertTrue(outcome is TrackAttemptOutcome.Empty)
    }

    @Test
    fun `downloaded empty plus failed yields empty not failed`() = runBlocking {
        val outcome = firstNonEmptyTrack(listOf("a", "b")) {
            if (it == "a") emptyList() else throw IOException("dead link")
        }
        assertTrue(outcome is TrackAttemptOutcome.Empty)
    }

    @Test
    fun `cancellation propagates instead of falling through`() = runBlocking {
        try {
            firstNonEmptyTrack(listOf("a", "b")) { throw CancellationException() }
            error("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
    }
}
