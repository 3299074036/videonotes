package com.zola.videonotes.data.download

import java.io.File
import java.security.MessageDigest

/**
 * 流式 SHA-256 工具（WP6）：自 LocalLlmPreferences.sha256Hex 抽出供 LLM/ASR 两侧共用，
 * 实现与原方法逐行一致（1MB 分块、不整文件载入内存）。须在 IO 线程调用。
 */
object Sha256 {

    fun hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 文件是否存在且哈希与期望一致（expected 为 null 视为通过）。须在 IO 线程调用。 */
    fun matches(file: File, expected: String?): Boolean {
        if (expected == null) return true
        if (!file.exists()) return false
        return hex(file).equals(expected, ignoreCase = true)
    }
}
