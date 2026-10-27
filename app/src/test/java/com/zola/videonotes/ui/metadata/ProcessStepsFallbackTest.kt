package com.zola.videonotes.ui.metadata

import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.SubtitleSource
import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.subtitle.SubtitleFetchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字幕未获取原因诊断：工序单第 3 步在转写兜底时写明字幕原因，不再静默转写。
 * 只测纯推导函数 deriveProcessSteps，不碰 ViewModel 与 WorkManager。
 */
class ProcessStepsFallbackTest {

    private fun videoInfo() = VideoInfo(
        platform = Platform.YOUTUBE,
        videoId = "7k6n46xBTxs",
        title = "How the sandwich was invented",
        coverUrl = "",
        durationSec = 108,
        author = "TED-Ed",
        sourceUrl = "https://www.youtube.com/watch?v=7k6n46xBTxs",
    )

    private fun segments(n: Int, source: SubtitleSource) =
        (0 until n).map {
            TranscriptSegment("7k6n46xBTxs", it, it * 1000L, (it + 1) * 1000L, "seg $it", source)
        }

    private fun steps(
        subtitleState: SubtitleUiState,
        transcribeState: TranscribeUiState = TranscribeUiState.Idle,
        reason: String? = null,
    ) = deriveProcessSteps(
        info = videoInfo(),
        subtitleState = subtitleState,
        transcribeState = transcribeState,
        generateState = GenerateNoteUiState.Idle,
        existingNoteId = null,
        pipelineState = PipelineUiState.Idle,
        subtitleFallbackReason = reason,
    ).steps

    @Test
    fun asrDone_showsFallbackReason() {
        val result = steps(
            subtitleState = SubtitleUiState.Fetched(
                SubtitleFetchResult(segments(3, SubtitleSource.ASR), SubtitleSource.ASR, ""),
            ),
            reason = "YouTube 未返回字幕轨道",
        )
        assertEquals(ProcessStepStatus.DONE, result[2].status)
        assertEquals("本机转写 · 共 3 段（字幕未获取：YouTube 未返回字幕轨道）", result[2].detail)
    }

    @Test
    fun asrDone_withoutReason_unchanged() {
        val result = steps(
            subtitleState = SubtitleUiState.Fetched(
                SubtitleFetchResult(segments(3, SubtitleSource.ASR), SubtitleSource.ASR, ""),
            ),
        )
        assertEquals("本机转写 · 共 3 段", result[2].detail)
    }

    @Test
    fun transcribeWorking_showsReasonOnPendingStep3() {
        val result = steps(
            subtitleState = SubtitleUiState.NotFetched("该视频暂无字幕，可尝试下方的语音转写"),
            transcribeState = TranscribeUiState.Working("正在下载音频...", 0.44f),
            reason = "YouTube 暂时限制了访问",
        )
        assertEquals(ProcessStepStatus.PENDING, result[2].status)
        assertEquals("字幕未获取：YouTube 暂时限制了访问，已转写兜底", result[2].detail)
        assertEquals(ProcessStepStatus.ACTIVE, result[1].status)
        assertEquals("正在下载音频...", result[1].detail)
    }

    @Test
    fun subtitlePath_unaffectedByReason() {
        val result = steps(
            subtitleState = SubtitleUiState.Fetched(
                SubtitleFetchResult(
                    segments(5, SubtitleSource.OFFICIAL),
                    SubtitleSource.OFFICIAL,
                    "中文（中国）",
                ),
            ),
        )
        assertEquals("已获取官方字幕，无需提取音频", result[1].detail)
        assertEquals("文稿就绪 · 共 5 段", result[2].detail)
    }

    @Test
    fun idleWithReason_showsGuidance() {
        val result = steps(
            subtitleState = SubtitleUiState.Failed("YouTube 字幕获取失败：x"),
            reason = "YouTube 字幕获取失败：x",
        )
        assertEquals(ProcessStepStatus.PENDING, result[2].status)
        assertEquals("字幕未获取：YouTube 字幕获取失败：x，可用本机转写", result[2].detail)
    }
}
