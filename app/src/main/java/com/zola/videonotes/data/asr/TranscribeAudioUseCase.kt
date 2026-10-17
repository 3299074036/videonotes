package com.zola.videonotes.data.asr

import com.zola.videonotes.data.asr.audio.BilibiliAudioFetcher
import com.zola.videonotes.data.asr.audio.PcmDecoder
import com.zola.videonotes.data.asr.audio.YoutubeAudioFetcher
import com.zola.videonotes.data.asr.engine.SherpaAsrEngine
import com.zola.videonotes.data.local.TranscriptSegmentDao
import com.zola.videonotes.data.local.toEntity
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 转写阶段（面向用户的进度文案）。 */
enum class TranscribeStage(val displayText: String) {
    /** 正在下载音频流 */
    DOWNLOADING_AUDIO("正在下载音频…"),

    /** 正在解码为 PCM */
    DECODING_AUDIO("正在解码音频…"),

    /** 正在端侧转写 */
    TRANSCRIBING("正在转写…"),

    /** 正在保存分段 */
    SAVING("正在保存…"),
}

/**
 * 音频转写用例（M3）：取音频 → 解码为 16kHz PCM → 端侧 ASR → 分句 → 存 Room。
 *
 * 全链路中文错误信息；调用方通过 [onStage] 接收阶段与进度（转写阶段 progress 为 0..1，
 * 其他阶段为 -1 表示不确定进度）。
 */
@Singleton
class TranscribeAudioUseCase @Inject constructor(
    private val youtubeAudioFetcher: YoutubeAudioFetcher,
    private val bilibiliAudioFetcher: BilibiliAudioFetcher,
    private val decoder: PcmDecoder,
    private val engine: SherpaAsrEngine,
    private val prefs: AsrPreferences,
    private val downloadManager: ModelDownloadManager,
    private val dao: TranscriptSegmentDao,
) {

    suspend fun execute(
        video: VideoInfo,
        onStage: (stage: TranscribeStage, progress: Float) -> Unit,
    ): Result<List<TranscriptSegment>> {
        return try {
            val model = AsrModelCatalog.get(prefs.selectedModelId.first())
            if (!downloadManager.isDownloaded(model.id)) {
                throw IOException("语音模型尚未下载，请先在设置 → 语音识别模型中下载")
            }
            val fetcher = when (video.platform) {
                Platform.YOUTUBE -> youtubeAudioFetcher
                Platform.BILIBILI -> bilibiliAudioFetcher
                else -> throw IOException("${video.platform.displayName}暂不支持语音转写")
            }

            onStage(TranscribeStage.DOWNLOADING_AUDIO, -1f)
            val audioFile = fetcher.fetch(video) { p ->
                onStage(TranscribeStage.DOWNLOADING_AUDIO, p)
            }.getOrThrow()

            onStage(TranscribeStage.DECODING_AUDIO, -1f)
            val chunks = decoder.decodeToChunks(audioFile)
            if (chunks.isEmpty()) throw IOException("音频解码失败：未能解出有效音频数据")

            onStage(TranscribeStage.TRANSCRIBING, 0f)
            val rawSegments = engine.transcribe(
                model = model,
                modelDir = downloadManager.modelDir(model.id),
                pcmChunks = chunks,
            ) { p -> onStage(TranscribeStage.TRANSCRIBING, p) }
            if (rawSegments.isEmpty()) throw IOException("转写结果为空，请检查音频是否有效")

            onStage(TranscribeStage.SAVING, -1f)
            val key = video.storageKey()
            val segments = rawSegments.mapIndexed { idx, s ->
                TranscriptSegment(key, idx, s.startMs, s.endMs, s.text, SubtitleSource.ASR)
            }
            dao.replaceAll(key, segments.map { it.toEntity() })
            Result.success(segments)
        } catch (e: CancellationException) {
            // 调用方取消（如界面取消按钮）：原样抛出，不包装为失败结果
            throw e
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("语音转写失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }
}
