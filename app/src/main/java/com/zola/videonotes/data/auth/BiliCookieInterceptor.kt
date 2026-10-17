package com.zola.videonotes.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * B站登录态拦截器：已登录时，给 bilibili.com 域名的请求自动带上 SESSDATA Cookie。
 *
 * - 只处理 host 以 bilibili.com 结尾的请求，其他域名原样放行；
 * - 与请求已有的 Cookie 头合并，不覆盖；
 * - 凭证读取走加密存储的同步读（拦截器本就运行在 OkHttp 后台线程）。
 */
class BiliCookieInterceptor @Inject constructor(
    private val authStore: BiliAuthStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.host.endsWith(BILIBILI_HOST_SUFFIX)) {
            return chain.proceed(request)
        }
        val sessData = authStore.sessData() ?: return chain.proceed(request)

        val loginCookie = "SESSDATA=$sessData"
        val existing = request.header(HEADER_COOKIE)
        val merged = when {
            existing.isNullOrBlank() -> loginCookie
            existing.contains("$KEY_SESSDATA=") -> existing
            else -> "$existing; $loginCookie"
        }
        return chain.proceed(
            request.newBuilder().header(HEADER_COOKIE, merged).build(),
        )
    }

    companion object {
        private const val BILIBILI_HOST_SUFFIX = "bilibili.com"
        private const val HEADER_COOKIE = "Cookie"
        private const val KEY_SESSDATA = "SESSDATA"
    }
}
