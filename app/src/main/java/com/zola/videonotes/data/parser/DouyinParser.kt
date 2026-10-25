package com.zola.videonotes.data.parser

import com.zola.videonotes.data.network.HeadlessWebView
import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抖音解析器（WebView 主路线 + HTTP 回退）。
 *
 * 背景：2026-10 起抖音分享页/详情页对纯 HTTP 返回 `byted_acrawler` JS 挑战页，
 * 页面里没有任何作品数据；页面内 JSON 接口一律要签名（`a_bogus` 等），纯 HTTP 无法拿到。
 * 因此主路线用无头 WebView 真跑一遍页面（挑战 → 重载 → 挂载 → 详情请求），
 * 再用注入的 JS 从 XHR 钩子 / SSR 数据里提取视频直链。
 *
 * 注意：视记只要音频做转写，不需要无水印，所以 play_addr 直链直接能用，
 * 不用再像下载器那样探 `aweme/v1/play` 做无水印处理。
 *
 * 回退：WebView 路线失败时走老 HTTP 路线（分享页 HTML 元信息尽力提取），
 * 仅返回元信息（无直链，无法转写），并在错误里说明。
 */
@Singleton
class DouyinParser @Inject constructor(
    private val redirects: RedirectResolver,
    private val client: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.DOUYIN

    private val videoIdPattern = Regex("""/video/(\d+)""")

    override fun matches(url: String): Boolean =
        DOUYIN_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            val finalUrl = redirects.resolve(cleanUrl)
            val videoId = videoIdPattern.find(finalUrl)?.groupValues?.get(1)
                ?: throw IOException("未能从抖音链接中识别视频 ID：分享页结构可能已变化")
            // 主路线：无头 WebView
            parseViaWebView(videoId, url.trim())?.let { return Result.success(it) }
            // 回退：老 HTTP 路线（仅元信息）
            parseViaHttp(finalUrl, videoId, url.trim())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("抖音解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    /** WebView 主路线：加载详情页，等 XHR 钩子或 SSR 数据就绪后提取。 */
    private suspend fun parseViaWebView(videoId: String, sourceUrl: String): VideoInfo? {
        val json = HeadlessWebView.loadAndExtract(
            url = "https://www.douyin.com/video/$videoId",
            userAgent = WEBVIEW_UA,
            readyCheckJs = READY_CHECK_JS,
            extractJs = EXTRACT_JS,
            timeoutMs = 40_000L,
            extraDocumentStartJs = listOf(XHR_HOOK_JS),
        ) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val videoUrl = obj.optString("video").takeIf { it.isNotBlank() } ?: return null
        return VideoInfo(
            platform = Platform.DOUYIN,
            videoId = videoId,
            title = obj.optString("title").ifBlank { "抖音视频 $videoId" },
            coverUrl = obj.optString("cover").ensureHttps(),
            durationSec = obj.optLong("duration"),
            author = obj.optString("author"),
            sourceUrl = sourceUrl,
            mediaUrl = videoUrl.ensureHttps(),
            mediaHeaders = mapOf(
                "Referer" to "https://www.douyin.com/",
                "User-Agent" to WEBVIEW_UA,
            ),
        )
    }

    /** HTTP 回退路线：分享页 HTML 元信息尽力提取（无直链，仅元信息）。 */
    private suspend fun parseViaHttp(
        finalUrl: String,
        videoId: String,
        sourceUrl: String,
    ): Result<VideoInfo> {
        val html = fetchHtml(finalUrl)
        val title = extractJsonField(html, "desc")?.unescapeJson().orEmpty()
        val author = extractJsonField(html, "nickname")?.unescapeJson().orEmpty()
        val cover = extractCover(html).orEmpty()
        if (title.isBlank() && author.isBlank()) {
            throw IOException("抖音解析失败：WebView 路线未拿到数据，且分享页结构已变化无法提取元信息")
        }
        return Result.success(
            VideoInfo(
                platform = Platform.DOUYIN,
                videoId = videoId,
                title = title.ifBlank { "抖音视频 $videoId" },
                coverUrl = cover.ensureHttps(),
                durationSec = 0,
                author = author,
                sourceUrl = sourceUrl,
            ),
        )
    }

    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RedirectResolver.MOBILE_UA)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("抖音分享页请求失败（HTTP ${response.code}），可能触发平台风控")
            }
            response.body?.string().orEmpty()
        }
    }

    /** 从内嵌 JSON 中提取 "key":"value" 的首个 value（原始转义形态）。 */
    private fun extractJsonField(html: String, key: String): String? =
        Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(html)?.groupValues?.get(1)

    /** 尽力提取封面：origin_cover.url_list 首个 https 地址。 */
    private fun extractCover(html: String): String? {
        val coverBlock = Regex(""""origin_cover"\s*:\s*\{[^}]{0,2000}""").find(html)?.value
            ?: return null
        return Regex(""""url_list"\s*:\s*\[\s*"(https:[^"]+)"""").find(coverBlock)
            ?.groupValues?.get(1)?.replace("\\/", "/")
    }

    companion object {
        private val DOUYIN_HOSTS = listOf("douyin.com", "v.douyin.com", "iesdouyin.com")

        /** WebView UA：桌面 Chrome（与 Client Hints / navigator.platform 对齐）。 */
        private const val WEBVIEW_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /**
         * XHR 钩子（文档开始前注入）：拦截页面自身的详情请求，
         * 把 `aweme/v1/web/aweme/detail` 的响应存到 `window.__vnAwemeDetail`。
         * 注意：页面若把 responseType 设成 'json'，读 responseText 会抛 InvalidStateError，
         * 此时改读 response 对象。
         */
        private const val XHR_HOOK_JS = """
(function () {
  try {
    if (window.__vnDetailHookInstalled) return;
    window.__vnDetailHookInstalled = true;
    var rawOpen = XMLHttpRequest.prototype.open;
    var rawSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__vnUrl = String(url || ''); } catch (e) { this.__vnUrl = ''; }
      return rawOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            var url = xhr.__vnUrl || '';
            if (url.indexOf('aweme/v1/web/aweme/detail') < 0) return;
            if (xhr.status !== 200) return;
            var text = '';
            try { text = xhr.responseText || ''; } catch (e) { text = ''; }
            if (!text) {
              try {
                var r = xhr.response;
                if (r) text = (typeof r === 'string') ? r : JSON.stringify(r);
              } catch (e) { text = ''; }
            }
            if (text) window.__vnAwemeDetail = text;
          } catch (e) {}
        });
      } catch (e) {}
      return rawSend.apply(this, arguments);
    };
  } catch (e) {}
})()
"""

        /**
         * 就绪判定：XHR 钩子已拿到详情，或 SSR 数据里已有 videoDetail。
         * 注意不按链接类型分支：`/video/<id>` 可能被 302 到图文页，视频判据在图文页上永不成立。
         */
        private const val READY_CHECK_JS = """
(function () {
  var raw = window.__vnAwemeDetail;
  if (typeof raw === 'string' && raw.length > 0) return 'true';
  var node = document.querySelector('#RENDER_DATA');
  if (node && node.textContent && node.textContent.indexOf('videoDetail') >= 0) return 'true';
  if (window.SSR_RENDER_DATA) return 'true';
  return 'false';
})()
"""

        /**
         * 提取：优先 XHR 钩子详情，其次 SSR RENDER_DATA，只回传必要字段
         * （evaluateJavascript 传大字符串又慢又容易出问题）。
         * 视频直链取 play_addr.url_list 首个（带水印亦可，视记只取音频）。
         */
        private const val EXTRACT_JS = """
(function () {
  try {
    function firstUrl(node) {
      if (!node) return '';
      var list = node.url_list || node.urlList || null;
      if (list && list.length) return String(list[0] || '');
      return '';
    }
    function pickVideo(detail) {
      if (!detail) return null;
      var video = detail.video;
      if (!video) return null;
      var url = firstUrl(video.play_addr) || firstUrl(video.playAddr);
      if (!url) return null;
      return {
        url: url,
        cover: firstUrl(video.cover) || firstUrl(video.origin_cover) || '',
        duration: video.duration || 0
      };
    }
    var detail = null;
    var raw = window.__vnAwemeDetail || '';
    if (raw) {
      try {
        var root = JSON.parse(raw);
        detail = root.aweme_detail || root.awemeDetail || (root.data && root.data.aweme_detail) || null;
      } catch (e) { detail = null; }
    }
    if (!detail) {
      try {
        var node = document.querySelector('#RENDER_DATA');
        if (node && node.textContent) {
          var app = JSON.parse(decodeURIComponent(node.textContent)).app;
          if (app && app.videoDetail) detail = app.videoDetail;
        }
      } catch (e) {}
    }
    if (!detail && window.SSR_RENDER_DATA) {
      try {
        var ssr = window.SSR_RENDER_DATA;
        detail = ssr.videoDetail || (ssr.app && ssr.app.videoDetail) || null;
      } catch (e) {}
    }
    if (!detail) return JSON.stringify({ video: '' });
    var v = pickVideo(detail);
    if (!v) return JSON.stringify({ video: '' });
    var author = detail.author || {};
    return JSON.stringify({
      video: v.url,
      title: detail.desc || '',
      author: author.nickname || '',
      cover: v.cover || firstUrl(detail.video ? detail.video.cover : null) || '',
      duration: Math.round((v.duration || 0) / 1000)
    });
  } catch (e) {
    return JSON.stringify({ video: '' });
  }
})()
"""
    }
}
