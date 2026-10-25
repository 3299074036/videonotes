package com.zola.videonotes.domain.model

/** 支持的视频平台。UNKNOWN 用于无法识别的链接。 */
enum class Platform(val displayName: String) {
    YOUTUBE("YouTube"),
    BILIBILI("B站"),
    DOUYIN("抖音"),
    XIAOHONGSHU("小红书"),
    KUAISHOU("快手"),
    UNKNOWN("未知平台"),
}
