package com.zola.videonotes.data.llm.local

import com.zola.videonotes.data.catalog.CatalogKind
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.CatalogStore
import com.zola.videonotes.data.catalog.toLocalLlmModel

/**
 * 本地 LLM 模型目录（M7a；一期 WP1 起改为目录仓库门面）。
 *
 * 条目数据已数据化到 assets/model_catalog.json（经 [CatalogStore] 解析，
 * 解析失败回退代码内种子），本对象保留旧 API 形态供引擎/下载/设置各层调用，
 * 数据不再写死在此。模型文件存 `filesDir/llm_models/<id>/<fileName>`（按 id 分目录，WP5），
 * 随卸载清除。
 *
 * 选择 Qwen3 的原因：中文与指令遵循在同体积下最强；
 * Q4_K_M 是速度/质量平衡点（社区实测推荐）。
 */
/** prompt 拼装模板：不同模型家族的 chat template 不同。 */
enum class PromptTemplate {
    /** Qwen3：`<|im_start|>role\n…<|im_end|>`。 */
    QWEN3,

    /** Llama 3：`<|start_header_id|>role<|end_header_id|>` + `<|eot_id|>`。 */
    LLAMA3,

    /** GGUF 内嵌模板（动态条目）：经 llama_chat_apply_template 由运行时拼装（WP2）。 */
    EMBEDDED,

    /** llama.cpp 预定义命名模板（hunyuan-dense / glmedge）：内嵌 Jinja 不受支持时走此路。 */
    NAMED,
}

data class LocalLlmModel(
    /** 稳定 ID，如 "qwen3-4b"；动态条目为 hf-- 合成 id。 */
    val id: String,
    /** 展示名，如 "Qwen3 4B"。 */
    val displayName: String,
    /** GGUF 文件名。 */
    val fileName: String,
    /** 下载直链（精选条目逐条锁定仓库+文件+sha256；国内名单以 ModelScope 为主）。 */
    val url: String,
    /** 文件字节数（用于进度展示）。 */
    val sizeBytes: Long,
    /** SHA-256（下载完校验，可空表示暂不校验）。 */
    val sha256: String? = null,
    /** 适用说明。 */
    val description: String,
    /** prompt 模板（默认 Qwen3）。 */
    val promptTemplate: PromptTemplate = PromptTemplate.QWEN3,
    /** 条目来源（精选/影子/动态）。 */
    val source: CatalogSource = CatalogSource.CURATED,
    /** 动态条目的来源仓库 "owner/repo"。 */
    val hfRepo: String? = null,
    /** 预定义命名模板名（promptTemplate == NAMED 时），如 hunyuan-dense / glmedge。 */
    val chatTemplateName: String? = null,
    /** 是否须在生成前过模板探针门（新收录的内嵌/命名模板精选条目）。 */
    val requiresProbe: Boolean = false,
)

object LocalLlmModels {

    const val DEFAULT_ID = "qwen3-4b"

    val QWEN3_4B: LocalLlmModel
        get() = requireNotNull(byId(DEFAULT_ID)) { "目录缺少默认条目 qwen3-4b" }

    val QWEN3_1_7B: LocalLlmModel?
        get() = byId("qwen3-1_7b")

    val LLAMA3_2_1B: LocalLlmModel?
        get() = byId("llama3_2-1b")

    /** 正式条目 + 影子条目（解析域全集，不含动态条目）。 */
    val ALL: List<LocalLlmModel>
        get() = CatalogStore.all()
            .filter { it.kind == CatalogKind.LLM }
            .map { it.toLocalLlmModel() }

    /** 正式条目（列表层可见、可下载）。 */
    val CURATED: List<LocalLlmModel>
        get() = CatalogStore.llmCurated().map { it.toLocalLlmModel() }

    fun byId(id: String): LocalLlmModel? = CatalogStore.llmById(id)?.toLocalLlmModel()
}
