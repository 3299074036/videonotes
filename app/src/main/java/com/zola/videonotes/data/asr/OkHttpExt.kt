package com.zola.videonotes.data.asr

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resumeWithException

/**
 * 桥接 OkHttp 同步 Call 与协程取消：协程取消时同步 cancel 底层的 Call，
 * 避免阻塞在 read() 里无法响应暂停/删除操作。
 */
internal suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call = newCall(request)
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        try {
            val response = call.execute()
            // 若等待期间已被取消，关闭响应并抛 CancellationException，保证上层走取消分支
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
