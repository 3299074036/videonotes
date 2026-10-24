package com.zola.videonotes.data.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** YouTube cookie 纯逻辑测试（域名门控与格式校验，不碰加密存储）。 */
class YoutubeCookieTest {

    @Test
    fun `youtube hosts match`() {
        assertTrue(YoutubeCookieStore.isYoutubeHost("youtube.com"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("www.youtube.com"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("music.youtube.com"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("youtu.be"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("www.youtube-nocookie.com"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("rr1---sn.googlevideo.com"))
        assertTrue(YoutubeCookieStore.isYoutubeHost("youtubei.googleapis.com"))
    }

    @Test
    fun `non youtube hosts rejected`() {
        assertFalse(YoutubeCookieStore.isYoutubeHost("notyoutube.com"))
        assertFalse(YoutubeCookieStore.isYoutubeHost("fakeyoutube.com"))
        assertFalse(YoutubeCookieStore.isYoutubeHost("example.com"))
        assertFalse(YoutubeCookieStore.isYoutubeHost("bilibili.com"))
        assertFalse(YoutubeCookieStore.isYoutubeHost("huggingface.co"))
    }

    @Test
    fun `cookie format validation`() {
        assertFalse(YoutubeCookieStore.isValidCookie(null))
        assertFalse(YoutubeCookieStore.isValidCookie(""))
        assertFalse(YoutubeCookieStore.isValidCookie("   "))
        assertFalse(YoutubeCookieStore.isValidCookie("noequals"))
        assertFalse(YoutubeCookieStore.isValidCookie("SID=; HSID="))
        assertTrue(YoutubeCookieStore.isValidCookie("SID=abc"))
        assertTrue(YoutubeCookieStore.isValidCookie("SID=abc; HSID=def; SSID=ghi"))
        assertTrue(YoutubeCookieStore.isValidCookie("  SID=abc; HSID=def  "))
    }
}
