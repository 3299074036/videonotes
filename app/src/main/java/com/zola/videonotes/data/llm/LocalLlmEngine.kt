package com.zola.videonotes.data.llm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.zola.videonotes.data.llm.local.LlamaCpp
import com.zola.videonotes.data.llm.local.LlmCrashBreadcrumb
import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.llm.local.LocalLlmModels
import com.zola.videonotes.data.llm.local.LocalLlmPreferences
import com.zola.videonotes.data.llm.local.PromptTemplate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.job

/**
 * 本地 LLM 引擎（M7a）：llama.cpp JNI，完全离线。
 *
 * - 模型懒加载：首次生成时 load，句柄常驻内存直到进程结束；
 *   切换模型时 unload 旧句柄。
 * - [chatStream] 在 Dispatchers.Default 执行 native 生成，
 *   token 经 JNI 回调实时上报；协程取消时调 [LlamaCpp.requestCancel]。
 * - 本地上下文与速度有限，单遍上限 4000 token、chunk 3000 token，
 *   长文稿会比云端切得更碎（map-reduce 轮数更多）。
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    private val prefs: LocalLlmPreferences,
    @ApplicationContext private val context: Context,
) : LlmEngine {

    init {
        // 原生崩溃面包屑：把落盘路径交给 native（须在首次 loadModel/generate 前；
        // 本类是单例、构造先于任何生成调用）。信号处理在 .so 加载时已安装。
        LlmCrashBreadcrumb.install(context)
    }

    override val type: LlmEngineType = LlmEngineType.LOCAL

    override val maxSingleTokens: Int = MAX_SINGLE_TOKENS_LOCAL

    override val chunkTokens: Int = CHUNK_TOKENS_LOCAL

    override val systemPromptAsset: String
        // 小模型用简化版 prompt（指令更短 + 示例），大模型用完整版；
        // 判定已数据化为条目字段 smallModelPrompt（WP1），动态条目恒 false
        get() = if (prefs.smallModelPrompt(cachedModelId)) {
            "system_note_local_small.txt"
        } else {
            "system_note_local.txt"
        }

    override val modelLabel: String
        get() = "本地模型"

    /** native 模型句柄（0 = 未加载），Kotlin 侧同步保护。 */
    private var handle: Long = 0L

    /** 已加载的模型文件绝对路径（用于检测切换）。 */
    private var loadedPath: String? = null

    /** 当前模型 ID 缓存（checkReady/chatStream 中更新，供 systemPromptAsset 同步读取）。 */
    @Volatile
    private var cachedModelId: String = "qwen3-4b"

    private val lock = Any()

    /** 诊断专用单线程：probe hang 时只卡住这一个线程，不污染 Default/IO 池。 */
    private val probeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-probe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /**
     * 生成进行中标志（2026-10-24 探针误伤案后加的忙时门禁）：
     * [chatStream] 进入 native generate 前置 true、退出时复位；[runSingleTokenProbe]
     * 入口见 true 直接拒绝，不碰句柄、不发任何 decode。StateFlow 供诊断页按钮置灰。
     * 注意：这是建议性门禁（覆盖"生成中点探针"的实测场景），不解决"探针进行中又
     * 起生成"的反向竞态——探针本身只有 1 个 token、正常亚秒级完成，窗口极小。
     */
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override suspend fun checkReady(): Result<Unit> {
        val model = prefs.currentModel()
            ?: return Result.failure(IOException("尚未选择本地模型，请先到模型管理页选择并下载"))
        cachedModelId = model.id
        if (!prefs.isModelReadyVerified(model)) {
            return Result.failure(IOException("本地模型「${model.displayName}」尚未下载，请先到设置页下载"))
        }
        // 设探针门的条目额外过探针结论门（WP2）：无有效结论/非 OK 一律按结论报错
        probeGate(model)?.let { return Result.failure(it) }
        return Result.success(Unit)
    }

    /**
     * 模板探针闸门：动态条目（注册表结论）与新收录精选条目（requiresProbe，
     * CuratedProbeStore 结论）共用。探针未通过时返回对应文案的失败
     * （口径同方案 §3.3.4），不设门条目或探针 OK 时返回 null。
     */
    private fun probeGate(model: com.zola.videonotes.data.llm.local.LocalLlmModel): IOException? {
        val state = when {
            model.source == com.zola.videonotes.data.catalog.CatalogSource.DYNAMIC ->
                prefs.dynamicProbeState(model)
            model.requiresProbe -> prefs.curatedProbeState(model)
            else -> return null
        }
        return when (state) {
            com.zola.videonotes.data.catalog.TemplateProbeState.OK -> null
            com.zola.videonotes.data.catalog.TemplateProbeState.NO_TEMPLATE ->
                IOException("该模型不含内嵌对话模板，无法用于对话生成")
            com.zola.videonotes.data.catalog.TemplateProbeState.UNSUPPORTED ->
                IOException("该模型的对话模板不受当前运行时支持")
            com.zola.videonotes.data.catalog.TemplateProbeState.LOAD_FAILED ->
                IOException("当前运行时无法加载该模型文件（架构或量化不受支持）")
            null -> IOException("该模型尚未完成模板校验，请到模型管理页重新下载或等待校验完成")
        }
    }

    override suspend fun chatStream(
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.Default) {
        val model = prefs.currentModel()
            ?: return@withContext Result.failure(IOException("尚未选择本地模型，请先到模型管理页选择并下载"))
        cachedModelId = model.id
        val file = prefs.fileFor(model)
        if (!prefs.isModelReadyVerified(model)) {
            return@withContext Result.failure(IOException("本地模型「${model.displayName}」尚未下载"))
        }
        probeGate(model)?.let { return@withContext Result.failure(it) }

        // 加载模型（懒加载；切换模型时先 unload）
        val h = try {
            ensureLoaded(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IOException("本地模型加载失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }

        val roles = messages.map { it.role }.toTypedArray()
        val contents = messages.map { it.content }.toTypedArray()
        val prompt = when (model.promptTemplate) {
            PromptTemplate.EMBEDDED -> {
                // 内嵌模板条目：prompt 必须在模型加载后经内嵌模板拼装（WP2）
                LlamaCpp.applyEmbeddedTemplate(h, roles, contents, true)
                    ?: return@withContext Result.failure(IOException(LlamaCpp.lastError()))
            }
            PromptTemplate.NAMED -> {
                // 命名模板条目：按探针定下的解析模式拼装（GLM-Edge 可能回退内嵌；
                // 无有效探针记录时不会走到这里——探针门已在前面拦下，此处兜底走命名）
                if (prefs.curatedProbeMode(model) ==
                    com.zola.videonotes.data.catalog.CuratedProbeStore.MODE_EMBEDDED
                ) {
                    LlamaCpp.applyEmbeddedTemplate(h, roles, contents, true)
                        ?: return@withContext Result.failure(IOException(LlamaCpp.lastError()))
                } else {
                    LlamaCpp.applyNamedTemplate(
                        h,
                        model.chatTemplateName ?: "",
                        roles,
                        contents,
                        true,
                    ) ?: return@withContext Result.failure(IOException(LlamaCpp.lastError()))
                }
            }
            else -> buildPrompt(messages, model.promptTemplate)
        }
        // prompt 构建完成：记 system/user 字符数与估算 token。
        // 注意：这里是 chars/2 粗估——2026-10-24 实测 4176 token 的 prompt 在此只估出
        // 约 2064，差约一倍（误导了用户对 prefill 速度的预期）；实际分词数以 JNI
        // generate 开始行的 n_prompt 为准，生成汇总里也有实际 prefill token 数。
        val systemChars = messages.firstOrNull { it.role == "system" }?.content?.length ?: 0
        val userChars = messages.filter { it.role != "system" }.sumOf { it.content.length }
        val estTokens = (systemChars + userChars) / 2
        LlmDiagnosticLog.log(
            context, "llm",
            "prompt 构建完成：system=${systemChars}字符，user=${userChars}字符，合计约${estTokens} tokens（chars/2 粗估；实际分词数见 generate 开始行的 n_prompt）",
        )
        if (estTokens > 3000) {
            LlmDiagnosticLog.log(
                context, "llm",
                "提示：prompt 较长（约${estTokens} tokens 粗估），首批 prefill 可能需要数分钟，属正常现象；生成进行中请勿运行单 token 诊断探针",
            )
        }
        LlmDiagnosticLog.log(
            context, "llm",
            "调用模型：engine=本地(${prefs.currentModel()?.displayName ?: "未选择"})，" +
                "maxTokens=$MAX_OUTPUT_TOKENS，temperature=$TEMPERATURE_LOCAL",
        )
        val sb = StringBuilder()
        // 取消时通知 native 停止采样
        coroutineContext.job.invokeOnCompletion {
            try {
                LlamaCpp.requestCancel(h)
            } catch (_: Exception) {
            }
        }
        try {
            var tokenCount = 0
            var nextLogAt = 500
            val callback = object : LlamaCpp.TokenCallback {
                override fun onToken(token: String) {
                    sb.append(token)
                    tokenCount++
                    // 每 500 token 记一行进度（避免每 token 都记太吵）
                    if (tokenCount >= nextLogAt) {
                        LlmDiagnosticLog.log(context, "llm", "已输出 $tokenCount tokens…")
                        nextLogAt += 500
                    }
                    onToken(token)
                }
            }
            val t0 = SystemClock.elapsedRealtime()
            // prefill 可能耗时很长（大 prompt 多 batch）：轮询 native 进度并推送到日志流，
            // 卡死时实时面板能看到最后卡在第几个 batch。
            // 2026-10-24 心跳：8B 在 4 线程上约 60s 才完成一批，轮询只在变化时记日志
            // 会让界面 60s 纹丝不动（正是用户误点探针的直接原因）；进度不变超过 30s
            // 时每 30s 补一条心跳，说明"还在算、卡在第几批、已耗时多久"。
            val progressPoller = launch {
                var last = ""
                var lastChangeMs = SystemClock.elapsedRealtime()
                var lastHeartbeatMs = 0L
                while (isActive) {
                    delay(5000)
                    val now = SystemClock.elapsedRealtime()
                    val p = runCatching { LlamaCpp.lastProgress() }.getOrDefault("")
                    if (p.isNotBlank() && p != last) {
                        last = p
                        lastChangeMs = now
                        LlmDiagnosticLog.log(context, "llm", "生成进度：$p（进行中…）")
                    } else if (p.isNotBlank() && now - lastChangeMs >= 30_000 &&
                        now - lastHeartbeatMs >= 30_000
                    ) {
                        lastHeartbeatMs = now
                        val elapsedSec = (now - t0) / 1000
                        LlmDiagnosticLog.log(
                            context, "llm",
                            "生成进度：仍在 $p，已耗时 ${elapsedSec}s（进行中…）",
                        )
                    }
                }
            }
            // 即时落盘：native 调用开始点。进程若死在 generate 内，后续汇总与
            // drain 都不会执行，导出日志里至少能看到「已进入 generate」这一行，
            // 与面包屑文件里的信号 + 里程碑互为印证。
            LlmDiagnosticLog.log(context, "llm", "generate 开始（进入 native 生成）")
            val rc = try {
                // 忙时门禁：进入 generate 前置标志，探针入口见此标志直接拒绝
                _isGenerating.value = true
                LlamaCpp.generate(h, prompt, MAX_OUTPUT_TOKENS, TEMPERATURE_LOCAL, callback)
            } finally {
                _isGenerating.value = false
                progressPoller.cancel()
            }
            val totalMs = SystemClock.elapsedRealtime() - t0
            // native 侧性能汇总（含 tokenize/prefill/decode 的 token 数与 tok/s），失败时也有部分数据
            val perf = runCatching { LlamaCpp.lastPerfSummary() }.getOrDefault("")
            val outChars = sb.length
            val estOutTokens = outChars / 2
            val summary = "generate 结束(rc=$rc): prompt=${prompt.length}字符, " +
                "输出=${outChars}字符，约${estOutTokens} tokens（chars/2 粗估）, 总耗时=${totalMs}ms | $perf"
            Log.i(TAG, summary)
            LlmDiagnosticLog.log(context, "llm", summary)
            LlmDiagnosticLog.log(
                context, "llm",
                "输出完成：共 ${outChars} 字符，约 ${estOutTokens} tokens（chars/2 粗估），$tokenCount tokens 实际回调",
            )
            // 把 generate 阶段的 JNI 日志（prefill batch 进度 / llama.cpp 内部日志 / ret=）落盘
            drainJniLogsToFile()
            ensureActive()
            if (rc != 0) {
                return@withContext Result.failure(IOException(LlamaCpp.lastError()))
            }
            val text = sb.toString()
            if (text.isBlank()) {
                Result.failure(IOException("本地模型返回为空，请重试"))
            } else {
                Result.success(text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(
                IOException("本地生成失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }
    }

    /**
     * 方案 A 诊断：只送 1 个 token 做一次 decode，区分 batch 问题 vs 根本性 hang。
     * - 成功 → decode 本身正常，hang 与 batch 大小/数量相关；
     * - 90s 超时 → 根本性 hang（此时 native 诊断线程仍卡住，句柄作废，需重启 App）。
     * 诊断跑在专用单线程上，hang 时只卡住这一个线程，不污染 Default/IO 池。
     * 结论写入 llm.log（App 内"详细日志"页可见）。
     * - 2026-10-24 忙时门禁：生成进行中时入口直接拒绝，不碰句柄、不发 decode。
     *   原因：探针与生成共用引擎单例上下文，探针 batch（pos=0）会撞在途生成已写入
     *   的 KV 位置校验（2026-10-24 实测 X=511 撞 Y=0），且失败路径的清 KV 曾把
     *   在途生成一并置于险境。隔离诊断请走方案 C（独立上下文）。
     */
    suspend fun runSingleTokenProbe(): Result<String> = withContext(probeDispatcher) {
        if (_isGenerating.value) {
            val msg = "生成进行中，探针会干扰在途生成，请等待结束"
            LlmDiagnosticLog.log(context, "llm", "[诊断] 方案A 已拒绝：$msg")
            return@withContext Result.failure(IOException(msg))
        }
        val model = prefs.currentModel()
            ?: return@withContext Result.failure(IOException("尚未选择本地模型，请先到模型管理页选择并下载"))
        val file = prefs.fileFor(model)
        if (!prefs.isModelReadyVerified(model)) {
            return@withContext Result.failure(
                IOException("本地模型「${model.displayName}」尚未下载，请先到模型管理页下载"),
            )
        }
        val h = try {
            ensureLoaded(file.absolutePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IOException("模型加载失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }
        LlmDiagnosticLog.log(context, "llm", "[诊断] 方案A：单 token 诊断开始（${PROBE_TIMEOUT_MS / 1000}s 超时）…")
        drainJniLogsToFile()
        val t0 = SystemClock.elapsedRealtime()
        val rc = try {
            withTimeout(PROBE_TIMEOUT_MS) { LlamaCpp.singleTokenProbe(h) }
        } catch (e: TimeoutCancellationException) {
            val msg = "单 token decode 在 ${PROBE_TIMEOUT_MS / 1000}s 内未返回：" +
                "根本性 hang（非 batch 问题）。native 诊断线程已卡住，请重启 App 后再试其它方案。"
            LlmDiagnosticLog.log(context, "llm", "[诊断] 方案A 结论：$msg")
            drainJniLogsToFile()
            return@withContext Result.failure(IOException(msg))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext Result.failure(
                IOException("诊断调用失败：${e.message ?: e.javaClass.simpleName}"),
            )
        }
        val ms = SystemClock.elapsedRealtime() - t0
        drainJniLogsToFile()
        if (rc != 0) {
            val err = runCatching { LlamaCpp.lastError() }.getOrDefault("")
            val msg = "单 token decode 失败（rc=$rc）：$err"
            LlmDiagnosticLog.log(context, "llm", "[诊断] 方案A 结论：$msg")
            return@withContext Result.failure(IOException(msg))
        }
        val verdict = "诊断通过 · ${ms}ms"
        LlmDiagnosticLog.log(context, "llm", "[诊断] 方案A 结论：单 token decode 成功（${ms}ms）")
        Result.success(verdict)
    }

    /**
     * 方案 C 诊断：最小化 native 测试。
     * 完全绕开封装层（每次用例独立加载模型、除 n_ctx/n_threads 外全部默认参数、
     * 只做一次 llama_decode），区分"JNI 封装问题" vs "llama.cpp 构建问题"。
     * 用例矩阵覆盖线程数 / batch 大小 / n_ctx 三个维度：
     * - 用例1（4线程/8tok/ctx2048）：最小基线；
     * - 用例2（1线程/8tok/ctx2048）：单线程是否正常 → 线程池嫌疑；
     * - 用例3（4线程/64tok/ctx2048）：batch 放大；
     * - 用例4（4线程/64tok/ctx8192）：封装层同款 n_ctx → n_ctx 嫌疑；
     * - 用例5（4线程/8tok/ctx2048，大核绑定）：把线程钉到 cpu4-7 →
     *   若显著快于用例1，则是调度器把线程放在了小核上。
     * 每个用例 90s 超时；结论写入 llm.log。
     */
    suspend fun runMinimalNativeProbe(): Result<String> = withContext(probeDispatcher) {
        val model = prefs.currentModel()
            ?: return@withContext Result.failure(IOException("尚未选择本地模型，请先到模型管理页选择并下载"))
        val file = prefs.fileFor(model)
        if (!prefs.isModelReadyVerified(model)) {
            return@withContext Result.failure(
                IOException("本地模型「${model.displayName}」尚未下载，请先到模型管理页下载"),
            )
        }
        val path = file.absolutePath
        LlmDiagnosticLog.log(
            context, "llm",
            "[诊断] 方案C：最小化 native 测试开始（5 个用例，每用例 ${PROBE_TIMEOUT_MS / 1000}s 超时）…",
        )
        data class Case(
            val threads: Int,
            val nCtx: Int,
            val tokens: Int,
            val pinBigCores: Int,
            val label: String,
        )
        val cases = listOf(
            Case(4, 2048, 8, 0, "用例1 · 4线程/8tok"),
            Case(1, 2048, 8, 0, "用例2 · 1线程/8tok"),
            Case(4, 2048, 64, 0, "用例3 · 4线程/64tok"),
            Case(4, 8192, 64, 0, "用例4 · 4线程/64tok/ctx8192"),
            Case(4, 2048, 8, 1, "用例5 · 绑大核/8tok"),
        )
        val sb = StringBuilder()
        for (c in cases) {
            LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C ${c.label}：开始…")
            drainJniLogsToFile()
            val t0 = SystemClock.elapsedRealtime()
            val rc = try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    LlamaCpp.minimalProbe(path, c.threads, c.nCtx, c.tokens, c.pinBigCores)
                }
            } catch (e: TimeoutCancellationException) {
                val msg = "${c.label}：❌ 90s 超时（hang）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C $msg")
                sb.appendLine(msg)
                drainJniLogsToFile()
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "${c.label}：❌ 调用异常（${e.message}）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C $msg")
                sb.appendLine(msg)
                continue
            }
            val ms = SystemClock.elapsedRealtime() - t0
            drainJniLogsToFile()
            if (rc != 0) {
                val err = runCatching { LlamaCpp.lastError() }.getOrDefault("")
                val msg = "${c.label}：❌ 失败 rc=$rc（$err）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C $msg")
                sb.appendLine(msg)
            } else {
                val msg = "${c.label} · ✅ ${ms}ms"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C ${c.label}：成功（${ms}ms）")
                sb.appendLine(msg)
            }
        }
        val verdict = sb.toString().trim()
        LlmDiagnosticLog.log(context, "llm", "[诊断] 方案C 汇总：\n$verdict")
        Result.success(verdict)
    }

    /**
     * 方案 T 诊断：固定 prompt × 线程数对比（2026-10-24 探针误伤案后加）。
     * 背景：旧生产默认值 nThreads=4 源自 2026-10-17「8 线程 prefill hang」报告，
     * 该报告已被 2026-10-24 用户真机实测证伪（8B、512tok/ctx8192：4 线程 57983ms、
     * 6 线程 42567ms、8 线程 40634ms，无 hang），生产线程数现按模型体积改为分档
     * （见 threadsForModelSize）；本入口保留供日后复验。
     * 本用例用方案 C 的独立上下文（每次用例独立加载，不碰常驻句柄、不破坏在途
     * 生成），同一模型文件、同一固定 prompt（512 token 一批、ctx8192），在
     * 4 / 6 / 8 线程 + 4 线程绑大核下各跑一批，逐用例记线程数/token 数/耗时/
     * 成败码（JNI cprobe 行含 tok/s，面包屑里程碑同步记录）。
     * 注意：每用例 90s 超时；4 线程一批约 60s，超时本身也是数据（记为 hang）。
     * 全程约 3–5 分钟；生成进行中运行只会争 CPU/内存拖慢生成，不破坏生成。
     * 生产线程数已按模型体积改为分档（见 threadsForModelSize），本入口保留供日后复验。
     */
    suspend fun runThreadSweepProbe(): Result<String> = withContext(probeDispatcher) {
        val model = prefs.currentModel()
            ?: return@withContext Result.failure(IOException("尚未选择本地模型，请先到模型管理页选择并下载"))
        val file = prefs.fileFor(model)
        if (!prefs.isModelReadyVerified(model)) {
            return@withContext Result.failure(
                IOException("本地模型「${model.displayName}」尚未下载，请先到模型管理页下载"),
            )
        }
        val path = file.absolutePath
        LlmDiagnosticLog.log(
            context, "llm",
            "[诊断] 方案T：线程诊断开始（512 token/批、ctx8192，4/6/8 线程 + 4 线程绑大核，每用例 ${PROBE_TIMEOUT_MS / 1000}s 超时，全程约 3–5 分钟）…",
        )
        data class Case(
            val threads: Int,
            val pinBigCores: Int,
            val label: String,
        )
        val cases = listOf(
            Case(4, 0, "线程4"),
            Case(6, 0, "线程6"),
            Case(8, 0, "线程8"),
            Case(4, 1, "线程4·绑大核"),
        )
        val sb = StringBuilder()
        for (c in cases) {
            LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T ${c.label}：开始…")
            drainJniLogsToFile()
            val t0 = SystemClock.elapsedRealtime()
            val rc = try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    LlamaCpp.minimalProbe(path, c.threads, N_CTX, 512, c.pinBigCores)
                }
            } catch (e: TimeoutCancellationException) {
                val msg = "${c.label} · 512tok/ctx8192 · ❌ 90s 超时（hang）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T $msg")
                sb.appendLine(msg)
                drainJniLogsToFile()
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = "${c.label} · 512tok/ctx8192 · ❌ 调用异常（${e.message}）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T $msg")
                sb.appendLine(msg)
                continue
            }
            val ms = SystemClock.elapsedRealtime() - t0
            drainJniLogsToFile()
            if (rc != 0) {
                val err = runCatching { LlamaCpp.lastError() }.getOrDefault("")
                val msg = "${c.label} · 512tok/ctx8192 · ❌ 失败 rc=$rc（$err）"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T $msg")
                sb.appendLine(msg)
            } else {
                val msg = "${c.label} · 512tok/ctx8192 · ✅ ${ms}ms"
                LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T ${c.label}：成功（${ms}ms）")
                sb.appendLine(msg)
            }
        }
        val verdict = sb.toString().trim()
        LlmDiagnosticLog.log(context, "llm", "[诊断] 方案T 汇总：\n$verdict")
        Result.success(verdict)
    }

    /**
     * 线程分档（仅 LLM 侧；ASR sherpa 不走此线程数）。
     * 档位按待加载模型文件实际体积：≤2GiB → 4 线程；≤4GiB → 6 线程；>4GiB → 8 线程。
     * n_threads_batch 与 n_threads 在 load_impl 的绑定关系保持不变。
     */
    private fun threadsForModelSize(bytes: Long): Int = when {
        bytes <= 2L * 1024 * 1024 * 1024 -> 4
        bytes <= 4L * 1024 * 1024 * 1024 -> 6
        else -> 8
    }

    /**
     * 确保模型已加载。线程安全；重复调用直接返回已加载句柄。
     * 抛 IOException（native 错误信息）。
     */
    private fun ensureLoaded(path: String): Long {
        synchronized(lock) {
            if (handle != 0L && loadedPath == path) return handle
            if (handle != 0L) {
                try {
                    LlamaCpp.unloadModel(handle)
                } catch (_: Exception) {
                }
                handle = 0L
                loadedPath = null
            }
            // 先校验文件完整性：大小与预期差 5% 以上视为下载不完整，直接报清楚，
            // 避免加载损坏文件导致难以定位的 native 失败。
            val file = java.io.File(path)
            val expected = prefs.expectedSizeForPath(path)
            if (expected != null && expected > 0) {
                val actual = file.length()
                // 允许 5% 误差（HF 上文件大小可能有微小出入）
                if (actual < expected * 0.95) {
                    throw IOException(
                        "模型文件不完整（实际 ${actual / 1_000_000}MB，预期约 ${expected / 1_000_000}MB），" +
                            "请到设置页删除后重新下载",
                    )
                }
            }
            // 线程分档（2026-10-24 用户真机实测，Qwen3-8B，512tok/ctx8192）：
            // 4 线程 57983ms、6 线程 42567ms、8 线程 40634ms；8 线程无 hang，
            // 2026-10-17 的 hang 报告至此证伪。绑大核 512tok 结果未见，不做绑核。
            // 证据边界：8 线程结论目前只有 512tok 一批的单点实证，未全面验证无 hang；
            // 面包屑保留，供方案 T 日后复验。
            val nThreads = threadsForModelSize(file.length())
            val t0 = SystemClock.elapsedRealtime()
            val h = LlamaCpp.loadModel(path, N_CTX, nThreads)
            val loadMs = SystemClock.elapsedRealtime() - t0
            if (h == 0L) {
                throw IOException(LlamaCpp.lastError())
            }
            val summary = "模型加载完成: ${loadMs}ms (threads=$nThreads, nCtx=$N_CTX, " +
                "model=${path.substringAfterLast('/')})"
            Log.i(TAG, summary)
            LlmDiagnosticLog.log(context, "llm", summary)
            // 把 loadModel 阶段的 JNI 日志（model info / ctx params / llama.cpp 内部日志）落盘
            drainJniLogsToFile()
            handle = h
            loadedPath = path
            return h
        }
    }

    /**
     * 取走 JNI 桥接日志并逐行写入 llm.log（tag `[llm-cpp]`）。
     * 失败时静默忽略（不影响主流程）。
     */
    private fun drainJniLogsToFile() {
        val raw = runCatching { LlamaCpp.drainJniLogs() }.getOrDefault("")
        if (raw.isBlank()) return
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line -> LlmDiagnosticLog.log(context, "llm-cpp", line) }
    }

    /** 按模型家族的 chat template 组装 prompt。 */
    private fun buildPrompt(messages: List<ChatMessage>, template: PromptTemplate): String {
        val sb = StringBuilder()
        when (template) {
            PromptTemplate.EMBEDDED -> {
                // 已在 chatStream 中经 JNI 内嵌模板拼装，不会走到这里
                throw IOException("内嵌模板条目应走 applyEmbeddedTemplate 拼装")
            }
            PromptTemplate.NAMED -> {
                // 已在 chatStream 中经 JNI 命名/内嵌模板拼装，不会走到这里
                throw IOException("命名模板条目应走 applyNamedTemplate 拼装")
            }
            PromptTemplate.LLAMA3 -> {
                sb.append("<|begin_of_text|>")
                for (m in messages) {
                    sb.append("<|start_header_id|>").append(m.role)
                        .append("<|end_header_id|>\n\n")
                        .append(m.content).append("<|eot_id|>")
                }
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            PromptTemplate.QWEN3 -> {
                for ((i, m) in messages.withIndex()) {
                    sb.append("<|im_start|>").append(m.role).append('\n')
                        .append(m.content)
                    // Qwen3 是混合推理模型：最后一个 user 消息末尾加 /no_think，
                    // 关掉思考模式，否则会把内心独白（"接下来，我需要…"）直接输出
                    if (m.role == "user" && i == messages.lastIndex) {
                        sb.append("\n/no_think")
                    }
                    sb.append("<|im_end|>\n")
                }
                sb.append("<|im_start|>assistant\n")
            }
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "VideoNotesLlm"

        /** 本地单遍上限：4000 token（Qwen3-4B 在手机上 KV cache 与速度的平衡点）。 */
        const val MAX_SINGLE_TOKENS_LOCAL = 4000

        /** 本地 map-reduce chunk：3000 token。 */
        const val CHUNK_TOKENS_LOCAL = 3000

        /** 上下文窗口：8192（Qwen3 原生支持 32k，手机上 8k 是内存与速度的折中）。 */
        const val N_CTX = 8192

        /** 单次生成上限：3000 token（笔记输出一般 1500–2500 token）。 */
        const val MAX_OUTPUT_TOKENS = 3000

        /** 本地温度：0.2，与云端一致，抑制幻觉。 */
        const val TEMPERATURE_LOCAL = 0.2f

        /** 方案 A 诊断超时：90s。1 token 的 decode 正常应在秒级完成。 */
        const val PROBE_TIMEOUT_MS = 90_000L
    }
}
