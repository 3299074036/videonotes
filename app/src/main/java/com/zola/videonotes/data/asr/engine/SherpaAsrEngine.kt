package com.zola.videonotes.data.asr.engine

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.zola.videonotes.data.work.WorkerDiagnosticLog
import com.zola.videonotes.domain.asr.AsrModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
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
class SherpaAsrEngine @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {

    /**
     * 转写 16kHz 单声道 PCM 块列表（[PcmDecoder] 产出）。
     *
     * @param model 选中的模型（决定引擎类型与文件名）
     * @param modelDir 模型文件目录（filesDir/models/<modelId>/）
     * @param pcmChunks 16kHz 单声道 PCM 块
     * @param onProgress 总体进度 0..1
     * @return 句子级分段（时间戳为整段音频的绝对毫秒）
     */
    /**
     * M-04：会话式转写。recognizer 只建一次，逐块喂 PCM、逐块出分段；
     * 调用方无需先把整段音频解成 List<FloatArray> 存在内存里。
     * 用完必须 [close]（或 `use {}`），否则 native recognizer 泄漏。
     * [processChunk] 内部切到 Dispatchers.Default（native decode 为阻塞调用）。
     */
    inner class TranscriptionSession(
        private val model: AsrModel,
        modelDir: File,
    ) : Closeable {
        private val recognizer: OfflineRecognizer = createRecognizer(model, modelDir)

        /** 已消费采样点数（16kHz），调用方可用它算进度与绝对时间。 */
        var consumedSamples = 0L
            private set

        private var chunkIndex = 0

        suspend fun processChunk(chunk: FloatArray): List<RawSegment> =
            withContext(Dispatchers.Default) {
                if (chunk.isEmpty()) return@withContext emptyList()
                val chunkOffsetMs = (consumedSamples * 1000.0 / SAMPLE_RATE).roundToLong()
                val chunkDurationMs = (chunk.size * 1000.0 / SAMPLE_RATE).roundToLong()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(chunk, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    // 2026-10-24 英文案：Zipformer-CTC 的 tokens.txt 是纯字节级 BPE
                    // 碎片（无汉字），走 tokens 路径入库即拉丁符号汤，LLM 只能用英文
                    // 分析这堆“密文”。故本分支（且仅本分支）改取 sherpa 经 bbpe.model
                    // 反解码后的 result.text；text 无逐 token 时间戳，分句时间戳走
                    // 「按块时长均匀分配」兜底。Transducer 不在此列（见组装注释）。
                    // 作用域条件必须是 engineKind，不得仅凭“带 bpe 文件”触发。
                    if (model.engineKind == AsrModel.EngineKind.ZIPFORMER_CTC) {
                        // 对照诊断（worker.log，详细日志页可见）：text 应为正常中文，
                        // tokens 应为符号汤；两者一致即机制定案，任一反常都值得复查。
                        WorkerDiagnosticLog.log(
                            appContext,
                            "asr",
                            "[对照] ${model.id} chunk=$chunkIndex " +
                                "text=${result.text.take(100)} | " +
                                "tokens=${result.tokens.take(8).joinToString(" ")}",
                        )
                        SentenceSegmenter.toSegmentsFromText(
                            text = result.text,
                            chunkOffsetMs = chunkOffsetMs,
                            chunkDurationMs = chunkDurationMs,
                        )
                    } else {
                        SentenceSegmenter.toSegments(
                            tokens = result.tokens.toList(),
                            timestampsSec = result.timestamps,
                            durationsSec = result.durations,
                            chunkOffsetMs = chunkOffsetMs,
                            chunkDurationMs = chunkDurationMs,
                        )
                    }
                } finally {
                    stream.release()
                }.also {
                    consumedSamples += chunk.size
                    chunkIndex++
                }
            }

        override fun close() {
            recognizer.release()
        }
    }

    /** 建会话（模型加载为阻塞 native 调用，内部切到 Dispatchers.Default）。 */
    suspend fun beginTranscription(model: AsrModel, modelDir: File): TranscriptionSession =
        withContext(Dispatchers.Default) { TranscriptionSession(model, modelDir) }

    /**
     * 转写 16kHz 单声道 PCM 块列表（[PcmDecoder] 产出）。
     *
     * @param model 选中的模型（决定引擎类型与文件名）
     * @param modelDir 模型文件目录（filesDir/models/<modelId>/）
     * @param pcmChunks 16kHz 单声道 PCM 块
     * @param onProgress 总体进度 0..1
     * @return 句子级分段（时间戳为整段音频的绝对毫秒）
     *
     * M-04：改走 [TranscriptionSession] 实现，逻辑与流式路径同一份；
     * 需要全量列表的调用方仍可用本函数，长音频优先走会话 + 解码流式回调。
     */
    suspend fun transcribe(
        model: AsrModel,
        modelDir: File,
        pcmChunks: List<FloatArray>,
        onProgress: (Float) -> Unit,
    ): List<RawSegment> {
        val out = mutableListOf<RawSegment>()
        val total = pcmChunks.size.coerceAtLeast(1)
        beginTranscription(model, modelDir).use { session ->
            pcmChunks.forEachIndexed { index, chunk ->
                out += session.processChunk(chunk)
                onProgress(((index + 1).toFloat() / total).coerceIn(0f, 1f))
            }
        }
        return out
    }

    /**
     * 组装 recognizer。模型文件按后缀名查找（而非硬编码前缀），目录缺失文件时抛中文错误。
     */
    private fun createRecognizer(model: AsrModel, dir: File): OfflineRecognizer {
        if (!dir.isDirectory) throw IOException("模型目录不存在：${model.displayName}")
        val numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        /**
         * 按目录条目文件名精确定位（B 档新分支用；旧分支维持后缀查找不动）。
         * 下载按条目文件名落盘，故目录名即条目名，不存在或为空时抛中文错误。
         */
        fun catalogFile(exactName: String, desc: String): String {
            val f = File(dir, exactName)
            if (f.exists() && f.length() > 0) return f.absolutePath
            throw IOException("模型文件缺失（$desc），请重新下载${model.displayName}")
        }

        /**
         * L-34：按条目文件名精确定位（替代后缀扫描：目录残留旧版同类文件时
         * listFiles 顺序未定义，firstOrNull 挑哪一个不确定）。在 model.files 里
         * 找以后缀结尾的条目名（目录顺序以条目为准），再走 catalogFile 精确取文件。
         */
        fun catalogFileBySuffix(suffix: String, desc: String): String {
            val name = model.files.firstOrNull { it.name.endsWith(suffix) }?.name
                ?: throw IOException("模型文件缺失（$desc），请重新下载${model.displayName}")
            return catalogFile(name, desc)
        }

        /** 在条目文件集中按名字关键字定位（Transducer 的 encoder/decoder/joiner 用）。 */
        fun catalogFileContaining(keyword: String, desc: String): String {
            val name = model.files.firstOrNull { it.name.contains(keyword) }?.name
                ?: throw IOException("模型文件缺失（$desc），请重新下载${model.displayName}")
            return catalogFile(name, desc)
        }

        /** BPE 词表路径（Zipformer-CTC / Transducer 的 bbpe.model；无此文件时传空串即不启用）。 */
        fun bpeVocabOrEmpty(): String =
            model.files.firstOrNull { it.name.endsWith("bpe.model") }
                ?.let { catalogFile(it.name, "BPE 词表") } ?: ""

        val modelConfig = when (model.engineKind) {
            AsrModel.EngineKind.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = catalogFileBySuffix("-encoder.int8.onnx", "编码器"),
                    decoder = catalogFileBySuffix("-decoder.int8.onnx", "解码器"),
                    language = "zh",
                    task = "transcribe",
                    enableTokenTimestamps = true,
                ),
                tokens = catalogFileBySuffix("-tokens.txt", "词表"),
                numThreads = numThreads,
            )
            AsrModel.EngineKind.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = catalogFileBySuffix("model.int8.onnx", "模型"),
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = catalogFileBySuffix("tokens.txt", "词表"),
                numThreads = numThreads,
            )
            // WP7：Paraformer 组装只需 model 路径（AAR 类字段实证：仅 model + qnnConfig），
            // tokens 走 OfflineModelConfig.tokens，与现有两支同构。
            // 时间戳行为待真机验证：Paraformer 结果若无 token 级时间戳，
            // SentenceSegmenter 既有「按块时长均匀分配」兜底自动生效。
            AsrModel.EngineKind.PARAFORMER -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = catalogFileBySuffix("model.int8.onnx", "模型"),
                ),
                tokens = catalogFileBySuffix("tokens.txt", "词表"),
                numThreads = numThreads,
            )
            // B 档新增：Zipformer-CTC（k2-fsa WenetSpeech 系）。单 model + tokens，
            // bbpe.model 随条目下载并传 bpeVocab（bbpe 子词解码用）。
            // 2026-10-24 英文案：本分支文本改取 result.text（见 transcribe 内注释），
            // 不再用 tokens 拼文本；bpeVocab 仍是 text 解码所必需，不可去掉。
            // 时间戳落点待真机验证（现为均匀分配兜底）。
            AsrModel.EngineKind.ZIPFORMER_CTC -> OfflineModelConfig(
                zipformerCtc = OfflineZipformerCtcModelConfig(
                    model = catalogFileContaining(".onnx", "模型"),
                ),
                tokens = catalogFile("tokens.txt", "词表"),
                bpeVocab = bpeVocabOrEmpty(),
                numThreads = numThreads,
            )
            // B 档新增：Zipformer Transducer（multi-zh-hans 配方）。官方 int8 组合是
            // encoder/joiner int8 + decoder fp32 混搭，目录层按文件名/大小/哈希逐文件表达，
            // 组装只取路径、不区分精度；tokens + bpeVocab 同上；原生 token 级时间戳。
            // 作用域注意：Transducer 虽有 bpe.model，但其 tokens 含中文词片、可读，
            // 故有意保持 tokens 路径——切到 text 路径会丢原生 token 时间戳（降级）。
            // text 路径的触发条件只认 engineKind == ZIPFORMER_CTC，不得扩到本分支。
            // 时间戳落点待真机验证。
            AsrModel.EngineKind.TRANSDUCER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = catalogFileContaining("encoder", "编码器"),
                    decoder = catalogFileContaining("decoder", "解码器"),
                    joiner = catalogFileContaining("joiner", "连接器"),
                ),
                tokens = catalogFile("tokens.txt", "词表"),
                bpeVocab = bpeVocabOrEmpty(),
                numThreads = numThreads,
            )
            // B 档新增：Dolphin CTC（Dataocean × 清华）。单 model 字段最简；
            // 文本后处理：输出中的语言/方言标签 token（<...> 形）由 SentenceSegmenter
            // 的标签跳过规则清洗；CTC 时间戳通路按框架实证，落点待真机验证。
            AsrModel.EngineKind.DOLPHIN -> OfflineModelConfig(
                dolphin = OfflineDolphinModelConfig(
                    model = catalogFileContaining(".onnx", "模型"),
                ),
                tokens = catalogFile("tokens.txt", "词表"),
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
