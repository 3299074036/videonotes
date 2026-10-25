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
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/126.0.0.0 Mobile Safari/537.36"

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

/** B站登录页协议/按钮重叠的运行时修正脚本（见 onPageFinished 注释）。 */
private const val BILI_OVERLAP_FIX_JS = """
(function(){
  function fix(){
    try{
      // 协议文案块的整体矩形：收集含协议关键词的文本节点父元素的并集矩形
      var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
      var node, top = Infinity, bottom = -Infinity, found = false;
      while(node = walker.nextNode()){
        var t = node.nodeValue || '';
        if(t.indexOf('我已阅读并同意') >= 0 || t.indexOf('未注册绑定的手机号') >= 0){
          var r = node.parentElement.getBoundingClientRect();
          if(r.bottom > 0){ top = Math.min(top, r.top); bottom = Math.max(bottom, r.bottom); found = true; }
        }
      }
      if(!found) return;
      // 「登录」按钮：取离协议块底部最近的一个（排除页眉等远处的同名按钮）。
      // 按钮可能是 button / a / div（B站移动端表单有用 div 做按钮的情况），且只认最外层
      // 文本恰为“登录”的元素（内层 span 文本也是“登录”，推它移不动按钮本身）。
      var best = null, bestDist = Infinity;
      document.querySelectorAll('button, a, div, span').forEach(function(el){
        var txt = (el.innerText || '').trim();
        if(txt !== '登录') return;
        var p = el.parentElement;
        if(p && (p.innerText || '').trim() === '登录') return;
        var br = el.getBoundingClientRect();
        if(br.width === 0 || br.height === 0) return;
        var d = Math.abs(br.top - bottom);
        if(d < bestDist){ bestDist = d; best = el; }
      });
      if(!best) return;
      var br = best.getBoundingClientRect();
      if(br.top < bottom + 8){
        var shift = Math.ceil(bottom - br.top + 16);
        var pos = getComputedStyle(best).position;
        if(pos === 'absolute' || pos === 'fixed'){
          var cur = parseFloat(getComputedStyle(best).top);
          best.style.top = ((isNaN(cur) ? br.top : cur) + shift) + 'px';
        } else {
          var m = parseFloat(getComputedStyle(best).marginTop) || 0;
          best.style.marginTop = (m + shift) + 'px';
        }
      }
    }catch(e){}
  }
  fix();
  // 单页应用会晚渲染、切标签、输手机号时重排版：一次性修必漏。改成 DOM 变化时防抖重修
  // （fix 本身幂等：推到位后再次测量不再重叠即不再推），WebView 关闭时随页面一起销毁。
  var timer = null;
  var obs = new MutationObserver(function(){
    if(timer) clearTimeout(timer);
    timer = setTimeout(fix, 300);
  });
  obs.observe(document.body, {childList:true, subtree:true, attributes:true, characterData:true});
  window.addEventListener('resize', function(){ setTimeout(fix, 300); });
})();
"""

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
    // 登录页协议文字与登录按钮重叠的修法：B站登录页是 Vue 单页应用，按钮位置按布局量出；
    // 系统字体放大时协议文字多换行会撑出定高容器、压到按钮上，且页面量布局的时机可能早于视口稳定。
    // textZoom=100 让页内字体不受系统字体缩放影响；加载完派发 resize 让 SPA 重算一次布局。
    settings.textZoom = 100
    // B站登录页是按移动端视口排版的 H5，不开这两个会导致元素错位重叠
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    settings.userAgentString = MOBILE_CHROME_UA
    // 部分页面依赖 WebChromeClient 处理 JS 对话框/console，不设置会导致静默失败
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onPageLoadingChanged(false)
            // 让单页应用按最终视口重算一次布局（协议区与登录按钮错位多因此而来）
            view?.evaluateJavascript("window.dispatchEvent(new Event('resize'));", null)
            // 协议文字与登录按钮重叠的兜底修正：静态查过 B站登录页——视口声明是标准 device-width，
            // 样式在 JS 包里（CSS 模块/内联样式），靠改 WebView 配置修不动。改用运行时实测实修：
            // 找到协议文案块与「登录」按钮，实测两者矩形，若重叠就把按钮往下推到不重叠为止。
            // 按文本找元素（不依赖 B站随时会变的类名）；失败时静默（JS 异常不影响 App）。
            view?.evaluateJavascript(BILI_OVERLAP_FIX_JS, null)
            // 每次页面加载完检查登录态：B站登录成功后会写入 SESSDATA
            val cookies = CookieManager.getInstance().getCookie(COOKIE_URL)
            onCookiesChanged(cookies)
        }
    }
}
