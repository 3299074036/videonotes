package com.zola.videonotes.data.asr.audio

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P2：下载断点续传 + 中断重试测试（拦截器返回写死的响应，不发真实请求）。
 *
 * 覆盖：整包成功、静默截断后 Range 续传、服务端忽略 Range 时重下、
 * 429 后重试、404 不重试、跨调用残留临时文件的 URL 标记校验。
 */
class AudioDownloadTest {

    private fun tempDir(): File =
        Files.createTempDirectory("audiodl").toFile()

    private fun clientOf(handler: (chain: Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> handler(chain) }.build()

    private fun responseFor(
        chain: Interceptor.Chain,
        code: Int,
        body: ResponseBody,
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val b = Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    /** 声明长度大于实际字节的体：读到实际末尾即 EOF，触发“下载不完整”校验。 */
    private fun truncatedBody(declared: Long, actual: ByteArray): ResponseBody =
        object : ResponseBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = declared
            override fun source() = Buffer().write(actual)
        }

    private fun bytes(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it + seed) % 256).toByte() }

    @Test
    fun `full download succeeds and cleans temp files`() = runBlocking {
        val dir = tempDir()
        try {
            val body = bytes(100, 0)
            val client = clientOf { chain ->
                responseFor(chain, 200, body.toResponseBody("application/octet-stream".toMediaType()))
            }
            val dest = File(dir, "a.mp4")
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals(body.toList(), out.readBytes().toList())
            assertTrue(!File(dir, "a.mp4.tmp").exists())
            assertTrue(!File(dir, "a.mp4.tmp.url").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `truncated download resumes with range`() = runBlocking {
        val dir = tempDir()
        try {
            val head = bytes(10, 0)
            val tail = bytes(90, 10)
            val ranges = mutableListOf<String?>()
            val client = clientOf { chain ->
                val range = chain.request().header("Range")
                ranges += range
                if (range == null) {
                    responseFor(chain, 200, truncatedBody(100, head))
                } else {
                    responseFor(
                        chain, 206, tail.toResponseBody("application/octet-stream".toMediaType()),
                        mapOf("Content-Range" to "bytes 10-99/100"),
                    )
                }
            }
            val dest = File(dir, "a.mp4")
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals((head + tail).toList(), out.readBytes().toList())
            assertEquals(listOf(null, "bytes=10-"), ranges)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `server ignoring range restarts from zero`() = runBlocking {
        val dir = tempDir()
        try {
            val head = bytes(10, 0)
            val full = bytes(100, 0)
            var calls = 0
            val client = clientOf { chain ->
                calls++
                if (calls == 1) {
                    responseFor(chain, 200, truncatedBody(100, head))
                } else {
                    // 服务端无视 Range：返回 200 全量，实现必须丢弃旧临时文件重下，不能拼成 110 字节。
                    responseFor(chain, 200, full.toResponseBody("application/octet-stream".toMediaType()))
                }
            }
            val dest = File(dir, "a.mp4")
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals(full.toList(), out.readBytes().toList())
            assertEquals(2, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `429 then 200 succeeds`() = runBlocking {
        val dir = tempDir()
        try {
            var calls = 0
            val body = bytes(50, 0)
            val client = clientOf { chain ->
                calls++
                if (calls == 1) {
                    responseFor(chain, 429, "".toResponseBody())
                } else {
                    responseFor(chain, 200, body.toResponseBody("application/octet-stream".toMediaType()))
                }
            }
            val dest = File(dir, "a.mp4")
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals(body.toList(), out.readBytes().toList())
            assertEquals(2, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `404 fails without retry`() = runBlocking {
        val dir = tempDir()
        try {
            var calls = 0
            val client = clientOf { chain ->
                calls++
                responseFor(chain, 404, "".toResponseBody())
            }
            val result = runCatching {
                client.downloadFile("https://cdn.example.com/a.mp4", File(dir, "a.mp4"))
            }
            assertTrue(result.isFailure)
            assertEquals(1, calls)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stale temp file with different url is discarded`() = runBlocking {
        val dir = tempDir()
        try {
            val dest = File(dir, "a.mp4")
            File(dir, "a.mp4.tmp").writeBytes(bytes(10, 0))
            File(dir, "a.mp4.tmp.url").writeText("https://other.example.com/x.mp4")
            val full = bytes(100, 0)
            val ranges = mutableListOf<String?>()
            val client = clientOf { chain ->
                ranges += chain.request().header("Range")
                responseFor(chain, 200, full.toResponseBody("application/octet-stream".toMediaType()))
            }
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals(full.toList(), out.readBytes().toList())
            assertEquals(listOf(null), ranges)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `matching temp file resumes across calls`() = runBlocking {
        val dir = tempDir()
        try {
            val head = bytes(10, 0)
            val tail = bytes(90, 10)
            val dest = File(dir, "a.mp4")
            File(dir, "a.mp4.tmp").writeBytes(head)
            File(dir, "a.mp4.tmp.url").writeText("https://cdn.example.com/a.mp4")
            val ranges = mutableListOf<String?>()
            val client = clientOf { chain ->
                ranges += chain.request().header("Range")
                responseFor(
                    chain, 206, tail.toResponseBody("application/octet-stream".toMediaType()),
                    mapOf("Content-Range" to "bytes 10-99/100"),
                )
            }
            val out = client.downloadFile("https://cdn.example.com/a.mp4", dest)
            assertEquals((head + tail).toList(), out.readBytes().toList())
            assertEquals(listOf("bytes=10-"), ranges)
        } finally {
            dir.deleteRecursively()
        }
    }
}
