package com.zola.videonotes.domain.model

/** 生成的课堂笔记（Markdown 源码，时间戳为 [[mm:ss]] pill 格式）。 */
data class Note(
    val id: Long,
    val videoStorageKey: String,
    val title: String,
    val markdown: String,
    val createdAt: Long,
    val llmModel: String,
    /** 来源视频标题（M5 笔记库列表展示）。 */
    val videoTitle: String = "",
)
