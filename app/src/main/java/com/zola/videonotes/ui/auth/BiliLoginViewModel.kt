package com.zola.videonotes.ui.auth

import androidx.lifecycle.ViewModel
import com.zola.videonotes.data.auth.BiliAuthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * B站登录页 ViewModel：
 * WebView 每次页面加载完把 Cookie 交过来，这里解析出 SESSDATA 后加密落盘，
 * 成功即通知界面关闭本页。
 */
@HiltViewModel
class BiliLoginViewModel @Inject constructor(
    private val authStore: BiliAuthStore,
) : ViewModel() {

    /** 登录完成（凭证已保存），界面收到 true 后应关闭本页。 */
    private val _loginDone = MutableStateFlow(false)
    val loginDone: StateFlow<Boolean> = _loginDone.asStateFlow()

    /**
     * WebView 侧捕获到 Cookie 字符串后调用。
     * @return 是否成功保存（拿到 SESSDATA 即视为登录成功）。
     */
    fun onCookiesCaptured(cookieHeader: String?): Boolean {
        if (_loginDone.value) return true
        val (sessData, biliJct, uid) = BiliAuthStore.parseBiliCookies(cookieHeader)
        if (sessData.isNullOrBlank()) return false
        authStore.save(sessData, biliJct.orEmpty(), uid.orEmpty())
        _loginDone.value = true
        return true
    }
}
