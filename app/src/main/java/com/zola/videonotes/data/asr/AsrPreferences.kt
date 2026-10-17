package com.zola.videonotes.data.asr

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.asrDataStore by preferencesDataStore(name = "asr_prefs")

/**
 * ASR 相关偏好（DataStore）：选中的模型、是否仅 Wi-Fi 下载模型。
 * 密钥类配置（LLM api_key）在 M6 用 EncryptedSharedPreferences，此处不涉及。
 */
@Singleton
class AsrPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only_download")
    }

    /** 当前选中的模型 id，默认 whisper-tiny-int8。 */
    val selectedModelId: Flow<String> = context.asrDataStore.data
        .map { it[Keys.SELECTED_MODEL_ID] ?: AsrModelCatalog.DEFAULT_MODEL_ID }

    /** 是否仅在 Wi-Fi 下下载模型，默认 true。 */
    val wifiOnly: Flow<Boolean> = context.asrDataStore.data
        .map { it[Keys.WIFI_ONLY] ?: true }

    suspend fun setSelectedModelId(id: String) {
        context.asrDataStore.edit { it[Keys.SELECTED_MODEL_ID] = id }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.asrDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }
}
