package com.zola.videonotes.data.llm

import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TimestampValidator 测试：pill 提取、±5 秒边界、失败剔除标注（M4 时间戳质量门禁）。 */
class TimestampValidatorTest {

    private fun segments(): List<TranscriptSegment> = listOf(
        TranscriptSegment(
            videoId = "v1", idx = 0, startMs = 0, endMs = 10_000,
            text = "开头", source = SubtitleSource.OFFICIAL,
        ),
        TranscriptSegment(
            videoId = "v1", idx = 1, startMs = 60_000, endMs = 90_000,
            text = "中间", source = SubtitleSource.OFFICIAL,
        ),
    )

    /** pill 正则提取多种格式：mm:ss 与 h:mm:ss，保留出现顺序。 */
    @Test
    fun `extractTimestamps extracts mmss and h_mm_ss formats`() {
        val refs = TimestampValidator.extractTimestamps(
            "见 [[05:30]] 的介绍和 [[1:02:03]] 的总结",
        )
        assertEquals(
            listOf(
                TimestampRef("[[05:30]]", 330),
                TimestampRef("[[1:02:03]]", 3723),
            ),
            refs,
        )
    }

    /** 非法 pill 不被提取（如秒数只有一位、冒号缺失、非数字）。 */
    @Test
    fun `extractTimestamps ignores malformed pills`() {
        val refs = TimestampValidator.extractTimestamps(
            "无效 [[1:2:3]] 与 [[abc]] 与 [[12:345]]",
        )
        assertTrue("refs=$refs", refs.isEmpty())
    }

    /** 上边界恰好 +5 秒通过、超出 1 秒失败。 */
    @Test
    fun `findFailures upper boundary tolerance`() {
        val segs = segments()
        val pass = TimestampValidator.findFailures(
            listOf(TimestampRef("[[01:35]]", 95)),
            segs,
        )
        assertTrue("95s 恰为 90s+5s，应通过", pass.isEmpty())
        val fail = TimestampValidator.findFailures(
            listOf(TimestampRef("[[01:36]]", 96)),
            segs,
        )
        assertEquals(listOf(TimestampRef("[[01:36]]", 96)), fail)
    }

    /** 下边界恰好 -5 秒通过、超出 1 秒失败。 */
    @Test
    fun `findFailures lower boundary tolerance`() {
        val segs = segments()
        val pass = TimestampValidator.findFailures(
            listOf(TimestampRef("[[00:55]]", 55)),
            segs,
        )
        assertTrue("55s 恰为 60s-5s，应通过", pass.isEmpty())
        val fail = TimestampValidator.findFailures(
            listOf(TimestampRef("[[00:54]]", 54)),
            segs,
        )
        assertEquals(listOf(TimestampRef("[[00:54]]", 54)), fail)
    }

    /** 完全无关的时间点校验失败；同一秒数去重。 */
    @Test
    fun `findFailures fails unrelated timestamps and dedupes`() {
        val failed = TimestampValidator.findFailures(
            listOf(
                TimestampRef("[[00:30]]", 30),
                TimestampRef("[[00:30]]", 30),
            ),
            segments(),
        )
        assertEquals(listOf(TimestampRef("[[00:30]]", 30)), failed)
    }

    /** 失败 pill 被替换为"（时间点待核实）"，通过的 pill 保留。 */
    @Test
    fun `stripFailures replaces failed pills with placeholder`() {
        val markdown = "见 [[01:35]] 的介绍和 [[00:05]] 的总结"
        val failures = listOf(TimestampRef("[[01:35]]", 95))
        val result = TimestampValidator.stripFailures(markdown, failures)
        assertEquals("见 （时间点待核实） 的介绍和 [[00:05]] 的总结", result)
    }

    /** 无失败时原文原样返回。 */
    @Test
    fun `stripFailures keeps markdown when no failures`() {
        val markdown = "见 [[00:05]] 的总结"
        assertEquals(markdown, TimestampValidator.stripFailures(markdown, emptyList()))
    }
}
