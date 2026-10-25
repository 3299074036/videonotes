package com.zola.videonotes.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** B站官方登录页。 */
private const val LOGIN_URL = "https://passport.bilibili.com/login"

/** 读取登录 Cookie 的域名（B站登录成功后把 SESSDATA 写到 .bilibili.com 域下）。 */
private const val COOKIE_URL = "https://www.bilibili.com"

/** 移动端 Chrome UA：B站登录页对桌面 UA 展示不一致，统一用移动端。 */

/**
 * B站登录页：内嵌 B站官方登录页，账号密码只在 B站页面输入。
 *
 * 登录成功后 B站会往 WebView 写 SESSDATA Cookie，本页捕获后加密存本地，
 * 自动关闭。退出登录走设置页「B站账号」分组。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliLoginScreen(
    onBack: () -> Unit,
    viewModel: BiliLoginViewModel = hiltViewModel(),
) {
    val loginDone by viewModel.loginDone.collectAsStateWithLifecycle()
    var pageLoading by remember { mutableStateOf(true) }

    LaunchedEffect(loginDone) {
        if (loginDone) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("B站登录") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "关闭",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            initBiliLoginWebView(
                                onPageLoadingChanged = { loading ->
                                    pageLoading = loading
                                },
                                onCookiesChanged = { cookies ->
                                    viewModel.onCookiesCaptured(cookies)
                                },
                            )
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxSize(),
                )
                if (pageLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

/** 初始化登录 WebView：允许 JS / DOM 存储 / Cookie（含第三方 Cookie，登录态写入需要）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.initBiliLoginWebView(
    onPageLoadingChanged: (Boolean) -> Unit,
    onCookiesChanged: (String?) -> Unit,
) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // passport.bilibili.com 把登录态写到 .bilibili.com 域下，属跨站写 Cookie
        cookieManager.setAcceptThirdPartyCookies(this, true)
    }
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    // textZoom=100 即 Android 默认值，保留仅作显式声明（JKVideo 的 RN WebView 同为默认设置）。
    settings.textZoom = 100
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    // 部分页面依赖 WebChromeClient 处理 JS 对话框/console，不设置会导致静默失败
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onPageLoadingChanged(false)
            // 每次页面加载完检查登录态：B站登录成功后会写入 SESSDATA
            val cookies = CookieManager.getInstance().getCookie(COOKIE_URL)
            onCookiesChanged(cookies)
        }
    }
}
