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
                    llmLog("笔记生成失败：阶段=修正重试，错误=${it.message ?: it.javaClass.simpleName}")
                    return Result.failure(it)
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
            llmLog("reduce 开始（合并 ${partials.size} 段）")
            val t0 = System.currentTimeMillis()
            val user = promptLoader.render(
                promptLoader.load("user_note_reduce.txt"),
                mapOf(
                    "title" to video.title,
                    "partials" to partials.joinToString("\n\n---\n\n"),
                ),
            )
            chat(engine, system, user, onProgress).also {
                llmLog(
                    "reduce 结束：耗时=${System.currentTimeMillis() - t0}ms，" +
                        (if (it.isSuccess) "输出约${it.getOrNull()?.length}字符" else "失败"),
                )
            }
        }
    }

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
