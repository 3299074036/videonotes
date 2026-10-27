package com.zola.videonotes.data.auth

/**
 * YouTube cookie 校验与 Netscape 文件解析（P1：Cookie 导入标准化）。
 *
 * 纯逻辑，不碰存储与网络，可单测。
 *
 * 背景：旧校验 [YoutubeCookieStore.isValidCookie] 只要有一对键值就认“已导入”，
 * 残缺串也能存进去，导致后续解析报 LOGIN_REQUIRED 时分不清是串不全还是真风控。
 * 新校验分三档：
 * - 无法保存：连一对可用键值都没有，或缺身份关键键（SID / LOGIN_INFO）；
 * - 可保存但缺推荐键：存入并提示缺项（不静默）；
 * - 完整：直接保存。
 * 校验失败不写存储（调用方只在可保存时才调 save）。
 */
object CookieKeys {
    /** 身份关键键：缺了登录态无意义，拒存并点名缺项。 */
    val YOUTUBE_REQUIRED = listOf("SID", "LOGIN_INFO")

    /** 推荐键：缺了仍可保存，但要提示用户补完整串。 */
    val YOUTUBE_RECOMMENDED = listOf("HSID", "SSID", "APISID", "SAPISID")
}

data class YoutubeCookieValidation(
    val pairs: Map<String, String>,
    val missingRequired: List<String>,
    val missingRecommended: List<String>,
) {
    /** 连一对可用键值都没有。 */
    val isMalformed: Boolean get() = pairs.isEmpty()

    /** 可保存：有可用键值且身份关键键齐全。 */
    val canSave: Boolean get() = !isMalformed && missingRequired.isEmpty()

    /** 完整串：可保存且推荐键也齐全。 */
    val isComplete: Boolean get() = canSave && missingRecommended.isEmpty()
}

/** 解析 "k=v; k=v" 形式的 cookie 串：分号切、等号切、两端去空格、空名空值丢弃、同名后者覆盖。 */
fun parseCookiePairs(header: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (part in header.split(";")) {
        val kv = part.split("=", limit = 2)
        if (kv.size != 2) continue
        val name = kv[0].trim()
        val value = kv[1].trim()
        if (name.isEmpty() || value.isEmpty()) continue
        out[name] = value
    }
    return out
}

/** 按身份关键键与推荐键校验 YouTube cookie 串。 */
fun validateYoutubeCookie(header: String): YoutubeCookieValidation {
    val pairs = parseCookiePairs(header)
    return YoutubeCookieValidation(
        pairs = pairs,
        missingRequired = CookieKeys.YOUTUBE_REQUIRED.filter { it !in pairs },
        missingRecommended = CookieKeys.YOUTUBE_RECOMMENDED.filter { it !in pairs },
    )
}

/**
 * 解析 Netscape cookies.txt（浏览器导出格式），只取 [hostMatches] 为真的域，
 * 拼成 "k=v; k=v" 的导入串（可直接走 [validateYoutubeCookie]）。
 *
 * 行格式（Tab 分隔 7 列）：domain / flag / path / secure / expiration / name / value。
 * - `#` 开头为注释，跳过；`#HttpOnly_` 前缀剥掉后按正常行处理；
 * - 列数不足、名或值为空的行跳过；
 * - 已过期（expiration 非 0 且早于当前时间）的跳过；
 * - 同名多次出现时后者覆盖前者（导出文件里父域与子域常重复）。
 */
fun parseNetscapeCookies(text: String, hostMatches: (String) -> Boolean): String {
    val nowSec = System.currentTimeMillis() / 1000
    val out = LinkedHashMap<String, String>()
    for (rawLine in text.lines()) {
        var line = rawLine
        if (line.isBlank()) continue
        if (line.startsWith("#HttpOnly_")) {
            line = line.removePrefix("#HttpOnly_")
        } else if (line.startsWith("#")) {
            continue
        }
        val cols = line.split("\t")
        if (cols.size < 7) continue
        val domain = cols[0].trim().removePrefix(".")
        if (!hostMatches(domain)) continue
        val expiration = cols[4].trim().toLongOrNull() ?: 0L
        if (expiration != 0L && expiration < nowSec) continue
        val name = cols[5].trim()
        val value = cols[6].trim()
        if (name.isEmpty() || value.isEmpty()) continue
        out[name] = value
    }
    return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
}
