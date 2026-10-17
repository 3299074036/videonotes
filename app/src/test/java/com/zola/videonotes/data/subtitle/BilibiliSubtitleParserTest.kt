package com.zola.videonotes.data.subtitle

import com.zola.videonotes.domain.model.SubtitleSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** B站字幕文件 JSON 解析测试。 */
class BilibiliSubtitleParserTest {

    private val sample = """
        {"body": [
          {"from": 1.23, "to": 3.73, "content": "第一行"},
          {"from": 4.0, "to": 5.5, "content": "   "},
          {"from": 6.0, "to": 7.0, "content": "第二行"},
          {"from": 8.0, "to": 9.0}
        ]}
    """.trimIndent()

    @Test
    fun `parseBilibiliSubtitleJson 秒转毫秒并跳过空content`() {
        val segments = parseBilibiliSubtitleJson(sample, "BILIBILI:BVxxx", SubtitleSource.OFFICIAL)

        // 空白 content 与缺失 content 的行被跳过，只剩 2 段；idx 紧凑编号
        assertEquals(2, segments.size)

        val first = segments[0]
        assertEquals("BILIBILI:BVxxx", first.videoId)
        assertEquals(0, first.idx)
        assertEquals(1230L, first.startMs)
        assertEquals(3730L, first.endMs)
        assertEquals("第一行", first.text)

        val second = segments[1]
        assertEquals(1, second.idx)
        assertEquals(6000L, second.startMs)
        assertEquals(7000L, second.endMs)
        assertEquals("第二行", second.text)
    }

    @Test
    fun `parseBilibiliSubtitleJson 空body返回空列表`() {
        assertEquals(0, parseBilibiliSubtitleJson("{}", "v", SubtitleSource.OFFICIAL).size)
        assertEquals(
            0,
            parseBilibiliSubtitleJson("""{"body": []}""", "v", SubtitleSource.OFFICIAL).size,
        )
    }
}
