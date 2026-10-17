package com.zola.videonotes.data.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自研 Markdown 解析器测试（纯 JVM）。 */
class MarkdownParserTest {

    @Test
    fun `parse headings levels`() {
        val blocks = MarkdownParser.parse("# 一级\n## 二级\n### 三级\n")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals(3, (blocks[2] as MdBlock.Heading).level)
        assertEquals("一级", ((blocks[0] as MdBlock.Heading).inlines[0] as MdInline.Text).text)
    }

    @Test
    fun `parse unordered and ordered lists`() {
        val blocks = MarkdownParser.parse("- 苹果\n- 香蕉\n\n1. 第一步\n2. 第二步\n")
        assertEquals(2, blocks.size)
        val ul = blocks[0] as MdBlock.UnorderedList
        assertEquals(2, ul.items.size)
        val ol = blocks[1] as MdBlock.OrderedList
        assertEquals(2, ol.items.size)
        assertEquals("第一步", (ol.items[0][0] as MdInline.Text).text)
    }

    @Test
    fun `parse table with header and rows`() {
        val md = "| 术语 | 解释 |\n|---|---|\n| LLM | 大语言模型 |\n| RAG | 检索增强 |\n"
        val blocks = MarkdownParser.parse(md)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("术语", "解释"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("LLM", "大语言模型"), table.rows[0])
    }

    @Test
    fun `parse mermaid code block keeps language`() {
        val md = "```mermaid\nmindmap\n  root((主题))\n```\n"
        val blocks = MarkdownParser.parse(md)
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.CodeBlock
        assertEquals("mermaid", code.language)
        assertTrue(code.code.contains("mindmap"))
    }

    @Test
    fun `parse details block with summary`() {
        val md = "<details><summary>查看答案</summary>\n答案是 42\n</details>\n"
        val blocks = MarkdownParser.parse(md)
        assertEquals(1, blocks.size)
        val details = blocks[0] as MdBlock.Details
        assertEquals("查看答案", details.summary)
        assertEquals(1, details.blocks.size)
        val para = details.blocks[0] as MdBlock.Paragraph
        assertEquals("答案是 42", (para.inlines[0] as MdInline.Text).text)
    }

    @Test
    fun `parse quote divider and paragraph`() {
        val blocks = MarkdownParser.parse("> 引用内容\n\n---\n\n普通段落\n")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Quote)
        assertTrue(blocks[1] is MdBlock.Divider)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `parse inline pill extracts seconds`() {
        val inlines = MarkdownParser.parseInlines("见 [[05:30]] 的介绍和 [[1:02:03]] 的总结")
        val pills = inlines.filterIsInstance<MdInline.Pill>()
        assertEquals(2, pills.size)
        assertEquals(330L, pills[0].seconds)
        assertEquals(3723L, pills[1].seconds)
        // pill 前后文本保留
        assertTrue(inlines.any { it is MdInline.Text && (it as MdInline.Text).text.contains("见") })
    }

    @Test
    fun `parse inline bold italic code link`() {
        val inlines = MarkdownParser.parseInlines("**粗体** 与 *斜体* 与 `代码` 与 [文本](https://example.com)")
        assertTrue(inlines.any { it is MdInline.Bold })
        assertTrue(inlines.any { it is MdInline.Italic })
        assertTrue(inlines.any { it is MdInline.Code })
        val link = inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("文本", link.text)
        assertEquals("https://example.com", link.url)
    }

    @Test
    fun `parse malformed input never crashes`() {
        // 未闭合代码块 / 未闭合 details / 孤立标记 / 空输入
        val inputs = listOf(
            "```kotlin\nval x = 1\n",
            "<details><summary>标题</summary>\n内容没有闭合",
            "**未闭合的粗体\n*孤立星号\n[[99:99]] 非法 pill",
            "",
            "   \n\n",
            "#没有空格的标题\n-没有空格的列表",
        )
        for (input in inputs) {
            val blocks = MarkdownParser.parse(input)
            // 不抛异常即通过；结果非空（除纯空输入外）
            assertTrue(blocks.isNotEmpty() || input.isBlank())
        }
    }

    @Test
    fun `parse heading with inline pill`() {
        val blocks = MarkdownParser.parse("## 小节要点 [[12:34]]")
        val heading = blocks[0] as MdBlock.Heading
        assertTrue(heading.inlines.any { it is MdInline.Pill })
    }
}
