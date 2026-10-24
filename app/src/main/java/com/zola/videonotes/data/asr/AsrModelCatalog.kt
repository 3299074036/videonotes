package com.zola.videonotes.data.asr

import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.toAsrModel
import com.zola.videonotes.domain.asr.AsrModel

/**
 * 端侧 ASR 模型目录（一期 WP1 起改为目录仓库门面）。
 *
 * 条目数据已数据化到 assets/model_catalog.json（经 [CatalogStore] 解析，
 * 解析失败回退代码内种子），本对象保留旧 API 形态。逐文件大小与 sha256
 * 均为 HF 官方真值（见 implementation-plan-phase1 定案表），不再是估值。
 *
 * 模型来源与许可证：
 * - 运行时 sherpa-onnx AAR：Apache-2.0（k2-fsa/sherpa-onnx）
 * - whisper 模型权重：MIT（OpenAI Whisper）；int8 量化版由 csukuangfj 发布
 * - SenseVoice / Paraformer 模型权重：遵循其原始许可证
 */
object AsrModelCatalog {

    /** 默认模型：最快，适合大多数场景先行体验。 */
    const val DEFAULT_MODEL_ID = "whisper-tiny-int8"

    val models: List<AsrModel>
        get() = CatalogStore.asrCurated().map { it.toAsrModel() }

    /** 按 id 取模型，未知 id 时回退默认模型。 */
    fun get(id: String): AsrModel =
        CatalogStore.asrById(id)?.toAsrModel() ?: default

    val default: AsrModel
        get() = models.first { it.id == DEFAULT_MODEL_ID }
}
