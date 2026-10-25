package com.zola.videonotes.data.pipeline

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管线共用 DataStore（pipeline_prefs）：PipelineStartStore 与 GenerateCrashGuard 共用同一实例。
 * 同名 preferencesDataStore 委托在全 App 只能声明一处（多处声明会抛
 * 「multiple DataStores active」），故在此统一声明、internal 可见。
 */
internal val Context.pipelineDataStore by preferencesDataStore(name = "pipeline_prefs")

/**
 * L-23：storageKey 的 SHA-256 十六进制（偏好键名用；32 位 hashCode 做唯一区分
 * 会碰撞串视频，64 位十六进制摘要在实践中不再碰撞）。
 */
internal fun sha256Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    // 注意：Byte 是有符号的，直接 %02x 会把负字节 sign-extend 成 8 位 f（如 -1 → ffffffff），
    // 必须先 & 0xFF 转无符号再格式化，否则摘要不是标准十六进制。
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * 处理管线启动时刻（DataStore，模式与 ThemePreferences / AsrPreferences 一致）：
 * 按视频 storageKey 持久化一轮处理（转写 / 生成）的入队时刻，
 * 供工序单「已用时」按真实任务时长计算——重进页面 / 进程重建都不归零。
 * 管线到达终态（完成 / 取消 / 失败或阻塞停步）时清除，下一轮重新打点。
 */
@Singleton
class PipelineStartStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** storageKey → 偏好键名：SHA-256 全长摘要，不再用 32 位 hashCode 做唯一区分。 */
    private fun keyFor(storageKey: String) =
        longPreferencesKey("start_sha_" + sha256Hex(storageKey))

    /** 旧键名（升级前公式）：删除时一并清理；旧键此后不再读写、自然冻结。 */
    private fun oldKeyFor(storageKey: String) =
        longPreferencesKey(
            "start_" + storageKey.replace(Regex("[^A-Za-z0-9]"), "_") +
                "_" + storageKey.hashCode().toUInt().toString(16),
        )

    /** 观察某视频的管线启动时刻；无记录时为 null。 */
    fun startMillis(storageKey: String): Flow<Long?> =
        context.pipelineDataStore.data.map { prefs -> prefs[keyFor(storageKey)] }

    /** 打点：已有记录则不覆盖（一轮处理只认最早的入队时刻）。 */
    suspend fun recordStartIfAbsent(
        storageKey: String,
        now: Long = System.currentTimeMillis(),
    ) {
        context.pipelineDataStore.edit { prefs ->
            val key = keyFor(storageKey)
            if (prefs[key] == null) {
                prefs[key] = now
            }
        }
    }

    /** 清除某视频的启动时刻记录（终态调用，下一轮重新打点；旧键一并清掉）。 */
    suspend fun clear(storageKey: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs.remove(keyFor(storageKey))
            prefs.remove(oldKeyFor(storageKey))
        }
    }
}
