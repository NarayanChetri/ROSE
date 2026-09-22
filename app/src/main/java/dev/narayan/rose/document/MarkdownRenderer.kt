package dev.narayan.rose.document

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import org.commonmark.node.Paragraph as MdParagraph
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import dev.narayan.rose.R
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import java.io.File

object MarkdownParserFactory {
    val parser: Parser by lazy {
        val extensions = listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create()
        )
        Parser.builder().extensions(extensions).build()
    }
}

/**
 * Sanitizes and normalizes loose or user-typed Markdown input before passing to CommonMark.
 * Handles:
 * - Unclosed formatting delimiters (e.g. `**this is a try sentence` -> `**this is a try sentence**`)
 * - Spaces inside delimiters (e.g. `** bold **` -> `**bold**`)
 * - Headings missing space after hash (e.g. `#Heading` -> `# Heading`)
 * - Inline HTML tags (e.g. `<b>`, `<i>`, `<del>`, `<br>`)
 * - Preserves code blocks and thematic breaks untouched.
 */
object MarkdownSanitizer {
    private val headingRegex = Regex("""^(#{1,6})([^\s#].*)$""")
    private val boldSpaceRegex = Regex("""\*\*\s+([^\*\n]+?)\s+\*\*""")
    private val boldLeadingSpaceRegex = Regex("""\*\*\s+([^\*\n]+?)\*\*""")
    private val boldTrailingSpaceRegex = Regex("""\*\*([^\*\n]+?)\s+\*\*""")
    private val strikeSpaceRegex = Regex("""~~\s+([^~\n]+?)\s+~~""")

    private val boldHtmlRegex = Regex("""<(?:b|strong)>(.*?)</(?:b|strong)>""", RegexOption.IGNORE_CASE)
    private val italicHtmlRegex = Regex("""<(?:i|em)>(.*?)</(?:i|em)>""", RegexOption.IGNORE_CASE)
    private val strikeHtmlRegex = Regex("""<(?:s|strike|del)>(.*?)</(?:s|strike|del)>""", RegexOption.IGNORE_CASE)
    private val codeHtmlRegex = Regex("""<code>(.*?)</code>""", RegexOption.IGNORE_CASE)
    private val brHtmlRegex = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    fun sanitize(input: String): String {
        if (input.isEmpty()) return input

        val lines = input.lines()
        val result = StringBuilder()
        var inFencedCodeBlock = false

        for (index in lines.indices) {
            val originalLine = lines[index]
            val trimmed = originalLine.trimStart()

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFencedCodeBlock = !inFencedCodeBlock
                result.append(originalLine)
                if (index < lines.size - 1) result.append("\n")
                continue
            }

            if (inFencedCodeBlock) {
                result.append(originalLine)
                if (index < lines.size - 1) result.append("\n")
                continue
            }

            var line = originalLine

            // 1. Heading without space (e.g. #Heading -> # Heading)
            line = headingRegex.replace(line) { m ->
                "${m.groupValues[1]} ${m.groupValues[2]}"
            }

            // 2. Inline HTML replacements
            line = brHtmlRegex.replace(line, "  \n")
            line = boldHtmlRegex.replace(line) { "**${it.groupValues[1]}**" }
            line = italicHtmlRegex.replace(line) { "*${it.groupValues[1]}*" }
            line = strikeHtmlRegex.replace(line) { "~~${it.groupValues[1]}~~" }
            line = codeHtmlRegex.replace(line) { "`" + it.groupValues[1] + "`" }

            // 3. Remove invalid spaces inside delimiters
            line = boldSpaceRegex.replace(line, "**$1**")
            line = boldLeadingSpaceRegex.replace(line, "**$1**")
            line = boldTrailingSpaceRegex.replace(line, "**$1**")
            line = strikeSpaceRegex.replace(line, "~~$1~~")

            // 4. Auto-close unclosed delimiters on this line
            line = closeUnclosedDelimiters(line)

            result.append(line)
            if (index < lines.size - 1) result.append("\n")
        }

        return result.toString()
    }

    private fun closeUnclosedDelimiters(line: String): String {
        val trimmed = line.trim()
        // Skip horizontal rules
        if (trimmed == "***" || trimmed == "---" || trimmed == "___" || trimmed == "* * *") {
            return line
        }

        var processed = line

        // Check for unclosed backticks
        val backtickCount = processed.count { it == '`' }
        if (backtickCount % 2 != 0) {
            processed = "$processed`"
        }

        // Check for unclosed strikethrough ~~
        val tildeCount = countOccurrences(processed, "~~")
        if (tildeCount % 2 != 0) {
            processed = "$processed~~"
        }

        // Check for unclosed bold **
        val boldCount = countOccurrences(processed, "**")
        if (boldCount % 2 != 0) {
            processed = "$processed**"
        }

        // Check for unclosed italic *
        val placeholder = "\u0000\u0000"
        val withoutBold = processed.replace("**", placeholder)
        val singleAsteriskCount = withoutBold.count { it == '*' }
        val isBulletList = trimmed.startsWith("* ")
        if (!isBulletList && singleAsteriskCount % 2 != 0) {
            processed = "$processed*"
        } else if (isBulletList && singleAsteriskCount > 1 && (singleAsteriskCount - 1) % 2 != 0) {
            processed = "$processed*"
        }

        return processed
    }

    private fun countOccurrences(str: String, target: String): Int {
        var count = 0
        var idx = 0
        while (idx < str.length) {
            val found = str.indexOf(target, idx)
            if (found >= 0) {
                count++
                idx = found + target.length
            } else {
                break
            }
        }
        return count
    }
}

/**
 * Top-level Composable for rendering Markdown documents with full fidelity.
 */
@Composable
fun MarkdownDocument(
    markdown: String,
    modifier: Modifier = Modifier,
    baseDir: File? = null,
    onLinkClick: ((String) -> Unit)? = null
) {
    if (markdown.isBlank()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.doc_empty_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val parsedDocument = remember(markdown) {
        try {
            MarkdownParserFactory.parser.parse(markdown)
            val cleanMarkdown = MarkdownSanitizer.sanitize(markdown)
            MarkdownParserFactory.parser.parse(cleanMarkdown)
        } catch (e: Exception) {
            null
        }
    }

    if (parsedDocument == null) {
        // Fallback for parser failure
        Text(
            text = markdown,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.padding(16.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var node = parsedDocument.firstChild
        while (node != null) {
            MarkdownNodeRenderer(
                node = node,
                baseDir = baseDir,
                onLinkClick = onLinkClick
            )
            node = node.next
        }
    }
}

/**
 * Backwards-compatible Drop-in replacement for naive MarkdownText.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onLinkClick: ((String) -> Unit)? = null
) {
    if (text.isBlank()) return

    val parsedDocument = remember(text) {
        try {
            MarkdownParserFactory.parser.parse(text)
            val cleanText = MarkdownSanitizer.sanitize(text)
            MarkdownParserFactory.parser.parse(cleanText)
        } catch (e: Exception) {
            null
        }
    }

    if (parsedDocument == null) {
        Text(text = text, style = style, color = color, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var node = parsedDocument.firstChild
        while (node != null) {
            MarkdownNodeRenderer(
                node = node,
                baseDir = null,
                onLinkClick = onLinkClick,
                defaultStyle = style,
                defaultColor = color
            )
            node = node.next
        }
    }
}

@Composable
private fun MarkdownNodeRenderer(
    node: Node,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?,
    defaultStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    defaultColor: Color = MaterialTheme.colorScheme.onSurface
) {
    when (node) {
        is Heading -> {
            MarkdownHeading(node, baseDir, onLinkClick)
        }
        is MdParagraph -> {
            MarkdownParagraph(node, baseDir, onLinkClick, defaultStyle, defaultColor)
        }
        is BulletList -> {
            MarkdownBulletList(node, baseDir, onLinkClick, depth = 0)
        }
        is OrderedList -> {
            MarkdownOrderedList(node, baseDir, onLinkClick, depth = 0)
        }
        is FencedCodeBlock -> {
            MarkdownCodeBlock(node.literal, node.info)
        }
        is IndentedCodeBlock -> {
            MarkdownCodeBlock(node.literal, null)
        }
        is BlockQuote -> {
            MarkdownBlockQuote(node, baseDir, onLinkClick)
        }
        is TableBlock -> {
            MarkdownTable(node, baseDir, onLinkClick)
        }
        is ThematicBreak -> {
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
        is HtmlBlock -> {
            // Render HTML blocks as formatted raw text safely
            val plain = node.literal.replace(Regex("<[^>]*>"), "")
            if (plain.isNotBlank()) {
                Text(
                    text = plain.trim(),
                    style = defaultStyle,
                    color = defaultColor
                )
            }
        }
        else -> {
            // For any other block types, render inline text if present
            val annotatedString = buildInlineAnnotatedString(node, MaterialTheme.colorScheme.primary)
            if (annotatedString.isNotBlank()) {
                MarkdownClickableText(annotatedString, defaultStyle, defaultColor, baseDir, onLinkClick)
            }
        }
    }
}

@Composable
private fun MarkdownHeading(
    heading: Heading,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?
) {
    val level = heading.level
    val primaryColor = MaterialTheme.colorScheme.primary
    val annotatedString = buildInlineAnnotatedString(heading, primaryColor)

    val (style, topPadding, bottomPadding) = when (level) {
        1 -> Triple(MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), 14.dp, 6.dp)
        2 -> Triple(MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), 12.dp, 4.dp)
        3 -> Triple(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold), 10.dp, 4.dp)
        4 -> Triple(MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), 8.dp, 2.dp)
        5 -> Triple(MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium), 6.dp, 2.dp)
        else -> Triple(MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), 4.dp, 2.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = bottomPadding)
    ) {
        MarkdownClickableText(
            annotatedString = annotatedString,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            baseDir = baseDir,
            onLinkClick = onLinkClick
        )
        if (level == 1) {
            Spacer(modifier = Modifier.height(4.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                thickness = 1.dp
            )
        }
    }
}

@Composable
private fun MarkdownParagraph(
    paragraph: MdParagraph,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?,
    defaultStyle: TextStyle,
    defaultColor: Color
) {
    // Check if the paragraph contains image nodes
    var containsImages = false
    var child = paragraph.firstChild
    while (child != null) {
        if (child is Image) {
            containsImages = true
            break
        }
        child = child.next
    }

    if (containsImages) {
        // If images are present, render block images and surrounding text separately
        var currentChild = paragraph.firstChild
        while (currentChild != null) {
            if (currentChild is Image) {
                MarkdownImage(currentChild, baseDir)
            } else {
                val inlineStr = buildInlineAnnotatedString(currentChild, MaterialTheme.colorScheme.primary)
                if (inlineStr.isNotBlank()) {
                    MarkdownClickableText(inlineStr, defaultStyle, defaultColor, baseDir, onLinkClick)
                }
            }
            currentChild = currentChild.next
        }
    } else {
        val annotatedString = buildInlineAnnotatedString(paragraph, MaterialTheme.colorScheme.primary)
        MarkdownClickableText(
            annotatedString = annotatedString,
            style = defaultStyle,
            color = defaultColor,
            baseDir = baseDir,
            onLinkClick = onLinkClick
        )
    }
}

@Composable
private fun MarkdownBulletList(
    bulletList: BulletList,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?,
    depth: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var item = bulletList.firstChild
        while (item != null) {
            if (item is ListItem) {
                MarkdownListItem(item, baseDir, onLinkClick, isOrdered = false, index = 0, depth = depth)
            }
            item = item.next
        }
    }
}

@Composable
private fun MarkdownOrderedList(
    orderedList: OrderedList,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?,
    depth: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (depth * 14).dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        var item = orderedList.firstChild
        var index = orderedList.startNumber
        while (item != null) {
            if (item is ListItem) {
                MarkdownListItem(item, baseDir, onLinkClick, isOrdered = true, index = index, depth = depth)
                index++
            }
            item = item.next
        }
    }
}

@Composable
private fun MarkdownListItem(
    listItem: ListItem,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?,
    isOrdered: Boolean,
    index: Int,
    depth: Int
) {
    // Check if task list item [ ] or [x]
    var isTaskChecked: Boolean? = null
    val firstChild = listItem.firstChild
    if (firstChild is MdParagraph && firstChild.firstChild is Text) {
        val textNode = firstChild.firstChild as Text
        val literal = textNode.literal
        if (literal.startsWith("[ ] ")) {
            isTaskChecked = false
            textNode.literal = literal.removePrefix("[ ] ")
        } else if (literal.startsWith("[x] ", ignoreCase = true)) {
            isTaskChecked = true
            textNode.literal = literal.substring(4)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isTaskChecked != null) {
                Icon(
                    imageVector = if (isTaskChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = if (isTaskChecked) "Checked" else "Unchecked",
                    tint = if (isTaskChecked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isOrdered) {
                Text(
                    text = "$index.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                val bullet = when (depth % 3) {
                    0 -> "•"
                    1 -> "◦"
                    else -> "▪"
                }
                Text(
                    text = bullet,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            var child = listItem.firstChild
            while (child != null) {
                when (child) {
                    is BulletList -> MarkdownBulletList(child, baseDir, onLinkClick, depth + 1)
                    is OrderedList -> MarkdownOrderedList(child, baseDir, onLinkClick, depth + 1)
                    else -> MarkdownNodeRenderer(child, baseDir, onLinkClick)
                }
                child = child.next
            }
        }
    }
}

@Composable
private fun MarkdownCodeBlock(code: String, language: String?) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val cleanCode = code.trimEnd()

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Code block header with language and copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language?.takeIf { it.isNotBlank() }?.lowercase() ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(cleanCode))
                        Toast.makeText(context, context.getString(R.string.doc_code_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.doc_copy_code),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = cleanCode,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlockQuote(
    blockQuote: BlockQuote,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var child = blockQuote.firstChild
                while (child != null) {
                    MarkdownNodeRenderer(
                        node = child,
                        baseDir = baseDir,
                        onLinkClick = onLinkClick,
                        defaultStyle = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        defaultColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    child = child.next
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(
    tableBlock: TableBlock,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?
) {
    val tableRows = mutableListOf<Pair<Boolean, List<TableCell>>>() // (isHeader, cells)

    var child = tableBlock.firstChild
    while (child != null) {
        when (child) {
            is TableHead -> {
                var row = child.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = mutableListOf<TableCell>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) cells.add(cell)
                            cell = cell.next
                        }
                        tableRows.add(true to cells)
                    }
                    row = row.next
                }
            }
            is TableBody -> {
                var row = child.firstChild
                while (row != null) {
                    if (row is TableRow) {
                        val cells = mutableListOf<TableCell>()
                        var cell = row.firstChild
                        while (cell != null) {
                            if (cell is TableCell) cells.add(cell)
                            cell = cell.next
                        }
                        tableRows.add(false to cells)
                    }
                    row = row.next
                }
            }
        }
        child = child.next
    }

    if (tableRows.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Column {
                tableRows.forEachIndexed { rowIndex, (isHeader, cells) ->
                    val rowBg = when {
                        isHeader -> MaterialTheme.colorScheme.surfaceContainerHigh
                        rowIndex % 2 == 1 -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.surface
                    }

                    Row(
                        modifier = Modifier
                            .background(rowBg)
                            .padding(vertical = if (isHeader) 8.dp else 6.dp)
                    ) {
                        cells.forEachIndexed { colIndex, cell ->
                            val cellAlignment = when (cell.alignment) {
                                TableCell.Alignment.CENTER -> TextAlign.Center
                                TableCell.Alignment.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            }

                            val annotatedText = buildInlineAnnotatedString(cell, MaterialTheme.colorScheme.primary)

                            Box(
                                modifier = Modifier
                                    .widthIn(min = 90.dp, max = 260.dp)
                                    .padding(horizontal = 10.dp),
                                contentAlignment = when (cell.alignment) {
                                    TableCell.Alignment.CENTER -> Alignment.Center
                                    TableCell.Alignment.RIGHT -> Alignment.CenterEnd
                                    else -> Alignment.CenterStart
                                }
                            ) {
                                MarkdownClickableText(
                                    annotatedString = annotatedText,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                        textAlign = cellAlignment
                                    ),
                                    color = if (isHeader) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                    baseDir = baseDir,
                                    onLinkClick = onLinkClick
                                )
                            }
                        }
                    }

                    if (isHeader) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 1.5.dp
                        )
                    } else if (rowIndex < tableRows.size - 1) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.8.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownImage(image: Image, baseDir: File?) {
    val destination = image.destination
    val context = LocalContext.current

    val model = remember(destination, baseDir) {
        when {
            destination.startsWith("http://") || destination.startsWith("https://") -> destination
            destination.startsWith("content://") || destination.startsWith("file://") -> android.net.Uri.parse(destination)
            baseDir != null -> {
                val localFile = File(baseDir, destination)
                if (localFile.exists()) localFile else destination
            }
            else -> destination
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context)
                .data(model)
                .crossfade(true)
                .build(),
            contentDescription = image.title ?: (image.firstChild as? Text)?.literal ?: "Image",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .heightIn(max = 320.dp),
            loading = {
                Box(
                    modifier = Modifier
                        .size(120.dp, 80.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            },
            error = {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = (image.firstChild as? Text)?.literal?.takeIf { it.isNotBlank() } ?: destination,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        )
    }
}

@Composable
private fun MarkdownClickableText(
    annotatedString: AnnotatedString,
    style: TextStyle,
    color: Color,
    baseDir: File?,
    onLinkClick: ((String) -> Unit)?
) {
    val uriHandler = LocalUriHandler.current
    val mergedStyle = style.copy(color = color)

    ClickableText(
        text = annotatedString,
        style = mergedStyle,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val url = annotation.item
                    if (onLinkClick != null) {
                        onLinkClick(url)
                    } else {
                        // Check if it's a relative link to another local file
                        if (!url.startsWith("http://") && !url.startsWith("https://") && baseDir != null) {
                            val localTarget = File(baseDir, url)
                            if (localTarget.exists()) {
                                onLinkClick?.invoke(localTarget.absolutePath)
                                return@let
                            }
                        }
                        try {
                            uriHandler.openUri(url)
                        } catch (e: Exception) {
                            // Ignored or unsupported scheme
                        }
                    }
                }
        }
    )
}

/**
 * Recursively builds an AnnotatedString from inline CommonMark AST nodes.
 */
private fun buildInlineAnnotatedString(
    parentNode: Node,
    primaryColor: Color
): AnnotatedString {
    return buildAnnotatedString {
        appendNode(parentNode, primaryColor)
    }
}

private fun AnnotatedString.Builder.appendNode(
    node: Node,
    primaryColor: Color
) {
    when (node) {
        is Text -> {
            append(node.literal)
        }
        is StrongEmphasis -> {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                var c = node.firstChild
                while (c != null) {
                    appendNode(c, primaryColor)
                    c = c.next
                }
            }
        }
        is Emphasis -> {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                var c = node.firstChild
                while (c != null) {
                    appendNode(c, primaryColor)
                    c = c.next
                }
            }
        }
        is Strikethrough -> {
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                var c = node.firstChild
                while (c != null) {
                    appendNode(c, primaryColor)
                    c = c.next
                }
            }
        }
        is Code -> {
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = primaryColor.copy(alpha = 0.12f),
                    fontWeight = FontWeight.Medium
                )
            ) {
                append(" ${node.literal} ")
            }
        }
        is HtmlInline -> {
            append(node.literal)
        }
        is Link -> {
            pushStringAnnotation(tag = "URL", annotation = node.destination)
            withStyle(
                SpanStyle(
                    color = primaryColor,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Medium
                )
            ) {
                var c = node.firstChild
                while (c != null) {
                    appendNode(c, primaryColor)
                    c = c.next
                }
            }
            pop()
        }
        is SoftLineBreak -> {
            append(" ")
        }
        is HardLineBreak -> {
            append("\n")
        }
        else -> {
            var child = node.firstChild
            while (child != null) {
                appendNode(child, primaryColor)
                child = child.next
            }
        }
    }
}
