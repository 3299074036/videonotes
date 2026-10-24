package com.zola.videonotes.data.download

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/**
 * 通用单文件下载内核（WP4）：自 ASR ModelDownloadManager.downloadOneFile 与
 * LLM LocalModelDownloadManager.downloadFile 抽取合并，全 App 只剩这一份单文件下载逻辑。
 *
 * 语义（与旧两侧一致）：
 * - Range 断点续传：本地已有部分 → 带 Range；206 续传、200 重下、416 = 本地已完整；
 * - 协程取消同步 cancel 底层 Call（取自 ASR 侧 executeCancellable 语义）；
 * - 下载后完整性：期望大小已知时必须精确一致（HF LFS / 清单真值均为精确字节数）；
 * - 期望 sha256 非空时全量校验，不符删文件并抛错（文案与收尾包强校验一致）。
 */
@Singleton
class ModelFileDownloader @Inject constructor(
    /** 下载专用客户端（NetworkModule @Named("download")）：禁用 callTimeout，GB 级下载不被总墙钟掐断。 */
    @javax.inject.Named("download") private val okHttpClient: OkHttpClient,
) {

    /**
     * 下载 [url] 到 [dest]。失败抛 IOException（取消抛 CancellationException）。
     * @param expectedSize 期望字节数（≤0 表示未知，跳过大小精确校验）
     * @param expectedSha256 期望 SHA-256（null 跳过哈希校验）
     * @param onProgress (已下载字节, 总字节) 回调
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        var existing = if (dest.exists()) dest.length() else 0L
        // 本地已达期望大小：直接走校验（断点续传的终点短路）
        if (expectedSize > 0 && existing == expectedSize) {
            verify(dest, expectedSize, expectedSha256)
            onProgress(existing, expectedSize)
            return
        }
        if (expectedSize > 0 && existing > expectedSize) {
            // 本地比期望还大：残缺/错位文件，删掉重下
            dest.delete()
            existing = 0L
        }
        val request = Request.Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .header("User-Agent", "VideoNotes/1.0")
            .get()
            .build()

        val response = okHttpClient.executeCancellable(request)
        response.use { resp ->
            val total: Long
            val append: Boolean
            when (resp.code) {
                416 -> {
                    // Range 起点超出 = 本地已完整
                    verify(dest, expectedSize, expectedSha256)
                    onProgress(dest.length(), if (expectedSize > 0) expectedSize else dest.length())
                    return
                }
                206 -> {
                    append = true
                    total = resp.header("Content-Range")
                        ?.substringAfterLast('/')
                        ?.toLongOrNull()
                        ?: (existing + (resp.body?.contentLength() ?: 0L))
                }
                200 -> {
                    if (existing > 0) {
                        // 服务器不支持断点续传，删掉重下
                        dest.delete()
                        existing = 0L
                    }
                    append = false
                    total = resp.body?.contentLength()?.takeIf { it > 0 }
                        ?: expectedSize.takeIf { it > 0 }
                        ?: 0L
                }
                else -> throw IOException("下载失败（HTTP ${resp.code}），请重试")
            }
            val body = resp.body ?: throw IOException("响应体为空")
            body.byteStream().use { input ->
                FileOutputStream(dest, append).use { output ->
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    onProgress(done, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        verify(dest, expectedSize, expectedSha256)
        onProgress(dest.length(), if (expectedSize > 0) expectedSize else dest.length())
    }

    /** 完整性校验：大小精确一致（已知时）+ sha256（非空时）；不符删文件抛错。 */
    private fun verify(dest: File, expectedSize: Long, expectedSha256: String?) {
        if (expectedSize > 0 && dest.length() != expectedSize) {
            val got = dest.length()
            if (got < expectedSize) {
                throw IOException("下载不完整，请重试")
            }
            dest.delete()
            throw IOException("下载文件大小不符，已删除，请重新下载")
        }
        if (expectedSha256 != null && !Sha256.matches(dest, expectedSha256)) {
            dest.delete()
            throw IOException("模型文件校验失败（SHA-256 不匹配），已删除损坏文件，请重新下载")
        }
    }
}

/** OkHttp 同步 Call 与协程取消的桥（语义同 data.asr.executeCancellable，内核自带一份）。 */
internal suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call: Call = newCall(request)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        try {
            val response = call.execute()
            if (!cont.isActive) {
                response.close()
                cont.cancel()
            } else {
                cont.resume(response, null)
            }
        } catch (e: IOException) {
            cont.resumeWithException(e)
        }
    }
}
