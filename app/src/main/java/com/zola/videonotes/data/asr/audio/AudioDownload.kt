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
 *
 * M-18：先写临时文件、完成后再原子重命名。旧实现直写目标文件：下载中途被取消/
 * 断网时目标文件已存在且非空，调用方的缓存检查（exists && length > 0）会把半截
 * 文件当成完整音频复用，导致转写失败或静默错误。
 */
internal suspend fun OkHttpClient.downloadFile(
    url: String,
    dest: File,
    headers: Map<String, String> = emptyMap(),
    onProgress: (Float) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    tmp.delete()
    val request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .get()
        .build()
    try {
        val response = executeCancellable(request)
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("音频下载失败（HTTP ${resp.code}）")
            val body = resp.body ?: throw IOException("音频内容为空")
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
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
    } catch (e: Exception) {
        // 失败（含取消）不留半截临时文件；目标文件只在完整下载后才出现。
        tmp.delete()
        throw e
    }
    // 原子发布：同目录 rename；失败则删临时文件并抛错，不留半截目标文件。
    if (!tmp.renameTo(dest)) {
        tmp.delete()
        throw IOException("音频下载失败：无法发布临时文件")
    }
    dest
}
