package com.zola.videonotes.data.asr.audio

import com.zola.videonotes.data.asr.executeCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 用 App 统一的 OkHttpClient 下载文件（音频流等），复用超时 / 重试 / 代理配置。
 * 支持自定义请求头（B站直链需要 Referer，YouTube 需要浏览器 UA）。
 * 协程取消时会同步 cancel 底层的 Call。必须在后台线程调用（内部已切 IO）。
 */
internal suspend fun OkHttpClient.downloadFile(
    url: String,
    dest: File,
    headers: Map<String, String> = emptyMap(),
    onProgress: (Float) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .get()
        .build()
    val response = executeCancellable(request)
    response.use { resp ->
        if (!resp.isSuccessful) throw IOException("音频下载失败（HTTP ${resp.code}）")
        val body = resp.body ?: throw IOException("音频内容为空")
        val total = body.contentLength().takeIf { it > 0 }
        body.byteStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total != null) {
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }
    dest
}
