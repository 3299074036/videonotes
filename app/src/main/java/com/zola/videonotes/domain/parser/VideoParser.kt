package com.zola.videonotes.domain.parser

import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo

/**
 * 视频链接解析器接口。
 *
 * 约定：
 * - 每个平台一个实现（YouTube / B站 / 抖音 / 小红书 / 快手 / 微博），平台接口变化时只改对应实现。
 * - [matches] 只做轻量字符串判断，不发起网络请求。
 * - [parse] 失败时返回 Result.failure，异常信息必须是面向用户的中文说明，不抛裸异常。
 */
interface VideoParser {

    /** 该解析器负责的平台。 */
    val platform: Platform

    /** 是否能处理该链接（纯本地判断，无网络开销）。 */
    fun matches(url: String): Boolean

    /** 解析视频元信息；失败时 Result 携带中文原因。 */
    suspend fun parse(url: String): Result<VideoInfo>
}
