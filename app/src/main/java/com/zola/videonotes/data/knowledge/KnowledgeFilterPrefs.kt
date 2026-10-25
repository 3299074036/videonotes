package com.zola.videonotes.data.knowledge

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zola.videonotes.domain.model.Platform
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.knowledgeFilterDataStore by preferencesDataStore(name = "knowledge_filter_prefs")

/**
 * 知识库筛选 chip 顺序（DataStore，模式与 PipelineStartStore 一致）：
 * 只存平台顺序（逗号分隔的平台名）；“全部”恒为第一位，不参与排序、不存。
 * 读不到或名字对不上时由调用方回落默认顺序，未出现的新平台按默认顺序补在末尾。
 */
@Singleton
class KnowledgeFilterPrefs @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val PLATFORM_ORDER = stringPreferencesKey("platform_order")
    }

    /** 已存顺序（平台名逗号分隔）；无记录时为空列表。 */
    val platformOrder: Flow<List<Platform>> =
        context.knowledgeFilterDataStore.data.map { prefs ->
            prefs[Keys.PLATFORM_ORDER]
                ?.split(",")
                ?.mapNotNull { name -> runCatching { Platform.valueOf(name) }.getOrNull() }
                ?.distinct()
                .orEmpty()
        }

    suspend fun savePlatformOrder(order: List<Platform>) {
        context.knowledgeFilterDataStore.edit { prefs ->
            prefs[Keys.PLATFORM_ORDER] = order.joinToString(",") { it.name }
        }
    }
}
