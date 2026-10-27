package com.zola.videonotes.data.catalog

/**
 * 模型表单校验（纯函数，无 Android 依赖，可单测）。
 *
 * 口径与设计稿一致：只在表单内报错，不动列表。
 * - 名称、标识、模板、至少一个文件必填；LLM 恒为单文件。
 * - 标识：小写字母、数字、下划线或连字符；新增时不可与生效列表内 id 重复（编辑时标识不可改）。
 * - 文件：文件名、链接、大小必填；链接须为 https（本机回环地址的 http 可放行，
 *   M-06）；文件名不得含 /、\ 或 ..（M-07，防目录穿越）；大小须大于 0；
 *   SHA-256 必填且须为 64 位十六进制（M-10：无哈希的模型首下载即成信任锚）。
 * - 识别模型按模板校验必需文件是否齐全（只认核心集：bpe/bbpe 模型文件不阻塞，
 *   缺了运行时报错时用户可再编辑补上）。
 */

data class EntryFileDraft(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
    val sha256: String,
)

data class EntryDraft(
    val displayName: String,
    val id: String,
    val originalName: String,
    val kind: CatalogKind,
    val templateId: String,
    val useCases: List<String>,
    val minRamGB: Double?,
    val description: String,
    val files: List<EntryFileDraft>,
    /** 本地 LLM 推理线程数覆盖：null = 自动分档；仅 LLM 条目有效。 */
    val threadsOverride: Int? = null,
)

data class ValidationError(
    val field: String,
    val message: String,
)

val LLM_TEMPLATES: List<String> = listOf(
    "gguf-qwen3-chat",
    "gguf-llama3-chat",
    "gguf-embedded",
    "gguf-named-hunyuan-dense",
    "gguf-named-glmedge",
)

val ASR_TEMPLATES: List<String> = listOf(
    "sherpa-whisper",
    "sherpa-sensevoice",
    "sherpa-paraformer",
    "sherpa-zipformer-ctc",
    "sherpa-transducer",
    "sherpa-dolphin",
)

/** 模板中文名（表单选择器用）。 */
fun templateLabel(templateId: String): String = when (templateId) {
    "gguf-qwen3-chat" -> "Qwen3 对话模板"
    "gguf-llama3-chat" -> "Llama3 对话模板"
    "gguf-embedded" -> "内嵌模板"
    "gguf-named-hunyuan-dense" -> "命名模板 · 混元"
    "gguf-named-glmedge" -> "命名模板 · GLM"
    "sherpa-whisper" -> "Whisper"
    "sherpa-sensevoice" -> "SenseVoice"
    "sherpa-paraformer" -> "Paraformer"
    "sherpa-zipformer-ctc" -> "Zipformer-CTC"
    "sherpa-transducer" -> "Transducer"
    "sherpa-dolphin" -> "Dolphin"
    else -> templateId
}

private val ID_PATTERN = Regex("^[a-z0-9_-]+$")
private val SHA_PATTERN = Regex("^[0-9a-fA-F]{64}$")

/** 回环地址判定（M-06/M-08 共用）：localhost / 127.x / ::1 视为本机源，http 可放行。 */
internal fun isLoopbackUrl(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
    return host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")
}

fun validateEntryDraft(
    draft: EntryDraft,
    existingIds: Set<String>,
    isNew: Boolean,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    if (draft.displayName.isBlank()) {
        errors += ValidationError("displayName", "名称不能为空")
    }
    if (draft.id.isBlank()) {
        errors += ValidationError("id", "标识不能为空")
    } else if (!ID_PATTERN.matches(draft.id)) {
        errors += ValidationError("id", "标识只允许小写字母、数字、下划线或连字符")
    } else if (isNew && draft.id in existingIds) {
        errors += ValidationError("id", "标识已存在，请换一个")
    }
    val allowedTemplates = if (draft.kind == CatalogKind.LLM) LLM_TEMPLATES else ASR_TEMPLATES
    if (draft.templateId !in allowedTemplates) {
        errors += ValidationError("templateId", "请选择模板")
    }
    if (draft.minRamGB != null && draft.minRamGB <= 0.0) {
        errors += ValidationError("minRamGB", "最低内存须大于 0")
    }
    if (draft.kind == CatalogKind.LLM && draft.threadsOverride != null &&
        draft.threadsOverride !in 1..16
    ) {
        errors += ValidationError("threads", "线程数须为 1–16 的整数")
    }
    if (draft.files.isEmpty()) {
        errors += ValidationError("files", "至少添加一个文件")
    } else {
        if (draft.kind == CatalogKind.LLM && draft.files.size != 1) {
            errors += ValidationError("files", "语言大模型只能有一个文件")
        }
        draft.files.forEachIndexed { index, file ->
            val prefix = "file_$index"
            if (file.name.isBlank()) {
                errors += ValidationError("${prefix}_name", "文件名不能为空")
            } else if (file.name.contains('/') || file.name.contains('\\') || file.name.contains("..")) {
                // M-07：文件名含分隔符或 .. 可穿越到模型目录外（写任意路径 / 覆盖他人文件）。
                errors += ValidationError("${prefix}_name", "文件名不能含 /、\\ 或 ..")
            }
            if (file.url.isBlank()) {
                errors += ValidationError("${prefix}_url", "下载链接不能为空")
            } else if (!file.url.startsWith("http://") && !file.url.startsWith("https://")) {
                errors += ValidationError("${prefix}_url", "下载链接须以 http:// 或 https:// 开头")
            } else if (file.url.startsWith("http://") && !isLoopbackUrl(file.url)) {
                // M-06：http 明文下载可被中间人替换文件；本机回环地址（本地自建源）除外。
                errors += ValidationError("${prefix}_url", "下载链接须为 https（http 明文可被替换文件；本机回环地址除外）")
            }
            if (file.sizeBytes == null || file.sizeBytes <= 0L) {
                errors += ValidationError("${prefix}_size", "大小须为大于 0 的字节数")
            }
            if (file.sha256.isBlank()) {
                // M-10：无哈希的模型首下载即成信任锚，损坏文件可直达 native；建条目时必须提供。
                errors += ValidationError("${prefix}_sha", "请填写 SHA-256（无哈希的模型损坏后可直达 native 崩溃）")
            } else if (!SHA_PATTERN.matches(file.sha256.trim())) {
                errors += ValidationError("${prefix}_sha", "SHA-256 须为 64 位十六进制")
            }
        }
        if (draft.kind == CatalogKind.ASR) {
            checkTemplateFiles(draft.templateId, draft.files)?.let {
                errors += ValidationError("files", it)
            }
        }
    }
    return errors
}

/**
 * 识别模型模板核心文件集校验（文件名按真实条目口径匹配）：
 * whisper 须有 encoder、decoder、tokens；transducer 须有 encoder、decoder、joiner、tokens；
 * 其余须有 1 个 .onnx 模型文件 + tokens 文件。
 */
fun checkTemplateFiles(templateId: String, files: List<EntryFileDraft>): String? {
    val names = files.map { it.name.lowercase() }
    fun has(sub: String) = names.any { it.contains(sub) }
    return when (templateId) {
        "sherpa-whisper" -> when {
            !has("encoder") -> "Whisper 模板须有 encoder 文件"
            !has("decoder") -> "Whisper 模板须有 decoder 文件"
            !has("tokens") -> "Whisper 模板须有 tokens 文件"
            else -> null
        }
        "sherpa-transducer" -> when {
            !has("encoder") -> "Transducer 模板须有 encoder 文件"
            !has("decoder") -> "Transducer 模板须有 decoder 文件"
            !has("joiner") -> "Transducer 模板须有 joiner 文件"
            !has("tokens") -> "Transducer 模板须有 tokens 文件"
            else -> null
        }
        "sherpa-sensevoice", "sherpa-paraformer", "sherpa-zipformer-ctc", "sherpa-dolphin" -> when {
            names.none { it.endsWith(".onnx") } -> "该模板须有 1 个 .onnx 模型文件"
            !has("tokens") -> "该模板须有 tokens 文件"
            else -> null
        }
        else -> null
    }
}

/** 新旧文件清单是否变化（编辑时：只改名称/描述/用途/内存不碰下载态，改了文件则清掉已下载）。 */
fun filesChanged(old: CatalogEntry, new: CatalogEntry): Boolean = old.files != new.files

/** 草稿转条目（保存时用；推荐位与来源由调用方按新增/编辑规则定）。 */
fun EntryDraft.toEntry(recommended: Boolean, source: CatalogSource): CatalogEntry =
    CatalogEntry(
        id = id.trim(),
        kind = kind,
        displayName = displayName.trim(),
        description = description.trim(),
        originalName = originalName.trim().takeIf { it.isNotBlank() },
        useCases = useCases,
        templateId = templateId,
        files = files.map {
            CatalogFile(
                name = it.name.trim(),
                url = it.url.trim(),
                sizeBytes = it.sizeBytes ?: 0L,
                sha256 = it.sha256.trim().takeIf { s -> s.isNotBlank() },
            )
        },
        recommended = recommended,
        minRamGB = minRamGB,
        threadsOverride = threadsOverride,
        source = source,
    )
