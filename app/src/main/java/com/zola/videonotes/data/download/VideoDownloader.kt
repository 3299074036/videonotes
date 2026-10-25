package com.zola.videonotes.data.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.zola.videonotes.data.asr.audio.downloadFile
import com.zola.videonotes.data.network.bilibili.BiliDashFetcher
import com.zola.videonotes.data.parser.ParserRegistry
import com.zola.videonotes.data.player.VideoStreamFetcher
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频下载器：解析成功后把视频存到系统「下载/VideoNotes」目录，用户在文件管理器里找得到。
 *
 * 各平台取流方式（与播放页取流对齐，下载的是同一路无水印/干净流）：
 * - 抖音/小红书：解析器给的直链（抖音已在 JS 里 playwm→play 去水印），单文件直下。
 * - YouTube：NewPipe progressive 流（音视频合一 mp4），单文件直下。
 * - B站：DASH 音视频分离 → 分别下载 → 系统 MediaMuxer 合成为单个 mp4（无需 ffmpeg）。
 *
 * 文件名：视频标题清洗后截断 60 字，非法字符替换为下划线；标题为空时用「平台_视频ID」。
 */
@Singleton
class VideoDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parserRegistry: ParserRegistry,
    private val streamFetcher: VideoStreamFetcher,
    private val dashFetcher: BiliDashFetcher,
    private val okHttpClient: okhttp3.OkHttpClient,
) {

    /**
     * 下载视频。
     * @param video 解析得到的 VideoInfo（mediaUrl 为空时抖音/小红书会先重新解析一次）
     * @param onProgress 0f..1f 进度回调
     * @return 成功时为 MediaStore Uri，失败时为中文原因
     */
    suspend fun download(video: VideoInfo, onProgress: (Float) -> Unit): Result<Uri> {
        return try {
            val fileName = sanitizeFileName(video.title.ifBlank { "${video.platform.name}_${video.videoId}" }) + ".mp4"
            val tmpFile = when (video.platform) {
                Platform.DOUYIN, Platform.XIAOHONGSHU -> {
                    val info = video.takeIf { it.mediaUrl.isNotBlank() }
                        ?: parserRegistry.parse(video.sourceUrl).getOrNull()
                        ?: throw IOException("该视频未解析到可下载的直链，请重新解析")
                    val url = info.mediaUrl.takeIf { it.isNotBlank() }
                        ?: throw IOException("该视频未解析到可下载的直链，请重新解析")
                    downloadSingle(url, info.mediaHeaders, onProgress)
                }
                Platform.YOUTUBE -> {
                    val stream = streamFetcher.fetch(video).getOrThrow()
                    downloadSingle(stream.url, stream.headers, onProgress)
                }
                Platform.BILIBILI -> {
                    downloadBilibili(video, onProgress)
                }
                else -> throw IOException("${video.platform.displayName}暂不支持视频下载")
            }
            val uri = saveToDownloads(tmpFile, fileName)
            runCatching { tmpFile.delete() }
            onProgress(1f)
            Result.success(uri)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("视频下载失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /** 单文件直下到缓存，再由调用方转存。 */
    private suspend fun downloadSingle(url: String, headers: Map<String, String>, onProgress: (Float) -> Unit): File {
        val tmp = File(context.cacheDir, "download_${System.currentTimeMillis()}.tmp")
        okHttpClient.downloadFile(url, tmp, headers, onProgress)
        return tmp
    }

    /** B站：DASH 视频流 + 最佳音频流分别下载，再用 MediaMuxer 合成为单个 mp4。 */
    private suspend fun downloadBilibili(video: VideoInfo, onProgress: (Float) -> Unit): File {
        val dash = dashFetcher.fetch(video).getOrThrow()
        val videoUrl = dash.video?.firstOrNull { it.baseUrl.isNotBlank() }?.baseUrl
            ?: throw IOException("该视频暂不支持下载（可能为付费内容）")
        val audioUrl = dash.audio?.filter { it.baseUrl.isNotBlank() }?.maxByOrNull { it.bandwidth }?.baseUrl
        val videoHeaders = mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )
        val videoFile = downloadSingle(videoUrl, videoHeaders) { p -> onProgress(p * 0.45f) }
        val outFile = File(context.cacheDir, "download_${System.currentTimeMillis()}.mp4")
        try {
            if (audioUrl != null) {
                val audioFile = downloadSingle(audioUrl, videoHeaders) { p -> onProgress(0.45f + p * 0.45f) }
                try {
                    mux(videoFile, audioFile, outFile)
                } finally {
                    runCatching { audioFile.delete() }
                }
            } else {
                // 无音频流：视频流本身可能已含音频，直接当成品
                videoFile.copyTo(outFile, overwrite = true)
            }
            onProgress(0.95f)
        } finally {
            runCatching { videoFile.delete() }
        }
        return outFile
    }

    /** MediaMuxer 合成：视频轨 + 音频轨 → 单个 mp4（系统原生，无需 ffmpeg）。
     * 借鉴 YunX DashMuxer 的关键点：
     * - 竖屏视频把旋转角写进 moov（MediaMuxer 只认 start 前的 setOrientationHint），否则竖屏变横屏；
     * - 先写完整视频轨再写音轨：本地文件播放无需流式交错，MediaMuxer 会正确生成 moov；
     * - 失败不留半成品：mux 异常时删除 outFile（MediaMuxer.stop 未走到时文件可能只有 ftyp 头）。
     */
    private fun mux(videoFile: File, audioFile: File, outFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoTrack = selectTrack(videoExtractor, "video/")
            val audioTrack = selectTrack(audioExtractor, "audio/")
            if (videoTrack < 0) throw IOException("视频流中没有视频轨")
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // 竖屏旋转角：必须在 start 之前设置
            val rotation = videoExtractor.getTrackFormat(videoTrack).getInteger(MediaFormat.KEY_ROTATION, 0)
            if (rotation != 0) muxer.setOrientationHint(rotation)
            val videoOut = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            var audioOut = -1
            if (audioTrack >= 0) {
                audioOut = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            }
            muxer.start()
            videoExtractor.selectTrack(videoTrack)
            copyTrack(videoExtractor, muxer, videoOut)
            if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack)
                copyTrack(audioExtractor, muxer, audioOut)
            }
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            throw e
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, outTrack: Int) {
        val buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            extractor.advance()
        }
    }

    /** 存入系统下载目录（MediaStore Downloads/VideoNotes），返回 Uri。
     * 借鉴 YunX DownloadSaver 的关键点：
     * - 写时挂 IS_PENDING=1，写完置 0：半成品不进「下载」列表、不占用户视野；
     * - 幽灵文件（文件已删但 MediaStore 残留）导致同名 insert 失败时，自动加时间戳防重保存。
     */
    private fun saveToDownloads(tmpFile: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val baseName = fileName.removeSuffix(".mp4")
        var candidate = fileName
        var uri: Uri? = null
        // 同名 insert 失败时加时间戳重试一次（幽灵文件场景）
        for (attempt in 0..1) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VideoNotes")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) break
            candidate = "${baseName}_${System.currentTimeMillis()}.mp4"
        }
        val target = uri ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(target)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            } ?: throw IOException("无法写入下载文件")
            // 写完解除 pending，文件正式出现在下载列表
            resolver.update(
                target,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) }
            throw e
        }
        return target
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val truncated = if (cleaned.length > 60) cleaned.take(60) else cleaned
        return truncated.ifBlank { "video" }
    }
}
