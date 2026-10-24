package com.zola.videonotes.data.auth

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * YouTube 登录态拦截器：已导入 cookie 时，给 YouTube 相关域名的请求自动带上 Cookie。
 *
 * - 只处理 [YoutubeCookieStore.isYoutubeHost] 为真的 host，其他域名原样放行；
 * - 与请求已有的 Cookie 头合并，不覆盖；已有 SID= 时视为已带登录态，不再追加；
 * - 凭证读取走加密存储的同步读（拦截器本就运行在 OkHttp 后台线程）；
 * - 本拦截器不打任何日志，cookie 永不进日志。
 */
class YoutubeCookieInterceptor @Inject constructor(
    private val cookieStore: YoutubeCookieStore,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!YoutubeCookieStore.isYoutubeHost(request.url.host)) {
            return chain.proceed(request)
        }
        val imported = cookieStore.cookie() ?: return chain.proceed(request)

        val existing = request.header(HEADER_COOKIE)
        val merged = when {
            existing.isNullOrBlank() -> imported
            existing.contains("$KEY_SID=") -> existing
            else -> "$existing; $imported"
        }
        return chain.proceed(
            request.newBuilder().header(HEADER_COOKIE, merged).build(),
        )
    }

    companion object {
        private const val HEADER_COOKIE = "Cookie"
        private const val KEY_SID = "SID"
    }
}
