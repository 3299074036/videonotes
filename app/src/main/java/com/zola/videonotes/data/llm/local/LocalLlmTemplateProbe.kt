package com.zola.videonotes.data.llm.local

import android.content.Context
import com.zola.videonotes.data.catalog.CatalogEntry
import com.zola.videonotes.data.catalog.CatalogSource
import com.zola.videonotes.data.catalog.CuratedProbeStore
import com.zola.videonotes.data.catalog.DynamicModelRegistry
import com.zola.videonotes.data.catalog.TemplateProbeState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态条目模板探针调度（WP2）：下载完成时执行一次，结果按文件指纹持久化到
 * 本地注册表（entry.json），不在每次生成时重复。
 *
 * 探针在专用单线程上跑（与引擎诊断探针同理：native 若 hang 只卡住该线程）。
 * 探针结论口径以 llama.cpp v0.7.0 为限：apply 不解析 Jinja、只认预定义模板集，
 * 「不支持」是运行时固有限制，不是文件损坏（文案见 UI 层）。
 */
@Singleton
class LocalLlmTemplateProbe @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: DynamicModelRegistry,
    private val curatedStore: CuratedProbeStore,
) {

    private val probeDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llm-template-probe").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** 对动态条目执行探针并落盘结论；条目非动态或文件缺失时返回 LOAD_FAILED。 */
    suspend fun probe(entry: CatalogEntry): TemplateProbeState = withContext(probeDispatcher) {
        // 面包屑路径须在首次 native 加载前设置（与引擎构造同一安装点，幂等）
        LlmCrashBreadcrumb.install(context)
        val file = registry.modelFile(entry)
        if (!file.exists()) return@withContext TemplateProbeState.LOAD_FAILED
        val code = runCatching { LlamaCpp.probeChatTemplate(file.absolutePath) }
            .getOrDefault(LlamaCpp.PROBE_LOAD_FAILED)
        val state = when (code) {
            LlamaCpp.PROBE_OK -> TemplateProbeState.OK
            LlamaCpp.PROBE_NO_TEMPLATE -> TemplateProbeState.NO_TEMPLATE
            LlamaCpp.PROBE_UNSUPPORTED -> TemplateProbeState.UNSUPPORTED
            else -> TemplateProbeState.LOAD_FAILED
        }
        val fp = file.length() to file.lastModified()
        registry.setProbe(entry.id, state, fp)
        state
    }

    /** 当前文件对应的有效探针结论（无结论或指纹已变返回 null）。 */
    fun cachedState(entry: CatalogEntry): TemplateProbeState? =
        if (entry.source == CatalogSource.DYNAMIC) registry.validProbe(entry) else null

    /**
     * 精选条目探针（国内名单定版）：按条目声明的模板通路实测并把结论+解析模式
     * 落盘到 [CuratedProbeStore]（指纹绑定当前文件）。
     *
     * - 命名模板条目：先试预定义命名模板；GLM-Edge（glmedge）命名不过时回退试
     *   内嵌模板，以探针实测可行为准定解析模式；混元（hunyuan-dense）只走命名。
     * - 内嵌模板条目：试 GGUF 内嵌模板。
     */
    suspend fun probeCurated(model: LocalLlmModel, file: File): TemplateProbeState =
        withContext(probeDispatcher) {
            LlmCrashBreadcrumb.install(context)
            if (!file.exists()) return@withContext TemplateProbeState.LOAD_FAILED
            val path = file.absolutePath
            val named = model.chatTemplateName
            var mode = CuratedProbeStore.MODE_EMBEDDED
            val code: Int
            if (named != null) {
                val namedCode = runCatching { LlamaCpp.probeChatTemplateNamed(path, named) }
                    .getOrDefault(LlamaCpp.PROBE_LOAD_FAILED)
                if (namedCode == LlamaCpp.PROBE_OK) {
                    mode = CuratedProbeStore.MODE_NAMED
                    code = namedCode
                } else if (named == "glmedge") {
                    // GLM-Edge：命名模板不过时回退内嵌模板，实测可行为准
                    val embCode = runCatching { LlamaCpp.probeChatTemplate(path) }
                        .getOrDefault(LlamaCpp.PROBE_LOAD_FAILED)
                    if (embCode == LlamaCpp.PROBE_OK) {
                        mode = CuratedProbeStore.MODE_EMBEDDED
                        code = embCode
                    } else {
                        mode = CuratedProbeStore.MODE_NAMED
                        code = namedCode
                    }
                } else {
                    mode = CuratedProbeStore.MODE_NAMED
                    code = namedCode
                }
            } else {
                code = runCatching { LlamaCpp.probeChatTemplate(path) }
                    .getOrDefault(LlamaCpp.PROBE_LOAD_FAILED)
            }
            val state = when (code) {
                LlamaCpp.PROBE_OK -> TemplateProbeState.OK
                LlamaCpp.PROBE_NO_TEMPLATE -> TemplateProbeState.NO_TEMPLATE
                LlamaCpp.PROBE_UNSUPPORTED -> TemplateProbeState.UNSUPPORTED
                else -> TemplateProbeState.LOAD_FAILED
            }
            curatedStore.save(model.id, state, mode, file.length() to file.lastModified())
            state
        }
}
