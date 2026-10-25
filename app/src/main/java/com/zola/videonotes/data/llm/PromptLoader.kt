package com.zola.videonotes.data.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Prompt 模板加载器：从 `assets/prompts/` 读取模板文件。
 *
 * 模板文件以 `#` 开头的注释行作为版本头，加载时自动去掉头部注释块，
 * 只保留真正发给模型的正文。
 */
@Singleton
class PromptLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 读取模板正文（去掉头部 `#` 注释行），失败时抛 IOException。 */
    fun load(fileName: String): String {
        val raw = context.assets.open("$PROMPT_DIR/$fileName")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return stripHeaderComments(raw)
    }

    /** 用占位符替换模板中的 `{key}`。单次扫描替换：旧循环 replace 会把标题里的 `{transcript}` 在 transcript 轮次二次展开（L-29）。 */
    fun render(template: String, vars: Map<String, String>): String {
        if (vars.isEmpty()) return template
        // 占位符含起止大括号，正则转义后单次匹配；lambda 返回值按字面使用，不做 $ 分组展开。
        val pattern = vars.keys.joinToString("|") { Regex.escape("{$it}") }.toRegex()
        return pattern.replace(template) { match ->
            vars[match.value.removeSurrounding("{", "}")] ?: match.value
        }
    }

    companion object {
        const val PROMPT_DIR = "prompts"

        /** 去掉文件头部的 `#` 注释行（版本注释），保留正文。 */
        fun stripHeaderComments(raw: String): String {
            val lines = raw.lines()
            val bodyStart = lines.indexOfFirst { !it.trimStart().startsWith("#") }
                .takeIf { it >= 0 } ?: lines.size
            return lines.drop(bodyStart).joinToString("\n").trim()
        }
    }
}
