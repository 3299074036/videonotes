package com.zola.videonotes.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.InetAddress
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
                if (response.code !in 300..399) return@withContext checkRedirectTarget(current)
                val location = response.header("Location") ?: return@withContext checkRedirectTarget(current)
                val base = current.toHttpUrlOrNull() ?: return@withContext checkRedirectTarget(current)
                // 兼容相对路径 Location
                current = base.resolve(location)?.toString() ?: return@withContext checkRedirectTarget(current)
            }
        }
        checkRedirectTarget(current)
    }

    companion object {
        /** 移动端 UA：短链服务对桌面 UA 可能返回不同的跳转结果。 */
        const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * L-09：终跳目标校验（RedirectResolver 与快手 expandRedirects 共用，调用方都在 IO 线程）。
 * 终跳非 https 时拒绝（凭证不得走明文，如小红书 web_session）；终跳 host 解析为回环 /
 * 内网 / 链路本地 / 组播地址时拒绝（防 Location 指向内网服务）；host 为空或 DNS 解析
 * 失败时同样拒绝（fail-closed：后续抓取本来也会失败，早拒早报错）。
 * 报错只带 host，不带完整 URL（查询串可能含 xsec_token 等凭证，不得进诊断文案）。
 * @return 原 url（校验通过时），调用方直接用返回值继续。
 * @throws IOException 终跳不合法时。
 */
internal fun checkRedirectTarget(url: String): String {
    val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("跳转目标地址非法")
    if (httpUrl.scheme != "https") {
        throw IOException("跳转目标不是 https，拒绝继续：${httpUrl.host}")
    }
    val address = runCatching { InetAddress.getByName(httpUrl.host) }.getOrNull()
        ?: throw IOException("跳转目标域名无法解析：${httpUrl.host}")
    if (address.isLoopbackAddress || address.isSiteLocalAddress ||
        address.isLinkLocalAddress || address.isMulticastAddress || address.isAnyLocalAddress
    ) {
        throw IOException("跳转目标为内网地址，拒绝继续：${httpUrl.host}")
    }
    return url
}
