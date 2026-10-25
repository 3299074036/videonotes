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
import com.zola.videonotes.data.auth.XhsAuthStore.Companion.XHS_DESKTOP_UA

/** 小红书官方登录页（直指 /login，别用站点根地址：根地址会被 302 到信息流再叠登录弹窗，手机上看到的是被裁切的信息流）。 */
private const val LOGIN_URL = "https://www.xiaohongshu.com/login"

/** 读取登录 Cookie 的域名（小红书登录成功后把 web_session 写到 .xiaohongshu.com 域下）。 */
private const val COOKIE_URL = "https://www.xiaohongshu.com"

/**
 * 小红书登录页：内嵌小红书官方登录页，账号密码只在小红书页面输入。
 *
 * 登录成功后小红书会往 WebView 写 web_session Cookie，本页捕获后经行为探测确认
 * 真登录态再加密存本地，自动关闭。退出登录走设置页「小红书账号」分组。
 *
 * ★ 两个 YunX 经验直接落在这里：
 * - 必须用桌面 UA：移动 UA 下 /login 是 404「你访问的页面不见了」；
 * - 窄屏会把登录表单裁掉：页面加载完把 viewport 覆盖成 1280 桌面宽度再派发 resize，
 *   让整页（含确认按钮）都渲染出来，用户双指缩放 / 左右拖动使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XhsLoginScreen(
    onBack: () -> Unit,
    viewModel: XhsLoginViewModel = hiltViewModel(),
) {
    val loginDone by viewModel.loginDone.collectAsStateWithLifecycle()
    var pageLoading by remember { mutableStateOf(true) }

    LaunchedEffect(loginDone) {
        if (loginDone) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小红书登录") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        initXhsLoginWebView(
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

/** 初始化登录 WebView：允许 JS / DOM 存储 / Cookie（含第三方 Cookie，登录态写入需要）。 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.initXhsLoginWebView(
    onPageLoadingChanged: (Boolean) -> Unit,
    onCookiesChanged: (String?) -> Unit,
) {
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        // xiaohongshu.com 把登录态写到 .xiaohongshu.com 域下，属跨站写 Cookie
        cookieManager.setAcceptThirdPartyCookies(this, true)
    }
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    // B站登录页的教训：页内字体不受系统字体缩放影响，避免协议区与按钮错位重叠
    settings.textZoom = 100
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    // 桌面 UA：移动 UA 下 /login 是 404
    settings.userAgentString = XHS_DESKTOP_UA
    // 部分页面依赖 WebChromeClient 处理 JS 对话框/console，不设置会导致静默失败
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onPageLoadingChanged(false)
            // 窄屏裁表单的修法：把 viewport 覆盖成 1280 桌面宽度并派发 resize，
            // 让单页应用按桌面布局重算（含确认按钮完整渲染出来）
            view?.evaluateJavascript(
                "(function(){var c='width=1280,minimum-scale=0.2,maximum-scale=5.0,user-scalable=yes';" +
                    "var m=document.querySelector('meta[name=\"viewport\"]');" +
                    "if(m){m.setAttribute('content',c);}else{var n=document.createElement('meta');" +
                    "n.name='viewport';n.content=c;document.head.appendChild(n);}" +
                    "window.dispatchEvent(new Event('resize'));})()",
                null,
            )
            // 每次页面加载完检查登录态：小红书登录成功后会写入 web_session
            val cookies = CookieManager.getInstance().getCookie(COOKIE_URL)
            onCookiesChanged(cookies)
        }
    }
}
