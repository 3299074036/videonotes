package com.zola.videonotes.data.network.newpipe

import com.zola.videonotes.data.parser.stringCapped
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException

/**
 * 把 NewPipeExtractor 的网络请求桥接到 App 统一的 OkHttpClient，
 * 使其复用超时 / 重试 / 日志 / 代理配置。
 *
 * 注意：NewPipe 的 Request/Response 与 okhttp3 同名，此处用全限定名区分。
 */
class OkHttpNewPipeDownloader(
    private val client: OkHttpClient,
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        for ((name, values) in request.headers()) {
            for (value in values) {
                builder.addHeader(name, value)
            }
        }
        val body = request.dataToSend()?.toRequestBody("application/octet-stream".toMediaType())
        builder.method(request.httpMethod(), body)

        client.newCall(builder.build()).execute().use { response ->
            val headerMap = mutableMapOf<String, MutableList<String>>()
            for ((name, value) in response.headers) {
                headerMap.getOrPut(name) { mutableListOf() }.add(value)
            }
            return Response(
                response.code,
                response.message,
                headerMap,
                response.body?.stringCapped(),
                response.request.url.toString(),
            )
        }
    }
}
