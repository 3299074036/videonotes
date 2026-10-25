package com.zola.videonotes.ui.navigation

import android.net.Uri

/** 全 App 路由定义：集中管理，避免各处手写路由字符串。 */
object Routes {

    /** 笔记库（M5 首页）。 */
    const val HOME = "home"

    /** 视频导入页：粘贴链接 / 最近导入，解析走元信息页（与分享意图同一路径）。 */
    const val IMPORT = "import"

    const val SETTINGS = "settings"

    /** 设置页 Tab 参数：kb_home（知识库）/ kb_settings（设置），外层可直达设置 Tab。 */
    const val ARG_START_TAB = "startTab"

    /** 构造设置页路由（默认知识库 Tab；传 kb_settings 直达设置 Tab）。 */
    fun settings(startTab: String = "kb_home"): String = "$SETTINGS?$ARG_START_TAB=$startTab"

    /** 模型管理页（M3）：模型下载 / 删除 / 选择。 */
    const val MODEL_MANAGEMENT = "model_management"

    /** 模型管理页参数：入口分区（直达模式），缺省 [SECTION_FULL] 为完整版。 */
    const val ARG_SECTION = "section"

    /** 分区：本地大语言模型（当前模型条 + 模型列表）。 */
    const val SECTION_LLM_LOCAL = "llm_local"

    /** 分区：云端 API 配置表单。 */
    const val SECTION_LLM_CLOUD = "llm_cloud"

    /** 分区：语音识别模型列表。 */
    const val SECTION_ASR = "asr"

    /** 分区：完整版（双分段切换 + 已下载汇总）。 */
    const val SECTION_FULL = "full"

    /** 含参数的模型管理页路由模板。 */
    const val MODEL_MANAGEMENT_ROUTE = "$MODEL_MANAGEMENT?$ARG_SECTION={$ARG_SECTION}"

    /** 构造模型管理页路由（按入口分区直达）。 */
    fun modelManagement(section: String = SECTION_FULL): String =
        "$MODEL_MANAGEMENT?$ARG_SECTION=$section"

    /** 开源许可页（M6）：第三方组件许可列表。 */
    const val LICENSES = "licenses"

    /** B站登录页：内嵌 B站官方登录页，捕获 SESSDATA 后自动关闭。 */
    const val BILI_LOGIN = "bili_login"

    /** 小红书登录页：内嵌小红书官方登录页，捕获 web_session 经行为探测确认后自动关闭。 */
    const val XHS_LOGIN = "xhs_login"

    /** 统一日志查看页：LLM 日志 / 转写日志，实时查看与导出。 */
    const val LOG_VIEWER = "log_viewer"

    /** 运维诊断页：LLM 诊断探针 / 日志管理 / 设备信息。 */
    const val OPS_DIAGNOSTICS = "ops_diagnostics"

    /** 崩溃日志页：worker.log 中 [CRASH] 条目的结构化过滤视图。 */
    const val CRASH_LOG = "crash_log"

    /** 笔记提示词编辑页：自定义 system 提示词（本地 / 云端两档）。 */
    const val PROMPT_EDITOR = "prompt_editor"

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

    /**
     * 播放器页参数：详情页解析好的直链（YunX 模式：解析结果就是播放源，直接播不重解析）。
     * 仅详情页跳转时传入；笔记页等没有解析结果的入口留空，走原有重解析逻辑。
     */
    const val ARG_MEDIA_URL = "mediaUrl"

    /**
     * 播放器页参数：详情页解析用的原始链接（含小红书 xsec_token 等查询串）。
     * 直链缺失或过期时用它重解析；留空时退回按 storageKey 重建的地址。
     */
    const val ARG_SOURCE_URL = "sourceUrl"

    private const val PLAYER_BASE = "player"

    /** 播放器页路由模板（M6）：站内播放 + 时间戳跳转。 */
    const val PLAYER_ROUTE =
        "$PLAYER_BASE?$ARG_STORAGE_KEY={$ARG_STORAGE_KEY}&$ARG_START_SECONDS={$ARG_START_SECONDS}" +
            "&$ARG_MEDIA_URL={$ARG_MEDIA_URL}&$ARG_SOURCE_URL={$ARG_SOURCE_URL}"

    /** 构造播放器页路由（存储键与链接做 URI 编码，含冒号与查询串）。 */
    fun player(
        storageKey: String,
        startSeconds: Long,
        mediaUrl: String = "",
        sourceUrl: String = "",
    ): String =
        "$PLAYER_BASE?$ARG_STORAGE_KEY=${Uri.encode(storageKey)}&$ARG_START_SECONDS=$startSeconds" +
            "&$ARG_MEDIA_URL=${Uri.encode(mediaUrl)}&$ARG_SOURCE_URL=${Uri.encode(sourceUrl)}"
}
