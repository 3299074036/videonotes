package com.zola.videonotes.data.network.newpipe

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
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
            }
        }
    }
}
