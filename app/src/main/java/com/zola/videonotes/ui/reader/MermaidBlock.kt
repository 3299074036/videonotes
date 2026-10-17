package com.zola.videonotes.ui.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zola.videonotes.ui.components.MetadataLoadingSkeleton
import com.zola.videonotes.ui.theme.VideoNotesTokens
import kotlinx.coroutines.delay

/** mermaid 渲染状态。 */
private sealed interface MermaidState {
    data object Loading : MermaidState
    data object Ready : MermaidState
    data class Failed(val message: String) : MermaidState
}

/** JS 回调用桥：渲染完成后回传内容高度，失败回传错误。 */
private class MermaidBridge(
    private val onHeight: (Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onHeight(heightPx: Double) {
        mainHandler.post { onHeight(heightPx.toInt().coerceAtLeast(1)) }
    }

    @JavascriptInterface
    fun onError(message: String) {
        mainHandler.post { onError(message) }
    }
}

/** mermaid.min.js 单例缓存（3.5MB，读一次常驻内存，避免重复 IO）。 */
private object MermaidJsCache {
    @Volatile
    private var cached: String? = null

    fun get(context: Context): String =
        cached ?: synchronized(this) {
            cached ?: context.assets.open("mermaid/mermaid.min.js").bufferedReader().use {
                it.readText()
            }.also { cached = it }
        }
}

/** HTML 转义（diagram 源码走 DOM textContent 取值，避免 JS 字符串转义坑）。 */
private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/**
 * Mermaid 知识地图块：全 App **唯一使用 WebView 的地方**。
 *
 * - mermaid.min.js 内联进 HTML（assets 本地，无网络请求）；
 * - `</script>` 转义为 `<\/script>`（JS 字符串中 `\/` 即 `/`，语义不变）；
 * - 主题跟随系统深浅色；渲染完成后 JS 回传 scrollHeight 动态定高；
 * - 加载中显示骨架屏，失败/超时回退显示代码原文。
 */
@Composable
fun MermaidBlock(
    diagram: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val density = LocalDensity.current

    var state by remember { mutableStateOf<MermaidState>(MermaidState.Loading) }
    var heightPx by remember { mutableIntStateOf(240) }
    // 已加载到 WebView 的 HTML：diagram 变化时才重新加载，避免重组时反复刷新
    var loadedHtml by remember { mutableStateOf<String?>(null) }

    val html = remember(diagram, darkTheme) {
        buildMermaidHtml(
            js = MermaidJsCache.get(context).replace("</script", "<\\/script"),
            diagram = escapeHtml(diagram),
            theme = if (darkTheme) "dark" else "default",
        )
    }

    // 渲染超时兜底：单次加载 15 秒仍未回调则回退显示源码
    LaunchedEffect(loadedHtml) {
        if (loadedHtml == null) return@LaunchedEffect
        delay(15_000)
        if (state is MermaidState.Loading) {
            state = MermaidState.Failed("渲染超时")
        }
    }

    Surface(
        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VideoNotesTokens.CornerMedium)),
    ) {
        Box(
            modifier = Modifier.height(
                with(density) { heightPx.toDp() },
            ),
        ) {
            when (val s = state) {
                is MermaidState.Loading -> {
                    MetadataLoadingSkeleton(
                        modifier = Modifier.padding(VideoNotesTokens.Space8),
                    )
                }
                is MermaidState.Failed -> {
                    Text(
                        text = diagram,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(VideoNotesTokens.Space16),
                    )
                }
                is MermaidState.Ready -> Unit
            }
            // WebView 始终挂载（透明底，加载中骨架屏透出；Ready 后显示内容）
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webViewClient = WebViewClient()
                        addJavascriptInterface(
                            MermaidBridge(
                                onHeight = {
                                    heightPx = (it + 16).coerceAtMost(1200)
                                    state = MermaidState.Ready
                                },
                                onError = { state = MermaidState.Failed(it) },
                            ),
                            "Android",
                        )
                    }
                },
                update = { webView ->
                    if (loadedHtml != html) {
                        loadedHtml = html
                        state = MermaidState.Loading
                        heightPx = 240
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    }
                },
                onRelease = { webView -> webView.destroy() },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun buildMermaidHtml(js: String, diagram: String, theme: String): String =
    """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<style>
html,body{margin:0;padding:0;background:transparent;}
#out{overflow-x:auto;padding:8px;}
#out svg{max-width:none !important;}
</style>
</head>
<body>
<pre id="src" style="display:none">$diagram</pre>
<div id="out"></div>
<script>$js</script>
<script>
(function(){
try{
mermaid.initialize({startOnLoad:false,theme:'$theme',securityLevel:'strict'});
var text=document.getElementById('src').textContent;
mermaid.render('vn-diagram',text).then(function(r){
document.getElementById('out').innerHTML=r.svg;
var h=document.documentElement.scrollHeight||document.body.scrollHeight||240;
Android.onHeight(h);
}).catch(function(e){Android.onError(String((e&&e.message)||e));});
}catch(e){Android.onError(String((e&&e.message)||e));}
})();
</script>
</body>
</html>"""
