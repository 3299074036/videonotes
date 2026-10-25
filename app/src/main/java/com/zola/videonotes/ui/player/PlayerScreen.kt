package com.zola.videonotes.ui.player

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.zola.videonotes.data.reader.DeepLinkBuilder
import com.zola.videonotes.ui.theme.VideoNotesTokens
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 站内播放器页（M6）：Media3 ExoPlayer + PlayerView。
 *
 * - B站直链 headers（Referer + UA）经 DefaultHttpDataSource 注入；
 * - 打开时 seek 到传入的起始秒数（pill 跳转）；
 * - ON_PAUSE 自动暂停，离开页面释放播放器；
 * - 直链失败（如 YouTube 限流/403）时提供重试与"用浏览器打开"回退；
 * - B站音视频合并播放时，音频流失败自动回退到纯视频（防 MergingMediaSource 整源失败黑屏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBackClick: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // 下载成功/失败弹提示，消费后回空闲
    LaunchedEffect(downloadState) {
        when (val s = downloadState) {
            is PlayerDownloadUiState.Done -> {
                snackbar.showSnackbar(s.message)
                viewModel.consumeDownloadState()
            }
            is PlayerDownloadUiState.Failed -> {
                snackbar.showSnackbar(s.message)
                viewModel.consumeDownloadState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频播放") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    // 右上角下载：点一下直接下载当前视频（无水印），不用退回详情页
                    IconButton(
                        onClick = { viewModel.downloadVideo() },
                        enabled = downloadState !is PlayerDownloadUiState.Working,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "下载视频",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 下载进度条（下载中才显示）。
            // ★ progress 必须捕获成普通 Float 再传进 lambda：lambda 是惰性求值的，
            //   状态从 Working 切到 Done 后它还会再跑一次，直接强转 state 必崩
            //   （ClassCastException: Done cannot be cast to Working）。
            //   另：downloadState 是委托属性，不能智能转换，先取到局部变量再判断。
            val state = downloadState
            if (state is PlayerDownloadUiState.Working) {
                val progress = state.progress
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (val state = uiState) {
                is PlayerUiState.Loading -> PlayerLoadingView(message = "正在加载视频…")
                is PlayerUiState.Failed -> PlayerErrorView(
                    message = state.message,
                    onRetry = { viewModel.load() },
                    onOpenInBrowser = {
                        DeepLinkBuilder.build(viewModel.storageKey, viewModel.startSeconds)
                            ?.let { link ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                            }
                    },
                )
                is PlayerUiState.Ready -> {
                    val exoPlayer = remember(state.stream.url, state.stream.audioUrl) {
                        val dataSourceFactory = DefaultHttpDataSource.Factory()
                            .setDefaultRequestProperties(state.stream.headers)
                        // B站 DASH 音视频分离：视频流无音频轨，用 MergingMediaSource
                        // 把独立音频流合成进来，否则有画面无声音。
                        val mediaSourceFactory = DefaultMediaSourceFactory(context)
                            .setDataSourceFactory(dataSourceFactory)
                        val videoItem = MediaItem.fromUri(state.stream.url)
                        val audioUrl = state.stream.audioUrl
                        // 音频流（403/过期）一旦失败会拖垮整个合并源导致黑屏；
                        // 降级开关：失败一次后自动回退到纯视频，保证有画面。
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
                                            playWhenReady = true
                                        }
                                    }
                                })
                                prepare()
                                if (viewModel.startSeconds > 0) {
                                    seekTo(viewModel.startSeconds * 1000)
                                }
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
            }
        }
        }
    }
}

/** 加载中占位。 */
@Composable
private fun PlayerLoadingView(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.CircularProgressIndicator()
            Spacer(modifier = Modifier.height(VideoNotesTokens.Space12))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 加载失败：中文原因 + 重试 + "用浏览器打开"回退
 * （直链可能被限流/403 时走 deep link）。
 */
@Composable
private fun PlayerErrorView(
    message: String,
    onRetry: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(VideoNotesTokens.Space24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space16))
        Button(onClick = onRetry) {
            Text("重试")
        }
        Spacer(modifier = Modifier.height(VideoNotesTokens.Space8))
        OutlinedButton(onClick = onOpenInBrowser) {
            Text("用浏览器打开")
        }
    }
}
