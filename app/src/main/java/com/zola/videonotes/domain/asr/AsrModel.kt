package com.zola.videonotes.domain.asr

/**
 * 端侧 ASR 模型描述。
 *
 * @param id 唯一标识，同时作为 filesDir/models/ 下的目录名
 * @param displayName 展示名
 * @param description 一句话说明（面向用户）
 * @param totalBytes 预估总大小（字节，仅用于展示；下载完整性以实际文件为准）
 * @param files 组成文件列表
 * @param engineKind 推理引擎类型，决定 SherpaAsrEngine 如何组装 recognizer
 */
data class AsrModel(
    val id: String,
    val displayName: String,
    val description: String,
    val totalBytes: Long,
    val files: List<AsrModelFile>,
    val engineKind: EngineKind,
) {
    enum class EngineKind {
        /** whisper 系列：encoder + decoder + tokens */
        WHISPER,

        /** SenseVoice：单个 model.int8.onnx + tokens */
        SENSE_VOICE,
    }
}

/** 模型组成文件：下载时逐个拉取，支持断点续传。 */
data class AsrModelFile(
    /** 存放在模型目录下的文件名 */
    val name: String,
    /** HTTPS 直链 */
    val url: String,
    /** 预估大小（字节，仅用于进度展示） */
    val sizeBytes: Long,
)

/** 格式化字节数为 "103 MB" / "12.9 MB" 风格，供模型管理页展示。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
