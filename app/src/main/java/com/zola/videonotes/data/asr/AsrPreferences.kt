package com.zola.videonotes.data.asr

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
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
        val SELECTION_MIGRATED_V1 = booleanPreferencesKey("selection_migrated_v1")
    }

    /**
     * 当前选中的模型 id，可空：缺 key = 从未选择（2026-10-24 合并包「初次不默认勾选」）。
     * 老用户迁移见 [migrateSelectionIfNeeded]：filesDir/models 下有任一文件即回填 Tiny，否则保持 null。
     */
    val selectedModelId: Flow<String?> = context.asrDataStore.data
        .map { it[Keys.SELECTED_MODEL_ID] }

    /** 是否仅在 Wi-Fi 下下载模型，默认 true（2026-10-24 合并包后开关已删，键残留无害、读数已全部移除）。 */
    val wifiOnly: Flow<Boolean> = context.asrDataStore.data
        .map { it[Keys.WIFI_ONLY] ?: true }

    suspend fun setSelectedModelId(id: String?) {
        context.asrDataStore.edit {
            if (id == null) it.remove(Keys.SELECTED_MODEL_ID) else it[Keys.SELECTED_MODEL_ID] = id
        }
    }

    /** 首次启动新版时的一次性迁移（幂等）：key 缺失且磁盘有任一识别模型文件 → 回填 Tiny，否则保持 null。 */
    suspend fun migrateSelectionIfNeeded() {
        val prefs = context.asrDataStore.data.first()
        if (prefs[Keys.SELECTION_MIGRATED_V1] == true) return
        if (prefs[Keys.SELECTED_MODEL_ID] == null) {
            val modelsRoot = java.io.File(context.filesDir, "models")
            val hasFile = modelsRoot.exists() &&
                modelsRoot.walkTopDown().any { it.isFile }
            if (hasFile) {
                context.asrDataStore.edit { it[Keys.SELECTED_MODEL_ID] = AsrModelCatalog.DEFAULT_MODEL_ID }
            }
        }
        context.asrDataStore.edit { it[Keys.SELECTION_MIGRATED_V1] = true }
    }

    suspend fun setWifiOnly(enabled: Boolean) {
        context.asrDataStore.edit { it[Keys.WIFI_ONLY] = enabled }
    }
}
