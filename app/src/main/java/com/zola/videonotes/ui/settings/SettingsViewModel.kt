package com.zola.videonotes.ui.settings

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.asr.AsrModelCatalog
import com.zola.videonotes.data.asr.AsrPreferences
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.auth.BiliAuthStore
import com.zola.videonotes.data.auth.YoutubeCookieStore
import com.zola.videonotes.data.llm.LlmCredentialStore
import com.zola.videonotes.data.llm.LlmEngineResolver
import com.zola.videonotes.data.llm.PromptOverrides
import com.zola.videonotes.data.llm.LlmEngineType
import com.zola.videonotes.data.llm.LlmSettings
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import com.zola.videonotes.data.theme.ThemeMode
import com.zola.videonotes.data.theme.ThemePreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.pow

/**
 * 设置页 ViewModel（M6）：存储管理。
 *
 * - 缓存大小：整个 cacheDir（含 audio 音频缓存、Coil 图片缓存等）；
 * - 清理缓存：只删 cacheDir/audio（转写/播放的临时音频），不动 filesDir/models；
 * - 模型存储：filesDir/models 大小展示（删除走 M3 的模型管理页）。
 * - B站账号：登录态展示与退出登录（登录页在 ui.auth）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val biliAuthStore: BiliAuthStore,
    private val youtubeCookieStore: YoutubeCookieStore,
    private val llmDownloadManager: LocalModelDownloadManager,
    private val asrDownloadManager: ModelDownloadManager,
    private val themePreferences: ThemePreferences,
    private val llmSettings: LlmSettings,
    private val engineResolver: LlmEngineResolver,
    private val asrPreferences: AsrPreferences,
    private val localLlmPreferences: LocalLlmPreferences,
    private val credentialStore: LlmCredentialStore,
    private val promptOverrides: PromptOverrides,
) : ViewModel() {

    // ---------- 外观：主题四档（与 MainActivity 共用同一偏好，改动即时生效） ----------

    val themeMode: StateFlow<ThemeMode> = themePreferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { themePreferences.setThemeMode(mode) }
    }

    // ---------- 大语言模型：引擎（与模型管理页共用同一偏好） ----------

    /**
     * 有效引擎（界面选中态与联动行按它走）：存储值为云端但配置未齐全时回落本地，
     * 推导在 [LlmEngineResolver] 单一口径。点选写入的是存储值（[setEngineType]）。
     */
    val effectiveEngineType: StateFlow<LlmEngineType> = engineResolver.effectiveEngineType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmEngineType.LOCAL)

    fun setEngineType(type: LlmEngineType) {
        viewModelScope.launch { llmSettings.setEngineType(type) }
    }

    /**
     * 本地模型行状态值：当前模型名 · 已下载实际大小 / 未下载；未选择时显示「未选择」。
     * WP9/D5：状态改读下载管理器状态流（其 COMPLETED 已经过大小 + sha256 强校验），
     * 与模型管理页同一口径；不再用同步弱判定 isModelReady 拼展示
     * （旧口径下哈希未过但大小达标时设置页会误报已下载）。
     * 模型定义走 LocalLlmPreferences 解析链（精选/影子/动态，WP1/WP3）。
     */
    val localModelText: StateFlow<String> = combine(
        localLlmPreferences.modelId,
        llmDownloadManager.states,
    ) { id, states ->
        if (id == null) {
            "未选择，到模型管理页选择"
        } else {
            val model = localLlmPreferences.resolveModel(id) ?: LocalLlmModels.QWEN3_4B
            val state = states[id]
            if (state?.status == LocalModelDownloadManager.DlStatus.COMPLETED) {
                "${model.displayName} · 已下载 ${formatBytes(state.downloadedBytes)}"
            } else {
                "${model.displayName} · 未下载"
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 云端 API 行状态值：密钥已配置 · 当前模型名 / 配置未完成 / 未配置。 */
    val cloudApiText: StateFlow<String> =
        combine(llmSettings.baseUrl, llmSettings.model) { url, model -> url to model }
            .map { (url, model) ->
                val apiKey = withContext(Dispatchers.IO) { credentialStore.getApiKey() }
                when {
                    // 「已配置」判定复用全局谓词（LlmEngineResolver），不在此另写一份
                    engineResolver.isCloudConfigured(url, model, apiKey) ->
                        "密钥已配置 · 模型 $model"
                    !apiKey.isNullOrBlank() || url.isNotBlank() || model.isNotBlank() ->
                        "配置未完成"
                    else -> "未配置"
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // ---------- 语音识别：当前模型名（与模型管理页共用同一偏好） ----------

    val asrModelName: StateFlow<String> = asrPreferences.selectedModelId
        .map { it?.let { id -> AsrModelCatalog.get(id).displayName } ?: "未选择" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 识别模型行状态值：模型名 · 已下载标称大小 / 未下载；未选择时显示「未选择」。 */
    val asrModelText: StateFlow<String> = asrPreferences.selectedModelId
        .map { id ->
            if (id == null) {
                "未选择，到模型管理页选择"
            } else {
                val model = AsrModelCatalog.get(id)
                if (asrDownloadManager.isDownloaded(id)) {
                    "${model.displayName} · 已下载 ${formatBytes(model.totalBytes)}"
                } else {
                    "${model.displayName} · 未下载"
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** 笔记提示词行状态值：本地与云端各自定义与否（「本地：已自定义 / 默认 · 云端：已自定义 / 默认」）。 */
    val promptOverrideText: StateFlow<String> = combine(
        promptOverrides.localSystem,
        promptOverrides.cloudSystem,
    ) { local, cloud ->
        val localText = if (local != null) "已自定义" else "默认"
        val cloudText = if (cloud != null) "已自定义" else "默认"
        "本地：$localText · 云端：$cloudText"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _cacheSizeText = MutableStateFlow("计算中…")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()

    private val _modelStorageText = MutableStateFlow("计算中…")
    val modelStorageText: StateFlow<String> = _modelStorageText.asStateFlow()

    /** B站登录态（登录页保存凭证后自动更新）。 */
    val biliLoggedIn: StateFlow<Boolean> = biliAuthStore.isLoggedIn

    /** 已登录 B站账号的 UID。 */
    val biliUid: StateFlow<String?> = biliAuthStore.uid

    /** YouTube cookie 是否已导入。 */
    val youtubeImported: StateFlow<Boolean> = youtubeCookieStore.isImported

    /** 清理结果：一次性消息，界面展示 Snackbar 后调 [consumeMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ---------- 语音识别（设置页单页重组：inline 选择，不再跳子页） ----------

    /** 可选的 ASR 模型列表（whisper tiny / SenseVoice / small）。 */
    init {
        refreshSizes()
    }

    /** 重新计算大小（清理后调用）。模型大小走下载管理器元数据（与模型管理页一致）。 */
    fun refreshSizes() {
        viewModelScope.launch {
            val cache = withContext(Dispatchers.IO) { dirSize(context.cacheDir) }
            // 与模型管理页同一口径：已完成下载的模型字节数之和
            val llmBytes = llmDownloadManager.states.value.values
                .filter { it.status == LocalModelDownloadManager.DlStatus.COMPLETED }
                .sumOf { it.downloadedBytes }
            val asrBytes = asrDownloadManager.states.value.values
                .filter { it.status == ModelDownloadManager.DlStatus.COMPLETED }
                .sumOf { it.downloadedBytes }
            _cacheSizeText.value = formatBytes(cache)
            _modelStorageText.value = formatBytes(llmBytes + asrBytes)
        }
    }

    /** 清理音频缓存（cacheDir/audio），不动模型与其他缓存。 */
    fun clearAudioCache() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val audioDir = File(context.cacheDir, "audio")
                    if (audioDir.exists()) audioDir.deleteRecursively()
                    true
                }.getOrDefault(false)
            }
            _message.value = if (ok) "已清理音频缓存" else "清理失败，请重试"
            refreshSizes()
        }
    }

    /** 退出 B站登录：清本地凭证 + 清 WebView Cookie。 */
    fun logoutBili() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                biliAuthStore.clear()
            }
            CookieManager.getInstance().removeAllCookies {}
            _message.value = "已退出 B站登录"
        }
    }

    /**
     * 导入 YouTube cookie：格式不对返回 false（界面保留对话框，让用户修正重贴）；
     * 格式通过返回 true（界面关闭对话框），保存走 IO 线程，结果经 Snackbar 告知。
     */
    fun importYoutubeCookie(cookie: String): Boolean {
        if (!YoutubeCookieStore.isValidCookie(cookie)) {
            _message.value = "cookie 格式不对，请粘贴从浏览器复制的整串 cookie"
            return false
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { youtubeCookieStore.save(cookie) }
            _message.value = "YouTube cookie 已导入"
        }
        return true
    }

    /** 清除 YouTube cookie。 */
    fun clearYoutubeCookie() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { youtubeCookieStore.clear() }
            _message.value = "YouTube cookie 已清除"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { runCatching { it.length() }.getOrDefault(0) }
    }

    companion object {
        /** 字节数 → 人性化文案（B/KB/MB/GB）。 */
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, 3)
            return "%.1f %s".format(bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
        }
    }
}
