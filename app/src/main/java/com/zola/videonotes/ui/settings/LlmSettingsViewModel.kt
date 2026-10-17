package com.zola.videonotes.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.llm.LlmClient
import com.zola.videonotes.data.llm.LlmCredentialStore
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmPresets
import com.zola.videonotes.data.llm.LlmSettings
import com.zola.videonotes.data.llm.LocalLlmEngine
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 大语言模型设置页 UI 状态。 */
data class LlmSettingsUiState(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
    val testing: Boolean = false,
    /** 测试结果文案（成功/失败共用，testSuccess 区分颜色）。 */
    val testResult: String? = null,
    val testSuccess: Boolean = false,
    /** 引擎类型。 */
    val engineType: LlmEngineType = LlmEngineType.CLOUD,
    /** 当前选择的本地模型 ID。 */
    val localModelId: String = LocalLlmModels.QWEN3_4B.id,
)

/**
 * 大语言模型设置 ViewModel（M4/M7a）。
 * base_url / model / engine_type 存 DataStore，api_key 存 EncryptedSharedPreferences。
 * 本地模型下载状态来自 [LocalModelDownloadManager]。
 */
@HiltViewModel
class LlmSettingsViewModel @Inject constructor(
    private val llmSettings: LlmSettings,
    private val credentialStore: LlmCredentialStore,
    private val llmClient: LlmClient,
    private val localPrefs: LocalLlmPreferences,
    val localDownloadManager: LocalModelDownloadManager,
    private val localLlmEngine: LocalLlmEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LlmSettingsUiState())
    val uiState: StateFlow<LlmSettingsUiState> = _uiState.asStateFlow()

    /** 本地模型下载状态（key 为 modelId）。 */
    val localDlStates: StateFlow<Map<String, LocalModelDownloadManager.ModelDlState>> =
        localDownloadManager.states

    /** 方案 A 诊断：进行中标志与结论文案（结论同时写入 llm.log，可在"详细日志"页查看全量）。 */
    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()
    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    private var testJob: Job? = null

    init {
        viewModelScope.launch {
            val (baseUrl, model) = llmSettings.snapshot()
            val key = withContext(Dispatchers.IO) { credentialStore.getApiKey() }.orEmpty()
            _uiState.update { it.copy(baseUrl = baseUrl, model = model, apiKey = key) }
        }
        viewModelScope.launch {
            llmSettings.engineType.collect { type ->
                _uiState.update { it.copy(engineType = type) }
            }
        }
        viewModelScope.launch {
            localPrefs.modelId.collect { id ->
                _uiState.update { it.copy(localModelId = id) }
            }
        }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.update { it.copy(baseUrl = value, testResult = null) }
        viewModelScope.launch { llmSettings.setBaseUrl(value) }
    }

    fun onPresetClick(preset: LlmPresets.Preset) {
        onBaseUrlChange(preset.baseUrl)
    }

    fun onModelChange(value: String) {
        _uiState.update { it.copy(model = value, testResult = null) }
        viewModelScope.launch { llmSettings.setModel(value) }
    }

    fun onApiKeyChange(value: String) {
        _uiState.update { it.copy(apiKey = value, testResult = null) }
        viewModelScope.launch(Dispatchers.IO) { credentialStore.setApiKey(value) }
    }

    /** 切换引擎类型。 */
    fun onEngineTypeChange(type: LlmEngineType) {
        _uiState.update { it.copy(engineType = type, testResult = null) }
        viewModelScope.launch { llmSettings.setEngineType(type) }
    }

    /** 选择本地模型。 */
    fun onLocalModelSelect(modelId: String) {
        _uiState.update { it.copy(localModelId = modelId) }
        viewModelScope.launch { localPrefs.setModelId(modelId) }
    }

    /** 本地模型：开始/继续下载。 */
    fun onLocalDownload(modelId: String) {
        localDownloadManager.startDownload(modelId)
    }

    /** 本地模型：暂停下载。 */
    fun onLocalPause(modelId: String) {
        localDownloadManager.pauseDownload(modelId)
    }

    /** 本地模型：删除。 */
    fun onLocalDelete(modelId: String) {
        viewModelScope.launch { localDownloadManager.deleteModel(modelId) }
    }

    /** 测试连接：GET {baseUrl}/models。 */
    fun testConnection() {
        if (_uiState.value.testing) return
        testJob?.cancel()
        _uiState.update { it.copy(testing = true, testResult = null) }
        testJob = viewModelScope.launch {
            try {
                val state = _uiState.value
                llmClient.testConnection(state.baseUrl, state.apiKey)
                    .onSuccess {
                        _uiState.update { s ->
                            s.copy(testing = false, testResult = "连接成功", testSuccess = true)
                        }
                    }
                    .onFailure { e ->
                        _uiState.update { s ->
                            s.copy(
                                testing = false,
                                testResult = e.message ?: "连接失败，请重试",
                                testSuccess = false,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(testing = false) }
                throw e
            }
        }
    }

    /**
     * 方案 A 诊断：单 token 诊断。
     * 只送 1 个 token 做一次 decode：成功=batch 问题，90s 超时=根本性 hang。
     * 结论同时写入 llm.log（"详细日志"页可见）。
     */
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

    /**
     * 方案 C 诊断：最小化 native 测试。
     * 完全绕开封装层，4 个用例覆盖线程数/batch/n_ctx，用于区分
     * "JNI 封装问题" vs "llama.cpp 构建问题"。结论同时写入 llm.log。
     */
    fun runMinimalProbe() {
        if (_probing.value) return
        viewModelScope.launch {
            _probing.value = true
            _probeResult.value = null
            try {
                localLlmEngine.runMinimalNativeProbe()
                    .onSuccess { _probeResult.value = "✅ 方案C完成：\n$it" }
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
