package com.zola.videonotes.ui.navigation

import android.net.Uri

/** 全 App 路由定义：集中管理，避免各处手写路由字符串。 */
object Routes {

    /** 笔记库（M5 首页）。 */
    const val HOME = "home"

    const val SETTINGS = "settings"

    /** 设置页 Tab 参数：kb_home（知识库）/ kb_settings（设置），外层可直达设置 Tab。 */
    const val ARG_START_TAB = "startTab"

    /** 构造设置页路由（默认知识库 Tab；传 kb_settings 直达设置 Tab）。 */
    fun settings(startTab: String = "kb_home"): String = "$SETTINGS?$ARG_START_TAB=$startTab"

    /** 语音识别模型管理页（M3）：模型下载 / 删除 / 选择 / 仅 Wi-Fi 开关。 */
    const val MODEL_MANAGEMENT = "model_management"

    /** 开源许可页（M6）：第三方组件许可列表。 */
    const val LICENSES = "licenses"

    /** B站登录页：内嵌 B站官方登录页，捕获 SESSDATA 后自动关闭。 */
    const val BILI_LOGIN = "bili_login"

    /** 元信息页参数：待解析的视频链接（分享文本亦可，解析器内部会提取 URL）。 */
    const val ARG_URL = "url"

    private const val METADATA_BASE = "metadata"

    /** 含参数的元信息页路由模板。 */
    const val METADATA_ROUTE = "$METADATA_BASE?$ARG_URL={$ARG_URL}"

    /** 构造元信息页路由（链接做 URI 编码）。 */
    fun metadata(url: String): String = "$METADATA_BASE?$ARG_URL=${Uri.encode(url)}"

    /** 笔记详情页参数：笔记 id。 */
    const val ARG_NOTE_ID = "noteId"

    private const val NOTE_DETAIL_BASE = "note_detail"

    /** 笔记详情页路由模板（M5）：富文本阅读 + 分享/导出/重命名/删除。 */
    const val NOTE_DETAIL_ROUTE = "$NOTE_DETAIL_BASE?$ARG_NOTE_ID={$ARG_NOTE_ID}"

    /** 构造笔记详情页路由。 */
    fun noteDetail(noteId: Long): String = "$NOTE_DETAIL_BASE?$ARG_NOTE_ID=$noteId"

    /** 播放器页参数：视频存储键（格式 "${platform.name}:${videoId}"）。 */
    const val ARG_STORAGE_KEY = "storageKey"

    /** 播放器页参数：起始秒数（pill 跳转传入）。 */
    const val ARG_START_SECONDS = "startSeconds"

    private const val PLAYER_BASE = "player"

    /** 播放器页路由模板（M6）：站内播放 + 时间戳跳转。 */
    const val PLAYER_ROUTE =
        "$PLAYER_BASE?$ARG_STORAGE_KEY={$ARG_STORAGE_KEY}&$ARG_START_SECONDS={$ARG_START_SECONDS}"

    /** 构造播放器页路由（存储键做 URI 编码，含冒号）。 */
    fun player(storageKey: String, startSeconds: Long): String =
        "$PLAYER_BASE?$ARG_STORAGE_KEY=${Uri.encode(storageKey)}&$ARG_START_SECONDS=$startSeconds"
}
