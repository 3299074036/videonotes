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
 * YouTube 登录态 cookie 加密存储（EncryptedSharedPreferences，AES256；照抄 BiliAuthStore）。
 *
 * - 存整串 cookie（SID / HSID / SSID / APISID / SAPISID / LOGIN_INFO 等全要，整串最稳），不拆字段；
 * - 用户从浏览器手动粘贴导入（见设置页 YouTube cookie 行），密码不经过本应用；
 * - 只进加密存储，不打 log、不回显（导入框永不预填已存值），文件位于应用私有目录，随应用卸载自动清除；
 * - 拦截器用同步读（OkHttp 后台线程），界面用 StateFlow。
 */
@Singleton
class YoutubeCookieStore @Inject constructor(
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

    private val _isImported = MutableStateFlow(savedCookie() != null)
    val isImported: StateFlow<Boolean> = _isImported.asStateFlow()

    /** 同步读取整串 cookie，未导入时返回 null。调用方应在 IO 线程使用。 */
    fun cookie(): String? = savedCookie()

    /** 保存导入的 cookie（覆盖旧值）。调用前应先过 [isValidCookie]。 */
    fun save(cookie: String) {
        prefs.edit().putString(KEY_COOKIE, cookie.trim()).apply()
        _isImported.value = true
    }

    /** 清除全部登录态。 */
    fun clear() {
        prefs.edit().clear().apply()
        _isImported.value = false
    }

    private fun savedCookie(): String? =
        prefs.getString(KEY_COOKIE, null)?.takeIf { it.isNotBlank() }

    companion object {
        private const val FILE_NAME = "youtube_auth"
        private const val KEY_COOKIE = "cookie"

        /** 需要带登录态的域名后缀（严格匹配：本身或子域名，notyoutube.com 这类不算）。 */
        private val YOUTUBE_HOST_SUFFIXES = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "googlevideo.com",
            "youtubei.googleapis.com",
        )

        /** 是否为 YouTube 相关域名（解析 / 字幕 / 音频直连都走这些域）。 */
        fun isYoutubeHost(host: String): Boolean {
            val lower = host.lowercase()
            return YOUTUBE_HOST_SUFFIXES.any { suffix ->
                lower == suffix || lower.endsWith(".$suffix")
            }
        }

        /** 粗校验：非空，且至少含一对非空键值（形如 SID=abc，可多对分号分隔）。 */
        fun isValidCookie(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return value.split(";").any { part ->
                val kv = part.split("=", limit = 2)
                kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()
            }
        }
    }
}
