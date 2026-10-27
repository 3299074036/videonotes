package com.zola.videonotes.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P0：错误分类纯逻辑测试（不碰网络与存储）。 */
class ParseErrorClassifierTest {

    @Test
    fun `youtube login required wins over blocked wording`() {
        // 真实上报过的原文：同时含 temporarily blocked 与 LOGIN_REQUIRED；
        // LOGIN_REQUIRED 优先，因为重导 cookie 是第一步（换网写进提示第二步）。
        val raw = "YouTube probably temporarily blocked anonymous watch access with this IP, " +
            "got error LOGIN_REQUIRED: \"Sign in to confirm that you're not a bot\""
        assertEquals(ParseErrorKind.NEED_LOGIN, ParseErrorClassifier.youtube(raw))
    }

    @Test
    fun `youtube blocked without login required is ip blocked`() {
        assertEquals(
            ParseErrorKind.IP_BLOCKED,
            ParseErrorClassifier.youtube("YouTube probably temporarily blocked anonymous watch access"),
        )
    }

    @Test
    fun `youtube other errors are unknown`() {
        assertEquals(ParseErrorKind.UNKNOWN, ParseErrorClassifier.youtube("Video unavailable"))
        assertEquals(ParseErrorKind.UNKNOWN, ParseErrorClassifier.youtube(""))
    }

    @Test
    fun `http codes map to kinds`() {
        assertEquals(ParseErrorKind.NOT_FOUND, ParseErrorClassifier.http(404))
        assertEquals(ParseErrorKind.IP_BLOCKED, ParseErrorClassifier.http(429))
        assertEquals(ParseErrorKind.NETWORK, ParseErrorClassifier.http(500))
        assertEquals(ParseErrorKind.NETWORK, ParseErrorClassifier.http(403))
    }

    @Test
    fun `bilibili 404 is not found regardless of message`() {
        assertEquals(ParseErrorKind.NOT_FOUND, ParseErrorClassifier.bilibili(-404, "啥都木有"))
    }

    @Test
    fun `bilibili restricted keywords`() {
        assertEquals(ParseErrorKind.RESTRICTED, ParseErrorClassifier.bilibili(-403, "付费内容"))
        assertEquals(ParseErrorKind.RESTRICTED, ParseErrorClassifier.bilibili(0, "大会员专享"))
        assertEquals(ParseErrorKind.RESTRICTED, ParseErrorClassifier.bilibili(0, "无权访问"))
        assertEquals(ParseErrorKind.UNKNOWN, ParseErrorClassifier.bilibili(0, "未知错误"))
    }

    @Test
    fun `parse exception message carries next step`() {
        val e = ParseException(ParseErrorKind.IP_BLOCKED, "微博风控拦截，请稍后重试")
        assertTrue(e.message!!.contains("请换网络后重试"))
        assertEquals(ParseErrorKind.IP_BLOCKED, e.kind)
    }

    @Test
    fun `parse exception without hint keeps detail as is`() {
        val e = ParseException(
            ParseErrorKind.INVALID_LINK,
            "分享链接已失效，请重新复制",
            appendHint = false,
        )
        assertEquals("分享链接已失效，请重新复制", e.message)
    }

    @Test
    fun `parse exception hint override wins over kind default`() {
        val e = ParseException(
            ParseErrorKind.NEED_LOGIN,
            "YouTube 解析失败：LOGIN_REQUIRED",
            hintOverride = "登录态可能已过期，请重新导入 cookie；仍失败请换网络重试",
        )
        assertTrue(e.message!!.contains("登录态可能已过期"))
    }

    @Test
    fun `xhs login exception kind follows token missing flag`() {
        val missing = XiaohongshuParser.XhsLoginRequiredException(tokenMissing = true)
        assertEquals(ParseErrorKind.INVALID_LINK, missing.kind)
        assertTrue(missing.message!!.contains("缺少 xsec_token"))

        val login = XiaohongshuParser.XhsLoginRequiredException(tokenMissing = false)
        assertEquals(ParseErrorKind.NEED_LOGIN, login.kind)
        assertTrue(login.message!!.contains("小红书要求登录"))
    }
}
