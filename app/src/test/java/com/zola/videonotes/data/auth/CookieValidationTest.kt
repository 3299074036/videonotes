package com.zola.videonotes.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1：YouTube cookie 校验与 Netscape 文件解析测试（纯逻辑，不碰存储）。 */
class CookieValidationTest {

    private val fullCookie =
        "SID=aaa; HSID=bbb; SSID=ccc; APISID=ddd; SAPISID=eee; LOGIN_INFO=fff"

    @Test
    fun `full cookie is complete`() {
        val check = validateYoutubeCookie(fullCookie)
        assertTrue(check.canSave)
        assertTrue(check.isComplete)
        assertTrue(check.missingRequired.isEmpty())
        assertTrue(check.missingRecommended.isEmpty())
    }

    @Test
    fun `missing sid or login info cannot save`() {
        val noSid = validateYoutubeCookie("HSID=bbb; SSID=ccc; APISID=ddd; SAPISID=eee; LOGIN_INFO=fff")
        assertFalse(noSid.canSave)
        assertEquals(listOf("SID"), noSid.missingRequired)

        val noLogin = validateYoutubeCookie("SID=aaa; HSID=bbb; SSID=ccc; APISID=ddd; SAPISID=eee")
        assertFalse(noLogin.canSave)
        assertEquals(listOf("LOGIN_INFO"), noLogin.missingRequired)
    }

    @Test
    fun `missing recommended keys can save with warning`() {
        val check = validateYoutubeCookie("SID=aaa; LOGIN_INFO=fff")
        assertTrue(check.canSave)
        assertFalse(check.isComplete)
        assertEquals(listOf("HSID", "SSID", "APISID", "SAPISID"), check.missingRecommended)
    }

    @Test
    fun `garbage and blank cookies are malformed`() {
        assertTrue(validateYoutubeCookie("noequals").isMalformed)
        assertTrue(validateYoutubeCookie("SID=; HSID=").isMalformed)
        assertTrue(validateYoutubeCookie("").isMalformed)
        assertTrue(validateYoutubeCookie("   ").isMalformed)
    }

    @Test
    fun `pairs tolerate spaces and last wins`() {
        val check = validateYoutubeCookie(" SID=aaa ; SID=bbb ; LOGIN_INFO=fff ")
        assertEquals("bbb", check.pairs["SID"])
        assertTrue(check.canSave)
    }

    @Test
    fun `netscape file parses youtube entries only`() {
        val text = listOf(
            "# Netscape HTTP Cookie File",
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\taaa",
            ".youtube.com\tTRUE\t/\tTRUE\t0\tHSID\tbbb",
            ".youtube.com\tTRUE\t/\tTRUE\t1\tSSID\texpired",
            ".google.com\tTRUE\t/\tTRUE\t4102444800\tSID\tgoogle-sid",
            "www.youtube.com\tTRUE\t/\tFALSE\t4102444800\tSID\twww-sid",
            "badline",
            ".youtube.com\tTRUE\t/\tTRUE\t4102444800\tEMPTY\t",
            "",
        ).joinToString("\n")
        // 注释、过期、非 YouTube 域、坏行、空值均丢弃；同名 SID 后者覆盖前者。
        assertEquals("SID=www-sid; HSID=bbb", parseNetscapeCookies(text, YoutubeCookieStore::isYoutubeHost))
    }

    @Test
    fun `netscape result passes validation`() {
        val text = ".youtube.com\tTRUE\t/\tTRUE\t4102444800\tSID\taaa\n" +
            ".youtube.com\tTRUE\t/\tTRUE\t0\tLOGIN_INFO\tfff\n"
        val header = parseNetscapeCookies(text, YoutubeCookieStore::isYoutubeHost)
        val check = validateYoutubeCookie(header)
        assertTrue(check.canSave)
        assertEquals(listOf("HSID", "SSID", "APISID", "SAPISID"), check.missingRecommended)
    }

    @Test
    fun `netscape empty or non youtube file yields blank`() {
        assertEquals("", parseNetscapeCookies("", YoutubeCookieStore::isYoutubeHost))
        assertEquals(
            "",
            parseNetscapeCookies(
                ".example.com\tTRUE\t/\tTRUE\t4102444800\tSID\taaa",
                YoutubeCookieStore::isYoutubeHost,
            ),
        )
    }
}
