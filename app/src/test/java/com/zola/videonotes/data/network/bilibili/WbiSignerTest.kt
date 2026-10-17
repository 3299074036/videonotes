package com.zola.videonotes.data.network.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WbiSigner 单元测试：使用固定测试向量验证签名算法与官方一致。
 * 注意：这些是算法测试向量，不含任何真实密钥。
 */
class WbiSignerTest {

    @Test
    fun `getMixinKey 与官方测试向量一致`() {
        val mixin = WbiSigner.getMixinKey(
            imgKey = "a1b2c3d4e5f60718293a4b5c6d7e8f90",
            subKey = "0987654321fedcba9876543210abcdef1",
        )
        assertEquals("ba3b4ec087f0a2c7ee3895faf1304175", mixin)
    }

    @Test
    fun `buildQueryString 按key排序并拼接`() {
        val query = WbiSigner.buildQueryString(
            mapOf(
                "wts" to "1729000000",
                "cid" to "42747825932",
                "bvid" to "BV1q3pS6tE4J",
            ),
        )
        assertEquals("bvid=BV1q3pS6tE4J&cid=42747825932&wts=1729000000", query)
    }

    @Test
    fun `buildQueryString 过滤特殊字符`() {
        val query = WbiSigner.buildQueryString(mapOf("b" to "a!b'c(d)e*f", "a" to "1"))
        assertEquals("a=1&b=abcdef", query)
    }

    @Test
    fun `sign 生成的w_rid 与官方测试向量一致`() {
        val signed = WbiSigner.sign(
            params = mapOf("bvid" to "BV1q3pS6tE4J", "cid" to "42747825932"),
            mixinKey = "ba3b4ec087f0a2c7ee3895faf1304175",
            wts = 1729000000L,
        )
        assertEquals("1729000000", signed["wts"])
        assertEquals("9eda96a3094ab87340c92dc1fa210bb0", signed["w_rid"])
        // 原业务参数保留
        assertEquals("BV1q3pS6tE4J", signed["bvid"])
        assertEquals("42747825932", signed["cid"])
    }

    @Test
    fun `parseKeys 提取文件名去扩展名`() {
        val keys = WbiSigner.parseKeys(
            imgUrl = "https://i0.hdslb.com/bfs/wbi/9cd4224d0c8d3e1f2a3b4c5d6e7f8091.png",
            subUrl = "https://i0.hdslb.com/bfs/wbi/1a2b3c4d5e6f708192a3b4c5d6e7f8091.png",
        )
        assertEquals(
            "9cd4224d0c8d3e1f2a3b4c5d6e7f8091" to "1a2b3c4d5e6f708192a3b4c5d6e7f8091",
            keys,
        )
    }

    @Test
    fun `parseKeys 空URL返回null`() {
        assertNull(WbiSigner.parseKeys("", ""))
        assertNull(WbiSigner.parseKeys("https://x/y.png", ""))
    }
}
