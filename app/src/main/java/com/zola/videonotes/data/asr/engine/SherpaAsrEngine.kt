package com.zola.videonotes.data.asr.engine

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.zola.videonotes.domain.asr.AsrModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/**
 * sherpa-onnx 端侧 ASR 引擎：按选中模型组装 OfflineRecognizer，转写 16kHz 单声道 PCM。
 *
 * - 长音频按块独立 decode，时间戳叠加块偏移，避免单次推理 OOM
 * - whisper 开启 enableTokenTimestamps 取词级时间戳；时间戳缺失时按块时长均匀分配
 * - 每次转写创建并 release recognizer，不在 DI 图中长期持有 native 资源
 * - 内部已切换到 Dispatchers.Default；decode() 为阻塞式 native 调用，禁止在主线程调用
 *
 * 注意：sherpa-onnx 的 native 库加载与推理只能在真机/模拟器上验证，JVM 单元测试无法覆盖此类。
 */
@Singleton
class SherpaAsrEngine @Inject constructor() {

    /**
     * 转写 16kHz 单声道 PCM 块列表（[PcmDecoder] 产出）。
     *
     * @param model 选中的模型（决定引擎类型与文件名）
     * @param modelDir 模型文件目录（filesDir/models/<modelId>/）
     * @param pcmChunks 16kHz 单声道 PCM 块
     * @param onProgress 总体进度 0..1
     * @return 句子级分段（时间戳为整段音频的绝对毫秒）
     */
    suspend fun transcribe(
        model: AsrModel,
        modelDir: File,
        pcmChunks: List<FloatArray>,
        onProgress: (Float) -> Unit,
    ): List<RawSegment> = withContext(Dispatchers.Default) {
        val recognizer = createRecognizer(model, modelDir)
        try {
            val out = mutableListOf<RawSegment>()
            val total = pcmChunks.size.coerceAtLeast(1)
            var consumedSamples = 0L
            pcmChunks.forEachIndexed { index, chunk ->
                if (chunk.isEmpty()) {
                    onProgress(((index + 1).toFloat() / total).coerceIn(0f, 1f))
                    return@forEachIndexed
                }
                val chunkOffsetMs = (consumedSamples * 1000.0 / SAMPLE_RATE).roundToLong()
                val chunkDurationMs = (chunk.size * 1000.0 / SAMPLE_RATE).roundToLong()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    out += SentenceSegmenter.toSegments(
                        tokens = result.tokens.toList(),
                        timestampsSec = result.timestamps,
                        durationsSec = result.durations,
                        chunkOffsetMs = chunkOffsetMs,
                        chunkDurationMs = chunkDurationMs,
                    )
                } finally {
                    stream.release()
                }
                consumedSamples += chunk.size
                onProgress(((index + 1).toFloat() / total).coerceIn(0f, 1f))
            }
            out
        } finally {
            recognizer.release()
        }
    }

    /**
     * 组装 recognizer。模型文件按后缀名查找（而非硬编码前缀），目录缺失文件时抛中文错误。
     */
    private fun createRecognizer(model: AsrModel, dir: File): OfflineRecognizer {
        if (!dir.isDirectory) throw IOException("模型目录不存在：${model.displayName}")
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        fun requiredFile(suffix: String, desc: String): String {
            val f = dir.listFiles()?.firstOrNull { it.name.endsWith(suffix) && it.length() > 0 }
                ?: throw IOException("模型文件缺失（$desc），请重新下载${model.displayName}")
            return f.absolutePath
        }

        val modelConfig = when (model.engineKind) {
            AsrModel.EngineKind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = requiredFile("-encoder.int8.onnx", "编码器"),
                    decoder = requiredFile("-decoder.int8.onnx", "解码器"),
                    language = "zh",
                    task = "transcribe",
                    enableTokenTimestamps = true,
                ),
                tokens = requiredFile("-tokens.txt", "词表"),
                numThreads = numThreads,
            )
            AsrModel.EngineKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = requiredFile("model.int8.onnx", "模型"),
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = requiredFile("tokens.txt", "词表"),
                numThreads = numThreads,
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        // 模型在应用私有目录（非 assets），AssetManager 传 null
        return OfflineRecognizer(null, config)
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
