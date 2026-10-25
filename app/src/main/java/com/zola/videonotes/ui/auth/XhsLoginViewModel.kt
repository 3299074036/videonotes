package com.zola.videonotes.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.auth.XhsAuthStore
import com.zola.videonotes.data.parser.isLoginWallUrl
import com.zola.videonotes.data.auth.XhsAuthStore.Companion.XHS_DESKTOP_UA
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * 小红书登录页 ViewModel：
 * WebView 每次页面加载完把 Cookie 交过来，这里解析出 web_session 后做**行为探测**再落盘，
 * 成功即通知界面关闭本页。
 *
 * ★ 必须行为探测、不能只看 Cookie 名字：小红书网页端 JS 启动后会自己种一个游客态的
 *   `web_session`，光有它不代表已登录。探测口径与解析同源：带 Cookie 请求首页 /explore，
 *   未登录会被 302 到 /login（或 website-login / captcha），登录态则正常返回内容页。
 */
@HiltViewModel
class XhsLoginViewModel @Inject constructor(
    private val authStore: XhsAuthStore,
    private val client: OkHttpClient,
) : ViewModel() {

    /** 登录完成（凭证已保存），界面收到 true 后应关闭本页。 */
    private val _loginDone = MutableStateFlow(false)
    val loginDone: StateFlow<Boolean> = _loginDone.asStateFlow()

    private var probing = false

    /**
     * WebView 侧捕获到 Cookie 字符串后调用。
     * 有 web_session 且未在探测中时做一次行为探测；探测通过才保存并关闭。
     */
    fun onCookiesCaptured(cookieHeader: String?) {
        if (_loginDone.value || probing) return
        val webSession = XhsAuthStore.parseWebSession(cookieHeader) ?: return
        probing = true
        viewModelScope.launch {
            val loggedIn = withContext(Dispatchers.IO) { probeLoggedIn(webSession) }
            probing = false
            if (loggedIn) {
                authStore.save(webSession)
                _loginDone.value = true
            }
        }
    }

    /** 行为探测：带 Cookie 请求 /explore，最终地址命中登录墙片段即未登录。 */
    private fun probeLoggedIn(webSession: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("https://www.xiaohongshu.com/explore")
                .header("User-Agent", XHS_DESKTOP_UA)
                .header("Cookie", "web_session=$webSession")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()
                !isLoginWallUrl(finalUrl)
            }
        } catch (t: Throwable) {
            false
        }
    }
}
