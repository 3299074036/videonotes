package com.zola.videonotes.data.subtitle

import com.zola.videonotes.domain.model.SubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** YouTube timedtext（json3 / srv3）解析测试。 */
class TimedtextParserTest {

    private val json3Sample = """
        {
          "events": [
            {"tStartMs": 1234, "dDurationMs": 2500, "segs": [{"utf8": "你好"}, {"utf8": "世界"}]},
            {"tStartMs": 4000, "dDurationMs": 1000},
            {"tStartMs": 5500, "dDurationMs": 1500, "segs": [{"utf8": "   "}]},
            {"tStartMs": 7000, "dDurationMs": 2000, "segs": [{"utf8": " 第二段 "}]}
          ]
        }
    """.trimIndent()

    @Test
    fun `parseTimedtextJson3 时间戳换算与空segs跳过`() {
        val segments = parseTimedtextJson3(json3Sample, "YOUTUBE:abc", SubtitleSource.OFFICIAL)

        // 无 segs 的 event 与空白文本的 event 被跳过，只剩 2 段
        assertEquals(2, segments.size)

        val first = segments[0]
        assertEquals("YOUTUBE:abc", first.videoId)
        assertEquals(0, first.idx)
        assertEquals(1234L, first.startMs)
        assertEquals(3734L, first.endMs)
        assertEquals("你好世界", first.text)
        assertEquals(SubtitleSource.OFFICIAL, first.source)

        val second = segments[1]
        assertEquals(1, second.idx)
        assertEquals(7000L, second.startMs)
        assertEquals(9000L, second.endMs)
        assertEquals("第二段", second.text)
    }

    @Test
    fun `parseTimedtextJson3 缺失duration时end等于start`() {
        val json = """{"events": [{"tStartMs": 100, "segs": [{"utf8": "x"}]}]}"""
        val segments = parseTimedtextJson3(json, "v", SubtitleSource.AUTO)
        assertEquals(1, segments.size)
        assertEquals(100L, segments[0].startMs)
        assertEquals(100L, segments[0].endMs)
    }

    private val srv3Sample = """
        <transcript>
        <text start="1.234" dur="2.5">第一行&lt;转义&gt;</text>
        <text start="4.0" dur="1.0">   </text>
        <text start="6.5" dur="0.5">第二行 &amp; 更多</text>
        </transcript>
    """.trimIndent()

    @Test
    fun `parseTimedtextSrv3 秒转毫秒与XML反转义`() {
        val segments = parseTimedtextSrv3(srv3Sample, "YOUTUBE:abc", SubtitleSource.AUTO)

        assertEquals(2, segments.size)
        assertEquals(1234L, segments[0].startMs)
        assertEquals(3734L, segments[0].endMs)
        assertEquals("第一行<转义>", segments[0].text)
        assertEquals(6500L, segments[1].startMs)
        assertEquals(7000L, segments[1].endMs)
        assertEquals("第二行 & 更多", segments[1].text)
    }
}
