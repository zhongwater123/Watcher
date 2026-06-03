package com.example.watcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.watcher.ui.components.EmptyHint

@Composable
internal fun VideoReportMarkdown(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = parseMarkdownBlocks(markdown)
    if (blocks.isEmpty()) {
        EmptyHint("暂无可展示的报告内容。")
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> MarkdownHeading(block)
                is MarkdownBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 27.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is MarkdownBlock.Bullet -> MarkdownListRow(
                    marker = "•",
                    text = block.text,
                    indent = block.indent
                )
                is MarkdownBlock.Numbered -> MarkdownListRow(
                    marker = "${block.number}.",
                    text = block.text,
                    indent = block.indent
                )
                is MarkdownBlock.Quote -> MarkdownQuote(text = block.text)
                is MarkdownBlock.Table -> MarkdownTable(rows = block.rows)
                MarkdownBlock.StructuredPlaceholder -> StructuredPlaceholder()
            }
        }
    }
}

@Composable
internal fun MarkdownNoteText(markdown: String) {
    VideoReportMarkdown(markdown = markdown)
}

@Composable
private fun MarkdownHeading(block: MarkdownBlock.Heading) {
    Text(
        text = block.text,
        style = when (block.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
        },
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun MarkdownListRow(marker: String, text: String, indent: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 18).dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MarkdownQuote(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(54.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
        )
        Text(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                .padding(14.dp),
            text = text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun MarkdownTable(rows: List<List<String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.take(12).forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { cell ->
                    Text(
                        modifier = Modifier.weight(1f),
                        text = cell,
                        style = if (index == 0) {
                            MaterialTheme.typography.labelLarge
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            if (index == 0 && rows.size > 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable
private fun StructuredPlaceholder() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Text(
            text = "原始结构化内容请在调试详情查看。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String, val indent: Int) : MarkdownBlock
    data class Numbered(val number: Int, val text: String, val indent: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data object StructuredPlaceholder : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val trimmed = markdown.trim()
    if (trimmed.isBlank()) return emptyList()
    if (looksLikeStructuredPayload(trimmed)) return listOf(MarkdownBlock.StructuredPlaceholder)

    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lineSequence().toList()
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trim()
        when {
            line.isBlank() -> index += 1
            looksLikeStructuredPayload(line) -> {
                blocks += MarkdownBlock.StructuredPlaceholder
                index += 1
            }
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length.coerceIn(1, 3)
                val text = line.drop(level).trim()
                if (text.isNotBlank()) blocks += MarkdownBlock.Heading(level, text)
                index += 1
            }
            line.startsWith(">") -> {
                val quoteLines = mutableListOf(line.removePrefix(">").trim())
                index += 1
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoteLines += lines[index].trim().removePrefix(">").trim()
                    index += 1
                }
                blocks += MarkdownBlock.Quote(quoteLines.joinToString("\n"))
            }
            line.isMarkdownBullet() -> {
                blocks += MarkdownBlock.Bullet(
                    text = line.drop(1).trim(),
                    indent = rawIndent(lines[index]).coerceAtMost(3)
                )
                index += 1
            }
            line.matches(numberedRegex) -> {
                val number = line.substringBefore(".").toIntOrNull() ?: 1
                blocks += MarkdownBlock.Numbered(
                    number = number,
                    text = line.substringAfter(".").trim(),
                    indent = rawIndent(lines[index]).coerceAtMost(3)
                )
                index += 1
            }
            isTableLine(line) -> {
                val rows = mutableListOf<List<String>>()
                while (index < lines.size && isTableLine(lines[index].trim())) {
                    val row = lines[index].trim().trim('|')
                        .split('|')
                        .map(String::trim)
                    if (!row.isDividerRow()) rows += row
                    index += 1
                }
                if (rows.isNotEmpty()) blocks += MarkdownBlock.Table(rows)
            }
            else -> {
                val paragraph = mutableListOf(line)
                index += 1
                while (index < lines.size) {
                    val next = lines[index].trim()
                    if (next.isBlank() || isBlockStart(next)) break
                    paragraph += next
                    index += 1
                }
                blocks += MarkdownBlock.Paragraph(paragraph.joinToString("\n"))
            }
        }
    }
    return blocks
}

private val numberedRegex = Regex("""\d+\.\s*.*""")

private fun isBlockStart(line: String): Boolean {
    return line.startsWith("#") ||
        line.startsWith(">") ||
        line.isMarkdownBullet() ||
        line.matches(numberedRegex) ||
        isTableLine(line) ||
        looksLikeStructuredPayload(line)
}

private fun String.isMarkdownBullet(): Boolean {
    val trimmed = trimStart()
    return trimmed.length > 1 &&
        (trimmed[0] == '-' || trimmed[0] == '*') &&
        trimmed.getOrNull(1) != trimmed[0]
}

private fun isTableLine(line: String): Boolean {
    return line.count { it == '|' } >= 2
}

private fun List<String>.isDividerRow(): Boolean {
    return isNotEmpty() && all { cell -> cell.isNotBlank() && cell.all { it == '-' || it == ':' } }
}

private fun rawIndent(line: String): Int {
    return line.takeWhile { it == ' ' || it == '\t' }.sumOf { if (it == '\t') 2 else 1 } / 2
}
