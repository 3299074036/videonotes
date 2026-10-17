package com.zola.videonotes.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DeepLinkBuilder 测试：平台 deep link 拼接与非法输入。 */
class DeepLinkBuilderTest {

    @Test
    fun `build youtube link with seconds param`() {
        val link = DeepLinkBuilder.build("YOUTUBE:dQw4w9WgXcQ", 754)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=754s", link)
    }

    @Test
    fun `build bilibili link with seconds param`() {
        val link = DeepLinkBuilder.build("BILIBILI:BV1xx411c7mD", 3723)
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD?t=3723", link)
    }

    @Test
    fun `build clamps negative seconds to zero`() {
        val link = DeepLinkBuilder.build("YOUTUBE:abc123", -5)
        assertEquals("https://www.youtube.com/watch?v=abc123&t=0s", link)
    }

    @Test
    fun `build returns null for unsupported platform`() {
        // P1 平台暂不支持跳转
        assertNull(DeepLinkBuilder.build("DOUYIN:abc123", 60))
        assertNull(DeepLinkBuilder.build("XIAOHONGSHU:abc123", 60))
        assertNull(DeepLinkBuilder.build("UNKNOWN:abc123", 60))
    }

    @Test
    fun `build returns null for malformed key`() {
        assertNull(DeepLinkBuilder.build("", 60))
        assertNull(DeepLinkBuilder.build("YOUTUBE:", 60))
        assertNull(DeepLinkBuilder.build("NOT_A_KEY", 60))
        assertNull(DeepLinkBuilder.build("NOSUCHPLATFORM:abc", 60))
    }

    @Test
    fun `platformOf and videoIdOf parse storage key`() {
        assertEquals(
            com.zola.videonotes.domain.model.Platform.BILIBILI,
            DeepLinkBuilder.platformOf("BILIBILI:BV1xx411c7mD"),
        )
        assertEquals("BV1xx411c7mD", DeepLinkBuilder.videoIdOf("BILIBILI:BV1xx411c7mD"))
        // videoId 含冒号时只切第一段
        assertEquals("a:b", DeepLinkBuilder.videoIdOf("YOUTUBE:a:b"))
        assertNull(DeepLinkBuilder.platformOf("BOGUS:x"))
    }
}
