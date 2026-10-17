package com.zola.videonotes.data.asr

import com.zola.videonotes.domain.asr.AsrModel
import com.zola.videonotes.domain.asr.AsrModelFile

private const val HF = "https://huggingface.co"

/**
 * 端侧 ASR 模型目录（硬编码）。
 *
 * 模型来源与许可证：
 * - 运行时 sherpa-onnx AAR：Apache-2.0（k2-fsa/sherpa-onnx）
 * - whisper 模型权重：MIT（OpenAI Whisper）；int8 量化版由 csukuangfj 发布
 * - SenseVoice 模型权重：遵循其原始许可证（FunAudioLLM/SenseVoice）
 *
 * 三个模型均为 HuggingFace 直链，App 内按需下载到应用私有目录，随卸载清除。
 */
object AsrModelCatalog {

    /** 默认模型：最快，适合大多数场景先行体验。 */
    const val DEFAULT_MODEL_ID = "whisper-tiny-int8"

    val models: List<AsrModel> = listOf(
        AsrModel(
            id = "whisper-tiny-int8",
            displayName = "Whisper Tiny（int8）",
            description = "体积最小、速度最快；中文日常内容可用，专业术语较多时建议换 SenseVoice",
            totalBytes = 103L * 1024 * 1024,
            files = listOf(
                AsrModelFile(
                    name = "tiny-encoder.int8.onnx",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-encoder.int8.onnx",
                    sizeBytes = (12.9 * 1024 * 1024).toLong(),
                ),
                AsrModelFile(
                    name = "tiny-decoder.int8.onnx",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-decoder.int8.onnx",
                    sizeBytes = (89.9 * 1024 * 1024).toLong(),
                ),
                AsrModelFile(
                    name = "tiny-tokens.txt",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/tiny-tokens.txt",
                    sizeBytes = 1_000_000,
                ),
            ),
            engineKind = AsrModel.EngineKind.WHISPER,
        ),
        AsrModel(
            id = "sensevoice-int8",
            displayName = "SenseVoice（int8）",
            description = "中文增强：中文网课、技术术语识别明显更准；速度介于 tiny 与 small 之间",
            totalBytes = 240L * 1024 * 1024,
            files = listOf(
                AsrModelFile(
                    name = "model.int8.onnx",
                    url = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx",
                    sizeBytes = 239L * 1024 * 1024,
                ),
                AsrModelFile(
                    name = "tokens.txt",
                    url = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt",
                    sizeBytes = 1_000_000,
                ),
            ),
            engineKind = AsrModel.EngineKind.SENSE_VOICE,
        ),
        AsrModel(
            id = "whisper-small-int8",
            displayName = "Whisper Small（int8）",
            description = "精度最高，体积最大、速度最慢；长视频或对准确率要求高时选用",
            totalBytes = 375L * 1024 * 1024,
            files = listOf(
                AsrModelFile(
                    name = "small-encoder.int8.onnx",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-encoder.int8.onnx",
                    sizeBytes = 112L * 1024 * 1024,
                ),
                AsrModelFile(
                    name = "small-decoder.int8.onnx",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-decoder.int8.onnx",
                    sizeBytes = 262L * 1024 * 1024,
                ),
                AsrModelFile(
                    name = "small-tokens.txt",
                    url = "$HF/csukuangfj/sherpa-onnx-whisper-small/resolve/main/small-tokens.txt",
                    sizeBytes = 1_000_000,
                ),
            ),
            engineKind = AsrModel.EngineKind.WHISPER,
        ),
    )

    /** 按 id 取模型，未知 id 时回退默认模型。 */
    fun get(id: String): AsrModel = models.firstOrNull { it.id == id } ?: default

    val default: AsrModel get() = models.first { it.id == DEFAULT_MODEL_ID }
}
