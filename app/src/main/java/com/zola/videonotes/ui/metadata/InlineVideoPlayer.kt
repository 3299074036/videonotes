package com.zola.videonotes.ui.metadata

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zola.videonotes.data.player.PlayableStream
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.ui.components.PlatformIcon
import com.zola.videonotes.ui.theme.VideoNotesTokens
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 详情页/处理中页共用顶部（两页上面部分做到一致，YunX 解析页版式）：
 * 平台图标 + 标题 / 作者 · 时长 / 本页播放器 / 媒体行（格式 + 下载状态）。
 * 工序单、取消/重新开始等下方内容由调用方各自保留。
 */
@Composable
fun VideoDetailTop(
    info: VideoInfo,
    inlineState: InlinePlayerState,
    onInlinePlayRequest: () -> Unit,
    onExpandClick: () -> Unit,
    downloadState: VideoDownloadUiState,
    onDownloadClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        // 头部：平台图标与标题顶对齐，标题最多 2 行、超长省略
        Row(verticalAlignment = Alignment.Top) {
            PlatformIcon(platform = info.platform)
            Spacer(modifier = Modifier.width(VideoNotesTokens.Space8))
            Text(
                text = info.title.ifBlank { "（无标题）" },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        // 作者 · 时长（对齐标题，与图标列左对齐空出 36dp）
        val metaText = buildString {
            if (info.author.isNotBlank()) append(info.author)
            if (info.durationSec > 0) {
                if (isNotEmpty()) append(" · ")
                append(formatDuration(info.durationSec))
            }
        }
        if (metaText.isNotEmpty()) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space4))
            Text(
                text = metaText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = VideoNotesTokens.Space32 + VideoNotesTokens.Space4,
                ),
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        // 本页播放器：点 ▶ 直接在本页播，不跳转
        InlineVideoPlayer(
            info = info,
            inlineState = inlineState,
            onPlayRequest = onInlinePlayRequest,
            onExpandClick = onExpandClick,
            downloadState = downloadState,
            onDownloadClick = onDownloadClick,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
        // 媒体行：格式 + 下载状态（下载/展开按钮已集成进播放器右上角，这里只显示状态）
        Surface(
            shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VideoNotesTokens.Space12),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "1. 视频",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (info.platform) {
                            Platform.DOUYIN, Platform.XIAOHONGSHU -> "无水印 · MP4"
                            Platform.BILIBILI -> "DASH 合成 · MP4"
                            else -> "MP4"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (downloadState) {
                    is VideoDownloadUiState.Working -> {
                        Text(
                            text = "下载中 ${(downloadState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is VideoDownloadUiState.Done -> {
                        Text(
                            text = "已保存到下载",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    is VideoDownloadUiState.Failed -> {
                        TextButton(onClick = onDownloadClick) {
                            Text("下载失败，点此重试")
                        }
                    }
                    VideoDownloadUiState.Idle -> {}
                }
            }
        }
        // 下载成功后的完整提示（不挤媒体行）
        if (downloadState is VideoDownloadUiState.Done) {
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
            Text(
                text = downloadState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
    }
}

/**
 * 本页内嵌播放器：封面 + 居中 ▶，点一下直接在本页取流播放（不跳转二级页）。
 * 右上角集成下载 + 展开（👁，跳二级全屏播放器）；播放失败（直链过期等）可重试重取流。
 */
@Composable
private fun InlineVideoPlayer(
    info: VideoInfo,
    inlineState: InlinePlayerState,
    onPlayRequest: () -> Unit,
    onExpandClick: () -> Unit,
    downloadState: VideoDownloadUiState,
    onDownloadClick: () -> Unit,
) {
    var playing by remember { mutableStateOf(false) }
    var playFailed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(VideoNotesTokens.CoverAspectRatio)
            .clip(RoundedCornerShape(VideoNotesTokens.CornerLarge))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // 播放中且已取到流：ExoPlayer 本页直播
            playing && inlineState is InlinePlayerState.Ready && !playFailed -> {
                InlinePlayerView(
                    stream = inlineState.stream,
                    onPlayError = { playFailed = true },
                )
            }
            // 播放中、取流中：加载圈
            playing && inlineState is InlinePlayerState.Loading -> {
                CoverContent(info = info, onCoverClick = null)
                CircularProgressIndicator(color = Color.White)
                Text(
                    text = "正在加载视频…",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = VideoNotesTokens.Space12),
                )
            }
            // 播放失败：取流失败或播流失败，点重试重新取流
            playing && (inlineState is InlinePlayerState.Failed || playFailed) -> {
                val message = (inlineState as? InlinePlayerState.Failed)?.message
                    ?: "视频播放失败，可能是直链已过期"
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = VideoNotesTokens.Space24),
                    )
                    Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
                    Button(
                        onClick = {
                            playFailed = false
                            onPlayRequest()
                        },
                    ) {
                        Text("重试")
                    }
                }
            }
            // 未播放：封面 + 居中 ▶ + 点击播放
            else -> {
                CoverContent(
                    info = info,
                    onCoverClick = {
                        playing = true
                        // 已取到流直接播；否则取流（抖音/小红书重解析约 5 秒）
                        if (inlineState is InlinePlayerState.Idle || inlineState is InlinePlayerState.Failed) {
                            playFailed = false
                            onPlayRequest()
                        }
                    },
                )
            }
        }
        // 右上角集成按钮：下载 + 展开（失败态不显示，失败态用中央重试）
        if (inlineState !is InlinePlayerState.Failed && !playFailed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(VideoNotesTokens.Space8),
            ) {
                PlayerOverlayButton(
                    icon = Icons.Filled.Download,
                    description = "下载视频",
                    enabled = downloadState !is VideoDownloadUiState.Working,
                    onClick = onDownloadClick,
                )
                PlayerOverlayButton(
                    icon = Icons.Filled.Visibility,
                    description = "展开到全屏播放器",
                    onClick = onExpandClick,
                )
            }
        }
    }
}

/** 封面内容：封面图（或占位）+ 居中播放钮 + 右下角「点击播放」。 */
@Composable
private fun CoverContent(
    info: VideoInfo,
    onCoverClick: (() -> Unit)?,
) {
    val coverModifier = if (onCoverClick != null) {
        Modifier
            .fillMaxSize()
            .clickable(onClick = onCoverClick)
    } else {
        Modifier.fillMaxSize()
    }
    Box(
        modifier = coverModifier,
        contentAlignment = Alignment.Center,
    ) {
        if (info.coverUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(info.coverUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = "视频封面，点击播放",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(VideoNotesTokens.Space48),
            )
        }
        if (onCoverClick != null) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.85f),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "播放",
                    tint = Color.Black,
                    modifier = Modifier
                        .padding(VideoNotesTokens.Space12)
                        .size(VideoNotesTokens.Space24),
                )
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Text(
                    text = "点击播放",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .padding(VideoNotesTokens.Space8)
                        .clip(RoundedCornerShape(VideoNotesTokens.CornerSmall))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(
                            horizontal = VideoNotesTokens.Space8,
                            vertical = VideoNotesTokens.Space2,
                        ),
                )
            }
        }
    }
}

/** 播放器右上角集成按钮：半透明黑圆底 + 白图标。 */
@Composable
private fun PlayerOverlayButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier = Modifier
            .size(VideoNotesTokens.Space32)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(VideoNotesTokens.Space16),
        )
    }
}

/**
 * 内嵌 ExoPlayer 视图：与二级播放器同款建播逻辑（B站 DASH 音视频合成、
 * 音频流失败自动回退纯视频、切后台自动暂停、离页释放）。
 */
@Composable
private fun InlinePlayerView(
    stream: PlayableStream,
    onPlayError: () -> Unit,
) {
    val context = LocalContext.current
    val exoPlayer = remember(stream.url, stream.audioUrl) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(stream.headers)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        val videoItem = MediaItem.fromUri(stream.url)
        val audioUrl = stream.audioUrl
        val audioFallbackDone = AtomicBoolean(false)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                if (audioUrl != null) {
                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(videoItem)
                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(audioUrl))
                    setMediaSource(MergingMediaSource(videoSource, audioSource))
                } else {
                    setMediaItem(videoItem)
                }
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (audioUrl != null && audioFallbackDone.compareAndSet(false, true)) {
                            val resumePosition = currentPosition
                            setMediaItem(videoItem)
                            prepare()
                            if (resumePosition > 0) seekTo(resumePosition)
                        } else {
                            onPlayError()
                        }
                    }
                })
                prepare()
                playWhenReady = true
            }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
