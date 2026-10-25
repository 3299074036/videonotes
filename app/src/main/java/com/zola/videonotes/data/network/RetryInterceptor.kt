package com.zola.videonotes.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 网络重试拦截器。
 *
 * 只对 GET 请求的 IO 异常（超时/断网）与 502/503/504 做有限次重试，指数退避；
 * POST 不重试（ttwid 注册、微博 fid 等重复提交有副作用）；被取消的请求立即抛，不重试；
 * 其他 4xx/业务错误直接返回，不重试。
 */
class RetryInterceptor(private val maxRetries: Int = 2) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // H-03/L-07：只对 GET 重试；POST 重试会重复提交。
        if (request.method != "GET") return chain.proceed(request)
        var lastError: IOException? = null
        repeat(maxRetries + 1) { attempt ->
            try {
                val response = chain.proceed(request)
                // 仅对网关类 5xx 做重试；重试前关闭旧响应体避免泄漏
                if (response.code !in setOf(502, 503, 504) || attempt == maxRetries) {
                    return response
                }
                response.close()
            } catch (e: IOException) {
                // 取消的请求立即抛，不重试（OkHttp 取消时抛 IOException("Canceled")）。
                if (chain.call().isCanceled() || e.message == "Canceled") throw e
                lastError = e
                if (attempt == maxRetries) throw e
            }
            // 指数退避：200ms → 400ms，避免对故障网关立即连发。
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(200L shl attempt)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("重试被中断", ie)
                }
            }
        }
        throw lastError ?: IOException("网络请求失败，请检查网络后重试")
    }
}
