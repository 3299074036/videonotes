package com.zola.videonotes.ui.ops

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.llm.LocalLlmEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 运维诊断页 ViewModel。
 * LLM 诊断探针（单 token / 最小化 native 测试）+ 设备信息。
 * 诊断逻辑与 LlmSettingsViewModel 的探针一致，UI 从模型管理搬到独立的运维页。
 */
@HiltViewModel
class OpsDiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localLlmEngine: LocalLlmEngine,
) : ViewModel() {

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    /** 设备信息：CPU / 架构 / App 版本 / llama.cpp 版本 / 构建优化等级。 */
    val deviceInfo: List<Pair<String, String>> = buildList {
        add("CPU" to "${Build.HARDWARE} · ${Runtime.getRuntime().availableProcessors()} 核")
        add("架构" to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"))
        add("Android" to "API ${Build.VERSION.SDK_INT}")
        add(
            "App 版本" to try {
                val pm = context.packageManager
                val pi = pm.getPackageInfo(context.packageName, 0)
                "${pi.versionName} (${pi.longVersionCode})"
            } catch (_: Exception) {
                "unknown"
            }
        )
        // 构建期常量：与 app/src/main/cpp/CMakeLists.txt 保持一致
        add("llama.cpp" to "v0.7.0")
        add("编译优化" to "-O3")
        add("ARM 架构档" to "armv8.2-a+dotprod+fp16")
    }

    /** 方案 A 诊断：单 token 诊断。 */
    fun runSingleTokenProbe() {
        if (_probing.value) return
        viewModelScope.launch {
            _probing.value = true
            _probeResult.value = null
            try {
                localLlmEngine.runSingleTokenProbe()
                    .onSuccess { _probeResult.value = "✅ $it" }
                    .onFailure { e -> _probeResult.value = "❌ ${e.message ?: "诊断失败"}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _probeResult.value = "❌ 诊断异常：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _probing.value = false
            }
        }
    }

    /** 方案 C 诊断：最小化 native 测试（5 用例）。 */
    fun runMinimalProbe() {
        if (_probing.value) return
        viewModelScope.launch {
            _probing.value = true
            _probeResult.value = null
            try {
                localLlmEngine.runMinimalNativeProbe()
                    .onSuccess { _probeResult.value = it }
                    .onFailure { e -> _probeResult.value = "❌ ${e.message ?: "诊断失败"}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _probeResult.value = "❌ 诊断异常：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _probing.value = false
            }
        }
    }
}
