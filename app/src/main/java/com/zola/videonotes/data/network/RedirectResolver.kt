package com.zola.videonotes.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 短链跳转追踪器：b23.tv / v.douyin.com / xhslink.com 等分享短链统一走这里还原。
 *
 * 手动逐跳读取 Location（关闭自动跟随），避免下载完整页面浪费流量。
 */
@Singleton
class RedirectResolver @Inject constructor(
    private val client: OkHttpClient,
) {

    suspend fun resolve(startUrl: String, maxHops: Int = 5): String = withContext(Dispatchers.IO) {
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        var current = startUrl
        repeat(maxHops) {
            val request = Request.Builder()
                .url(current)
                .header("User-Agent", MOBILE_UA)
                .get()
                .build()
            noRedirectClient.newCall(request).execute().use { response ->
                if (response.code !in 300..399) return@withContext current
                val location = response.header("Location") ?: return@withContext current
                val base = current.toHttpUrlOrNull() ?: return@withContext current
                // 兼容相对路径 Location
                current = base.resolve(location)?.toString() ?: return@withContext current
            }
        }
        current
    }

    companion object {
        /** 移动端 UA：短链服务对桌面 UA 可能返回不同的跳转结果。 */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
