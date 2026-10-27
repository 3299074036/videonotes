package com.zola.videonotes.data.network.newpipe

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NewPipe 全局初始化：整个 App 只初始化一次。
 * 视频解析器（M1）与字幕获取器（M2）共享此实例，避免重复 init。
 */
@Singleton
class NewPipeSetup @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val ready = AtomicBoolean(false)

    /** 幂等：已初始化则直接返回。 */
    fun ensureInit() {
        if (ready.get()) return
        synchronized(this) {
            if (ready.compareAndSet(false, true)) {
                NewPipe.init(OkHttpNewPipeDownloader(okHttpClient))
                // iOS 客户端开关保持关闭：2026-10-28 已在真机验证，三明治视频
                // 开启后仍为空轨道（iOS 响应亦无字幕），对字幕无帮助，反而每次
                // 取视频多一次 iOS 请求；故关闭。YouTube 字幕当前拿不到时走
                // 转写兜底，此为已接受的最终形态（见工序页第 3 步原因文案）。
                YoutubeStreamExtractor.setFetchIosClient(false)
            }
        }
    }
}
