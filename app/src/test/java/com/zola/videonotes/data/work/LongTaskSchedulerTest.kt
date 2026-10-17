package com.zola.videonotes.data.work

import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长任务调度器测试（M6）：唯一任务命名规则与 inputData 键稳定性。
 * 只测顶层纯函数，不需要 Android Context。
 */
class LongTaskSchedulerTest {

    private val video = VideoInfo(
        platform = Platform.YOUTUBE,
        videoId = "dQw4w9WgXcQ",
        title = "测试视频",
        coverUrl = "https://example.com/cover.jpg",
        durationSec = 600,
        author = "测试作者",
        sourceUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    )

    @Test
    fun `transcribe unique name rule`() {
        assertEquals(
            "transcribe_YOUTUBE:dQw4w9WgXcQ",
            transcribeUniqueName(video.storageKey()),
        )
    }

    @Test
    fun `generate unique name rule`() {
        assertEquals(
            "generate_BILIBILI:BV1xx411c7mD",
            generateUniqueName("BILIBILI:BV1xx411c7mD"),
        )
    }

    @Test
    fun `transcribe request carries video input data`() {
        val request = buildTranscribeRequest(video)
        val input = request.workSpec.input
        assertEquals(video.storageKey(), input.getString(KEY_STORAGE_KEY))
        assertEquals("YOUTUBE", input.getString(KEY_PLATFORM))
        assertEquals("dQw4w9WgXcQ", input.getString(KEY_VIDEO_ID))
        assertEquals("测试视频", input.getString(KEY_TITLE))
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            input.getString(KEY_SOURCE_URL),
        )
        assertEquals(600L, input.getLong(KEY_DURATION_SEC, -1))
        // Worker 类正确
        assertEquals(
            TranscribeWorker::class.java.name,
            request.workSpec.workerClassName,
        )
    }

    @Test
    fun `generate request carries video input data`() {
        val request = buildGenerateNoteRequest(video)
        val input = request.workSpec.input
        assertEquals(video.storageKey(), input.getString(KEY_STORAGE_KEY))
        assertEquals(
            GenerateNoteWorker::class.java.name,
            request.workSpec.workerClassName,
        )
    }

    @Test
    fun `input data round trips to video info`() {
        val restored = video.toInputData().toVideoInfo()
        assertEquals(video.platform, restored?.platform)
        assertEquals(video.videoId, restored?.videoId)
        assertEquals(video.title, restored?.title)
        assertEquals(video.sourceUrl, restored?.sourceUrl)
        assertEquals(video.durationSec, restored?.durationSec)
        assertTrue(restored?.storageKey() == video.storageKey())
    }
}
