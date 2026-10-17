package com.zola.videonotes.data.asr.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** LinearResampler 流式重采样测试（纯 Kotlin，无需设备）。 */
class LinearResamplerTest {

    /** 44100→16000：1 秒 440Hz 正弦波，输出长度 ≈ 16000（±2 容差）。 */
    @Test
    fun `44100 to 16000 produces expected length`() {
        val srcRate = 44100
        val src = FloatArray(srcRate) { i -> sin(2 * PI * 440 * i / srcRate).toFloat() }
        val dst = LinearResampler(srcRate).process(src)
        assertTrue("size=${dst.size}", abs(dst.size - 16000) <= 2)
    }

    /** 48000→16000：常数信号经线性插值后仍为常数。 */
    @Test
    fun `constant signal stays constant`() {
        val srcRate = 48000
        val src = FloatArray(srcRate) { 0.6f }
        val dst = LinearResampler(srcRate).process(src)
        assertTrue("size=${dst.size}", abs(dst.size - 16000) <= 2)
        for (v in dst) {
            assertEquals(0.6f, v, 0.001f)
        }
    }

    /** 单调递增的斜坡信号，重采样后仍单调递增（线性插值保单调）。 */
    @Test
    fun `monotonic ramp stays monotonic`() {
        val srcRate = 44100
        val src = FloatArray(srcRate) { i -> i.toFloat() / srcRate }
        val dst = LinearResampler(srcRate).process(src)
        for (i in 1 until dst.size) {
            assertTrue("dst[$i]=${dst[i]} < dst[${i - 1}]=${dst[i - 1]}", dst[i] >= dst[i - 1])
        }
    }

    /** 块边界连续：两块连续 process 的拼接 ≈ 一次完整 process（允许末尾少量差异）。 */
    @Test
    fun `chunked processing is continuous at boundaries`() {
        val srcRate = 44100
        val src = FloatArray(srcRate * 2) { i -> sin(2 * PI * 440 * i / srcRate).toFloat() }
        val whole = LinearResampler(srcRate).process(src)

        val chunked = LinearResampler(srcRate)
        val half = srcRate
        val part1 = chunked.process(src.copyOfRange(0, half))
        val part2 = chunked.process(src.copyOfRange(half, src.size))
        val stitched = part1 + part2

        // 分块在边界处少/多至多 1 个插值样本，比较公共前缀
        val n = minOf(whole.size, stitched.size) - 2
        for (i in 0 until n) {
            assertTrue(
                "i=$i whole=${whole[i]} stitched=${stitched[i]}",
                abs(whole[i] - stitched[i]) < 0.05f,
            )
        }
    }

    /** 空输入返回空数组。 */
    @Test
    fun `empty input returns empty array`() {
        val dst = LinearResampler(44100).process(FloatArray(0))
        assertEquals(0, dst.size)
    }
}
