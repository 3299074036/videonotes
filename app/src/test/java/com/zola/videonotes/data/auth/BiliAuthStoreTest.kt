package com.zola.videonotes.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** BiliAuthStore.parseBiliCookies 测试：WebView Cookie 字符串解析。 */
class BiliAuthStoreTest {

    /** 完整 Cookie：SESSDATA / bili_jct / DedeUserID 都能解析出来。 */
    @Test
    fun `parseBiliCookies extracts all fields`() {
        val (sessData, biliJct, uid) = BiliAuthStore.parseBiliCookies(
            "SESSDATA=abc123; bili_jct=def456; DedeUserID=789; buvid3=xyz",
        )
        assertEquals("abc123", sessData)
        assertEquals("def456", biliJct)
        assertEquals("789", uid)
    }

    /** 缺字段：未登录态（无 SESSDATA）时对应字段为 null，其他字段不受影响。 */
    @Test
    fun `parseBiliCookies returns null for missing fields`() {
        val (sessData, biliJct, uid) = BiliAuthStore.parseBiliCookies(
            "buvid3=xyz; b_nut=123; bili_jct=def456",
        )
        assertNull(sessData)
        assertEquals("def456", biliJct)
        assertNull(uid)
    }

    /** 空字符串 / 纯空白 / null：全部为 null。 */
    @Test
    fun `parseBiliCookies handles blank input`() {
        assertEquals(Triple(null, null, null), BiliAuthStore.parseBiliCookies(""))
        assertEquals(Triple(null, null, null), BiliAuthStore.parseBiliCookies("   "))
        assertEquals(Triple(null, null, null), BiliAuthStore.parseBiliCookies(null))
    }

    /** 字段名大小写必须精确匹配：sessdata 小写不被识别。 */
    @Test
    fun `parseBiliCookies matches field names case sensitively`() {
        val (sessData, _, _) = BiliAuthStore.parseBiliCookies("sessdata=abc123")
        assertNull(sessData)
    }

    /** 容错：多余空格、空片段、值为空的字段不影响解析。 */
    @Test
    fun `parseBiliCookies tolerates whitespace and empty parts`() {
        val (sessData, biliJct, uid) = BiliAuthStore.parseBiliCookies(
            "  SESSDATA = abc123 ; ; bili_jct= ; DedeUserID=789  ",
        )
        assertEquals("abc123", sessData)
        assertNull(biliJct)
        assertEquals("789", uid)
    }
}
