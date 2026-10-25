package com.zola.videonotes.data.parser

import okhttp3.ResponseBody
import java.io.IOException

/**
 * 解析器共用小工具（模块内部可见）。
 */

/** 从分享文本中提取第一个 http(s) 链接；分享文本常夹带标题、口令等杂质。 */
internal fun extractFirstUrl(text: String): String? =
    Regex("""https?://[^\s"'<>]+""").find(text)?.value
        // M-22：分享文本常以句号/逗号/括号收尾，正则会把尾标吞进 URL 导致解析失败；
        // 剔除尾部中英文标点（合法 URL 极少以这些字符结尾，误伤可接受）。
        ?.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}', '。', '，', '、', '；', '：', '！', '？', '）', '》', '…')

/**
 * M-14：取 URL 的 host（小写）。解析失败返回 null，调用方按不匹配处理（fail-closed）。
 */
internal fun String.urlHost(): String? =
    runCatching { java.net.URI(this).host }.getOrNull()?.lowercase()

/**
 * M-14：按 host 精确匹配（本站或子域）。旧实现用 url.contains(host)，会把
 * `https://evil.com/?x=bilibili.com` 这类 URL 误判为本站链接（域名片段出现在路径/参数里）。
 */
internal fun String.urlHostMatches(vararg hosts: String): Boolean {
    val host = urlHost() ?: return false
    return hosts.any { h -> host == h || host.endsWith(".$h") }
}

/**
 * L-14：登录墙判定（path 段匹配）。最终地址的 path 段命中 login / website-login /
 * captcha 即判撞墙；查询串含这些片段不误判（如 ?title=...login...）。
 * 地址解析失败时回退全 URL 子串（fail-closed，与旧行为一致）。
 */
internal fun isLoginWallUrl(url: String): Boolean {
    val path = runCatching { java.net.URI(url).path }.getOrNull()
    if (path.isNullOrBlank()) {
        return LOGIN_WALL_SEGMENTS.any { url.contains(it) }
    }
    return path.split('/').any { it in LOGIN_WALL_SEGMENTS }
}

private val LOGIN_WALL_SEGMENTS = setOf("login", "website-login", "captcha")

/** http 封面链接升级为 https，避免明文流量被系统拦截；`//` 开头的 scheme-relative 链接也补成 https。 */
internal fun String.ensureHttps(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://" + removePrefix("http://")
    else -> this
}

/**
 * L-10：带上限读文本响应体。HTML / 字幕类响应此前整包进内存，超大响应可致内存暴涨；
 * 超过 [maxBytes]（默认 10MB）即抛错，不等整包下完。调用方均为文本类下载点，
 * 视频 / 音频流不走这里。
 *
 * 实现：先看 Content-Length（已知超限直接拒）；再用 source.request(maxBytes + 1)
 * 只拉上限 + 1 字节——拉满说明流还没完，直接拒；拉到 EOF 且未超限时 buffer 即全部内容。
 */
internal fun ResponseBody.stringCapped(maxBytes: Long = 10 * 1024 * 1024): String {
    if (contentLength() > maxBytes) {
        throw IOException("响应体超过 ${maxBytes / 1024 / 1024}MB 上限")
    }
    val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    val source = source()
    source.request(maxBytes + 1)
    if (source.buffer.size > maxBytes) {
        throw IOException("响应体超过 ${maxBytes / 1024 / 1024}MB 上限")
    }
    return source.buffer.readString(source.buffer.size, charset)
}

/**
 * 补全 URL 的 scheme：B站字幕文件 URL 常以 `//` 开头。
 */
internal fun String.ensureHttpScheme(): String = when {
    startsWith("//") -> "https:$this"
    startsWith("http://") || startsWith("https://") -> this
    else -> "https://$this"
}

/**
 * 反转义分享页内嵌 JSON 字符串中的 \" 与 \uXXXX。
 * 只处理解析器需要的最小子集，不是通用 JSON 解析器。
 */
internal fun String.unescapeJson(): String {
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '\\' && i + 1 < length) {
            when (val next = this[i + 1]) {
                'u' -> {
                    if (i + 5 < length) {
                        out.append(this.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 6
                    } else {
                        out.append(c)
                        i++
                    }
                }
                '"' -> {
                    out.append('"')
                    i += 2
                }
                '\\' -> {
                    out.append('\\')
                    i += 2
                }
                '/' -> {
                    out.append('/')
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'r' -> {
                    out.append('\r')
                    i += 2
                }
                else -> {
                    out.append(next)
                    i += 2
                }
            }
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
