package com.zola.videonotes.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.auth.BiliAuthStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

/**
 * B站登录页 ViewModel：
 * WebView 每次页面加载完把 Cookie 交过来，这里解析出 SESSDATA 后先调 nav 验有效性，
 * 通过才加密落盘，成功即通知界面关闭本页。
 *
 * L-08：此前 SESSDATA 存在即判登录成功并落盘，过期 SESSDATA 下设置页仍显示已登录，
 * 后续接口 -101 时用户困惑。现保存前调一次 x/web-interface/nav 验 code（-101 即未登录），
 * 无效不保存、不关闭登录页。onPageFinished 每次加载都会重推 Cookie（预登录页可能携带
 * 旧过期 Cookie），被拒时不弹窗，避免在用户还没登录时误报；用户完成登录后的新捕获
 * 会再次验证并通过。
 */
@HiltViewModel
class BiliLoginViewModel @Inject constructor(
    private val authStore: BiliAuthStore,
    private val client: OkHttpClient,
) : ViewModel() {

    /** 登录完成（凭证已保存），界面收到 true 后应关闭本页。 */
    private val _loginDone = MutableStateFlow(false)
    val loginDone: StateFlow<Boolean> = _loginDone.asStateFlow()

    /** 正在验证的 SESSDATA：同一 Cookie 重复推送时去重，避免重复发 nav。 */
    private var validatingSessData: String? = null

    /**
     * WebView 侧捕获到 Cookie 字符串后调用。
     * SESSDATA 缺失时直接忽略；拿到后异步验 nav，通过才保存并关闭登录页。
     * 唯一调用方 BiliLoginScreen 不用返回值（fire-and-forget）。
     */
    fun onCookiesCaptured(cookieHeader: String?) {
        if (_loginDone.value) return
        val (sessData, biliJct, uid) = BiliAuthStore.parseBiliCookies(cookieHeader)
        if (sessData.isNullOrBlank()) return
        if (validatingSessData == sessData) return
        validatingSessData = sessData
        viewModelScope.launch {
            try {
                // L-08：保存前验 nav。统一客户端不含 B站 Cookie 拦截器，显式 Cookie 头
                // 即为本次捕获的新凭证，不会被旧存量覆盖；凭证本身不打日志。
                val valid = withContext(Dispatchers.IO) { checkNavValid(sessData) }
                if (valid) {
                    authStore.save(sessData, biliJct.orEmpty(), uid.orEmpty())
                    _loginDone.value = true
                }
                // 无效时不保存、不关闭：用户仍在登录页，完成登录后的新捕获会再次验证。
            } finally {
                if (validatingSessData == sessData) validatingSessData = null
            }
        }
    }

    /**
     * 调 x/web-interface/nav 验 SESSDATA 有效性：code == 0 为已登录，-101 未登录，
     * 其他 code / 网络异常一律按无效处理（fail-closed，登录页不关闭，用户可重试）。
     */
    private fun checkNavValid(sessData: String): Boolean {
        val request = Request.Builder()
            .url("https://api.bilibili.com/x/web-interface/nav")
            .header("User-Agent", BILIBILI_UA)
            .header("Cookie", "SESSDATA=$sessData")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val code = response.body?.string()?.let { JSONObject(it).optInt("code", -1) } ?: -1
                return code == 0
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return false
        }
    }

    companion object {
        private const val BILIBILI_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
