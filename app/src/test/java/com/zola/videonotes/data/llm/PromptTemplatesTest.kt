package com.zola.videonotes.data.llm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prompt 模板关键指令存在性断言（M4 质量门禁冻结项）。
 * 读取源码 assets 目录下的真实模板文件（单元测试工作目录为 module 根目录）。
 * 模板为精简设计：笔记五节结构与质量门禁定义在 system prompt，
 * user prompt 只做占位填充与阶段指令。
 */
class PromptTemplatesTest {

    private fun load(name: String): String {
        val file = File("src/main/assets/prompts/$name")
        assertTrue("模板缺失：${file.path}", file.exists())
        val content = file.readText()
        val firstLine = content.lineSequence().first()
        assertTrue("模板缺少版本头：$name", firstLine.startsWith("#") && firstLine.contains("v1"))
        return content
    }

    /** system prompt 必须包含：五节结构、pill 时间戳格式、质量门禁（5 秒依据、编造属严重错误）。 */
    @Test
    fun `system prompt contains structure pill format and quality gate`() {
        val system = load("system_note.txt")
        // 笔记结构五节
        assertTrue(system.contains("## 小节要点"))
        assertTrue(system.contains("## 知识地图"))
        assertTrue(system.contains("```mermaid"))
        assertTrue(system.contains("## 概念卡片"))
        assertTrue(system.contains("## 自测题"))
        assertTrue(system.contains("<details>"))
        assertTrue(system.contains("## 时间索引"))
        // 时间戳格式（冻结的 pill 语法）
        assertTrue(system.contains("[[mm:ss]]"))
        assertTrue(system.contains("[[h:mm:ss]]"))
        // 质量门禁
        assertTrue(system.contains("5 秒"))
        assertTrue(system.contains("编造"))
        assertTrue(system.contains("严重错误"))
    }

    /** 单遍 user prompt：填充占位符，结构要求委托给 system prompt。 */
    @Test
    fun `single pass user prompt fills placeholders and defers to system prompt`() {
        val prompt = load("user_note_single.txt")
        assertTrue(prompt.contains("{title}"))
        assertTrue(prompt.contains("{transcript}"))
        assertTrue(prompt.contains("system prompt"))
    }

    /** 分段 user prompt：只输出本段小节要点，时间戳不做偏移。 */
    @Test
    fun `chunk user prompt requires per-chunk key points without shifting timestamps`() {
        val prompt = load("user_note_chunk.txt")
        assertTrue(prompt.contains("小节要点"))
        assertTrue(prompt.contains("不要做任何偏移"))
    }

    /** reduce user prompt：合并去重分段要点，再生成其余四节。 */
    @Test
    fun `reduce user prompt requires merging chunks`() {
        val prompt = load("user_note_reduce.txt")
        assertTrue(prompt.contains("合并"))
        assertTrue(prompt.contains("去重"))
        assertTrue(prompt.contains("{partials}"))
    }

    /** 修正 user prompt：只做最小修正、5 秒依据、保持 pill 格式、无依据则删除。 */
    @Test
    fun `fix timestamps user prompt scopes to minimal correction`() {
        val prompt = load("fix_timestamps.txt")
        assertTrue(prompt.contains("最小修正"))
        assertTrue(prompt.contains("5 秒"))
        assertTrue(prompt.contains("[[mm:ss]]"))
        assertTrue(prompt.contains("删除"))
    }
}
