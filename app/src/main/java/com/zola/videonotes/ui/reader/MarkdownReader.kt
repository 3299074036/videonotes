package com.zola.videonotes.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
                    Row {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = VideoNotesTokens.Space8),
                        )
                        RichParagraph(
                            inlines = item,
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
                    Row {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = VideoNotesTokens.Space8),
                        )
                        RichParagraph(
                            inlines = item,
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
            Row(
                modifier = modifier.height(IntrinsicSize.Min),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(2.dp),
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight(),
                ) {}
                Spacer(modifier = Modifier.width(VideoNotesTokens.Space12))
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
            TableBlock(table = block, modifier = modifier)
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
    data class Link(val text: String, val url: String) : Segment
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
            is MdInline.Link -> out.add(Segment.Link(inline.text, inline.url))
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
        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                        ),
                        modifier = Modifier.clickable { onLinkClick(seg.url) },
                    )
                }
                is Segment.Pill -> {
                    TimestampPill(
                        seconds = seg.seconds,
                        onClick = { onPillClick(seg.seconds) },
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

/** 表格：列等宽、整体横向滚动。 */
@Composable
private fun TableBlock(table: MdBlock.Table, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val minWidth = maxOf(maxWidth, (table.header.size * 140).dp)
        Column(
            modifier = Modifier
                .width(minWidth)
                .horizontalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                table.header.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(VideoNotesTokens.Space8),
                    )
                }
            }
            HorizontalDivider()
            table.rows.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    // 列数不足时补空，超出时截断（与表头对齐）
                    for (i in table.header.indices) {
                        Text(
                            text = row.getOrNull(i).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .padding(VideoNotesTokens.Space8),
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
