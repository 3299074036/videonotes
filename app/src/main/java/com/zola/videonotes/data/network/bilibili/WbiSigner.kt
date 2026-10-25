package com.zola.videonotes.data.network.bilibili

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * B站 WBI 签名（纯 Kotlin 实现，无需登录）。
 *
 * 流程：
 * 1. `x/web-interface/nav` → data.wbi_img.img_url / sub_url，取文件名（去扩展名）得 img_key / sub_key
 * 2. mixin_key = (img_key + sub_key) 按 [MIXIN_KEY_ENC_TAB] 重排后取前 32 字符
 * 3. 参数加 wts（秒级时间戳），按 key 排序；值过滤 `!'()*` 字符后 urlencode，拼接成 query
 * 4. w_rid = md5(query + mixin_key)；最终请求参数 = 原参数 + wts + w_rid
 */
object WbiSigner {

    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26,
        17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20,
        34, 44, 60,
    )

    /**
     * 从 nav 接口的 img_url / sub_url 中提取 img_key / sub_key（文件名去扩展名）。
     * 任一为空、或两 key 总长不足 64 时返回 null（L-05：mixin 表最大下标 63，
     * 总长不足时 getMixinKey 会越界；调用方已把 null 转为“密钥解析失败”）。
     */
    fun parseKeys(imgUrl: String, subUrl: String): Pair<String, String>? {
        val imgKey = fileNameNoExt(imgUrl)
        val subKey = fileNameNoExt(subUrl)
        if (imgKey.isNullOrEmpty() || subKey.isNullOrEmpty() || imgKey.length + subKey.length < 64) {
            return null
        }
        return imgKey to subKey
    }

    private fun fileNameNoExt(url: String): String? {
        if (url.isBlank()) return null
        return url.substringAfterLast('/').substringBefore('.').takeIf { it.isNotEmpty() }
    }

    /** 计算 mixin_key：(imgKey + subKey) 按表重排后取前 32 字符。 */
    fun getMixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return buildString(MIXIN_KEY_ENC_TAB.size) {
            for (i in MIXIN_KEY_ENC_TAB) {
                append(raw[i])
            }
        }.take(32)
    }

    /**
     * 对业务参数签名：加入 wts，计算 w_rid，返回包含 wts / w_rid 的完整参数表。
     * 返回的参数值同样经过 [filterValue] 过滤，保证实际发出的请求参数与签名时一致。
     *
     * @param params 业务参数（如 bvid、cid）
     * @param mixinKey [getMixinKey] 的结果
     * @param wts 秒级时间戳，默认当前时间；单元测试可传入固定值
     */
    fun sign(
        params: Map<String, String>,
        mixinKey: String,
        wts: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> {
        val all = params.mapValues { filterValue(it.value) }.toMutableMap()
        all["wts"] = wts.toString()
        val query = buildQueryString(all)
        all["w_rid"] = md5Hex(query + mixinKey)
        return all
    }

    /**
     * 构造待签名的 query 字符串：按 key 排序，值过滤 `!'()*` 后 urlencode，用 & 拼接。
     * 注意：`URLEncoder` 把空格编为 `+`，B站签名参数通常不含空格，保持与官方一致即可。
     */
    fun buildQueryString(params: Map<String, String>): String =
        params.toSortedMap().entries.joinToString("&") { (key, value) ->
            "$key=${urlEncode(filterValue(value))}"
        }

    private fun filterValue(value: String): String =
        value.filter { it !in "!\'()*" }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1))
            }
        }
    }
}
