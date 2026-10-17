package com.zola.videonotes.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * 网络重试拦截器。
 *
 * 对 GET 请求的 IO 异常（超时/断网）与 502/503/504 做有限次重试，
 * 其他 4xx/业务错误直接返回，不重试。
 */
class RetryInterceptor(private val maxRetries: Int = 2) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
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
                lastError = e
                if (attempt == maxRetries) throw e
            }
        }
        throw lastError ?: IOException("网络请求失败，请检查网络后重试")
    }
}
