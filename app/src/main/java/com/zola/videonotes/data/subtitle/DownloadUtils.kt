package com.zola.videonotes.data.subtitle

import com.zola.videonotes.data.parser.stringCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 用 App 统一的 OkHttpClient 下载文本内容（字幕文件等），复用超时 / 重试 / 代理配置。
 * 必须在 IO 线程调用。
 */
internal suspend fun OkHttpClient.downloadText(url: String): String = withContext(Dispatchers.IO) {
    val request = Request.Builder().url(url).get().build()
    newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("字幕文件下载失败（HTTP ${response.code}）")
        }
        response.body?.stringCapped()?.takeIf { it.isNotBlank() }
            ?: throw IOException("字幕文件内容为空")
    }
}
