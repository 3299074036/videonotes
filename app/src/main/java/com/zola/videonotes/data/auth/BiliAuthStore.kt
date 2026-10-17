package com.zola.videonotes.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B站登录凭证加密存储（EncryptedSharedPreferences，AES256）。
 *
 * - 用户在内嵌的 B站官方登录页完成登录，App 只从 WebView Cookie 里捕获
 *   SESSDATA / bili_jct / DedeUserID，密码不经过本应用；
 * - 凭证只进加密存储，不打 log、不做内存明文缓存（CookieManager 自身除外）；
 * - 文件位于应用私有目录，随应用卸载自动清除。
 */
@Singleton
class BiliAuthStore @Inject constructor(
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

    private val _uid = MutableStateFlow(savedUid())
    val uid: StateFlow<String?> = _uid.asStateFlow()

    /** 同步读取 SESSDATA，未登录时返回 null。调用方应在 IO 线程使用。 */
    fun sessData(): String? = savedSessData()

    /** 保存登录凭证（覆盖旧值）。 */
    fun save(sessData: String, biliJct: String, uid: String) {
        prefs.edit()
            .putString(KEY_SESSDATA, sessData.trim())
            .putString(KEY_BILI_JCT, biliJct.trim())
            .putString(KEY_UID, uid.trim())
            .apply()
        _uid.value = uid.trim().takeIf { it.isNotBlank() }
        _isLoggedIn.value = sessData.isNotBlank()
    }

    /** 清除全部登录凭证（退出登录）。 */
    fun clear() {
        prefs.edit().clear().apply()
        _uid.value = null
        _isLoggedIn.value = false
    }

    private fun savedSessData(): String? =
        prefs.getString(KEY_SESSDATA, null)?.takeIf { it.isNotBlank() }

    private fun savedUid(): String? =
        prefs.getString(KEY_UID, null)?.takeIf { it.isNotBlank() }

    private fun isSavedLoggedIn(): Boolean = savedSessData() != null

    companion object {
        private const val FILE_NAME = "bili_auth"
        private const val KEY_SESSDATA = "sessdata"
        private const val KEY_BILI_JCT = "bili_jct"
        private const val KEY_UID = "uid"

        /**
         * 解析 WebView CookieManager 返回的 cookie 字符串，提取 SESSDATA / bili_jct / DedeUserID。
         *
         * 输入形如 "SESSDATA=abc; bili_jct=def; DedeUserID=123"。
         * @return Triple(sessData, biliJct, uid)，缺失的字段为 null。
         */
        fun parseBiliCookies(cookieHeader: String?): Triple<String?, String?, String?> {
            if (cookieHeader.isNullOrBlank()) return Triple(null, null, null)
            var sessData: String? = null
            var biliJct: String? = null
            var uid: String? = null
            for (part in cookieHeader.split(";")) {
                val kv = part.split("=", limit = 2)
                if (kv.size != 2) continue
                val value = kv[1].trim().takeIf { it.isNotEmpty() } ?: continue
                when (kv[0].trim()) {
                    "SESSDATA" -> sessData = value
                    "bili_jct" -> biliJct = value
                    "DedeUserID" -> uid = value
                }
            }
            return Triple(sessData, biliJct, uid)
        }
    }
}
