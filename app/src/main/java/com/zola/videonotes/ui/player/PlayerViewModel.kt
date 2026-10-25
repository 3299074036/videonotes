package com.zola.videonotes.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zola.videonotes.data.auth.XhsAuthStore
import com.zola.videonotes.data.download.VideoDownloader
import com.zola.videonotes.data.local.VideoDao
import com.zola.videonotes.data.parser.ParserRegistry
import com.zola.videonotes.data.player.PlayableStream
import com.zola.videonotes.data.player.VideoStreamFetcher
import com.zola.videonotes.data.reader.DeepLinkBuilder
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 播放器页 UI 状态。 */
sealed interface PlayerUiState {
    /** 正在获取可播放流 */
    data object Loading : PlayerUiState

    /** 可播放：stream 为直链 + 请求头 */
    data class Ready(val stream: PlayableStream) : PlayerUiState

    /** 获取失败：message 为中文原因 */
    data class Failed(val message: String) : PlayerUiState
}

/** 播放器页下载 UI 状态（右上角下载图标用）。 */
sealed interface PlayerDownloadUiState {
    /** 空闲 */
    data object Idle : PlayerDownloadUiState

    /** 下载中：progress 0f..1f */
    data class Working(val progress: Float) : PlayerDownloadUiState

    /** 下载成功：message 为保存位置提示 */
    data class Done(val message: String) : PlayerDownloadUiState

    /** 下载失败：message 为面向用户的中文原因 */
    data class Failed(val message: String) : PlayerDownloadUiState
}

/**
 * 播放器页 ViewModel（M6）。
 *
 * 取流优先级（YunX 模式：解析结果就是播放源）：
 * 1. 详情页带过来的 mediaUrl（小红书）：直接播，不重解析。请求头不走导航（Cookie 不进
 *    路由参数），播放时按 [XhsAuthStore] 现取桌面 UA + Referer + web_session。
 * 2. 详情页带过来的 sourceUrl（含小红书 xsec_token）：重解析一次取新鲜直链。
 * 3. 库里存的原始链接（同 2，新解析的含 token；笔记页点播放走这条）。
 * 4. 兜底：按 storageKey 重建的地址重解析（抖音可用；小红书无 token 会撞登录墙）。
 * YouTube 的 sourceUrl 按标准 watch 链接重建，B站直接用 videoId。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val streamFetcher: VideoStreamFetcher,
    private val parserRegistry: ParserRegistry,
    private val videoDownloader: VideoDownloader,
    private val xhsAuthStore: XhsAuthStore,
    private val videoDao: VideoDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 视频存储键。 */
    val storageKey: String = savedStateHandle.get<String>(Routes.ARG_STORAGE_KEY).orEmpty()

    /** 详情页解析好的直链（可能为空：笔记页等入口不带）。 */
    private val mediaUrlArg: String = savedStateHandle.get<String>(Routes.ARG_MEDIA_URL).orEmpty()

    /** 详情页解析用的原始链接（含查询串；可能为空）。 */
    private val sourceUrlArg: String = savedStateHandle.get<String>(Routes.ARG_SOURCE_URL).orEmpty()

    /** 起始秒数（pill 跳转传入）。 */
    val startSeconds: Long = savedStateHandle.get<Long>(Routes.ARG_START_SECONDS) ?: 0L

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _downloadState = MutableStateFlow<PlayerDownloadUiState>(PlayerDownloadUiState.Idle)
    val downloadState: StateFlow<PlayerDownloadUiState> = _downloadState.asStateFlow()

    /** 下载当前视频（无水印）到系统下载目录。下载中重复点击直接忽略。 */
    fun downloadVideo() {
        if (_downloadState.value is PlayerDownloadUiState.Working) return
        _downloadState.value = PlayerDownloadUiState.Working(0f)
        viewModelScope.launch {
            val video = buildVideoInfo(storageKey) ?: run {
                _downloadState.value = PlayerDownloadUiState.Failed("视频信息无效")
                return@launch
            }
            // VideoDownloader 内部处理直链缺失：抖音/小红书重解析，B站/YouTube 取流
            videoDownloader.download(video) { p ->
                _downloadState.value = PlayerDownloadUiState.Working(p)
            }.fold(
                onSuccess = {
                    _downloadState.value = PlayerDownloadUiState.Done("已保存到下载/VideoNotes，可在文件管理器中查看")
                },
                onFailure = { e ->
                    _downloadState.value = PlayerDownloadUiState.Failed(e.message ?: "视频下载失败")
                },
            )
        }
    }

    /** 下载提示消费后回到空闲（避免成功/失败文案常驻）。 */
    fun consumeDownloadState() {
        if (_downloadState.value !is PlayerDownloadUiState.Working) {
            _downloadState.value = PlayerDownloadUiState.Idle
        }
    }

    init {
        load()
    }

    /** 获取可播放流；失败重试入口。 */
    fun load() {
        _uiState.value = PlayerUiState.Loading
        viewModelScope.launch {
            val video = buildVideoInfo(storageKey)
            if (video == null) {
                _uiState.value = PlayerUiState.Failed("视频信息无效")
                return@launch
            }
            // 小红书 + 详情页带了直链：直接播（YunX 模式），不重解析。
            if (video.platform == Platform.XIAOHONGSHU && mediaUrlArg.isNotBlank()) {
                _uiState.value = PlayerUiState.Ready(
                    PlayableStream(url = mediaUrlArg, headers = xhsMediaHeaders()),
                )
                return@launch
            }
            // 抖音/小红书：播放前重新解析取新鲜直链。buildVideoInfo 已算好有效 sourceUrl
            // （导航参数 → 库里原链接 → storageKey 反推地址），直接用它解析。
            val playableVideo = if (video.platform == Platform.DOUYIN || video.platform == Platform.XIAOHONGSHU) {
                parserRegistry.parse(video.sourceUrl).getOrNull() ?: run {
                    _uiState.value = PlayerUiState.Failed("该视频暂不支持站内播放，请重新解析")
                    return@launch
                }
            } else {
                video
            }
            streamFetcher.fetch(playableVideo)
                .onSuccess { _uiState.value = PlayerUiState.Ready(it) }
                .onFailure { _uiState.value = PlayerUiState.Failed(it.message ?: "视频加载失败，请重试") }
        }
    }

    /**
     * 小红书播放请求头（YunX：缺 Referer/Cookie 就 403）。
     * Cookie 从凭证库现取，不经导航参数传递。
     */
    private fun xhsMediaHeaders(): Map<String, String> = buildMap {
        put("User-Agent", XhsAuthStore.XHS_DESKTOP_UA)
        put("Referer", "https://www.xiaohongshu.com/")
        xhsAuthStore.webSession()?.let { put("Cookie", "web_session=$it") }
    }

    private suspend fun buildVideoInfo(storageKey: String): VideoInfo? {
        val platform = DeepLinkBuilder.platformOf(storageKey) ?: return null
        val videoId = DeepLinkBuilder.videoIdOf(storageKey).takeIf { it.isNotBlank() } ?: return null
        val reconstructed = when (platform) {
            Platform.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId"
            Platform.DOUYIN -> "https://www.douyin.com/video/$videoId"
            Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/explore/$videoId"
            else -> ""
        }
        // 有效 sourceUrl 优先级：详情页带过来的原始链接 → 库里存的原始链接
        // （新解析的含小红书 xsec_token）→ 按 storageKey 反推的地址（小红书无 token 会撞墙）。
        val storedUrl = runCatching { videoDao.getByStorageKey(storageKey)?.url.orEmpty() }.getOrElse { "" }
        val sourceUrl = sourceUrlArg.ifBlank { storedUrl }.ifBlank { reconstructed }
        return VideoInfo(
            platform = platform,
            videoId = videoId,
            title = "",
            coverUrl = "",
            durationSec = 0,
            author = "",
            sourceUrl = sourceUrl,
            mediaUrl = mediaUrlArg,
            mediaHeaders = if (platform == Platform.XIAOHONGSHU) xhsMediaHeaders() else emptyMap(),
        )
    }
}
