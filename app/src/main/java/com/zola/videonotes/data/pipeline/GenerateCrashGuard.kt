package com.zola.videonotes.data.pipeline

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本地生成崩溃熔断的错误文案（Worker 失败输出的 KEY_ERROR 原文）。
 * 管线据此把失败落点映射为「生成步熔断阻塞」而非普通失败——须与 Worker
 * 输出逐字一致，故只在此定义一处，Worker 与管线共用，不要另处复制。
 */
const val GENERATE_CRASH_BREAKER_MESSAGE = "本地生成已连续 2 次崩溃，已停止自动重试"

/**
 * 本地生成崩溃熔断（DataStore，与 [PipelineStartStore] 共用 pipeline_prefs）。
 *
 * 背景：天玑 9400 上 llama.cpp 原生崩溃会直接杀进程，generate() 永不返回，
 * WorkManager 与管线重连会把任务反复复活（曾 7 分钟拉起 10 次）却没有任何
 * 熔断。本守卫按视频 storageKey 维护两份状态：
 *
 * - `inProgress`：进入本地生成前置位，generate() 正常返回（成功或已处理的
 *   失败/取消）后清除；进程若死在生成中途，标记会残留，成为下次的死亡证据；
 * - `consecutiveDeaths`：连续死亡计数。Worker 启动时发现残留标记即记一次
 *   死亡；达到 [MAX_CONSECUTIVE_DEATHS] 后自动生成一律熔断（见
 *   [GENERATE_CRASH_BREAKER_MESSAGE]），只许用户手动重试（[reset] 清零后入队）
 *   或切换引擎。一次生成正常成功后计数清零。
 *
 * 仅本地引擎需要守卫：云端生成的失败都是可处理的异常，不杀进程。
 */
@Singleton
class GenerateCrashGuard @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** storageKey → 键后缀：SHA-256 全长摘要（与 PipelineStartStore 同法）。 */
    private fun suffix(storageKey: String) = "sha_" + sha256Hex(storageKey)

    /** 旧后缀（升级前公式）：清理时一并处理，旧键此后不再写入。 */
    private fun oldSuffix(storageKey: String) =
        storageKey.replace(Regex("[^A-Za-z0-9]"), "_") +
            "_" + storageKey.hashCode().toUInt().toString(16)

    private fun inProgressKey(storageKey: String) =
        booleanPreferencesKey("gen_inprog_${suffix(storageKey)}")

    private fun oldInProgressKey(storageKey: String) =
        booleanPreferencesKey("gen_inprog_${oldSuffix(storageKey)}")

    private fun deathsKey(storageKey: String) =
        intPreferencesKey("gen_deaths_${suffix(storageKey)}")

    private fun oldDeathsKey(storageKey: String) =
        intPreferencesKey("gen_deaths_${oldSuffix(storageKey)}")

    /**
     * Worker 启动时登记：若上次的 inProgress 标记仍在（= 上次进程死在生成
     * 中途），记一次死亡并清除旧标记。返回当前连续死亡数；不置新标记
     * （由 [markInProgress] 在确认未熔断后置位）。
     */
    suspend fun registerStart(storageKey: String): Int {
        var deaths = 0
        context.pipelineDataStore.edit { prefs ->
            val ik = inProgressKey(storageKey)
            val dk = deathsKey(storageKey)
            deaths = prefs[dk] ?: 0
            // L-23：升级前残留的旧标记也算死亡证据（跨版本不丢熔断计数），读到即清。
            if (prefs[ik] == true || prefs[oldInProgressKey(storageKey)] == true) {
                deaths += 1
                prefs[dk] = deaths
                prefs.remove(ik)
                prefs.remove(oldInProgressKey(storageKey))
            }
        }
        return deaths
    }

    /** 进入本地生成前置位（generate() 正常返回后必须清除）。 */
    suspend fun markInProgress(storageKey: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs[inProgressKey(storageKey)] = true
        }
    }

    /** generate() 已正常返回（失败/取消等已处理落点）：只清标记，不动计数；旧键一并清。 */
    suspend fun clearInProgress(storageKey: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs.remove(inProgressKey(storageKey))
            prefs.remove(oldInProgressKey(storageKey))
        }
    }

    /** 一次生成正常成功：清标记并把连续死亡计数清零；旧键一并清。 */
    suspend fun onGenerateSuccess(storageKey: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs.remove(inProgressKey(storageKey))
            prefs.remove(deathsKey(storageKey))
            prefs.remove(oldInProgressKey(storageKey))
            prefs.remove(oldDeathsKey(storageKey))
        }
    }

    /** 用户手动重试：清零计数与标记，人工重试永远允许（熔断只拦自动复活）；旧键一并清。 */
    suspend fun reset(storageKey: String) {
        context.pipelineDataStore.edit { prefs ->
            prefs.remove(inProgressKey(storageKey))
            prefs.remove(deathsKey(storageKey))
            prefs.remove(oldInProgressKey(storageKey))
            prefs.remove(oldDeathsKey(storageKey))
        }
    }

    companion object {
        /** 连续死亡达到此数即熔断：即本地生成最多自动尝试 2 次，第 3 次起拦下。 */
        const val MAX_CONSECUTIVE_DEATHS = 2
    }
}
