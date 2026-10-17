package com.zola.videonotes.data.llm.local

/**
 * llama.cpp JNI 桥（M7a）。
 *
 * C++ 实现在 `app/src/main/cpp/`（`videonotes_llama` 库），
 * NDK 构建后 `System.loadLibrary` 加载。
 *
 * 线程模型：所有方法都在调用线程同步执行；
 * [generate] 在采样循环中逐 token 回调 [TokenCallback.onToken]，
 * 并检查取消标志（[requestCancel] 写入）。
 */
object LlamaCpp {

    init {
        System.loadLibrary("videonotes_llama")
    }

    /** 加载模型，成功返回非 0 句柄，失败返回 0（用 [lastError] 取原因）。 */
    external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Long

    /** 释放模型句柄。 */
    external fun unloadModel(handle: Long)

    /**
     * 流式生成。prompt 为完整对话文本（含 chat template），
     * 每采样出一个 token 就回调 [callback]，结束返回 0，
     * 失败返回非 0（用 [lastError] 取原因）。
     */
    external fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        callback: TokenCallback,
    ): Int

    /** 请求取消正在进行的 [generate]（线程安全）。 */
    external fun requestCancel(handle: Long)

    /** 取最近一次 native 错误（中文）。 */
    external fun lastError(): String

    /**
     * 取 generate 实时进度（prefill batch 进度 / decode 进行中），Kotlin 侧可轮询。
     * JNI 侧每完成一个 prefill batch 更新一次；卡死时最后一次的值即卡住位置。
     */
    external fun lastProgress(): String

    /**
     * 取最近一次 generate 的性能汇总（native 计时），格式如：
     * `[llm] tokenize 120ms | prefill 4173 tok in 8500ms (491 tok/s) | decode 800 tok in 40000ms (20.0 tok/s) | rc=0`。
     * generate 未执行过时返回空字符串。
     */
    external fun lastPerfSummary(): String

    /** token 流式回调（native 线程调用）。 */
    interface TokenCallback {
        fun onToken(token: String)
    }
}
