package com.zola.videonotes.data.llm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM API Key 加密存储（EncryptedSharedPreferences，AES256）。
 *
 * - 文件位于应用私有目录，随应用卸载自动清除；
 * - 绝不硬编码任何密钥，Key 仅由用户在设置页输入；
 * - 读取为同步阻塞调用，调用方应在 IO 线程使用。
 */
@Singleton
class LlmCredentialStore @Inject constructor(
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

    /** 返回已保存的 API Key，未设置时返回 null。 */
    fun getApiKey(): String? =
        prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    /** 保存 API Key（覆盖旧值）。 */
    fun setApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    /** 清除已保存的 API Key。 */
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val FILE_NAME = "llm_credentials"
        private const val KEY_API_KEY = "api_key"
    }
}
