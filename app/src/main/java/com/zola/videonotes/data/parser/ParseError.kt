package com.zola.videonotes.data.parser

import java.io.IOException

/**
 * 解析失败分类（P0：yt-dlp 借鉴第一件，错误分类分流）。
 *
 * 目标：失败不再只有一句笼统的“请检查链接或稍后重试”，而是按类别给下一步。
 * UI 层只读 [ParseException.message]（里面已带下一步），因此已有机读类别可用时，
 * 未来 UI 可直接按 [ParseException.kind] 分支，不必再解析文案。
 *
 * 分类原则（诚实优先）：
 * - 只有能自信归类的才给具体类别；含糊的（例如“作品可能已删除或页面改版”这种
 *   删除与改版二选一）一律归 [ParseErrorKind.UNKNOWN]，保留原文案，不硬套类别。
 * - 解析器内部的基础设施失败（如 ttwid 注册）不归类，保持原 IOException。
 * - [ParseException] 是 [IOException] 的子类，各解析器原有的
 *   `catch (e: IOException) { Result.failure(e) }` 会原样透传，不改成功路径。
 */
enum class ParseErrorKind(val nextStep: String) {
    /** 需登录态：YouTube LOGIN_REQUIRED、小红书登录墙。 */
    NEED_LOGIN("请重新导入 cookie 后重试"),

    /** 视频已删 / 内容不存在。 */
    NOT_FOUND("该视频可能已删除，无法解析"),

    /** 会员 / 付费 / 地区限制。 */
    RESTRICTED("该视频为会员、付费或地区限制内容，无法解析"),

    /** 疑似 IP / 平台风控：换网是第一步。 */
    IP_BLOCKED("可能触发平台风控，请换网络后重试"),

    /** 网络失败。 */
    NETWORK("请检查网络后重试"),

    /** 内容形态不支持：图集、图文笔记、无视频的微博等。 */
    UNSUPPORTED("该内容暂不支持解析"),

    /** 链接本身无效或不完整：缺 xsec_token、无法识别 ID、短链已失效。 */
    INVALID_LINK("请重新粘贴完整视频链接"),

    /** 无法自信归类：保留原文案，只补通用下一步。 */
    UNKNOWN("请检查链接或稍后重试"),
}

/**
 * 带类别的解析失败。
 *
 * @param kind 类别。
 * @param detail 原始失败说明（不含下一步）。
 * @param hintOverride 覆盖 [ParseErrorKind.nextStep] 的下一步文案
 *   （例如 YouTube NEED_LOGIN 要区分“未导入”和“已导入但过期”）。
 * @param appendHint 为 false 时 message 就是 detail 原样
 *   （detail 本身已含明确下一步时用，避免重复）。
 */
open class ParseException(
    val kind: ParseErrorKind,
    detail: String,
    hintOverride: String? = null,
    appendHint: Boolean = true,
) : IOException(
    if (!appendHint) detail else "$detail（${hintOverride ?: kind.nextStep}）",
)

/**
 * 外部字符串到类别的纯函数（可单测，不碰网络与存储）。
 *
 * 只做有把握的映射：YouTube 靠 NewPipe 原文里的 LOGIN_REQUIRED /
 * temporarily blocked；B站靠接口 code（-404 为不存在）与文案关键词；
 * 页面请求靠 HTTP 状态码（404 不存在、429 风控，其余归网络失败）。
 */
object ParseErrorClassifier {

    fun youtube(rawMsg: String): ParseErrorKind = when {
        rawMsg.contains("LOGIN_REQUIRED") -> ParseErrorKind.NEED_LOGIN
        rawMsg.contains("temporarily blocked") -> ParseErrorKind.IP_BLOCKED
        else -> ParseErrorKind.UNKNOWN
    }

    fun http(code: Int): ParseErrorKind = when (code) {
        404 -> ParseErrorKind.NOT_FOUND
        429 -> ParseErrorKind.IP_BLOCKED
        else -> ParseErrorKind.NETWORK
    }

    fun bilibili(code: Int, message: String): ParseErrorKind = when {
        code == -404 -> ParseErrorKind.NOT_FOUND
        message.contains("付费") || message.contains("会员") ||
            message.contains("无权") || message.contains("权限") -> ParseErrorKind.RESTRICTED
        else -> ParseErrorKind.UNKNOWN
    }
}
