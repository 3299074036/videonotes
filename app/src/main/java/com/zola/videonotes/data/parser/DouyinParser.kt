package com.zola.videonotes.data.parser

import com.zola.videonotes.data.network.HeadlessWebView
import com.zola.videonotes.data.network.RedirectResolver
import com.zola.videonotes.domain.model.Platform
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.parser.VideoParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 抖音解析器（视频 + 图文/图集）。
 *
 * 逻辑与参考实现 YunX 的 DouyinParser 完全对齐（AGPL-3.0，仅作接口适配）：
 * - 主路线：隐藏 WebView 真跑一遍页面（过 byted_acrawler 挑战 → 重载 → 挂载 → 详情请求），
 *   从 XHR 钩子 / SSR / DOM 里提取视频直链；无水印在 JS 里直接 `playwm` → `play` 替换。
 * - 回退：老的 HTTP + SSR 路线（2026-10 起基本必然拿不到数据，仅作保留）。
 * - 图文：返回图片列表，调用方据此提示不支持转写（视记只做视频笔记）。
 *
 * 关键细节（照搬，勿改）：
 * 1. 就绪判定两条路任一成立：XHR 钩子有数据，或 DOM 里有真正的作品图
 *    （`biz_tag=aweme_images` / `tplv-dy-aweme-images`，排除封面与相关推荐）。
 * 2. 提取优先级：SSR（RENDER_DATA 的 app.videoDetail，图文/动图完整清单）→
 *    XHR 钩子（视频页详情）→ DOM 作品图兜底；图片与动图必须成对、同一数据源。
 * 3. 钩子必须在文档开始前挂上，否则页面自己的详情请求已发出。
 */
@Singleton
class DouyinParser @Inject constructor(
    private val redirects: RedirectResolver,
    private val client: OkHttpClient,
) : VideoParser {

    override val platform: Platform = Platform.DOUYIN

    private val videoIdPattern = Regex("""/(?:video|note)/(\d+)""")
    private val typePattern = Regex("""/(?:share/)?(video|note|slides)/""")

    /** 探测无水印直链时依次尝试的清晰度（仅 HTTP 回退路线用；WebView 路线在 JS 里直接替换）。 */
    private val playRatios = listOf("1080p", "720p", "540p", "360p")
    private val videoIdFromUrlPattern = Regex("""[?&]video_id=([^&\s]+)""")
    private val contentRangeTotalPattern = Regex("""/(\d+)\s*$""")

    /** 进程内缓存的匿名 ttwid（仅 HTTP 回退路线用）。 */
    @Volatile
    private var cachedTtwid: String? = null

    override fun matches(url: String): Boolean =
        DOUYIN_HOSTS.any { url.contains(it, ignoreCase = true) }

    override suspend fun parse(url: String): Result<VideoInfo> {
        return try {
            val cleanUrl = extractFirstUrl(url) ?: url.trim()
            val finalUrl = redirects.resolve(cleanUrl)
            val videoId = videoIdPattern.find(finalUrl)?.groupValues?.get(1)
                ?: throw IOException("未能从抖音链接中识别视频 ID：分享页结构可能已变化")
            val type = typePattern.find(finalUrl)?.groupValues?.get(1)
                ?.takeIf { it == "note" } ?: "video"
            // 主路线：隐藏 WebView 真跑一遍页面
            fetchViaWebView(type, videoId, url.trim())?.let { return Result.success(it) }
            // 回退：老的 HTTP + SSR 路线
            parseViaHttp(type, videoId, url.trim())
        } catch (e: IOException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Result.failure(IOException("抖音解析失败：${t.message ?: t.javaClass.simpleName}"))
        }
    }

    // ------------------------------------------------------------------ 隐藏 WebView 路线

    /** 隐藏 WebView 从页面自己的详情响应里取回的作品事实。 */
    private data class WebPayload(
        val title: String,
        val author: String,
        val images: List<String>,
        val video: String,
        val cover: String,
        /** 与 [images] 一一对应的动图资源；没有动图时是等长的空串。 */
        val motions: List<String> = emptyList(),
    )

    /**
     * 用隐藏 WebView 打开作品页并取回事实；拿不到（超时 / 页面没渲染出东西 / 没有前台 Activity）返回 null。
     * 走 `www.douyin.com/{note|video}/{id}`：分享域只是 302 到这里，直接打开终点域名可省一次跳转。
     * 用桌面 UA：站点是 PC 版详情页，桌面 UA 才走那条渲染分支。
     */
    private suspend fun fetchViaWebView(type: String, videoId: String, sourceUrl: String): VideoInfo? {
        val url = "https://www.douyin.com/$type/$videoId"
        val json = HeadlessWebView.loadAndExtract(
            url = url,
            userAgent = UA_CHROME,
            readyCheckJs = readyCheckJs(),
            extractJs = EXTRACT_JS,
            // 页面要在 WebView 里跑完 acrawler 挑战 → 重载 → 挂载 → 再自己发详情请求才拿到数据，
            // 实测视频页冷启动要 25s 上下，所以留足余量（比默认值大）
            timeoutMs = 40_000L,
            // 钩子必须在文档开始前挂上，才能拿到页面自己发出的详情响应（视频那条路要用）
            extraDocumentStartJs = listOf(XHR_DETAIL_HOOK_JS),
        ) ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        // JS 返回 error 字段表示提取脚本内部异常
        obj.optString("error").takeIf { it.isNotBlank() && it != "null" }?.let { return null }
        val images = obj.optJSONArray("images")?.toStringList().orEmpty()
        val motions = obj.optJSONArray("motions")?.toStringList().orEmpty()
        val video = obj.optString("video").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        if (images.isEmpty() && video.isBlank()) return null
        val payload = WebPayload(
            title = obj.optString("title").takeIf { it != "null" }.orEmpty(),
            author = obj.optString("author").takeIf { it != "null" }.orEmpty(),
            images = images,
            video = video,
            cover = obj.optString("cover").takeIf { it != "null" }.orEmpty(),
            motions = motions,
        )
        return buildFromWebView(videoId, sourceUrl, payload)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    /** 把 WebView 取回的事实拼成 VideoInfo（图文优先：详情里 `images` 有值就是图文）。 */
    private fun buildFromWebView(videoId: String, sourceUrl: String, payload: WebPayload): VideoInfo {
        val mediaHeaders = mapOf(
            "User-Agent" to UA_IPHONE,
            "Referer" to "https://www.douyin.com/",
        )
        // 图文：视记只做视频笔记，明确告知不支持（而非静默失败）
        if (payload.images.isNotEmpty()) {
            throw IOException("该抖音内容为图文笔记（${payload.images.size} 张图），暂不支持转写生成笔记")
        }
        return VideoInfo(
            platform = Platform.DOUYIN,
            videoId = videoId,
            title = payload.title.ifBlank { "抖音视频 $videoId" },
            coverUrl = payload.cover.ensureHttps(),
            durationSec = 0,
            author = payload.author,
            sourceUrl = sourceUrl,
            mediaUrl = payload.video.ensureHttps(),
            mediaHeaders = mediaHeaders,
        )
    }

    /**
     * 「文档开始前」注入的 XHR 钩子：把页面自己发出的作品详情响应原文存到 `window.__yunxAwemeDetail`。
     * 只认 `aweme/v1/web/aweme/detail`，且只收 200 且响应体非空。
     * 不能只读 responseText：页面若把 responseType 设成 'json'，读 responseText 会抛
     * InvalidStateError（被 try 吞掉 → 钩子永远拿不到值，表现就是「一直不就绪」）。
     */
    private val XHR_DETAIL_HOOK_JS = """
(function () {
  try {
    if (window.__yunxDetailHookInstalled) return;
    window.__yunxDetailHookInstalled = true;
    var rawOpen = XMLHttpRequest.prototype.open;
    var rawSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      try { this.__yunxUrl = String(url || ''); } catch (e) { this.__yunxUrl = ''; }
      return rawOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var xhr = this;
      try {
        xhr.addEventListener('load', function () {
          try {
            var url = xhr.__yunxUrl || '';
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
            if (text) window.__yunxAwemeDetail = text;
          } catch (e) {}
        });
      } catch (e) {}
      return rawSend.apply(this, arguments);
    };
  } catch (e) {}
})()
"""

    /**
     * 就绪判断：两条路任一条成立即可。
     * 不能按链接里的类型分支：`/video/<id>`、 `?modal_id=<id>` 这类链接抖音会自行
     * 302 到真正的作品页（图文 / 动图会落到 `/note/<id>`），于是「按链接判类型」必然判错 ——
     * 视频那条判据（等 XHR 钩子）在图文页上永远不会成立，只能干等到 40s 超时后整条解析失败。
     * DOM 那条判据只认真正的作品图：`biz_tag=aweme_images` 或 `tplv-dy-aweme-images`。
     * 封面是 `biz_tag=pcweb_cover`、「相关推荐」带 `WEBPC_RELATED`，两者都不算 —— 否则视频页会
     * 刚渲染出封面就「就绪」，拿回一堆封面当作品图（视频直链只在钩子数据里，早退就丢了）。
     */
    private fun readyCheckJs(): String = """
(function () {
  var raw = window.__yunxAwemeDetail;
  if (typeof raw === 'string' && raw.length > 0) return 'true';
  var imgs = document.querySelectorAll('img');
  for (var i = 0; i < imgs.length; i++) {
    if (isWorkImage(imgs[i].src || '')) return 'true';
  }
  return 'false';
  function isWorkImage(s) {
    if (!s || s.indexOf('douyinpic.com') < 0) return false;
    if (s.indexOf('WEBPC_RELATED') >= 0) return false;
    return s.indexOf('biz_tag=aweme_images') >= 0 || s.indexOf('tplv-dy-aweme-images') >= 0;
  }
})()
"""

    /**
     * 取数：多条数据源都试，返回一个小对象（`evaluateJavascript` 会把它序列化成 JSON）。
     * 关键取舍：图片与动图资源必须成对、来自同一个源，否则下标会错位。优先级：
     * 1. SSR（`<script id="RENDER_DATA">` 里 URL 编码的 `app.videoDetail`）—— 图文/动图的
     *    完整清单与每张的动图资源（`images[].video.playAddr[].src`）都在这里，动图只有这条路能拿到；
     * 2. XHR 钩子（`window.__yunxAwemeDetail`）—— 视频页的详情，视频直链在这里；
     * 3. DOM 里的作品图 —— 最后兜底，拿不到动图资源（所以动图会退化成静态图）。
     * 不把整份详情（100KB+）回传：`evaluateJavascript` 传大字符串又慢又容易出问题，只回传必要字段。
     */
    private val EXTRACT_JS = """
(function () {
  try {
    function firstUrl(node) {
      if (!node) return '';
      var list = node.url_list || node.urlList || null;
      if (list && list.length) return String(list[0] || '');
      return '';
    }

    function keysOf(node) {
      if (!node || typeof node !== 'object') return '';
      var keys = [];
      for (var p in node) keys.push(p);
      return keys.join(',');
    }

    // 动图资源：每张图各自的短视频（Live Photo 的「会动的那一半」）。两种数据源形态不同：
    // - 网页 SSR：`video.playAddr` 是 [{src: '...'}]
    // - 接口 JSON（XHR 钩子）：`video.play_addr` 是 {uri, url_list: [...]}（snake_case）
    function motionUrl(item) {
      if (!item) return '';
      var v = item.video;
      if (!v) return '';
      var direct = firstUrl(v.playAddr) || firstUrl(v.play_addr)
          || firstUrl(v.playApi) || firstUrl(v.play_addr_h264)
          || firstUrl(v.downloadAddr) || firstUrl(v.download_addr);
      if (direct) return direct;
      var list = v.playAddr || v.play_addr || null;
      if (list && list.length) {
        var first = list[0];
        if (first) return String(first.src || first.url || '');
      }
      return '';
    }

    // 钩子那份详情（视频/图文页页面自己会请求 aweme/v1/web/aweme/detail）
    function hookDetail() {
      var raw = window.__yunxAwemeDetail || '';
      if (!raw) return null;
      try {
        var root = JSON.parse(raw);
        return root.aweme_detail || root.awemeDetail || (root.data && root.data.aweme_detail) || null;
      } catch (e) { return null; }
    }

    // SSR 详情：抖音把作品详情 URL 编码后塞在 <script id="RENDER_DATA"> 里（app.videoDetail）。
    // 记录失败原因（ssrNote），下次「为什么没走到 SSR」直接看日志即可。
    var ssrNote = 'none';
    function readSsrDetail() {
      var node = document.querySelector('#RENDER_DATA');
      if (node && node.textContent) {
        try {
          var app = JSON.parse(decodeURIComponent(node.textContent)).app;
          if (app && app.videoDetail) { ssrNote = 'rd-ok'; return app.videoDetail; }
          ssrNote = 'rd-no-videoDetail';
        } catch (e) {
          ssrNote = 'rd-' + (e && e.name ? e.name : 'parse-fail');
        }
      } else {
        ssrNote = 'rd-missing';
      }
      try {
        var ssr = window.SSR_RENDER_DATA;
        if (ssr && ssr.app && ssr.app.videoDetail) { ssrNote += '+global-ok'; return ssr.app.videoDetail; }
        ssrNote += '+global-' + (typeof ssr);
      } catch (e) {}
      return null;
    }

    // 图片 + 动图资源成对抽取（保证下标一致）；同时带上首项的字段名，便于排查字段改版
    function imagesFrom(node) {
      if (!node || !node.length) return null;
      var list = [];
      var mlist = [];
      for (var i = 0; i < node.length; i++) {
        var item = node[i];
        var u = firstUrl(item);
        if (!u && item.display_image) u = firstUrl(item.display_image);
        if (!u && item.owner_watermark_image) u = firstUrl(item.owner_watermark_image);
        if (u) {
          list.push(u);
          mlist.push(motionUrl(item));
        }
      }
      if (!list.length) return null;
      return {
        images: list,
        motions: mlist,
        imgKeys: keysOf(node[0]),
        videoKeys: keysOf(node[0] && node[0].video)
      };
    }

    // DOM 里的作品图兜底（只认 aweme_images / tplv-dy-aweme-images，排除封面与「相关推荐」）
    function domWorkImages() {
      var result = [];
      var all = document.querySelectorAll('img');
      for (var i = 0; i < all.length; i++) {
        var src = all[i].src || '';
        if (src.indexOf('douyinpic') < 0) continue;
        if (src.indexOf('WEBPC_RELATED') >= 0) continue;
        var isWork = src.indexOf('biz_tag=aweme_images') >= 0 || src.indexOf('tplv-dy-aweme-images') >= 0;
        if (isWork && result.indexOf(src) < 0) result.push(src);
      }
      return result;
    }

    var hooked = hookDetail();
    var ssr = readSsrDetail();

    var images = [];
    var motions = [];
    var src = '';
    var picked = imagesFrom(ssr && ssr.images);
    if (picked) { src = 'ssr'; }
    if (!picked) {
      picked = imagesFrom(hooked && hooked.images);
      if (picked) { src = 'hook'; }
    }
    if (!picked) {
      picked = imagesFrom(hooked && hooked.image_post_info && hooked.image_post_info.images);
      if (picked) { src = 'hook'; }
    }
    if (picked) {
      images = picked.images;
      motions = picked.motions;
    } else {
      images = domWorkImages();
      src = images.length ? 'dom' : 'none';
    }

    // 视频直链：钩子优先（视频页的详情在这里），其次 SSR
    var v = (hooked && hooked.video) || (ssr && ssr.video) || null;
    var video = '';
    if (v) {
      video = firstUrl(v.play_addr) || firstUrl(v.playAddr) || firstUrl(v.play_addr_h264);
      if (!video && v.bit_rate && v.bit_rate.length) {
        for (var i = 0; i < v.bit_rate.length; i++) {
          var candidate = firstUrl(v.bit_rate[i].play_addr);
          if (candidate) { video = candidate; break; }
        }
      }
      video = video.replace('/playwm/', '/play/');
    }

    var d = hooked || ssr;
    var author = '';
    if (d && d.author) author = d.author.nickname || d.author.unique_id || '';
    if (!author && d && d.authorInfo) author = d.authorInfo.nickname || d.authorInfo.nickName || '';

    // 标题：优先详情里的 desc；没有就退到 document.title —— 抖音详情页标题就是「<文案> - 抖音」，
    // 去掉尾巴即可，实测与 desc 等价。
    var title = (d && d.desc) || '';
    if (!title) {
      var pageTitle = document.title || '';
      var tail = pageTitle.lastIndexOf(' - 抖音');
      if (tail > 0) pageTitle = pageTitle.substring(0, tail);
      title = pageTitle;
    }

    var motionCount = 0;
    for (var m = 0; m < motions.length; m++) {
      if (motions[m]) motionCount++;
    }

    return {
      title: title,
      author: author,
      video: video,
      images: images,
      motions: motions,
      src: src,
      motionCount: motionCount,
      // 诊断字段：没取到动图时靠它们定位（数据源、SSR 状态、图片项与 video 节点的字段名）
      ssr: ssrNote,
      imgKeys: picked ? picked.imgKeys : '',
      videoKeys: picked ? picked.videoKeys : '',
      cover: (v && (firstUrl(v.cover) || firstUrl(v.origin_cover))) || ''
    };
  } catch (e) {
    return { error: 'js: ' + e };
  }
})()
"""

    // ------------------------------------------------------------------ HTTP 回退路线

    /** 老的分享页地址（仅回退路线用；主路线直接打开 `www.douyin.com/{type}/{id}`）。 */
    private fun buildShareUrl(type: String, videoId: String): String =
        "https://www.iesdouyin.com/share/$type/$videoId/"

    /**
     * 拉分享页并取出作品对象；服务端没给作品数据时，换一枚新的匿名 ttwid 再试一次。
     * 抖音是按匿名 Cookie（ttwid）决定要不要在 SSR 里塞作品数据的。缓存里那枚
     * 一旦被风控标记，后续每次都只会返回「有元数据、没有 `videoInfoRes`」的页面。
     * 只在失败路径上多花一次请求：第一次就成功的话行为和以前完全一致。
     */
    private suspend fun parseViaHttp(type: String, videoId: String, sourceUrl: String): Result<VideoInfo> {
        val shareUrl = buildShareUrl(type, videoId)
        for (attempt in 0..1) {
            val ttwid = if (attempt == 0) ensureTtwid() else renewTtwid()
            try {
                val html = fetchHtml(shareUrl, ttwid)
                val routerJson = extractInlineJson(html, "window._ROUTER_DATA")
                    ?: throw IOException("抖音分享页里没有 window._ROUTER_DATA（作品可能已删除或页面改版）")
                val data = extractVideoData(routerJson, videoId)
                return Result.success(buildFromHttpData(data, videoId, sourceUrl))
            } catch (e: IOException) {
                // 只有「页面/数据不对」这一类值得换 ttwid 重试；网络失败再试一次也是白搭。
                // 第二次仍失败就把它抛出去。
                if (attempt == 1) throw e
            }
        }
        throw IOException("抖音解析失败")
    }

    private suspend fun fetchHtml(url: String, ttwid: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_IPHONE)
            .header("Referer", "https://www.iesdouyin.com/")
            .header("Cookie", ttwid)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("抖音分享页请求失败（HTTP ${response.code}），可能触发平台风控")
            }
            response.body?.string().orEmpty()
        }
    }

    /** 从 HTML 里抠 `window.KEY = {...};` 内嵌 JSON（括号匹配，容忍分号与换行）。 */
    private fun extractInlineJson(html: String, key: String): String? {
        val start = html.indexOf(key) ?: return null
        if (start < 0) return null
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in braceStart until html.length) {
            val c = html[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(braceStart, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 从 `window._ROUTER_DATA` 里取出作品对象。
     * 字段名按参考实现走：显式 `name=` 过的键是 camelCase（`videoInfoRes`、`video_(id)/page`），
     * 其余字段直接用 Python 字段名（snake_case，如 `item_list`）。
     */
    private fun extractVideoData(routerJson: String, expectedId: String): JSONObject {
        val router = runCatching { JSONObject(routerJson) }.getOrNull()
            ?: throw IOException("window._ROUTER_DATA 不是合法 JSON")
        val loaderData = router.optJSONObject("loaderData")
            ?: throw IOException("window._ROUTER_DATA 里没有 loaderData")
        val page = loaderData.optJSONObject("video_(id)/page")
            ?: loaderData.optJSONObject("note_(id)/page")
            ?: throw IOException("window._ROUTER_DATA 里既没有 video_(id)/page 也没有 note_(id)/page")
        val videoInfoRes = page.optObjectAny("videoInfoRes", "video_info_res")
        val itemList = videoInfoRes?.optArrayAny("item_list", "itemList")
        itemList?.optJSONObject(0)?.let { return it }
        val echoedId = page.optStringAny("itemId", "item_id")
        val detail = buildString {
            append("videoInfoRes=").append(if (videoInfoRes == null) "缺失" else "存在")
            append(", item_list=").append(if (itemList == null) "缺失" else "长度 ${itemList.length()}")
            append(", itemId=").append(echoedId.ifBlank { "缺失" })
            append(", 请求id=").append(expectedId)
            append(", isSpider=").append(page.optBoolean("isSpider"))
        }
        throw IOException("抖音没有返回该作品的数据（作品可能已删除或不可见，也可能被风控拦截）[$detail]")
    }

    /** 从 HTTP 路线的数据拼 VideoInfo（视频走无水印探测，图文返回图片数提示）。 */
    private suspend fun buildFromHttpData(data: JSONObject, videoId: String, sourceUrl: String): VideoInfo {
        val title = data.optStringAny("desc")
        val author = data.optObjectAny("author")
        val mediaHeaders = mapOf(
            "User-Agent" to UA_IPHONE,
            "Referer" to "https://www.iesdouyin.com/",
        )
        val images = data.optArrayAny("images")
        val video = data.optObjectAny("video")
        if (images != null && images.length() > 0) {
            throw IOException("该抖音内容为图文笔记（${images.length()} 张图），暂不支持转写生成笔记")
        }
        if (video != null) {
            val resolved = resolveVideo(video, mediaHeaders)
            if (resolved != null) {
                return VideoInfo(
                    platform = Platform.DOUYIN,
                    videoId = videoId,
                    title = title.ifBlank { "抖音视频 $videoId" },
                    coverUrl = firstUrl(video.optObjectAny("cover")).orEmpty().ensureHttps(),
                    durationSec = 0,
                    author = author?.optStringAny("nickname").orEmpty(),
                    sourceUrl = sourceUrl,
                    mediaUrl = resolved.ensureHttps(),
                    mediaHeaders = mediaHeaders,
                )
            }
        }
        throw IOException("抖音分享页未返回可用视频数据")
    }

    /** 无水印解析（仅 HTTP 回退路线用）：优先探 play 端点，探不到则 playwm→play 回退。 */
    private suspend fun resolveVideo(video: JSONObject, mediaHeaders: Map<String, String>): String? {
        val playAddr = video.optObjectAny("play_addr", "playAddr")
        extractPlayToken(playAddr)?.let { token ->
            probePlay(token, mediaHeaders)?.let { return it }
        }
        return firstUrl(playAddr)?.replace("playwm", "play")
    }

    /** 有 `uri` 用 `uri`，否则遍历 play_addr 直链从 `video_id` 查询参数里取。 */
    private fun extractPlayToken(playAddr: JSONObject?): String? {
        val uri = playAddr?.optStringAny("uri").orEmpty()
        if (uri.isNotBlank()) return uri
        val urlList = playAddr?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val videoId = videoIdFromUrlPattern.find(urlList.optString(i))?.groupValues?.getOrNull(1)
            if (!videoId.isNullOrBlank()) return videoId
        }
        return null
    }

    /**
     * 逐个清晰度探测 play 端点，取总文件体积最大的一路。
     * 各 ratio 指向同一视频的不同转码，体积大即更清晰；用 `Range: bytes=0-1` 只取 1 字节即可
     * 从 `Content-Range`/`Content-Length` 拿到总长。单路失败（超时/被拒）不影响其它清晰度。
     */
    private suspend fun probePlay(videoId: String, mediaHeaders: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            var bestUrl: String? = null
            var bestSize = 0L
            for (ratio in playRatios) {
                val url = "https://aweme.snssdk.com/aweme/v1/play/?video_id=$videoId&ratio=$ratio"
                val result = runCatching {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", mediaHeaders["User-Agent"].orEmpty())
                        .header("Referer", mediaHeaders["Referer"].orEmpty())
                        .header("Range", "bytes=0-1")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val size = response.header("Content-Range")
                            ?.substringAfter("/", "")
                            ?.toLongOrNull()
                            ?: response.header("Content-Length")?.toLongOrNull()
                            ?: 0L
                        val finalUrl = response.request.url.toString()
                        if (size > 0L) finalUrl to size else null
                    }
                }.getOrNull() ?: continue
                if (result.second > bestSize) {
                    bestSize = result.second
                    bestUrl = result.first
                }
            }
            bestUrl
        }

    // ------------------------------------------------------------------ ttwid

    private suspend fun ensureTtwid(): String {
        cachedTtwid?.let { return it }
        return registerTtwid().also { cachedTtwid = it }
    }

    /** 丢掉缓存里的 ttwid 并重新注册一枚（缓存那枚可能已被风控标记）。 */
    private suspend fun renewTtwid(): String {
        cachedTtwid = null
        return registerTtwid().also { cachedTtwid = it }
    }

    /** 注册匿名 ttwid（直接读多条 Set-Cookie，取 ttwid）。 */
    private suspend fun registerTtwid(): String = withContext(Dispatchers.IO) {
        val body = """{"region":"cn","aid":1768,"needFid":false,"service":"www.iesdouyin.com","union":true,"fid":""}"""
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(TTWID_REGISTER_URL)
            .header("User-Agent", UA_IPHONE)
            .header("Referer", "https://www.iesdouyin.com/")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw IOException("ttwid 注册请求失败", e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("ttwid 注册失败（HTTP ${resp.code}）")
            }
            val registered = resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
            val redirectUrl = try {
                JSONObject(resp.body?.string().orEmpty()).optString("redirect_url")
            } catch (e: Exception) {
                ""
            }
            val fromCallback = if (redirectUrl.isBlank()) null else try {
                callbackTtwid(redirectUrl)
            } catch (e: IOException) {
                null
            }
            fromCallback ?: registered
                ?: throw IOException("ttwid 注册未返回可用 Cookie")
        }
    }

    /** 跟随 register 返回的 `redirect_url` 再收一次 Set-Cookie（仅在 IO 线程里用）。 */
    private fun callbackTtwid(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA_IPHONE)
            .header("Referer", "https://www.iesdouyin.com/")
            .get()
            .build()
        return client.newBuilder().followRedirects(false).build().newCall(request).execute().use { resp ->
            resp.headers.values("Set-Cookie").firstNotNullOfOrNull { extractTtwid(it) }
        }
    }

    private fun extractTtwid(setCookie: String): String? =
        Regex("""ttwid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    // ------------------------------------------------------------------ 工具

    private fun firstUrl(node: JSONObject?): String? {
        val urlList = node?.optArrayAny("url_list", "urlList") ?: return null
        for (i in 0 until urlList.length()) {
            val url = urlList.optString(i)
            if (url.isNotBlank()) return url
        }
        return null
    }

    companion object {
        private val DOUYIN_HOSTS = listOf("douyin.com", "v.douyin.com", "iesdouyin.com")
        private const val TTWID_REGISTER_URL = "https://ttwid.bytedance.com/ttwid/union/register/"

        /** 桌面 Chrome UA（WebView 路线用，与 Client Hints / navigator.platform 对齐）。 */
        private const val UA_CHROME =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** iPhone UA（分享页与媒体直链用）。 */
        private const val UA_IPHONE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}

// ---------------------------------------------------------------------- JSON 取值
// 抖音分享页同一字段在不同端点上 casing 不一致（snake_case / camelCase 混用），
// 这里统一按「候选键」取值，避免因改版把整条解析打挂。

private fun JSONObject.optStringAny(vararg keys: String): String {
    for (key in keys) {
        if (isNull(key)) continue
        val value = optString(key, "")
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun JSONObject.optObjectAny(vararg keys: String): JSONObject? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONObject(key)?.let { return it }
    }
    return null
}

private fun JSONObject.optArrayAny(vararg keys: String): JSONArray? {
    for (key in keys) {
        if (isNull(key)) continue
        optJSONArray(key)?.let { return it }
    }
    return null
}
