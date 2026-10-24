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

    /**
     * 设置原生崩溃面包屑文件路径：native 信号处理（JNI_OnLoad 时安装）在
     * 生成类调用的线程发生原生崩溃时，把信号与里程碑追加写入该文件。
     * 须在首次 generate / loadModel 前调用（见 LlmCrashBreadcrumb）。
     */
    external fun setBreadcrumbPath(path: String)

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

    /**
     * 方案 A 诊断：只对 1 个 token 做一次 decode。
     * 成功返回 0；若 native 侧 hang 则此调用不返回，Kotlin 侧用超时判定。
     */
    external fun singleTokenProbe(handle: Long): Int

    /**
     * 方案 C 诊断：最小化 native 测试。
     * 完全绕开封装层（独立加载模型、默认参数、只调 llama_decode），
     * 用于区分"JNI 封装问题" vs "llama.cpp 构建问题"。
     * 成功返回 0；hang 则不返回，Kotlin 侧用超时判定。
     */
    external fun minimalProbe(
        modelPath: String,
        nThreads: Int,
        nCtx: Int,
        nTokens: Int,
        pinBigCores: Int,
    ): Int

    /** 取最近一次 native 错误（中文）。 */
    external fun lastError(): String

    /**
     * WP2 探针返回码：有内嵌模板且 llama_chat_apply_template 试拼通过。
     * 注意 v0.7.0 的 apply 不解析 Jinja、只认预定义模板集，
     * [PROBE_UNSUPPORTED] 是运行时固有限制，不是文件损坏。
     */
    /** WP2：GGUF 内嵌模板探针（仅元数据级加载模型，不建上下文）。返回 PROBE_* 码。 */
    external fun probeChatTemplate(modelPath: String): Int

    /**
     * WP2：用已加载模型的内嵌模板拼装 prompt（动态条目生成路径）。
     * 失败返回 null，原因经 [lastError] 取。
     */
    external fun applyEmbeddedTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    /**
     * 预定义命名模板探针（国内名单定版）：直接以模板名（hunyuan-dense / glmedge）
     * 调 llama_chat_apply_template 试拼。返回 PROBE_* 码（无「无模板」态）。
     */
    external fun probeChatTemplateNamed(modelPath: String, templateName: String): Int

    /**
     * 用 llama.cpp 预定义命名模板拼装 prompt（内嵌 Jinja 不受支持时的兜底路径）。
     * 失败返回 null，原因经 [lastError] 取。
     */
    external fun applyNamedTemplate(
        handle: Long,
        templateName: String,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

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

    /**
     * 取走 JNI 桥接日志（llama.cpp 内部日志 + 关键诊断行，多行 `\n` 连接），
     * 返回后 native 侧清空 buffer。Kotlin 侧在 loadModel/generate 结束后调用，
     * 逐行写入 llm.log（App 内"详细日志"页可见）。无日志时返回空字符串。
     */
    external fun drainJniLogs(): String

    /** token 流式回调（native 线程调用）。 */
    interface TokenCallback {
        fun onToken(token: String)
    }

    /** probeChatTemplate 返回码：模板可用。 */
    const val PROBE_OK = 0

    /** probeChatTemplate 返回码：GGUF 无内嵌对话模板。 */
    const val PROBE_NO_TEMPLATE = 1

    /** probeChatTemplate 返回码：模板不被 v0.7.0 apply 支持。 */
    const val PROBE_UNSUPPORTED = 2

    /** probeChatTemplate 返回码：模型加载失败。 */
    const val PROBE_LOAD_FAILED = 3
}
