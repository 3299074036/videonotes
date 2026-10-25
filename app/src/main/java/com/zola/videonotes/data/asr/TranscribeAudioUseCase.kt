package com.zola.videonotes.data.asr

import com.zola.videonotes.data.asr.audio.BilibiliAudioFetcher
import com.zola.videonotes.data.asr.audio.DouyinAudioFetcher
import com.zola.videonotes.data.asr.audio.PcmDecoder
import com.zola.videonotes.data.asr.audio.XhsAudioFetcher
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

/**
 * 转写文本是否为“符号汤”（字节级 BPE 碎片未解码的产物）。
 *
 * 判定（两者同时满足才算汤，缺一不可）：
 * - 一个中文字都没有；
 * - 字母中拉丁扩展字符（U+0100–U+024F，如 Ǝ Ľ ŋ Ş）占比 ≥ 30%。
 *
 * 英文课程本身无中文，但拉丁扩展占比近 0，不会误伤；中英混合有中文，直接放行。
 * 字母总量 < 10 时不判定（样本太小，比值不可信）。纯 Kotlin，可 JVM 单元测试。
 */
internal fun isTranscriptSoup(text: String): Boolean {
    var chinese = 0
    var latinExt = 0
    var letters = 0
    for (ch in text) {
        if (!ch.isLetter()) continue
        letters++
        when (ch.code) {
            in 0x4E00..0x9FFF, in 0x3400..0x4DBF, in 0xF900..0xFAFF -> chinese++
            in 0x0100..0x024F -> latinExt++
        }
    }
    if (letters < 10 || chinese > 0 || latinExt == 0) return false
    return latinExt.toDouble() / letters >= 0.3
}

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
    private val douyinAudioFetcher: DouyinAudioFetcher,
    private val xhsAudioFetcher: XhsAudioFetcher,
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
            val selectedId = prefs.selectedModelId.first()
                ?: throw IOException("尚未选择识别模型，请先到模型管理页选择并下载")
            val model = AsrModelCatalog.get(selectedId)
            if (!downloadManager.isDownloaded(model.id)) {
                throw IOException("语音模型尚未下载，请先在设置 → 语音识别模型中下载")
            }
            val fetcher = when (video.platform) {
                Platform.YOUTUBE -> youtubeAudioFetcher
                Platform.BILIBILI -> bilibiliAudioFetcher
                Platform.DOUYIN -> douyinAudioFetcher
                Platform.XIAOHONGSHU -> xhsAudioFetcher
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
            // 2026-10-24 英文案：字节级 BPE 碎片汤（如 Zipformer-CTC 旧 tokens 路径）
            // 非空但不可读，必须在入库前拦住，否则 LLM 只能用英文分析这堆“密文”。
            if (isTranscriptSoup(rawSegments.joinToString("") { it.text })) {
                throw IOException("转写结果异常，请更换识别模型")
            }

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
