package com.zola.videonotes.data.asr

import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.UserCatalog
import com.zola.videonotes.data.catalog.mergeUserCatalog
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

    /** 生效模型：精选池 + 用户改动（已移除的不在列；改过的以改动为准）。 */
    val models: List<AsrModel>
        get() = mergeUserCatalog(CatalogStore.asrCurated(), UserCatalog.snapshot())
            .map { it.toAsrModel() }

    /**
     * 按 id 取模型（用户改动优先 → 精选池 → 默认模型）。
     * 注意：已移除的 id 在此仍返回精选池原值（历史笔记展示名用）；下载/转写等执行链
     * 不走这里，改走 [ModelDownloadManager.resolveModel]（已移除返回 null，不回退默认）。
     */
    fun get(id: String): AsrModel =
        UserCatalog.entryFor(id)?.toAsrModel()
            ?: CatalogStore.asrById(id)?.toAsrModel() ?: default

    val default: AsrModel
        get() = models.firstOrNull { it.id == DEFAULT_MODEL_ID }
            ?: CatalogStore.asrById(DEFAULT_MODEL_ID)?.toAsrModel()
            ?: throw IllegalStateException("目录缺少默认条目 $DEFAULT_MODEL_ID")
}
