package com.zola.videonotes.ui.player

import java.util.concurrent.ConcurrentHashMap

/**
 * M-23：播放器页的一次性直链载体。直链含 CDN token，不再经导航参数传递
 * （导航参数会进 saved state，随返回栈持久化）。
 * put → navigate → PlayerViewModel 构造时 take；进程被杀后 take 为空，
 * 播放器退回库里原始链接重解析（既有兜底），不影响播放。
 */
object PlaybackArgsStore {
    data class Args(val mediaUrl: String, val sourceUrl: String)

    private val argsByKey = ConcurrentHashMap<String, Args>()

    fun put(storageKey: String, args: Args) {
        argsByKey[storageKey] = args
    }

    /** 取出并清除；重复进入播放器时不再复用旧直链（可能已过期）。 */
    fun take(storageKey: String): Args? = argsByKey.remove(storageKey)
}
