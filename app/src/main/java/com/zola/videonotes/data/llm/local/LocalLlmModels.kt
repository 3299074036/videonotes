package com.zola.videonotes.data.llm.local

/**
 * 本地 LLM 模型目录（M7a）。
 *
 * 模型为 GGUF Q4_K_M，从 HuggingFace 直链下载到应用私有目录
 *（`filesDir/llm_models/`，随卸载清除），与 ASR 模型管理同模式。
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
}

data class LocalLlmModel(
    /** 稳定 ID，如 "qwen3-4b"。 */
    val id: String,
    /** 展示名，如 "Qwen3 4B（推荐）"。 */
    val displayName: String,
    /** GGUF 文件名。 */
    val fileName: String,
    /** HuggingFace 直链。 */
    val url: String,
    /** 文件字节数（用于进度展示）。 */
    val sizeBytes: Long,
    /** SHA-256（下载完校验，可空表示暂不校验）。 */
    val sha256: String? = null,
    /** 适用说明。 */
    val description: String,
    /** prompt 模板（默认 Qwen3）。 */
    val promptTemplate: PromptTemplate = PromptTemplate.QWEN3,
)

object LocalLlmModels {

    val QWEN3_4B = LocalLlmModel(
        id = "qwen3-4b",
        displayName = "Qwen3 4B（推荐）",
        fileName = "Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
        sizeBytes = 2_497_281_120L,
        description = "质量最好，生成一篇笔记约 3–6 分钟，需要约 4GB 可用内存",
    )

    val QWEN3_1_7B = LocalLlmModel(
        id = "qwen3-1_7b",
        displayName = "Qwen3 1.7B（更快）",
        fileName = "Qwen3-1.7B-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
        sizeBytes = 1_100_000_000L,
        description = "速度快约一倍，复杂长文稿质量略低，需要约 2GB 可用内存",
    )

    /**
     * 方案 B 诊断对照模型：Llama 3.2 1B Instruct（Q4_K_M）。
     * 用于区分 prefill hang 是 Qwen 特定问题还是通用问题。
     * 注意：Llama 3 家族用自家的 chat template（见 [PromptTemplate.LLAMA3]）。
     */
    val LLAMA3_2_1B = LocalLlmModel(
        id = "llama3_2-1b",
        displayName = "Llama 3.2 1B（诊断对照）",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        sizeBytes = 807_694_368L,
        description = "诊断对照用：排查 prefill hang 是否为 Qwen 特定问题。体积小、速度快，不适合正式笔记生成",
        promptTemplate = PromptTemplate.LLAMA3,
    )

    val ALL = listOf(QWEN3_4B, QWEN3_1_7B, LLAMA3_2_1B)

    fun byId(id: String): LocalLlmModel? = ALL.find { it.id == id }
}
