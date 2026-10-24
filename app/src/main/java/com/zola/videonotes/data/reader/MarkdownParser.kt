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
    data class UnorderedList(val items: List<ListItem>) : MdBlock
    data class OrderedList(val items: List<ListItem>) : MdBlock
    data class CodeBlock(val language: String?, val code: String) : MdBlock
    data class Quote(val inlines: List<MdInline>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Details(val summary: String, val blocks: List<MdBlock>) : MdBlock
    data object Divider : MdBlock
}

/** 列表项：indent 为缩进层级（2 空格一级，嵌套列表用）；number 仅有序列表有（原文编号，渲染时保留）。 */
data class ListItem(
    val inlines: List<MdInline>,
    val indent: Int = 0,
    val number: Int? = null,
)

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
            """|\[((?:[^\]\n]|\](?!\())+?)\]\(([^)\s\n]+(?:\)[^)\s\n]*)*)\)""" + // 3,4: [文本](url)，文本可含 ]（非 ]( 者），URL 可含成对括号
            """|`([^`\n]+)`""" + // 5: 行内代码
            """|\*\*(.+?)\*\*""" + // 6: 粗体（非贪婪）
            """|\*([^*\n]+?)\*""", // 7: 斜体
    )

    private val HEADING_REGEX = Regex("""^(#{1,6})\s*(.*)$""")
    private val UNORDERED_REGEX = Regex("""^\s*[-*]\s+(.+)$""")
    private val ORDERED_REGEX = Regex("""^\s*(\d+)[.)]\s+(.+)$""")
    private val DIVIDER_REGEX = Regex("""^\s*(---|\*\*\*|___)\s*$""")
    private val TABLE_SEPARATOR_REGEX = Regex("""^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$""")
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
                    val (block, next) = parseTable(lines, i, i + 2)
                    blocks.add(block)
                    i = next
                }
                isTableWithoutSeparator(lines, i) -> {
                    val (block, next) = parseTable(lines, i, i + 1)
                    blocks.add(block)
                    i = next
                }
                UNORDERED_REGEX.matches(line) -> {
                    val items = mutableListOf<ListItem>()
                    while (i < lines.size && UNORDERED_REGEX.matches(lines[i]) &&
                        !DIVIDER_REGEX.matches(lines[i].trim())
                    ) {
                        val raw = lines[i]
                        val indent = (raw.length - raw.trimStart().length) / 2
                        items.add(
                            ListItem(
                                inlines = parseInlines(UNORDERED_REGEX.find(raw)!!.groupValues[1].trim()),
                                indent = indent,
                            ),
                        )
                        i++
                    }
                    blocks.add(MdBlock.UnorderedList(items))
                }
                ORDERED_REGEX.matches(line) -> {
                    val items = mutableListOf<ListItem>()
                    while (i < lines.size && ORDERED_REGEX.matches(lines[i])) {
                        val raw = lines[i]
                        val m = ORDERED_REGEX.find(raw)!!
                        val indent = (raw.length - raw.trimStart().length) / 2
                        items.add(
                            ListItem(
                                inlines = parseInlines(m.groupValues[2].trim()),
                                indent = indent,
                                number = m.groupValues[1].toIntOrNull(),
                            ),
                        )
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
            isTableWithoutSeparator(lines, i) ||
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

    /**
     * 无分隔行表格启发式识别（小模型常漏写 |---|---| 行）：
     * 连续两行含竖线且列数一致，且首行不是列表项（避免把带竖线的列表项误判成表）。
     * 标准分隔行表由 isTableHeader 优先处理，这里排除分隔行本身。
     */
    private fun isTableWithoutSeparator(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        val first = lines[i]
        if (!first.contains("|")) return false
        if (UNORDERED_REGEX.matches(first) || ORDERED_REGEX.matches(first)) return false
        val second = lines[i + 1]
        if (second.isBlank() || !second.contains("|")) return false
        if (TABLE_SEPARATOR_REGEX.matches(second.trim())) return false
        return splitRow(first).size == splitRow(second).size
    }

    private fun parseTable(lines: List<String>, start: Int, dataStart: Int): Pair<MdBlock, Int> {
        val header = splitRow(lines[start])
        var i = dataStart
        val rows = mutableListOf<List<String>>()
        while (i < lines.size && lines[i].isNotBlank() && lines[i].contains("|")) {
            val cells = splitRow(lines[i])
            // 行列数对齐：行短补空，行长把多余格并入最后一格（不静默丢内容）
            val row = when {
                cells.size < header.size -> cells + List(header.size - cells.size) { "" }
                cells.size > header.size -> cells.take(header.size - 1) +
                    cells.drop(header.size - 1).joinToString(" | ")
                else -> cells
            }
            rows.add(row)
            i++
        }
        return MdBlock.Table(header, rows) to i
    }

    /** 切分表格行：只切未转义的竖线，`\|` 还原为字面 `|`。 */
    private fun splitRow(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|")
            .split(Regex("""(?<!\\)\|"""))
            .map { it.replace("\\|", "|").trim() }
}
