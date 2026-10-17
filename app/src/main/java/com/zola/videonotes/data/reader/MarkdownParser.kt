package com.zola.videonotes.data.reader

import com.zola.videonotes.data.llm.TimestampValidator

/**
 * Markdown 块级元素（自研解析器产出，`ui/reader` 渲染）。
 *
 * 覆盖 M4 prompt 输出的全部结构：标题 / 列表 / 表格 / 代码块（含 mermaid）
 * / 引用 / 分隔线 / details 折叠块。解析失败的行按普通段落处理，不抛异常。
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock
    data class Paragraph(val inlines: List<MdInline>) : MdBlock
    data class UnorderedList(val items: List<List<MdInline>>) : MdBlock
    data class OrderedList(val items: List<List<MdInline>>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Details(val summary: String, val blocks: List<MdBlock>) : MdBlock
    data object Divider : MdBlock
}

/** Markdown 行内元素。 */
sealed interface MdInline {
    data class Text(val text: String) : MdInline
    data class Bold(val inlines: List<MdInline>) : MdInline
    data class Italic(val inlines: List<MdInline>) : MdInline
    data class Code(val code: String) : MdInline
    data class Link(val text: String, val url: String) : MdInline
    /** 时间戳 pill：raw 如 `[[12:34]]`，seconds 为跳转秒数。 */
    data class Pill(val raw: String, val seconds: Long) : MdInline
}

/**
 * 自研 Markdown 解析器（纯 Kotlin，无第三方依赖，JVM 可测）。
 *
 * 行级块解析 + 行内正则分词。容错优先：任何未识别的结构都退化为段落，
 * 绝不崩溃（用户笔记内容不可信，LLM 输出也可能不规范）。
 */
object MarkdownParser {

    /**
     * 行内分词：pill / 链接 / 行内代码 / 粗体 / 斜体（顺序决定优先级）。
     * pill 正则复用 [TimestampValidator.PILL_REGEX]（单点定义，与质量门禁一致）。
     */
    private val INLINE_REGEX = Regex(
        "(" + TimestampValidator.PILL_REGEX.pattern + ")" + // 1: pill，2: 小时部分
            """|\[([^\]\n]+)\]\(([^)\s\n]+)\)""" + // 3,4: [文本](url)
            """|`([^`\n]+)`""" + // 5: 行内代码
            """|\*\*(.+?)\*\*""" + // 6: 粗体（非贪婪）
            """|\*([^*\n]+?)\*""", // 7: 斜体
    )

    private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
    private val UNORDERED_REGEX = Regex("""^\s*[-*]\s+(.+)$""")
    private val ORDERED_REGEX = Regex("""^\s*\d+[.)]\s+(.+)$""")
    private val DIVIDER_REGEX = Regex("""^\s*(---|\*\*\*|___)\s*$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)+\|?\s*$""")
    private val SUMMARY_REGEX = Regex("""<summary>(.*?)</summary>""")

    /** 解析全文为块列表。 */
    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.lines()
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                line.isBlank() -> i++
                line.startsWith("```") -> {
                    val (block, next) = parseCodeBlock(lines, i)
                    blocks.add(block)
                    i = next
                }
                trimmed.startsWith("<details", ignoreCase = true) -> {
                    val (block, next) = parseDetails(lines, i)
                    blocks.add(block)
                    i = next
                }
                HEADING_REGEX.matches(trimmed) -> {
                    val m = HEADING_REGEX.find(trimmed)!!
                    blocks.add(
                        MdBlock.Heading(
                            level = m.groupValues[1].length.coerceAtMost(6),
                            inlines = parseInlines(m.groupValues[2].trim()),
                        ),
                    )
                    i++
                }
                trimmed.startsWith(">") -> {
                    val quoteLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quoteLines.add(lines[i].trimStart().removePrefix(">").trimStart())
                        i++
                    }
                    blocks.add(MdBlock.Quote(parseInlines(quoteLines.joinToString("\n"))))
                }
                DIVIDER_REGEX.matches(line.trim()) -> {
                    blocks.add(MdBlock.Divider)
                    i++
                }
                isTableHeader(lines, i) -> {
                    val (block, next) = parseTable(lines, i)
                    blocks.add(block)
                    i = next
                }
                UNORDERED_REGEX.matches(line) -> {
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size && UNORDERED_REGEX.matches(lines[i]) &&
                        !DIVIDER_REGEX.matches(lines[i].trim())
                    ) {
                        items.add(parseInlines(UNORDERED_REGEX.find(lines[i])!!.groupValues[1].trim()))
                        i++
                    }
                    blocks.add(MdBlock.UnorderedList(items))
                }
                ORDERED_REGEX.matches(line) -> {
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size && ORDERED_REGEX.matches(lines[i])) {
                        items.add(parseInlines(ORDERED_REGEX.find(lines[i])!!.groupValues[1].trim()))
                        i++
                    }
                    blocks.add(MdBlock.OrderedList(items))
                }
                else -> {
                    val paraLines = mutableListOf<String>()
                    while (i < lines.size && !lines[i].isBlank() && !isBlockStart(lines, i)) {
                        paraLines.add(lines[i].trim())
                        i++
                    }
                    if (paraLines.isNotEmpty()) {
                        blocks.add(MdBlock.Paragraph(parseInlines(paraLines.joinToString("\n"))))
                    } else {
                        // 防御：既非空行也不是块起始（理论上不可达），按单行段落消费避免死循环
                        blocks.add(MdBlock.Paragraph(parseInlines(line.trim())))
                        i++
                    }
                }
            }
        }
        return blocks
    }

    /** 解析行内元素（递归处理粗体/斜体嵌套）。 */
    fun parseInlines(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<MdInline>()
        var last = 0
        for (m in INLINE_REGEX.findAll(text)) {
            val start = m.range.first
            if (start > last) {
                result.add(MdInline.Text(text.substring(last, start)))
            }
            val pill = m.groups[1]?.value.orEmpty()
            val linkText = m.groups[3]?.value.orEmpty()
            val linkUrl = m.groups[4]?.value.orEmpty()
            val code = m.groups[5]?.value.orEmpty()
            val bold = m.groups[6]?.value.orEmpty()
            val italic = m.groups[7]?.value.orEmpty()
            when {
                pill.isNotEmpty() -> {
                    val seconds = TimestampValidator.parseToSeconds(
                        pill.removeSurrounding("[[", "]]"),
                    )
                    if (seconds != null) result.add(MdInline.Pill(pill, seconds))
                    else result.add(MdInline.Text(pill))
                }
                linkText.isNotEmpty() ->
                    result.add(MdInline.Link(linkText, linkUrl))
                code.isNotEmpty() -> result.add(MdInline.Code(code))
                bold.isNotEmpty() -> result.add(MdInline.Bold(parseInlines(bold)))
                italic.isNotEmpty() -> result.add(MdInline.Italic(parseInlines(italic)))
            }
            last = m.range.last + 1
        }
        if (last < text.length) {
            result.add(MdInline.Text(text.substring(last)))
        }
        return result.filterNot { it is MdInline.Text && it.text.isEmpty() }
    }

    /** 判断第 i 行是否为某个块的起始（段落收集时遇到即停）。 */
    private fun isBlockStart(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        val trimmed = line.trimStart()
        return line.startsWith("```") ||
            trimmed.startsWith("<details", ignoreCase = true) ||
            HEADING_REGEX.matches(trimmed) ||
            trimmed.startsWith(">") ||
            DIVIDER_REGEX.matches(line.trim()) ||
            isTableHeader(lines, i) ||
            UNORDERED_REGEX.matches(line) ||
            ORDERED_REGEX.matches(line)
    }

    private fun parseCodeBlock(lines: List<String>, start: Int): Pair<MdBlock, Int> {
        val language = lines[start].removePrefix("```").trim().takeIf { it.isNotEmpty() }
        val code = StringBuilder()
        var i = start + 1
        while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
            code.appendLine(lines[i])
            i++
        }
        // 未闭合的代码块：消费到文末，同样产出代码块（容错）
        val next = if (i < lines.size) i + 1 else i
        return MdBlock.CodeBlock(language, code.toString().trimEnd('\n')) to next
    }

    private fun parseDetails(lines: List<String>, start: Int): Pair<MdBlock, Int> {
        val inner = mutableListOf<String>()
        var i = start
        // 首行去掉 <details …> 前缀（可能与 <summary> 同行）
        inner.add(lines[i].replace(Regex("""^<details[^>]*>""", RegexOption.IGNORE_CASE), ""))
        i++
        while (i < lines.size && !lines[i].contains("</details>", ignoreCase = true)) {
            inner.add(lines[i])
            i++
        }
        val next = if (i < lines.size) i + 1 else i
        val innerText = inner.joinToString("\n")
            .replace(Regex("""</details.*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        val summary = SUMMARY_REGEX.find(innerText)?.groupValues?.get(1)?.trim().orEmpty()
        val body = SUMMARY_REGEX.replace(innerText, "").trim()
        // 未闭合的 details：同样产出折叠块（容错）
        return MdBlock.Details(
            summary = summary.ifBlank { "展开查看" },
            blocks = parse(body),
        ) to next
    }

    private fun isTableHeader(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val line = lines[i]
        if (!line.contains("|")) return false
        return TABLE_SEPARATOR_REGEX.matches(lines[i + 1].trim())
    }

    private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock, Int> {
        val header = splitRow(lines[start])
        var i = start + 2 // 跳过分隔行
        val rows = mutableListOf<List<String>>()
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
            rows.add(splitRow(lines[i]))
            i++
        }
        return MdBlock.Table(header, rows) to i
    }

    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split("|").map { it.trim() }
}
