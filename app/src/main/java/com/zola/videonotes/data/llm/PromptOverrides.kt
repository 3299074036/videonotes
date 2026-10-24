package com.zola.videonotes.data.llm

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.promptOverridesDataStore by preferencesDataStore(name = "prompt_overrides")

/**
 * 用户自定义 system 提示词（2026-10-24 合并包，一期只开放 system）：
 * 按引擎分两档（本地 / 云端）各存各的，空 = 用默认资产文本。
 * user 模板（单遍 / 分段 / 合并 / 修正）与导入导出放二期。
 */
@Singleton
class PromptOverrides @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private object Keys {
        val LOCAL_SYSTEM = stringPreferencesKey("local_system")
        val CLOUD_SYSTEM = stringPreferencesKey("cloud_system")
    }

    /** 本地档自定义 system 文本（null/空 = 用默认）。 */
    val localSystem: Flow<String?> = context.promptOverridesDataStore.data
        .map { it[Keys.LOCAL_SYSTEM]?.takeIf { text -> text.isNotBlank() } }

    /** 云端档自定义 system 文本（null/空 = 用默认）。 */
    val cloudSystem: Flow<String?> = context.promptOverridesDataStore.data
        .map { it[Keys.CLOUD_SYSTEM]?.takeIf { text -> text.isNotBlank() } }

    /** 按引擎取自定义 system 文本（null = 用默认）。 */
    fun systemFor(type: LlmEngineType): Flow<String?> =
        if (type == LlmEngineType.LOCAL) localSystem else cloudSystem

    suspend fun setLocalSystem(text: String?) {
        context.promptOverridesDataStore.edit {
            if (text.isNullOrBlank()) it.remove(Keys.LOCAL_SYSTEM) else it[Keys.LOCAL_SYSTEM] = text
        }
    }

    suspend fun setCloudSystem(text: String?) {
        context.promptOverridesDataStore.edit {
            if (text.isNullOrBlank()) it.remove(Keys.CLOUD_SYSTEM) else it[Keys.CLOUD_SYSTEM] = text
        }
    }
}
