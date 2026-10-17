package com.zola.videonotes.ui.settings

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.auth.BiliAuthStore
import com.zola.videonotes.data.asr.ModelDownloadManager
import com.zola.videonotes.data.llm.local.LocalModelDownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val llmDownloadManager: LocalModelDownloadManager,
    private val asrDownloadManager: ModelDownloadManager,
) : ViewModel() {

    private val _cacheSizeText = MutableStateFlow("计算中…")
    val cacheSizeText: StateFlow<String> = _cacheSizeText.asStateFlow()

    private val _modelStorageText = MutableStateFlow("计算中…")
    val modelStorageText: StateFlow<String> = _modelStorageText.asStateFlow()

    /** B站登录态（登录页保存凭证后自动更新）。 */
    val biliLoggedIn: StateFlow<Boolean> = biliAuthStore.isLoggedIn

    /** 已登录 B站账号的 UID。 */
    val biliUid: StateFlow<String?> = biliAuthStore.uid

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
