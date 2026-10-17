package com.zola.videonotes.data.parser

/**
 * 解析器共用小工具（模块内部可见）。
 */

/** 从分享文本中提取第一个 http(s) 链接；分享文本常夹带标题、口令等杂质。 */
internal fun extractFirstUrl(text: String): String? =
    Regex("""https?://[^\s"'<>]+""").find(text)?.value

/** http 封面链接升级为 https，避免明文流量被系统拦截。 */
internal fun String.ensureHttps(): String =
    if (startsWith("http://")) "https://" + removePrefix("http://") else this

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
