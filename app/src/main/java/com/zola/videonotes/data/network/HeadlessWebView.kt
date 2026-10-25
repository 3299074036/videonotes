package com.zola.videonotes.data.network

import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 无头 WebView：用真实浏览器引擎加载页面，跑完 JS 挑战/渲染后提取数据。
 *
 * 适用场景：纯 HTTP 被 JS 挑战挡住的平台（抖音 `byted_acrawler`）。
 * 原理不是绕过风控，而是像正常浏览器一样把 JS 跑完，再从 DOM/JS 变量里取数据。
 *
 * 指纹一致性（缺一个就可能被服务端判定为伪装环境、不给数据）：
 * 1. UA 与 Client Hints 对齐：只改 UA 不改 `Sec-CH-UA`，服务端看到自相矛盾的指纹；
 * 2. `navigator.platform` 覆盖：Android WebView 的 platform 恒为 `Linux armv8l`，
 *    与桌面 Chrome UA 不一致时会被风控标记，须在文档开始前注入脚本覆盖 getter；
 * 3. 调用方脚本（如 XHR 钩子）同样须在文档开始前注入，否则页面自身的首批请求已发出。
 */
object HeadlessWebView {

    /** 最近一次失败原因（供调用方拼具体错误信息）：NO_ACTIVITY / TIMEOUT / EXTRACT_EMPTY / EXTRACT_NULL。 */
    @Volatile
    var lastFailure: String? = null
        private set

    /**
     * 加载页面并提取数据。
     *
     * @param url 目标页面 URL
     * @param userAgent 浏览器 UA（桌面 Chrome 或移动 Chrome）
     * @param readyCheckJs 就绪判定脚本，返回 "true" 时认为页面数据已就绪
     * @param extractJs 提取脚本，返回 JSON 字符串
     * @param timeoutMs 总超时（抖音冷启动约 25 秒，默认 40 秒）
     * @param extraDocumentStartJs 文档开始前注入的脚本（XHR 钩子等）
     * @return 提取脚本返回的 JSON 字符串，失败/超时/无前台 Activity 时返回 null（原因见 [lastFailure]）
     */
    suspend fun loadAndExtract(
        url: String,
        userAgent: String,
        readyCheckJs: String,
        extractJs: String,
        timeoutMs: Long = 40_000L,
        extraDocumentStartJs: List<String> = emptyList(),
    ): String? = withContext(Dispatchers.Main.immediate) {
        lastFailure = null
        val activity = HeadlessWebViewTracker.currentActivity()
        if (activity == null) {
            lastFailure = "NO_ACTIVITY"
            return@withContext null
        }
        val started = System.currentTimeMillis()
        val webView = WebView(activity)
        // 1×1 + 全透明：满足「必须挂上窗口」，又不占视觉
        activity.addContentView(webView, ViewGroup.LayoutParams(1, 1))
        webView.alpha = 0f
        try {
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = userAgent
                alignClientHintsWith(userAgent)
            }
            // navigator.platform 覆盖 + 调用方脚本：必须在页面自身脚本之前跑
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                platformNameOf(userAgent)?.let { platformName ->
                    val platformJs = "(function(){try{Object.defineProperty(navigator,'platform'," +
                        "{get:function(){return '$platformName';},configurable:true});}catch(e){}})()"
                    WebViewCompat.addDocumentStartJavaScript(webView, platformJs, setOf("*"))
                }
                extraDocumentStartJs.forEach { script ->
                    WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                }
            }
            webView.webViewClient = WebViewClient()
            webView.loadUrl(url)
            val deadline = started + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                delay(350L)
                if (webView.evaluate(readyCheckJs) == "true") {
                    val extracted = webView.evaluate(extractJs)
                    if (extracted.isNullOrBlank()) {
                        lastFailure = "EXTRACT_EMPTY"
                        return@withContext null
                    }
                    return@withContext extracted
                }
            }
            lastFailure = "TIMEOUT"
            null
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
            runCatching { webView.destroy() }
        }
    }

    /** UA 对应的 navigator.platform 取值；移动 UA 不用覆盖（WebView 默认值即一致）。 */
    private fun platformNameOf(userAgent: String): String? = when {
        userAgent.contains("Windows") -> "Win32"
        userAgent.contains("Mac OS X") -> "MacIntel"
        else -> null
    }

    /**
     * Client Hints 对齐：按 UA 里的 Chrome 大版本生成 UserAgentMetadata，
     * 使 `Sec-CH-UA` 与 UA 的品牌/版本/平台/机型保持一致。
     * 不支持 USER_AGENT_METADATA 的设备上静默跳过。
     */
    private fun android.webkit.WebSettings.alignClientHintsWith(userAgent: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val major = Regex("Chrome/(\\d+)").find(userAgent)?.groupValues?.get(1) ?: "131"
        val full = "$major.0.0.0"
        val brandVersions = listOf(
            Triple("Google Chrome", major, full),
            Triple("Chromium", major, full),
            Triple("Not_A Brand", "24", "24.0.0.0"),
        ).map { (brand, brandMajor, brandFull) ->
            UserAgentMetadata.BrandVersion.Builder()
                .setBrand(brand)
                .setMajorVersion(brandMajor)
                .setFullVersion(brandFull)
                .build()
        }
        val isAndroid = userAgent.contains("Android")
        val isIos = userAgent.contains("iPhone") || userAgent.contains("iPad")
        val metadata = UserAgentMetadata.Builder()
            .setBrandVersionList(brandVersions)
            .setFullVersion(full)
            .setPlatform(if (isAndroid) "Android" else if (isIos) "iOS" else "Windows")
            .setPlatformVersion(if (isAndroid) "14.0.0" else if (isIos) "17.0.0" else "10.0.0")
            .setArchitecture(if (isAndroid || isIos) "" else "x86")
            .setModel(if (isAndroid) "Pixel 8" else "")
            .setMobile(isAndroid || isIos || userAgent.contains("Mobile"))
            .setWow64(false)
            .build()
        WebSettingsCompat.setUserAgentMetadata(this, metadata)
    }

    /** 在 WebView 上执行 JS，返回字符串结果（JSON 值已解码）；3 秒超时返回 null。 */
    private suspend fun WebView.evaluate(script: String): String? =
        withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                evaluateJavascript(script, ValueCallback { value ->
                    if (cont.isActive) cont.resume(value)
                })
            }
        }?.let(::decodeJsonValue)

    /** evaluateJavascript 返回的 JSON 编码值解码为原始字符串。 */
    private fun decodeJsonValue(value: String): String? {
        if (value == "null") return null
        // JS 返回字符串时回调值是 JSON 字符串字面量（如 "hello"），需解析还原；
        // 返回布尔/数字时直接返回原值（如 true）。
        return if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            runCatching { org.json.JSONTokener(value).nextValue() as? String }.getOrNull()
                ?: value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}
