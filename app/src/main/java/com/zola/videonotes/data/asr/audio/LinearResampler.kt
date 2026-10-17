package com.zola.videonotes.data.asr.audio

/**
 * 流式线性重采样器：任意源采样率的单声道 PCM → 16kHz 单声道。
 *
 * 用法：对解码出的每一块源采样率 PCM 依次调用 [process]，把返回的 FloatArray
 * 按顺序拼接即得到完整的 16kHz PCM。块边界通过保留上块最后一个采样点保证插值连续。
 *
 * 纯 Kotlin，可在 JVM 单元测试（含块边界连续性校验）。
 */
class LinearResampler(
    private val srcRate: Int,
    private val dstRate: Int = TARGET_SAMPLE_RATE,
) {
    init {
        require(srcRate > 0 && dstRate > 0) { "采样率必须为正数" }
    }

    /** 上一块的最后一个采样（跨块插值用），null 表示尚未处理过任何数据。 */
    private var lastSample: Float? = null

    /**
     * 下一个输出采样在"以 [lastSample] 为原点"的坐标系中的源位置（可为小数）。
     * 初始为 0；每处理完一块换算到新坐标系。
     */
    private var nextPos = 0.0

    /**
     * 处理一块源采样率的单声道 PCM，返回对应的 16kHz 采样。
     * 空输入返回空数组（状态不变）。
     */
    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val ratio = srcRate.toDouble() / dstRate
        val base = if (lastSample != null) 1 else 0
        val total = base + input.size
        val out = ArrayList<Float>((input.size * dstRate.toDouble() / srcRate + 2).toInt())
        var pos = nextPos
        while (true) {
            val i = pos.toInt()
            if (i + 1 >= total) break
            val frac = (pos - i).toFloat()
            val s0 = sampleAt(i, input, base)
            val s1 = sampleAt(i + 1, input, base)
            out.add(s0 + (s1 - s0) * frac)
            pos += ratio
        }
        // 状态换算到"以本块最后一个采样为原点"的新坐标系
        nextPos = pos - (total - 1)
        lastSample = input[input.size - 1]
        return out.toFloatArray()
    }

    private fun sampleAt(i: Int, input: FloatArray, base: Int): Float =
        if (i < base) lastSample!! else input[i - base]

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
    }
}
