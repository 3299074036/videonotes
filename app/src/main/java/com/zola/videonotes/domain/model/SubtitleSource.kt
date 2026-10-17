package com.zola.videonotes.domain.model

/**
 * 字幕/转写文本的来源。
 *
 * OFFICIAL：平台官方字幕（如 B站 CC 字幕、YouTube 上传者字幕）
 * AUTO：平台自动生成的字幕（如 YouTube 自动字幕、B站 AI 字幕）
 * ASR：端侧语音识别转写（M3 兜底链路）
 */
enum class SubtitleSource(val displayName: String) {
    OFFICIAL("官方字幕"),
    AUTO("自动生成"),
    ASR("语音转写"),
}
