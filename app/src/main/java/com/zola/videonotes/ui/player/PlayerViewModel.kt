package com.zola.videonotes.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * 播放器页 ViewModel（M6）。
 *
 * 从路由参数取 storageKey（格式 "${platform.name}:${videoId}"）重建 VideoInfo，
 * 经 [VideoStreamFetcher] 取可播放直链。YouTube 的 sourceUrl 按标准 watch 链接重建，
 * B站直接用 videoId（extractBiliIds 只看 videoId）。
 * 抖音/小红书：解析器已拿到的直链不会持久化到 storageKey 里，播放时按 sourceUrl
 * 重新解析一次取新鲜直链（抖音直链有时效，重解析比缓存更可靠）。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val streamFetcher: VideoStreamFetcher,
    private val parserRegistry: ParserRegistry,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 视频存储键。 */
    val storageKey: String = savedStateHandle.get<String>(Routes.ARG_STORAGE_KEY).orEmpty()

    /** 起始秒数（pill 跳转传入）。 */
    val startSeconds: Long = savedStateHandle.get<Long>(Routes.ARG_START_SECONDS) ?: 0L

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

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
            // 抖音/小红书：storageKey 里没有直链，播放前重新解析取新鲜直链
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

    private fun buildVideoInfo(storageKey: String): VideoInfo? {
        val platform = DeepLinkBuilder.platformOf(storageKey) ?: return null
        val videoId = DeepLinkBuilder.videoIdOf(storageKey).takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = when (platform) {
            Platform.YOUTUBE -> "https://www.youtube.com/watch?v=$videoId"
            Platform.DOUYIN -> "https://www.douyin.com/video/$videoId"
            Platform.XIAOHONGSHU -> "https://www.xiaohongshu.com/explore/$videoId"
            else -> ""
        }
        return VideoInfo(
            platform = platform,
            videoId = videoId,
            title = "",
            coverUrl = "",
            durationSec = 0,
            author = "",
            sourceUrl = sourceUrl,
        )
    }
}
