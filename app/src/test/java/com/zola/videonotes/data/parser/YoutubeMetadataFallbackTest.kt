package com.zola.videonotes.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P4：YouTube watch 页元数据兜底解析测试（纯 HTML 解析，不发网络请求）。 */
class YoutubeMetadataFallbackTest {

    private fun page(og: String = "", ld: String = ""): String =
        "<html><head>$og$ld</head><body></body></html>"

    @Test
    fun `og tags plus json ld merge`() {
        val html = page(
            og = "<meta property=\"og:title\" content=\"Test &amp; Video\">" +
                "<meta property=\"og:image\" content=\"https://i.ytimg.com/vi/abc123/hqdefault.jpg\">",
            ld = "<script type=\"application/ld+json\">" +
                "{\"@context\":\"https://schema.org\",\"@type\":\"VideoObject\"," +
                "\"name\":\"LD Title\",\"description\":\"desc\"," +
                "\"thumbnailUrl\":\"https://i.ytimg.com/vi/abc123/ld.jpg\"," +
                "\"duration\":\"PT15M30S\",\"author\":{\"@type\":\"Person\",\"name\":\"Some Channel\"}}" +
                "</script>",
        )
        val meta = parseYoutubeWatchMetadata(html)!!
        // og: 优先于 JSON-LD；作者与时长只取 JSON-LD。
        assertEquals("Test & Video", meta.title)
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", meta.coverUrl)
        assertEquals(930L, meta.durationSec)
        assertEquals("Some Channel", meta.author)
    }

    @Test
    fun `json ld only fills all fields`() {
        val html = page(
            ld = "<script type=\"application/ld+json\">" +
                "{\"@type\":\"VideoObject\",\"name\":\"LD Only\"," +
                "\"thumbnailUrl\":[\"https://example.com/first.jpg\",\"https://example.com/second.jpg\"]," +
                "\"duration\":\"PT1H2M3S\",\"author\":\"Plain Author\"}" +
                "</script>",
        )
        val meta = parseYoutubeWatchMetadata(html)!!
        assertEquals("LD Only", meta.title)
        assertEquals("https://example.com/first.jpg", meta.coverUrl)
        assertEquals(3723L, meta.durationSec)
        assertEquals("Plain Author", meta.author)
    }

    @Test
    fun `json ld graph form is recognized`() {
        val html = page(
            ld = "<script type=\"application/ld+json\">" +
                "{\"@context\":\"https://schema.org\",\"@graph\":[" +
                "{\"@type\":\"WebPage\",\"name\":\"not this\"}," +
                "{\"@type\":\"VideoObject\",\"name\":\"Graph Video\",\"duration\":\"PT45S\"}]}" +
                "</script>",
        )
        val meta = parseYoutubeWatchMetadata(html)!!
        assertEquals("Graph Video", meta.title)
        assertEquals(45L, meta.durationSec)
    }

    @Test
    fun `attribute order swapped still parses`() {
        val html = page(
            og = "<meta content=\"Swapped Title\" property=\"og:title\">" +
                "<meta content=\"https://example.com/cover.jpg\" property=\"og:image\">",
        )
        val meta = parseYoutubeWatchMetadata(html)!!
        assertEquals("Swapped Title", meta.title)
        assertEquals("https://example.com/cover.jpg", meta.coverUrl)
    }

    @Test
    fun `no title yields null`() {
        // 只有封面、没有可确认的标题：兜底无意义，返回 null 保留原错误。
        val html = page(
            og = "<meta property=\"og:image\" content=\"https://example.com/cover.jpg\">",
            ld = "<script type=\"application/ld+json\">{\"@type\":\"VideoObject\",\"duration\":\"PT10S\"}</script>",
        )
        assertNull(parseYoutubeWatchMetadata(html))
        assertNull(parseYoutubeWatchMetadata("<html><head></head></html>"))
    }

    @Test
    fun `og video is ignored by design`() {
        // og:video 是失效的 Flash 地址，解析结果里根本没有它的位置——这里只确认标题解析不受它干扰。
        val html = page(
            og = "<meta property=\"og:title\" content=\"Has Og Video\">" +
                "<meta property=\"og:video\" content=\"https://www.youtube.com/v/abc123\">" +
                "<meta property=\"og:video:type\" content=\"application/x-shockwave-flash\">",
        )
        val meta = parseYoutubeWatchMetadata(html)!!
        assertEquals("Has Og Video", meta.title)
        assertEquals("", meta.coverUrl)
    }

    @Test
    fun `iso durations parse`() {
        assertEquals(3723L, parseIso8601Duration("PT1H2M3S"))
        assertEquals(930L, parseIso8601Duration("PT15M30S"))
        assertEquals(45L, parseIso8601Duration("PT45S"))
        assertEquals(90061L, parseIso8601Duration("P1DT1H1M1S"))
        assertEquals(0L, parseIso8601Duration("not-a-duration"))
    }
}
