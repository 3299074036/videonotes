package com.zola.videonotes.data.asr.audio

import com.zola.videonotes.data.asr.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 用 App 统一的 OkHttpClient 下载文件（音频流、视频直链等），复用超时 / 重试 / 代理配置。
 * 支持自定义请求头（B站直链需要 Referer，YouTube 需要浏览器 UA）。
 * 协程取消时会同步 cancel 底层的 Call。必须在后台线程调用（内部已切 IO）。
 *
 * M-18：先写临时文件、完成后再原子重命名。旧实现直写目标文件：下载中途被取消/
 * 断网时目标文件已存在且非空，调用方的缓存检查（exists && length > 0）会把半截
 * 文件当成完整音频复用，导致转写失败或静默错误。
 *
 * P2：断点续传 + 中断重试（Range 语义与模型下载 ModelFileDownloader 对齐）。
 * - 同一次调用内最多 3 次尝试：流中断、429 / 5xx 时退避 1s、2s 后重试；
 * - 重试带 Range（按临时文件现长续传）：206 续写、200 丢弃旧临时文件重下、
 *   416 删临时文件后重下（服务端文件比临时文件还小，旧临时文件已无意义）；
 * - 静默截断也算失败：流提前结束但字节数对不上 Content-Length / Content-Range
 *   时抛错进重试，否则半截文件会被当成完整音频发布（旧实现无此校验）；
 * - 跨调用残留的临时文件只有 URL 标记一致时才续传（.tmp.url），否则丢弃重下，
 *   防止旧 URL 的半截文件拼到新 URL 上；
 * - 取消与不可重试的 HTTP 错误（4xx 除 429）不重试；最终失败时删临时文件与标记，
 *   目标文件只在完整下载后才出现（M-18 不变）。
 */
internal suspend fun OkHttpClient.downloadFile(
    url: String,
    dest: File,
    headers: Map<String, String> = emptyMap(),
    onProgress: (Float) -> Unit = {},
): File = withContext(Dispatchers.IO) {
    dest.parentFile?.mkdirs()
    val tmp = File(dest.parentFile, dest.name + ".tmp")
    val marker = File(dest.parentFile, dest.name + ".tmp.url")
    // 跨调用残留：标记与本次 URL 不一致时，旧临时文件不能续传，直接丢弃。
    if (tmp.exists() && runCatching { marker.readText() }.getOrNull() != url) {
        tmp.delete()
        marker.delete()
    }
    if (!tmp.exists()) {
        runCatching { marker.writeText(url) }
    }
    var lastError: IOException? = null
    try {
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                attemptDownload(url, tmp, headers, onProgress)
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    marker.delete()
                    throw IOException("音频下载失败：无法发布临时文件")
                }
                marker.delete()
                return@withContext dest
            } catch (e: NonRetryableDownloadException) {
                tmp.delete()
                marker.delete()
                throw e
            } catch (e: IOException) {
                lastError = e
                // 取消必须优先于重试：流式读中断可能是用户取消
                //（OkHttp 在读中断时抛 IOException 而非 CancellationException）。
                currentCoroutineContext().ensureActive()
                if (attempt < MAX_ATTEMPTS) {
                    delay(RETRY_BACKOFF_MS * attempt)
                    continue
                }
                tmp.delete()
                marker.delete()
                throw e
            }
        }
    } catch (e: CancellationException) {
        // 取消（含退避等待中被取消）：不重试，删临时文件与标记后原样抛出。
        tmp.delete()
        marker.delete()
        throw e
    }
    tmp.delete()
    marker.delete()
    throw lastError ?: IOException("音频下载失败")
}

/** 单次下载尝试：发请求、按状态码决定续写 / 重下 / 重试 / 失败。 */
private suspend fun OkHttpClient.attemptDownload(
    url: String,
    tmp: File,
    headers: Map<String, String>,
    onProgress: (Float) -> Unit,
) {
    val existing = if (tmp.exists()) tmp.length() else 0L
    val request = Request.Builder()
        .url(url)
        .apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (existing > 0) header("Range", "bytes=$existing-")
        }
        .get()
        .build()
    val response = executeCancellable(request)
    response.use { resp ->
        when (resp.code) {
            416 -> {
                // Range 起点超出：服务端文件比临时文件还小，旧临时文件已无意义，
                // 删掉后由外层重试循环重新下载（下次尝试不带 Range）。
                tmp.delete()
                throw IOException("音频下载失败（HTTP 416），将重新下载")
            }
            206 -> {
                val total = resp.header("Content-Range")
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: (existing + (resp.body?.contentLength() ?: -1L)).takeIf { it > 0 }
                streamBody(resp, tmp, append = true, startAt = existing, total = total, onProgress = onProgress)
            }
            200 -> {
                if (existing > 0) {
                    // 服务器不支持断点续传：丢弃旧临时文件，从头下。
                    tmp.delete()
                }
                val total = resp.body?.contentLength()?.takeIf { it > 0 }
                streamBody(resp, tmp, append = false, startAt = 0L, total = total, onProgress = onProgress)
            }
            429, 500, 502, 503, 504 -> throw IOException("音频下载失败（HTTP ${resp.code}）")
            else -> throw NonRetryableDownloadException("音频下载失败（HTTP ${resp.code}）")
        }
    }
}

/** 流式写盘：读完后按已知总量校验，静默截断视为失败（进重试续传）。 */
private fun streamBody(
    resp: Response,
    tmp: File,
    append: Boolean,
    startAt: Long,
    total: Long?,
    onProgress: (Float) -> Unit,
) {
    val body = resp.body ?: throw IOException("音频内容为空")
    body.byteStream().use { input ->
        FileOutputStream(tmp, append).use { output ->
            val buf = ByteArray(64 * 1024)
            var done = startAt
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                done += n
                if (total != null && total > 0) {
                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (total != null && total > 0 && done != total) {
                throw IOException("音频下载不完整（已下 $done / 共 $total 字节），将断点续传")
            }
        }
    }
}

/** 不可重试的下载失败（4xx 除 429 等客户端错误）：外层循环直接失败，不消耗重试次数。 */
private class NonRetryableDownloadException(message: String) : IOException(message)

private const val MAX_ATTEMPTS = 3
private const val RETRY_BACKOFF_MS = 1000L
