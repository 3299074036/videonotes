package com.zola.videonotes.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.zola.videonotes.data.reader.MdBlock
import com.zola.videonotes.data.reader.MdInline
import com.zola.videonotes.data.reader.MarkdownParser
import com.zola.videonotes.ui.theme.VideoNotesTokens

/**
 * 自研 Markdown 阅读器（M5）：渲染 M4 生成的课堂笔记。
 *
 * - pill（时间戳）渲染为可点击小芯片，FlowRow 交错排布（避免 InlineTextContent
 *   占位尺寸估算，保证芯片与文字对齐可预期）；
 * - 链接为可点击的下划线文本；代码块等宽 + 横向滚动；表格横向滚动；
 * - mermaid 代码块走唯一的 WebView 渲染（[MermaidBlock]）。
 */
@Composable
fun MarkdownReader(
    markdown: String,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val sections = remember(blocks) { buildRailSections(blocks) }
    // 无任何时间 pill 的笔记（如纯文本导入）保持原平铺渲染，不出空轨
    if (sections.none { it.anchorSeconds != null }) {
        Column(
            verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
            modifier = modifier,
        ) {
            blocks.forEach { block ->
                MdBlockView(
                    block = block,
                    onPillClick = onPillClick,
                    onLinkClick = onLinkClick,
                )
            }
        }
        return
    }
    // 纸上课堂时间轨：按标题分节，每节首个 pill 移出正文、上左侧轨
    Column(
        verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
        modifier = modifier,
    ) {
        sections.forEach { section ->
            RailSectionView(
                section = section,
                onPillClick = onPillClick,
                onLinkClick = onLinkClick,
            )
        }
    }
}

/** 时间轨上的一节：左轨列（竖线 + 锚点 pill）+ 右侧正文块列。
 *
 * 2026-10-24 闪退修复（方案 A）：此前 Row 用 height(IntrinsicSize.Min) 让竖线
 * Box 以 fillMaxHeight 撑满整节；一旦节内含表格（BoxWithConstraints，一种
 * SubcomposeLayout），索要 intrinsic 尺寸即抛 IllegalStateException（note 2 首炸）。
 * 现竖线改在 Row 上以 drawBehind 绘制：绘制发生在测量之后，不索要 intrinsic，
 * 故含表格的节不再触发该异常；视觉与之前一致（线居轨中、贯穿整节、pill 顶对齐）。
 */
@Composable
private fun RailSectionView(
    section: RailSection,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val railLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    Row(
        modifier = Modifier.drawBehind {
            val strokeWidth = 2.dp.toPx()
            val centerX = VideoNotesTokens.RailWidth.toPx() / 2f
            drawLine(
                color = railLineColor,
                start = Offset(centerX, 0f),
                end = Offset(centerX, size.height),
                strokeWidth = strokeWidth,
            )
        },
    ) {
        Box(
            modifier = Modifier.width(VideoNotesTokens.RailWidth),
        ) {
            section.anchorSeconds?.let { seconds ->
                RailTimestampPill(
                    seconds = seconds,
                    onClick = { onPillClick(seconds) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
        Column(
            verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space12),
            modifier = Modifier.weight(1f),
        ) {
            section.blocks.forEach { block ->
                MdBlockView(
                    block = block,
                    onPillClick = onPillClick,
                    onLinkClick = onLinkClick,
                )
            }
        }
    }
}

/** 一节：锚点秒数（无 pill 的节为 null）+ 已摘出锚点 pill 的块列表。 */
private data class RailSection(
    val anchorSeconds: Long?,
    val blocks: List<MdBlock>,
)

/**
 * 按标题块分节，并把每节正文中第一个时间 pill 摘出作为轨锚点。
 * 只处理标题 / 段落 / 引用 / 列表里的 pill；代码块、表格、Details 内的 pill 保持行内原样。
 */
private fun buildRailSections(blocks: List<MdBlock>): List<RailSection> {
    val rawSections = mutableListOf<MutableList<MdBlock>>()
    var current = mutableListOf<MdBlock>()
    rawSections.add(current)
    for (block in blocks) {
        if (block is MdBlock.Heading && current.isNotEmpty()) {
            current = mutableListOf()
            rawSections.add(current)
        }
        current.add(block)
    }
    return rawSections.filter { it.isNotEmpty() }.map { sectionBlocks ->
        var anchor: Long? = null
        val stripped = sectionBlocks.map { block ->
            if (anchor != null) {
                block
            } else {
                val (newBlock, found) = removeFirstPill(block)
                if (found != null) anchor = found
                newBlock
            }
        }
        RailSection(anchorSeconds = anchor, blocks = stripped)
    }
}

/** 摘出块内第一个 pill（返回新块与被摘秒数；无 pill 时原样返回）。 */
private fun removeFirstPill(block: MdBlock): Pair<MdBlock, Long?> = when (block) {
    is MdBlock.Heading -> {
        val (inlines, found) = removeFirstPillInlines(block.inlines)
        block.copy(inlines = inlines) to found
    }
    is MdBlock.Paragraph -> {
        val (inlines, found) = removeFirstPillInlines(block.inlines)
        block.copy(inlines = inlines) to found
    }
    is MdBlock.Quote -> {
        val (inlines, found) = removeFirstPillInlines(block.inlines)
        block.copy(inlines = inlines) to found
    }
    is MdBlock.UnorderedList -> {
        var found: Long? = null
        val items = block.items.map { item ->
            if (found != null) {
                item
            } else {
                val (newItem, f) = removeFirstPillInlines(item.inlines)
                if (f != null) {
                    found = f
                    item.copy(inlines = newItem)
                } else {
                    item
                }
            }
        }
        block.copy(items = items) to found
    }
    is MdBlock.OrderedList -> {
        var found: Long? = null
        val items = block.items.map { item ->
            if (found != null) {
                item
            } else {
                val (newItem, f) = removeFirstPillInlines(item.inlines)
                if (f != null) {
                    found = f
                    item.copy(inlines = newItem)
                } else {
                    item
                }
            }
        }
        block.copy(items = items) to found
    }
    else -> block to null
}

/** 摘出行内列表中第一个 pill（可嵌套在粗体 / 斜体里）。 */
private fun removeFirstPillInlines(
    inlines: List<MdInline>,
): Pair<List<MdInline>, Long?> {
    val out = ArrayList<MdInline>(inlines.size)
    var found: Long? = null
    for (inline in inlines) {
        if (found != null) {
            out.add(inline)
            continue
        }
        when (inline) {
            is MdInline.Pill -> found = inline.seconds // 摘出，不放回正文
            is MdInline.Bold -> {
                val (sub, f) = removeFirstPillInlines(inline.inlines)
                if (f != null) {
                    found = f
                    if (sub.isNotEmpty()) out.add(inline.copy(inlines = sub))
                } else {
                    out.add(inline)
                }
            }
            is MdInline.Italic -> {
                val (sub, f) = removeFirstPillInlines(inline.inlines)
                if (f != null) {
                    found = f
                    if (sub.isNotEmpty()) out.add(inline.copy(inlines = sub))
                } else {
                    out.add(inline)
                }
            }
            else -> out.add(inline)
        }
    }
    return out to found
}

/** 全文时间 pill 总数（含 Details 折叠块内；供详情页底部阅读条展示）。 */
internal fun countTimestampPills(blocks: List<MdBlock>): Int {
    fun countInlines(inlines: List<MdInline>): Int = inlines.sumOf { inline ->
        when (inline) {
            is MdInline.Pill -> 1
            is MdInline.Bold -> countInlines(inline.inlines)
            is MdInline.Italic -> countInlines(inline.inlines)
            else -> 0
        }
    }
    return blocks.sumOf { block ->
        when (block) {
            is MdBlock.Heading -> countInlines(block.inlines)
            is MdBlock.Paragraph -> countInlines(block.inlines)
            is MdBlock.Quote -> countInlines(block.inlines)
            is MdBlock.UnorderedList -> block.items.sumOf { countInlines(it.inlines) }
            is MdBlock.OrderedList -> block.items.sumOf { countInlines(it.inlines) }
            is MdBlock.Table -> (block.header + block.rows.flatten()).sumOf {
                countInlines(MarkdownParser.parseInlines(it))
            }
            is MdBlock.Details -> countTimestampPills(block.blocks)
            else -> 0
        }
    }
}

/** 块级渲染分发（internal：DetailsBlock 递归渲染内部块时复用）。 */
@Composable
internal fun MdBlockView(
    block: MdBlock,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            }
            RichParagraph(
                inlines = block.inlines,
                onPillClick = onPillClick,
                onLinkClick = onLinkClick,
                style = style.copy(fontWeight = FontWeight.Bold),
                modifier = modifier.padding(top = VideoNotesTokens.Space8),
            )
        }
        is MdBlock.Paragraph -> {
            RichParagraph(
                inlines = block.inlines,
                onPillClick = onPillClick,
                onLinkClick = onLinkClick,
                modifier = modifier,
            )
        }
        is MdBlock.UnorderedList -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = modifier,
            ) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = (item.indent * 16).dp),
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = VideoNotesTokens.Space8),
                        )
                        RichParagraph(
                            inlines = item.inlines,
                            onPillClick = onPillClick,
                            onLinkClick = onLinkClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        is MdBlock.OrderedList -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(VideoNotesTokens.Space8),
                modifier = modifier,
            ) {
                block.items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.padding(start = (item.indent * 16).dp),
                    ) {
                        Text(
                            text = "${item.number ?: index + 1}.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = VideoNotesTokens.Space8),
                        )
                        RichParagraph(
                            inlines = item.inlines,
                            onPillClick = onPillClick,
                            onLinkClick = onLinkClick,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        is MdBlock.CodeBlock -> {
            if (block.language?.equals("mermaid", ignoreCase = true) == true) {
                MermaidBlock(diagram = block.code, modifier = modifier)
            } else {
                CodeBlockView(code = block.code, modifier = modifier)
            }
        }
        is MdBlock.Quote -> {
            // 2026-10-24 闪退修复（方案 A，同 RailSectionView）：左侧色条改
            // drawBehind 绘制，不再用 height(IntrinsicSize.Min) + fillMaxHeight，
            // 消除第二处 intrinsic 索要（子树目前无 SubcomposeLayout，但同类隐患清零）。
            val quoteBarColor = MaterialTheme.colorScheme.primary
            Row(
                modifier = modifier.drawBehind {
                    val barWidth = 4.dp.toPx()
                    val corner = 2.dp.toPx()
                    drawRoundRect(
                        color = quoteBarColor,
                        topLeft = Offset.Zero,
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(corner, corner),
                    )
                },
            ) {
                // 色条改绘制后不再占布局宽，间隔补 4dp，使正文起始 x 与之前一致（4dp 条 + 12dp 间隔）。
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space12 + 4.dp))
                RichParagraph(
                    inlines = block.inlines,
                    onPillClick = onPillClick,
                    onLinkClick = onLinkClick,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = FontStyle.Italic,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is MdBlock.Table -> {
            TableBlock(
                table = block,
                onPillClick = onPillClick,
                onLinkClick = onLinkClick,
                modifier = modifier,
            )
        }
        is MdBlock.Details -> {
            DetailsBlock(
                block = block,
                onPillClick = onPillClick,
                onLinkClick = onLinkClick,
                modifier = modifier,
            )
        }
        is MdBlock.Divider -> {
            HorizontalDivider(
                modifier = modifier.padding(vertical = VideoNotesTokens.Space4),
            )
        }
    }
}

/** 行内片段：展平后的渲染单元（FlowRow 交错排布）。 */
private sealed interface Segment {
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
    ) : Segment
    data class Link(
        val text: String,
        val url: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
    ) : Segment
    data class Pill(val seconds: Long) : Segment
}

/** 把 MdInline 树展平为渲染片段（合并相邻同样式文本）。 */
private fun flattenSegments(inlines: List<MdInline>): List<Segment> {
    val out = mutableListOf<Segment>()
    fun emit(seg: Segment) {
        val last = out.lastOrNull()
        if (seg is Segment.Text && last is Segment.Text &&
            seg.bold == last.bold && seg.italic == last.italic && seg.code == last.code
        ) {
            out[out.lastIndex] = last.copy(text = last.text + seg.text)
        } else {
            out.add(seg)
        }
    }
    fun walk(inline: MdInline, bold: Boolean, italic: Boolean) {
        when (inline) {
            is MdInline.Text -> emit(Segment.Text(inline.text, bold, italic))
            is MdInline.Bold -> inline.inlines.forEach { walk(it, true, italic) }
            is MdInline.Italic -> inline.inlines.forEach { walk(it, bold, true) }
            is MdInline.Code -> emit(Segment.Text(inline.code, bold, italic, code = true))
            is MdInline.Link -> out.add(Segment.Link(inline.text, inline.url, bold, italic))
            is MdInline.Pill -> out.add(Segment.Pill(inline.seconds))
        }
    }
    inlines.forEach { walk(it, false, false) }
    return out
}

/**
 * 富文本段落：Text 片段 / 链接 / pill 芯片用 FlowRow 交错排布，
 * 芯片尺寸由自身内容决定，无需估算占位。
 */
@Composable
private fun RichParagraph(
    inlines: List<MdInline>,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val segments = remember(inlines) { flattenSegments(inlines) }
    if (segments.isEmpty()) return
    FlowRow(
        // 段内不留统一间距（中文无词间空格，统一间距会在句中造出多余空隙）；
        // pill 自带左右 padding 与文字隔开。
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalArrangement = Arrangement.Center,
        modifier = modifier,
    ) {
        segments.forEach { seg ->
            when (seg) {
                is Segment.Text -> {
                    Text(
                        text = seg.text,
                        style = style.merge(
                            SpanStyle(
                                fontWeight = if (seg.bold) FontWeight.Bold else null,
                                fontStyle = if (seg.italic) FontStyle.Italic else null,
                                fontFamily = if (seg.code) FontFamily.Monospace else null,
                                background = if (seg.code) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    Color.Unspecified
                                },
                            ),
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                is Segment.Link -> {
                    Text(
                        text = seg.text,
                        style = style.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ).merge(
                            SpanStyle(
                                fontWeight = if (seg.bold) FontWeight.Bold else null,
                                fontStyle = if (seg.italic) FontStyle.Italic else null,
                            ),
                        ),
                        modifier = Modifier.clickable { onLinkClick(seg.url) },
                    )
                }
                is Segment.Pill -> {
                    TimestampPill(
                        seconds = seg.seconds,
                        onClick = { onPillClick(seg.seconds) },
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/** 代码块：等宽字体 + 横向滚动。 */
@Composable
private fun CodeBlockView(code: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(VideoNotesTokens.CornerMedium),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = code.ifBlank { " " },
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(VideoNotesTokens.Space16),
        )
    }
}

/** 表格：列宽显式（不用 weight，避开横向滚动容器里 weight 塌到最小宽致全表竖排的老 bug）。
 * 单元格走 RichParagraph 行内解析（粗体 / 链接 / pill 在表内同样生效、可点）。 */
@Composable
private fun TableBlock(
    table: MdBlock.Table,
    onPillClick: (Long) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columnCount = table.header.size.coerceAtLeast(1)
        val columnWidth = maxOf(maxWidth / columnCount, 140.dp)
        Column(
            modifier = Modifier
                .width(columnWidth * columnCount)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                table.header.forEach { cell ->
                    RichParagraph(
                        inlines = MarkdownParser.parseInlines(cell),
                        onPillClick = onPillClick,
                        onLinkClick = onLinkClick,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier
                            .width(columnWidth)
                            .padding(VideoNotesTokens.Space8),
                    )
                }
            }
            HorizontalDivider()
            table.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 解析器已把行对齐到表头列数，这里只做越界兜底
                    for (i in table.header.indices) {
                        RichParagraph(
                            inlines = MarkdownParser.parseInlines(row.getOrNull(i).orEmpty()),
                            onPillClick = onPillClick,
                            onLinkClick = onLinkClick,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .width(columnWidth)
                                .padding(VideoNotesTokens.Space8),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
