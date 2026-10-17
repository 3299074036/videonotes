package com.zola.videonotes.domain.asr

import com.zola.videonotes.domain.model.VideoInfo
import java.io.File

/**
 * 音频获取器：按平台下载视频的音频流，供端侧 ASR 转写。
 * 各平台独立实现，接口变化时只改一处。
 *
 * @param onProgress 下载进度 0..1
 * @return 下载到应用 cacheDir/audio/ 的音频文件
 */
interface AudioFetcher {

    suspend fun fetch(video: VideoInfo, onProgress: (Float) -> Unit): Result<File>
}
