package com.zola.videonotes.data.parser

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0：快手解析器 fixture 单测（拦截器返回写死的页面，不发真实请求）。
 *
 * 注意：fixture 是按 2026-10-25 实测页面结构重建的（INIT_STATE 5 键全混淆串、
 * 唯一 photo 对象无 id 字段），不是逐字节抓包；覆盖的是代码必须处理的“形状”，
 * 而非某次真实响应的全文。ID 均为虚构。
 */
class KuaishouParserTest {

    private data class Stub(val code: Int, val location: String? = null, val body: String = "")

    private fun clientFor(stub: (String) -> Stub): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val s = stub(request.url.toString())
                val builder = Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(s.code)
                    .message("stub")
                    .body(s.body.toResponseBody("text/html".toMediaType()))
                if (s.location != null) builder.header("Location", s.location)
                builder.build()
            }
            .build()

    private fun photoJson(
        caption: String,
        user: String,
        video: String,
        cover: String,
        atlas: Boolean = false,
    ): String {
        val mv = if (atlas) "[]" else """[{"url":"$video"}]"""
        val ext = if (atlas) ""","ext_params":{"atlas":{"list":[{"a":1}]}}""" else ""
        return """{"caption":"$caption","userName":"$user","mainMvUrls":$mv,"coverUrls":[{"url":"$cover"}]$ext}"""
    }

    private fun initStateHtml(vararg entries: Pair<String, String>): String {
        val json = entries.joinToString(",") { (k, v) -> """"$k":$v""" }
        return "<html><head><script>window.INIT_STATE = {$json};</script></head><body></body></html>"
    }

    /** /f/ 分享码整条跳转链：f → short-video → fw/photo，最后单 photo 兜底成功。 */
    @Test
    fun `f link chain plus single photo fallback succeeds`() = runBlocking {
        val html = initStateHtml(
            "obfA" to """{"other":1}""",
            "obfB" to """{"other":2}""",
            "obfC" to """{"photo":${photoJson("两位老父亲看孩子真是辛苦了", "浪花娱乐", "http://cdn.example.com/v.mp4", "http://cdn.example.com/c.jpg")}}""",
            "obfD" to """{"other":3}""",
            "obfE" to """{"other":4}""",
        )
        val client = clientFor { url ->
            when {
                url.contains("/f/") -> Stub(302, "/short-video/abc123")
                url.contains("/short-video/") -> Stub(302, "/fw/photo/3xTestId9")
                url.contains("/fw/photo/") -> Stub(200, body = html)
                else -> Stub(404)
            }
        }
        val result = KuaishouParser(client).parse("https://www.kuaishou.com/f/X-Test123")
        assertTrue(result.isSuccess)
        val info = result.getOrThrow()
        assertEquals("3xTestId9", info.videoId)
        assertEquals("两位老父亲看孩子真是辛苦了", info.title)
        assertEquals("浪花娱乐", info.author)
        assertEquals("https://cdn.example.com/v.mp4", info.mediaUrl)
        assertEquals("https://v.kuaishou.com/", info.mediaHeaders["Referer"])
    }

    /** 链接 id 的 key 命中时优先用它（即使页里还有别的 photo）。 */
    @Test
    fun `linkId key match wins over other photos`() = runBlocking {
        val html = initStateHtml(
            "3xTestId9" to """{"photo":${photoJson("目标视频", "作者A", "http://cdn.example.com/a.mp4", "http://cdn.example.com/a.jpg")}}""",
            "otherKey" to """{"photo":${photoJson("别处视频", "作者B", "http://cdn.example.com/b.mp4", "http://cdn.example.com/b.jpg")}}""",
        )
        val client = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(200, body = html) else Stub(404)
        }
        val info = KuaishouParser(client)
            .parse("https://www.kuaishou.com/fw/photo/3xTestId9").getOrThrow()
        assertEquals("目标视频", info.title)
        assertEquals("作者A", info.author)
    }

    /** 多 photo 且无链接 id 的 key 时仍严格报错，不猜。 */
    @Test
    fun `multiple photos without linkId key still fails`() = runBlocking {
        val html = initStateHtml(
            "keyA" to """{"photo":${photoJson("视频A", "作者A", "http://cdn.example.com/a.mp4", "http://cdn.example.com/a.jpg")}}""",
            "keyB" to """{"photo":${photoJson("视频B", "作者B", "http://cdn.example.com/b.mp4", "http://cdn.example.com/b.jpg")}}""",
        )
        val client = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(200, body = html) else Stub(404)
        }
        val result = KuaishouParser(client).parse("https://www.kuaishou.com/fw/photo/3xTestId9")
        assertTrue(result.isFailure)
        val e = result.exceptionOrNull() as? ParseException
            ?: error("expected ParseException, got ${result.exceptionOrNull()}")
        assertEquals(ParseErrorKind.UNKNOWN, e.kind)
        assertTrue(e.message!!.contains("不匹配"))
    }

    /** 只有图集、无视频时归为不支持（不是笼统失败）。 */
    @Test
    fun `atlas only photo is unsupported`() = runBlocking {
        val html = initStateHtml(
            "3xTestId9" to """{"photo":${photoJson("图集", "作者A", "", "http://cdn.example.com/a.jpg", atlas = true)}}""",
        )
        val client = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(200, body = html) else Stub(404)
        }
        val result = KuaishouParser(client).parse("https://www.kuaishou.com/fw/photo/3xTestId9")
        assertTrue(result.isFailure)
        val e = result.exceptionOrNull() as? ParseException
            ?: error("expected ParseException, got ${result.exceptionOrNull()}")
        assertEquals(ParseErrorKind.UNSUPPORTED, e.kind)
    }

    /** 页面里没有 INIT_STATE 时失败，文案保留。 */
    @Test
    fun `page without init state fails`() = runBlocking {
        val client = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(200, body = "<html><body>no state</body></html>") else Stub(404)
        }
        val result = KuaishouParser(client).parse("https://www.kuaishou.com/fw/photo/3xTestId9")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("window.INIT_STATE"))
    }

    /** 作品页 HTTP 状态映射：404 不存在、429 风控。 */
    @Test
    fun `http codes map to kinds`() = runBlocking {
        val notFound = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(404) else Stub(404)
        }
        val e404 = notFound.let {
            KuaishouParser(it).parse("https://www.kuaishou.com/fw/photo/3xTestId9")
                .exceptionOrNull() as? ParseException
        } ?: error("expected ParseException for 404")
        assertEquals(ParseErrorKind.NOT_FOUND, e404.kind)

        val blocked = clientFor { url ->
            if (url.contains("/fw/photo/")) Stub(429) else Stub(429)
        }
        val e429 = KuaishouParser(blocked).parse("https://www.kuaishou.com/fw/photo/3xTestId9")
            .exceptionOrNull() as? ParseException
            ?: error("expected ParseException for 429")
        assertEquals(ParseErrorKind.IP_BLOCKED, e429.kind)
    }
}
