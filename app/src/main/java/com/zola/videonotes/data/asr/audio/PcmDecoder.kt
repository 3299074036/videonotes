package com.zola.videonotes.data.asr.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * 音频解码器：MediaExtractor + MediaCodec 把音频文件解码为 16kHz 单声道 PCM。
 *
 * - 按 [chunkSeconds] 秒一块切分输出，避免长音频一次性占用数百 MB 内存
 * - 立体声取声道平均；[LinearResampler] 处理 44100/48000 等常见源采样率
 * - 内部已切换到 Dispatchers.Default，调用方无需切线程
 */
@Singleton
class PcmDecoder @Inject constructor() {

    /**
     * 解码为 16kHz 单声道 FloatArray 块列表（每块 [chunkSeconds] 秒，不足一块的尾部单独成块）。
     * 采样值范围 -1..1。
     */
    suspend fun decodeToChunks(file: File, chunkSeconds: Int = 60): List<FloatArray> =
        withContext(Dispatchers.Default) {
            require(file.exists() && file.length() > 0) { "音频文件不存在或为空" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                    extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IOException("未找到音频轨道")
                extractor.selectTrack(trackIndex)
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IOException("无法识别音频格式")
                val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val srcChannels = runCatching {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }.getOrDefault(1)
                decodeWithCodec(extractor, mime, format, srcRate, srcChannels, chunkSeconds)
            } finally {
                extractor.release()
            }
        }

    private fun decodeWithCodec(
        extractor: MediaExtractor,
        mime: String,
        format: MediaFormat,
        srcRate: Int,
        srcChannels: Int,
        chunkSeconds: Int,
    ): List<FloatArray> {
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            return drain(codec, extractor, srcChannels, srcRate, chunkSeconds)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
                // stop 可能抛 IllegalStateException，release 仍要执行
            }
            codec.release()
        }
    }

    private fun drain(
        codec: MediaCodec,
        extractor: MediaExtractor,
        srcChannels: Int,
        srcRate: Int,
        chunkSeconds: Int,
    ): List<FloatArray> {
        val resampler = LinearResampler(srcRate)
        val chunkSize = LinearResampler.TARGET_SAMPLE_RATE * chunkSeconds
        val chunks = mutableListOf<FloatArray>()
        var cur = FloatArray(chunkSize)
        var curPos = 0

        fun emit(samples: FloatArray) {
            var off = 0
            while (off < samples.size) {
                val n = min(chunkSize - curPos, samples.size - off)
                samples.copyInto(cur, curPos, off, off + n)
                curPos += n
                off += n
                if (curPos == chunkSize) {
                    chunks.add(cur)
                    cur = FloatArray(chunkSize)
                    curPos = 0
                }
            }
        }

        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var outputEos = false
        while (!outputEos) {
            if (!inputEos) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        val n = extractor.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
            }
            when (val outIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // 暂无输出，继续轮询
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 解码器可能中途修正格式；罕见，保持按初始声道数混音
                }
                else -> if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        emit(resampler.process(pcm16ToMonoFloat(outBuf, info, srcChannels)))
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputEos = true
                    }
                }
            }
        }
        if (curPos > 0) {
            chunks.add(cur.copyOf(curPos))
        }
        return chunks
    }

    /**
     * 把一块 MediaCodec 输出的 PCM16 转为单声道 float（-1..1）。
     * 多声道取平均；不足一帧的尾部字节丢弃。
     */
    private fun pcm16ToMonoFloat(
        outBuf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
    ): FloatArray {
        outBuf.position(info.offset)
        outBuf.limit(info.offset + info.size)
        val shortBuf = outBuf.asShortBuffer()
        val frames = shortBuf.remaining() / channels
        val mono = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            val base = f * channels
            for (c in 0 until channels) {
                sum += shortBuf.get(base + c) / 32768f
            }
            mono[f] = sum / channels
        }
        return mono
    }
}
