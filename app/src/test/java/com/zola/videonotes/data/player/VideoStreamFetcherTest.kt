package com.zola.videonotes.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放器流选择测试（M6）：纯函数 selectProgressive，不碰网络。
 */
class VideoStreamFetcherTest {

    private fun candidate(
        url: String = "https://example.com/v.mp4",
        itag: Int = 0,
        height: Int = 0,
        isMp4: Boolean = true,
    ) = ProgressiveCandidate(url, itag, height, isMp4)

    @Test
    fun `prefers itag 22 over 18`() {
        val best = VideoStreamFetcher.selectProgressive(
            listOf(
                candidate(url = "https://example.com/18.mp4", itag = 18, height = 360),
                candidate(url = "https://example.com/22.mp4", itag = 22, height = 720),
            ),
        )
        assertEquals("https://example.com/22.mp4", best?.url)
    }

    @Test
    fun `prefers itag 18 over higher resolution unknown itag`() {
        val best = VideoStreamFetcher.selectProgressive(
            listOf(
                candidate(url = "https://example.com/137.mp4", itag = 137, height = 1080),
                candidate(url = "https://example.com/18.mp4", itag = 18, height = 360),
            ),
        )
        assertEquals("https://example.com/18.mp4", best?.url)
    }

    @Test
    fun `mp4 preferred over webm at same level`() {
        val best = VideoStreamFetcher.selectProgressive(
            listOf(
                candidate(url = "https://example.com/43.webm", itag = 43, height = 360, isMp4 = false),
                candidate(url = "https://example.com/x.mp4", itag = 999, height = 360, isMp4 = true),
            ),
        )
        assertEquals("https://example.com/x.mp4", best?.url)
    }

    @Test
    fun `falls back to highest resolution`() {
        val best = VideoStreamFetcher.selectProgressive(
            listOf(
                candidate(url = "https://example.com/360.mp4", itag = 999, height = 360),
                candidate(url = "https://example.com/720.mp4", itag = 998, height = 720),
            ),
        )
        assertEquals("https://example.com/720.mp4", best?.url)
    }

    @Test
    fun `blank urls filtered and empty returns null`() {
        assertNull(VideoStreamFetcher.selectProgressive(emptyList()))
        assertNull(
            VideoStreamFetcher.selectProgressive(
                listOf(candidate(url = "  "), candidate(url = "")),
            ),
        )
    }

    @Test
    fun `single valid candidate returned`() {
        val best = VideoStreamFetcher.selectProgressive(
            listOf(candidate(url = "https://example.com/only.mp4", itag = 18, height = 360)),
        )
        assertEquals("https://example.com/only.mp4", best?.url)
    }
}
