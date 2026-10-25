package com.zola.videonotes.data.llm

import com.zola.videonotes.data.llm.local.LlmDiagnosticLog
import com.zola.videonotes.data.local.NoteDao
import com.zola.videonotes.data.local.NoteEntity
import com.zola.videonotes.data.local.TranscriptSegmentDao
import com.zola.videonotes.data.local.toDomain
import com.zola.videonotes.domain.model.Note
import com.zola.videonotes.domain.model.TranscriptSegment
import com.zola.videonotes.domain.model.VideoInfo
import com.zola.videonotes.domain.model.storageKey
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** 笔记生成阶段（UI 展示用）。 */
enum class GenerateStage(val displayText: String) {
    /** 读取文稿与配置 */
    PREPARING("正在准备文稿…"),

    /** LLM 流式生成中 */
    GENERATING("正在生成…"),

    /** 程序化时间戳校验 */
    VALIDATING("正在校验时间戳…"),

    /** 修正重试（最多 1 次） */
    FIXING("正在修正时间戳…"),

    /** 完成 */
    DONE("生成完成"),
}

/**
 * 笔记生成用例（M4）。
 *
 * 流程：取文稿（官方字幕 > 自动字幕 > ASR，按 Room 缓存顺序；无文稿直接失败）
 * → 单遍或 map-reduce 生成 → 时间戳质量门禁 → 最多 1 次修正重试
 * → 仍失败则剔除并标注 → 存 Room → 返回。
 *
 * 进度回调 [onProgress]：(阶段, 已接收字符数)，生成中用于展示"已接收 N 字"。
 * 协程取消会原样抛出，由调用方处理。
 */
@Singleton
class NoteGenerator @Inject constructor(
    private val transcriptSegmentDao: TranscriptSegmentDao,
    private val noteDao: NoteDao,
    private val engineProvider: LlmEngineProvider,
    private val promptLoader: PromptLoader,
    private val promptOverrides: PromptOverrides,
    @ApplicationContext private val appContext: Context,
) {

    /** 详细日志：走 LlmDiagnosticLog，同时落盘 + 实时面板推送。 */
    private fun llmLog(message: String) = LlmDiagnosticLog.log(appContext, "llm", message)

    private fun engineLabel(engine: LlmEngine): String =
        if (engine.type == LlmEngineType.LOCAL) "本地" else "云端"

    suspend fun generate(
        video: VideoInfo,
        onProgress: (stage: GenerateStage, receivedChars: Int) -> Unit,
    ): Result<Note> {
        val tAll0 = System.currentTimeMillis()
        onProgress(GenerateStage.PREPARING, 0)

        val engine = engineProvider.current()
        engine.checkReady().getOrElse { return Result.failure(it) }

        val segments = transcriptSegmentDao.getByVideoId(video.storageKey())
            .map { it.toDomain() }
        if (segments.isEmpty()) {
            llmLog("笔记生成失败：阶段=取文稿，错误=请先获取字幕或完成语音转写")
            return Result.failure(IOException("请先获取字幕或完成语音转写"))
        }

        val system = runCatching {
            promptOverrides.systemFor(engine.type).first()
        }.onFailure {
            llmLog("自定义提示词读取失败，已回退默认提示词")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    appContext, "自定义提示词读取失败，已回退默认", android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }.getOrNull() ?: promptLoader.load(engine.systemPromptAsset)
        val transcriptText = formatTranscript(segments)
        llmLog(
            "笔记生成开始：引擎=${engineLabel(engine)}（${engine.modelLabel}），" +
                "字幕段数=${segments.size}，文稿约${transcriptText.length}字符",
        )

        // 生成：单遍或 map-reduce
        var markdown = try {
            generateMarkdown(video, engine, system, segments, transcriptText, onProgress)
                .getOrElse {
                    llmLog("笔记生成失败：阶段=LLM生成，错误=${it.message ?: it.javaClass.simpleName}")
                    return Result.failure(it)
                }
        } catch (e: CancellationException) {
            throw e
        }

        // 质量门禁
        onProgress(GenerateStage.VALIDATING, 0)
        val timestamps = TimestampValidator.extractTimestamps(markdown)
        llmLog("时间戳门禁：检查 ${timestamps.size} 个时间点")
        // 2026-10-24 英文案：合格笔记恒含时间戳，0 时间点恒等于结构失败。
        // 旧逻辑下 findFailures 对空集返回空，门禁“全部通过”空通过，结构全失的
        // 输出一路存成正式笔记——此处改为失败优先，不再放行。
        if (timestamps.isEmpty()) {
            llmLog("时间戳门禁：生成结果不含时间戳，拒绝存库")
            return Result.failure(IOException("生成结果不含时间戳，请重试"))
        }
        var failures = TimestampValidator.findFailures(timestamps, segments)
        if (failures.isNotEmpty()) {
            llmLog("时间戳门禁：${failures.size} 个时间点未通过，进入修正重试")
            onProgress(GenerateStage.FIXING, 0)
            val fixed = try {
                fixTimestamps(
                    markdown = markdown,
                    failures = failures,
                    segments = segments,
                    engine = engine,
                    system = system,
                    onProgress = onProgress,
                ).getOrElse {
                    if (it is FixInputTooLongException) {
                        // H-02：修正输入超限不是生成失败：保留原文，后续流程剔除失败时间点并标注。
                        llmLog("修正输入超限，已跳过修正，保留原文并剔除失败时间点")
                        markdown
                    } else {
                        llmLog("笔记生成失败：阶段=修正重试，错误=${it.message ?: it.javaClass.simpleName}")
                        return Result.failure(it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            }
            markdown = fixed
            failures = TimestampValidator.findFailures(
                TimestampValidator.extractTimestamps(markdown),
                segments,
            )
            if (failures.isNotEmpty()) {
                llmLog("时间戳门禁：修正后仍有 ${failures.size} 个失败，已剔除并标注「时间点待核实」")
            } else {
                llmLog("时间戳门禁：修正重试后全部通过")
            }
        } else {
            llmLog("时间戳门禁：全部通过")
        }
        if (failures.isNotEmpty()) {
            markdown = TimestampValidator.stripFailures(markdown, failures)
        }

        val entity = NoteEntity(
            videoStorageKey = video.storageKey(),
            // 抖音文案常是几百字日记全文，直接当标题会撑坏详情页布局（TopAppBar + 大标题）；
            // 取首行、截断到 40 字，完整文案仍保留在 videoTitle 字段里。
            title = video.title.toNoteTitle(),
            markdown = markdown.trim(),
            createdAt = System.currentTimeMillis(),
            llmModel = engine.modelLabel,
            videoTitle = video.title,
        )
        val id = noteDao.insert(entity)
        onProgress(GenerateStage.DONE, 0)
        llmLog("笔记生成完成：总耗时=${System.currentTimeMillis() - tAll0}ms，笔记 id=$id")
        return Result.success(entity.copy(id = id).toDomain())
    }

    /** 单遍生成，或超限时 map-reduce。 */
    private suspend fun generateMarkdown(
        video: VideoInfo,
        engine: LlmEngine,
        system: String,
        segments: List<TranscriptSegment>,
        transcriptText: String,
        onProgress: (GenerateStage, Int) -> Unit,
    ): Result<String> {
        val chunks = TranscriptChunker.chunk(
            segments,
            maxSingleTokens = engine.maxSingleTokens,
            chunkTokens = engine.chunkTokens,
        )
        llmLog(
            "文稿切分：${chunks.size} 段（chunkTokens=${engine.chunkTokens}，" +
                "单遍上限=${engine.maxSingleTokens}）",
        )
        return if (chunks.size == 1) {
            llmLog("单遍生成开始（文稿约${transcriptText.length}字符）")
            val t0 = System.currentTimeMillis()
            val user = promptLoader.render(
                promptLoader.load("user_note_single.txt"),
                mapOf(
                    "title" to video.title,
                    "duration" to formatSeconds(video.durationSec),
                    "transcript" to transcriptText,
                ),
            )
            chat(engine, system, user, onProgress).also {
                llmLog(
                    "单遍生成结束：耗时=${System.currentTimeMillis() - t0}ms，" +
                        (if (it.isSuccess) "输出约${it.getOrNull()?.length}字符" else "失败"),
                )
            }
        } else {
            llmLog("启用 map-reduce：${chunks.size} 个 chunk")
            val partials = mutableListOf<String>()
            for ((index, chunk) in chunks.withIndex()) {
                val chunkText = formatTranscript(chunk)
                llmLog("chunk ${index + 1}/${chunks.size} 开始（本段约${chunkText.length}字符）")
                val t0 = System.currentTimeMillis()
                val user = promptLoader.render(
                    promptLoader.load("user_note_chunk.txt"),
                    mapOf(
                        "title" to video.title,
                        "chunkIndex" to (index + 1).toString(),
                        "chunkCount" to chunks.size.toString(),
                        "transcript" to chunkText,
                    ),
                )
                val partial = chat(engine, system, user, onProgress)
                    .getOrElse { return Result.failure(it) }
                llmLog(
                    "chunk ${index + 1}/${chunks.size} 完成：" +
                        "耗时=${System.currentTimeMillis() - t0}ms，输出约${partial.length}字符",
                )
                partials.add(partial)
            }
            reducePartials(partials, video, engine, system, onProgress)
        }
    }

    /**
     * H-02：递归 reduce。单次 reduce 的输入（partials 拼接 + 模板余量）超过引擎单遍上限时，
     * 按 chunkTokens 分组、先组内 reduce、再对组结果递归 reduce，直到一次能装下为止。
     * 每层都会缩小输入（组结果远短于组输入）；深度达上限仍装不下、或合并后未缩小，
     * 直接失败并明示，不再像以前那样一次塞几万 token 导致 rc=12 失败或静默截断。
     * partials 只有 1 段时直接返回该段，不再多跑一轮 reduce。
     */
    private suspend fun reducePartials(
        partials: List<String>,
        video: VideoInfo,
        engine: LlmEngine,
        system: String,
        onProgress: (GenerateStage, Int) -> Unit,
        depth: Int = 0,
    ): Result<String> {
        if (partials.isEmpty()) return Result.failure(IOException("没有可合并的分段结果"))
        if (partials.size == 1) return Result.success(partials[0])
        val joined = partials.joinToString("\n\n---\n\n")
        val est = TranscriptChunker.estimateTokens(joined) + REDUCE_OVERHEAD_TOKENS
        if (est <= engine.maxSingleTokens) {
            llmLog("reduce 开始（合并 ${partials.size} 段，深度=$depth，预估约${est} token）")
            val t0 = System.currentTimeMillis()
            val user = promptLoader.render(
                promptLoader.load("user_note_reduce.txt"),
                mapOf(
                    "title" to video.title,
                    "partials" to joined,
                ),
            )
            return chat(engine, system, user, onProgress).also {
                llmLog(
                    "reduce 结束：耗时=${System.currentTimeMillis() - t0}ms，" +
                        (if (it.isSuccess) "输出约${it.getOrNull()?.length}字符" else "失败"),
                )
            }
        }
        if (depth >= MAX_REDUCE_DEPTH) {
            return Result.failure(
                IOException("文稿过长：递归合并 $MAX_REDUCE_DEPTH 层后仍超出单遍上限，请拆短视频或换大上下文模型"),
            )
        }
        // 分组：每组拼接估算 ≤ chunkTokens；单段超限的独占一组（下层 size==1 直接返回，不再放大）。
        val groups = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentTokens = 0
        for (partial in partials) {
            val pt = TranscriptChunker.estimateTokens(partial)
            if (current.isNotEmpty() && currentTokens + pt > engine.chunkTokens) {
                groups.add(current)
                current = mutableListOf()
                currentTokens = 0
            }
            current.add(partial)
            currentTokens += pt
        }
        if (current.isNotEmpty()) groups.add(current)
        llmLog("reduce 分组：${partials.size} 段 → ${groups.size} 组（深度=$depth），组内先合并")
        val groupResults = mutableListOf<String>()
        for ((gi, group) in groups.withIndex()) {
            llmLog("reduce 组 ${gi + 1}/${groups.size}（${group.size} 段）开始")
            val r = reducePartials(group, video, engine, system, onProgress, depth + 1)
            groupResults.add(r.getOrElse { return Result.failure(it) })
        }
        // 组结果总量必须小于本层输入，否则递归无法收敛（防御性检查）。
        val shrunk = TranscriptChunker.estimateTokens(groupResults.joinToString("\n\n---\n\n"))
        if (shrunk >= est) {
            return Result.failure(
                IOException("文稿过长：合并后未缩小（预估 ${shrunk} token），无法在上下文内完成，请拆短视频"),
            )
        }
        return reducePartials(groupResults, video, engine, system, onProgress, depth + 1)
    }

    /** 修正输入超限标记：调用方据此跳过修正而非整单失败（H-02）。 */
    private class FixInputTooLongException(message: String) : IOException(message)

    /** 修正重试：列出失败时间戳 + 其前后 30 秒原文，要求模型只做最小修正。 */
    private suspend fun fixTimestamps(
        markdown: String,
        failures: List<TimestampRef>,
        segments: List<TranscriptSegment>,
        engine: LlmEngine,
        system: String,
        onProgress: (GenerateStage, Int) -> Unit,
    ): Result<String> {
        val unique = failures.distinctBy { it.seconds }
        val failedList = unique.joinToString("\n") { "- ${it.raw}" }
        val contextLines = linkedSetOf<String>()
        for (ref in unique) {
            val tMs = ref.seconds * 1000
            segments.filter {
                it.startMs >= tMs - CONTEXT_MS && it.startMs <= tMs + CONTEXT_MS
            }.forEach { seg ->
                contextLines.add("[${formatTimestamp(seg.startMs)}] ${seg.text}")
            }
        }
        val user = promptLoader.render(
            promptLoader.load("fix_timestamps.txt"),
            mapOf(
                "failed" to failedList,
                "context" to contextLines.joinToString("\n").ifBlank { "（无相关文稿片段）" },
            ),
        )
        // 修正时附上原文，约束模型只做最小改动
        val fixSystem = system + "\n\n以下是需要修正的笔记正文，请基于它输出修正后的完整版本：\n\n$markdown"
        // H-02：修正输入超限时不硬发（否则本地 rc=12 或云端 400，整单白跑）。
        // 返回 FixInputTooLongException，由调用方跳过修正：保留原文，后续流程会剔除
        // 失败时间点并标注「时间点待核实」，笔记本身不受影响。
        val fixEst = TranscriptChunker.estimateTokens(fixSystem) + TranscriptChunker.estimateTokens(user)
        if (fixEst > engine.maxSingleTokens) {
            llmLog("修正输入超限（预估约${fixEst} token > 单遍上限 ${engine.maxSingleTokens}），跳过修正")
            return Result.failure(FixInputTooLongException("修正输入过长（预估约${fixEst} token），已跳过修正"))
        }
        return chat(engine, fixSystem, user, onProgress)
    }

    /** 流式对话：累计字符数通过 onProgress 上报。 */
    private suspend fun chat(
        engine: LlmEngine,
        system: String,
        user: String,
        onProgress: (GenerateStage, Int) -> Unit,
    ): Result<String> {
        var received = 0
        // 先切到"正在生成"阶段：prefill 可能耗时很长，别等到第一个 token 才切，
        // 否则 prefill 期间 UI 永远显示"正在准备文稿"
        onProgress(GenerateStage.GENERATING, 0)
        return engine.chatStream(
            messages = listOf(
                ChatMessage("system", system),
                ChatMessage("user", user),
            ),
            onToken = { token ->
                received += token.length
                onProgress(GenerateStage.GENERATING, received)
            },
        )
    }

    /** 文稿格式化为 `[mm:ss] 文本` 行，供 prompt 使用。 */
    private fun formatTranscript(segments: List<TranscriptSegment>): String =
        segments.joinToString("\n") { "[${formatTimestamp(it.startMs)}] ${it.text}" }

    /** 毫秒 → m:ss 或 h:mm:ss（data 层自用，不依赖 ui 包）。 */
    private fun formatTimestamp(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%d:%02d".format(minutes, secs)
        }
    }

    private fun formatSeconds(totalSeconds: Long): String =
        formatTimestamp(totalSeconds * 1000)

    companion object {
        /** 修正 prompt 中失败时间戳的上下文半径：前后 30 秒。 */
        private const val CONTEXT_MS = 30_000L

        /** reduce 输入估算余量：模板 + 标题 + 分隔符（估算本身已含 partials 文本）。 */
        private const val REDUCE_OVERHEAD_TOKENS = 500

        /** 递归 reduce 最大深度：正常笔记 1–2 层即收敛，超深说明输入异常。 */
        private const val MAX_REDUCE_DEPTH = 5
    }
}

/** 笔记标题最大长度（超出截断 + 省略号）。 */
private const val NOTE_TITLE_MAX_LEN = 40

/** 视频标题 → 笔记标题：取首行、去空白、超长截断（抖音长文案全文当标题会撑坏布局）。 */
internal fun String.toNoteTitle(): String {
    val firstLine = lineSequence().firstOrNull()?.trim().orEmpty()
    if (firstLine.isBlank()) return "未命名笔记"
    return if (firstLine.length > NOTE_TITLE_MAX_LEN) firstLine.take(NOTE_TITLE_MAX_LEN) + "…" else firstLine
}
