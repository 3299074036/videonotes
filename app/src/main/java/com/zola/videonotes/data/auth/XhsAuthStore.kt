package com.zola.videonotes.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小红书登录凭证加密存储（EncryptedSharedPreferences，AES256，照 B站那套）。
 *
 * - 用户在内嵌的小红书官方登录页完成登录，App 只从 WebView Cookie 里捕获
 *   `web_session`，密码不经过本应用；
 * - ★ `web_session` 只能说明「浏览器有过会话」，不能说明「用户已登录」
 *   （小红书网页端 JS 启动后会自己种一个游客态的 web_session）；
 *   真正的登录态判定走行为探测（见 XhsLoginViewModel：带 Cookie 请求 /explore，
 *   未登录会被 302 到登录墙），这里只存原始凭证；
 * - 文件位于应用私有目录，随应用卸载自动清除。
 */
@Singleton
class XhsAuthStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val _isLoggedIn = MutableStateFlow(isSavedLoggedIn())
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    /** 同步读取 web_session 凭证，未保存时返回 null。调用方应在 IO 线程使用。 */
    fun webSession(): String? = prefs.getString(KEY_WEB_SESSION, null)?.takeIf { it.isNotBlank() }

    /** 保存登录凭证（覆盖旧值）。 */
    fun save(webSession: String) {
        prefs.edit().putString(KEY_WEB_SESSION, webSession.trim()).apply()
        _isLoggedIn.value = webSession.isNotBlank()
    }

    /** 清除全部登录凭证（退出登录）。 */
    fun clear() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
    }

    private fun isSavedLoggedIn(): Boolean = webSession() != null

    companion object {
        private const val FILE_NAME = "xhs_auth"
        private const val KEY_WEB_SESSION = "web_session"

        /** 小红书桌面 UA：登录页与解析页都必须用桌面 UA（移动 UA 下 /login 是 404，解析页也下发不全）。 */
        const val XHS_DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        /** 从 WebView Cookie 头里解析 web_session（游客态的也照样解析出值，登录态另由行为探测判定）。 */
        fun parseWebSession(cookieHeader: String?): String? {
            if (cookieHeader.isNullOrBlank()) return null
            for (part in cookieHeader.split(";")) {
                val kv = part.split("=", limit = 2)
                if (kv.size != 2) continue
                if (kv[0].trim() == "web_session") {
                    return kv[1].trim().takeIf { it.isNotEmpty() }
                }
            }
            return null
        }
    }
}
