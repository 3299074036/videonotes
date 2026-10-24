package com.zola.videonotes.data.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 当前网络是否为 Wi-Fi / 以太网（自两个下载器原 isWifiConnected 合并）。 */
object NetworkGate {

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

/**
 * 移动网络大文件下载的一次性确认（WP4 / 设计 §5.1）：
 * 「仅 Wi-Fi」开关关闭时，单文件 > 500 MB 在移动网络下首次下载需用户确认；
 * 确认状态只存内存（进程内有效，不落盘），按条目 id 记。
 */
@Singleton
class MobileDownloadConsent @Inject constructor() {

    private val granted = ConcurrentHashMap.newKeySet<String>()

    /** 该条目在移动网络下载 [sizeBytes] 是否还需要确认。 */
    fun needsConsent(modelId: String, sizeBytes: Long): Boolean =
        sizeBytes > THRESHOLD_BYTES && modelId !in granted

    fun grant(modelId: String) {
        granted += modelId
    }

    companion object {
        const val THRESHOLD_BYTES: Long = 500L * 1024 * 1024
    }
}
