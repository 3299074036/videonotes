package com.zola.videonotes.data.catalog

import com.zola.videonotes.data.llm.local.PromptTemplate

/** 目录条目类型。 */
enum class CatalogKind { LLM, ASR }

/** 条目来源：精选（我方清单）/ 影子（内置但不提供下载，如 llama 诊断条目）/ 动态（HF 检索下载）。 */
enum class CatalogSource { CURATED, SHADOW, DYNAMIC }

/** 动态条目下载后的模板探针结论（WP2）。口径见 implementation-plan-phase1 §3.3.4。 */
enum class TemplateProbeState {
    /** 内嵌模板存在且 llama_chat_apply_template 试拼通过。 */
    OK,

    /** GGUF 不含内嵌对话模板。 */
    NO_TEMPLATE,

    /** 有模板但 v0.7.0 的 apply 不支持（不解析 Jinja、只认预定义模板集）。 */
    UNSUPPORTED,

    /** 模型文件本身加载失败（架构/量化不受支持等）。 */
    LOAD_FAILED,
}

/** 条目组成文件（LLM 恒为单文件；ASR 为多文件）。sha256 为 null 表示无官方哈希（动态非 LFS 文件）。 */
data class CatalogFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null,
)

/**
 * 统一目录条目：精选层来自 assets/model_catalog.json，动态层由 HF 文件层即时合成，
 * 两者同构，共用同一条「下载 → sha 强校验 → 按 id 存储 → 选用」管线。
 */
data class CatalogEntry(
    val id: String,
    val kind: CatalogKind,
    val displayName: String,
    val description: String,
    /** 中文主名对应的英文原名（副行展示）；null 则不展示副行英文名。 */
    val originalName: String? = null,
    /** 用途标签：zh=中文 / en=英文 / dialect=方言 / light=轻量（用途筛选 chips 用）。 */
    val useCases: List<String> = emptyList(),
    /** 模板标识：gguf-qwen3-chat / gguf-llama3-chat / gguf-embedded / sherpa-whisper / sherpa-sensevoice / sherpa-paraformer / sherpa-zipformer-ctc / sherpa-transducer / sherpa-dolphin。 */
    val templateId: String,
    val files: List<CatalogFile>,
    val recommended: Boolean = false,
    /** 小模型简化 prompt（精选 LLM 用；动态恒 false，见方案 D12）。 */
    val smallModelPrompt: Boolean = false,
    /** 下载量：一期不填充不展示，字段预留（§14 Q2）。 */
    val downloadCount: Long? = null,
    /** 所需整机内存下限（GB，总内存口径）；动态条目按经验式估算（C6）。 */
    val minRamGB: Double? = null,
    /** 本地 LLM 推理线程数覆盖：null = 自动（按模型文件大小分档）；仅 LLM 条目有效，ASR 不走此项。 */
    val threadsOverride: Int? = null,
    val source: CatalogSource = CatalogSource.CURATED,
    /** 动态条目来源仓库 "owner/repo"；精选条目由首文件 URL 推导（C10 去重用）。 */
    val hfRepo: String? = null,
    /** 来源档位：official=模型方官方发布 / third-party=第三方量化转换；null 不标注（存量条目）。 */
    val origin: String? = null,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    /** LLM 条目的 prompt 模板（由 templateId 映射）。 */
    val promptTemplate: PromptTemplate
        get() = when {
            templateId == "gguf-llama3-chat" -> PromptTemplate.LLAMA3
            templateId == "gguf-embedded" -> PromptTemplate.EMBEDDED
            templateId.startsWith("gguf-named-") -> PromptTemplate.NAMED
            else -> PromptTemplate.QWEN3
        }

    /** 预定义命名模板名（templateId 为 gguf-named-<name> 时，如 hunyuan-dense / glmedge）。 */
    val namedTemplate: String?
        get() = if (templateId.startsWith("gguf-named-")) {
            templateId.removePrefix("gguf-named-")
        } else {
            null
        }

    /**
     * 是否须在生成前过模板探针门：新收录的内嵌/命名模板精选条目。
     * 旧精选条目（手拼 QWEN3/LLAMA3）与影子条目不设门，行为不变；
     * 动态条目的探针门走注册表通路，不在此列。
     */
    val requiresProbe: Boolean
        get() = source == CatalogSource.CURATED &&
            (templateId == "gguf-embedded" || templateId.startsWith("gguf-named-"))
}
