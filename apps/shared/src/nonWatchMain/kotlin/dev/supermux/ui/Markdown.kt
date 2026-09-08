package dev.supermux.ui

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

enum class SpanStyleKind { PLAIN, BOLD, ITALIC, CODE, LINK, STRIKE }

/**
 * One inline run. A `LINK` span points at *either* a web [url] (opens the browser)
 * or a file [ref] (opens the in-app editor) — never both. Non-link kinds leave both null.
 */
data class MdSpan(
    val text: String,
    val kind: SpanStyleKind,
    val ref: FilePathRef? = null,
    val url: String? = null,
)

/** GFM per-column text alignment, derived from the `:--:` delimiter row. */
enum class ColumnAlign { LEFT, CENTER, RIGHT }

sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
    // Block depth (iOS MarkdownView parity): headings, blockquotes, bullet + numbered list items.
    // ADDITIVE — existing Prose/Code unchanged. Consumers with an exhaustive `when` must add arms.
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** [task] null = plain bullet, false = unchecked `☐`, true = checked `☑` (GFM task list, display-only). */
    data class Bullet(val text: String, val task: Boolean? = null) : MdBlock
    data class Numbered(val n: Int, val text: String) : MdBlock
    /** GFM table. [headers].size is the canonical column count; rows are padded/truncated to it. */
    data class Table(
        val headers: List<String>,
        val aligns: List<ColumnAlign>,
        val rows: List<List<String>>,
    ) : MdBlock
    /** Standalone image (a paragraph that is solely `![alt](url)`). */
    data class Image(val url: String, val alt: String) : MdBlock
}

// One parser instance is cheap; GFM flavour gives tables, strikethrough, task lists and links.
private val gfmFlavour = GFMFlavourDescriptor()
// The no-arg-CancellationToken constructor is deprecated but the replacement isn't needed here.
@Suppress("DEPRECATION")
private fun parseTree(src: CharSequence): ASTNode = MarkdownParser(gfmFlavour).buildMarkdownTreeFromString(src)

/**
 * Split markdown into block-level pieces (iOS MarkdownView.parseMarkdown parity), backed by the
 * `org.jetbrains:markdown` GFM parser. Prose/heading/quote/list-item blocks carry their *raw
 * inline markdown*; the renderer inline-parses it via [parseInlineMarkdown]. Fenced/indented code,
 * GFM tables and standalone images become their own structured blocks.
 */
fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    for (child in parseTree(input).children) collectBlock(child, input, out)
    return out
}

private fun collectBlock(node: ASTNode, src: String, out: MutableList<MdBlock>) {
    when (node.type) {
        MarkdownElementTypes.PARAGRAPH -> {
            val meaningful = node.children.filter { it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL }
            if (meaningful.size == 1 && meaningful[0].type == MarkdownElementTypes.IMAGE) {
                imageBlock(meaningful[0], src)?.let { out.add(it) }
            } else {
                val text = node.getTextInNode(src).toString().trim()
                if (text.isNotBlank()) out.add(MdBlock.Prose(text))
            }
        }
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val level = atxLevel(node.type.name)
            val text = node.findChildOfType(MarkdownTokenTypes.ATX_CONTENT)?.getTextInNode(src)?.toString()?.trim() ?: ""
            out.add(MdBlock.Heading(level, text))
        }
        MarkdownElementTypes.SETEXT_1 -> out.add(MdBlock.Heading(1, setextText(node, src)))
        MarkdownElementTypes.SETEXT_2 -> out.add(MdBlock.Heading(2, setextText(node, src)))
        MarkdownElementTypes.CODE_FENCE -> {
            val lang = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(src)?.toString()?.trim()?.ifEmpty { null }
            val code = node.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
                .joinToString("\n") { it.getTextInNode(src).toString() }
            out.add(MdBlock.Code(lang, code))
        }
        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(src).toString().trimEnd('\n').lines().joinToString("\n") { it.removePrefix("    ") }
            out.add(MdBlock.Code(null, code))
        }
        MarkdownElementTypes.BLOCK_QUOTE -> out.add(MdBlock.Quote(quoteText(node, src)))
        MarkdownElementTypes.UNORDERED_LIST ->
            node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { listItem(it, src, out, ordered = false) }
        MarkdownElementTypes.ORDERED_LIST ->
            node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }.forEach { listItem(it, src, out, ordered = true) }
        GFMElementTypes.TABLE -> tableBlock(node, src)?.let { out.add(it) }
        // HTML blocks, link-reference definitions, horizontal rules, blank lines: nothing to render.
        else -> {}
    }
}

private fun atxLevel(name: String): Int = name.substringAfter("ATX_").toIntOrNull() ?: 1

private fun setextText(node: ASTNode, src: String): String =
    node.findChildOfType(MarkdownTokenTypes.SETEXT_CONTENT)?.getTextInNode(src)?.toString()?.trim()
        ?: node.getTextInNode(src).toString().lines().first().trim()

/** Strip the leading `>` (and one optional space) from each quoted line. */
private fun quoteText(node: ASTNode, src: String): String =
    node.getTextInNode(src).toString().lines().joinToString("\n") { line ->
        val t = line.trimStart()
        if (t.startsWith(">")) t.removePrefix(">").removePrefix(" ") else line
    }.trim()

private val taskPrefix = Regex("""^\[([ xX])]\s+""")

private fun listItem(item: ASTNode, src: String, out: MutableList<MdBlock>, ordered: Boolean) {
    val para = item.findChildOfType(MarkdownElementTypes.PARAGRAPH)
    var text = (para?.getTextInNode(src)?.toString() ?: directInlineText(item, src)).trim()

    // GFM task lists: the `[ ]`/`[x]` is its own CHECK_BOX token (the paragraph text is already clean).
    var task: Boolean? = item.findChildOfType(GFMTokenTypes.CHECK_BOX)?.getTextInNode(src)?.toString()?.contains('x', ignoreCase = true)
    // Fallback for tokenizations that keep the checkbox inside the item text.
    taskPrefix.find(text)?.let { m ->
        if (task == null) task = m.groupValues[1].lowercase() == "x"
        text = text.removeRange(m.range)
    }

    if (ordered) {
        val n = item.findChildOfType(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(src)?.toString()
            ?.trim()?.trimEnd('.', ')')?.toIntOrNull() ?: 1
        out.add(MdBlock.Numbered(n, text))
    } else {
        out.add(MdBlock.Bullet(text, task))
    }

    // Flatten nested lists into sibling bullets (the flat model has no indent level, matching iOS).
    item.children.filter { it.type == MarkdownElementTypes.UNORDERED_LIST || it.type == MarkdownElementTypes.ORDERED_LIST }
        .forEach { collectBlock(it, src, out) }
}

private fun directInlineText(item: ASTNode, src: String): String {
    val skip = setOf(
        MarkdownTokenTypes.LIST_BULLET, MarkdownTokenTypes.LIST_NUMBER, MarkdownTokenTypes.EOL,
        GFMTokenTypes.CHECK_BOX, MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST,
    )
    return item.children.filter { it.type !in skip }.joinToString("") { it.getTextInNode(src).toString() }
}

// An IMAGE nests its `[alt](url)` inside an INLINE_LINK child, so descend one level for dest/alt.
private fun imageBlock(node: ASTNode, src: String): MdBlock.Image? {
    val link = node.findChildOfType(MarkdownElementTypes.INLINE_LINK) ?: node
    val url = linkDestination(link, src) ?: return null
    return MdBlock.Image(url, imageAlt(link, src))
}

private fun imageAlt(link: ASTNode, src: String): String =
    link.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(src)?.toString()?.trim()?.trim('[', ']').orEmpty()

private fun tableBlock(node: ASTNode, src: String): MdBlock.Table? {
    val lines = node.getTextInNode(src).toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.size < 2) return null
    val headers = splitTableRow(lines[0])
    if (headers.isEmpty()) return null
    val aligns = parseAligns(lines[1], headers.size)
    val rows = lines.drop(2).map { padRow(splitTableRow(it), headers.size) }
    return MdBlock.Table(headers, aligns, rows)
}

/** Split a `| a | b |` row into trimmed cells, honoring `\|` escapes and optional edge pipes. */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
    val cells = mutableListOf<String>()
    val sb = StringBuilder()
    var esc = false
    for (c in s) {
        when {
            esc -> { sb.append(c); esc = false }
            c == '\\' -> { sb.append(c); esc = true }
            c == '|' -> { cells.add(sb.toString().trim()); sb.clear() }
            else -> sb.append(c)
        }
    }
    cells.add(sb.toString().trim())
    return cells
}

private fun parseAligns(separator: String, n: Int): List<ColumnAlign> {
    val cells = splitTableRow(separator)
    return (0 until n).map { i ->
        val c = cells.getOrNull(i)?.trim().orEmpty()
        val left = c.startsWith(":")
        val right = c.endsWith(":")
        when {
            left && right -> ColumnAlign.CENTER
            right -> ColumnAlign.RIGHT
            else -> ColumnAlign.LEFT
        }
    }
}

private fun padRow(cells: List<String>, n: Int): List<String> =
    if (cells.size >= n) cells.take(n) else cells + List(n - cells.size) { "" }

/**
 * Minimal inline markdown to spans (GFM): bold, italic, code, strikethrough,
 * `[label](url)` links and images. Backed by the GFM AST; unmatched markers stay literal.
 * File-path detection is layered on top of PLAIN/CODE spans (iOS FilePathLinks parity).
 */
fun parseInlineMarkdown(input: String): List<MdSpan> {
    if (input.isEmpty()) return emptyList()
    val sink = SpanSink()
    parseTree(input).children.forEach { emitInline(it, input, SpanStyleKind.PLAIN, sink) }
    return sink.build().flatMap { splitLinks(it) }
}

private fun emitInline(node: ASTNode, src: String, kind: SpanStyleKind, sink: SpanSink) {
    when (node.type) {
        MarkdownElementTypes.STRONG ->
            node.children.filter { it.type != MarkdownTokenTypes.EMPH }.forEach { emitInline(it, src, promote(kind, SpanStyleKind.BOLD), sink) }
        MarkdownElementTypes.EMPH ->
            node.children.filter { it.type != MarkdownTokenTypes.EMPH }.forEach { emitInline(it, src, promote(kind, SpanStyleKind.ITALIC), sink) }
        GFMElementTypes.STRIKETHROUGH ->
            node.children.filter { it.type != GFMTokenTypes.TILDE }.forEach { emitInline(it, src, promote(kind, SpanStyleKind.STRIKE), sink) }
        MarkdownElementTypes.CODE_SPAN -> sink.append(codeSpanInner(node, src), SpanStyleKind.CODE)
        MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK ->
            emitLink(node, src, sink)
        MarkdownElementTypes.IMAGE -> emitImage(node, src, sink)
        else -> {
            if (node.children.isEmpty()) {
                val t = when (node.type) {
                    MarkdownTokenTypes.EOL -> " "
                    MarkdownTokenTypes.HARD_LINE_BREAK -> "\n"
                    else -> node.getTextInNode(src).toString()
                }
                sink.append(t, kind)
            } else {
                node.children.forEach { emitInline(it, src, kind, sink) }
            }
        }
    }
}

// Flat span model can't nest styles, so the *outermost* formatting wins (bold-inside-italic → italic).
private fun promote(current: SpanStyleKind, wrap: SpanStyleKind): SpanStyleKind =
    if (current == SpanStyleKind.PLAIN) wrap else current

private fun codeSpanInner(node: ASTNode, src: String): String {
    val inner = node.children.filter { it.type != MarkdownTokenTypes.BACKTICK && it.type != MarkdownTokenTypes.ESCAPED_BACKTICKS }
        .joinToString("") { it.getTextInNode(src).toString() }
    // CommonMark: a single leading & trailing space is stripped when the content isn't all-spaces.
    return if (inner.length >= 2 && inner.first() == ' ' && inner.last() == ' ' && inner.isNotBlank())
        inner.substring(1, inner.length - 1) else inner
}

private fun linkDestination(node: ASTNode, src: String): String? =
    node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(src)?.toString()?.trim()?.trim('<', '>')?.ifEmpty { null }

private fun emitLink(node: ASTNode, src: String, sink: SpanSink) {
    val dest = linkDestination(node, src)
    if (dest == null) {
        // Reference link with no in-message definition: render the source literally.
        sink.append(node.getTextInNode(src).toString(), SpanStyleKind.PLAIN)
        return
    }
    var label = node.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(src)?.toString()?.trim().orEmpty()
    if (label.startsWith("[") && label.endsWith("]")) label = label.substring(1, label.length - 1)
    sink.addSpan(MdSpan(label.ifEmpty { dest }, SpanStyleKind.LINK, url = dest))
}

private fun emitImage(node: ASTNode, src: String, sink: SpanSink) {
    val link = node.findChildOfType(MarkdownElementTypes.INLINE_LINK) ?: node
    val dest = linkDestination(link, src)
    if (dest == null) {
        sink.append(node.getTextInNode(src).toString(), SpanStyleKind.PLAIN)
        return
    }
    val alt = imageAlt(link, src)
    sink.addSpan(MdSpan(alt.ifEmpty { dest }, SpanStyleKind.LINK, url = dest))
}

/** Coalesces consecutive same-kind plain runs; link spans are emitted standalone. */
private class SpanSink {
    private val out = mutableListOf<MdSpan>()
    private val buf = StringBuilder()
    private var curKind = SpanStyleKind.PLAIN

    fun append(text: String, kind: SpanStyleKind) {
        if (text.isEmpty()) return
        if (buf.isNotEmpty() && kind != curKind) flush()
        curKind = kind
        buf.append(text)
    }

    fun addSpan(span: MdSpan) {
        flush()
        out.add(span)
    }

    private fun flush() {
        if (buf.isNotEmpty()) {
            out.add(MdSpan(buf.toString(), curKind))
            buf.clear()
        }
    }

    fun build(): List<MdSpan> {
        flush()
        return out
    }
}

/** Split a PLAIN or CODE span into PLAIN/CODE + LINK sub-spans on detected file paths.
 *  Other kinds (BOLD/ITALIC/STRIKE/already-LINK) pass through unchanged. */
private fun splitLinks(span: MdSpan): List<MdSpan> {
    if (span.kind != SpanStyleKind.PLAIN && span.kind != SpanStyleKind.CODE) return listOf(span)
    val matches = findFilePathRefs(span.text)
    if (matches.isEmpty()) return listOf(span)
    val out = mutableListOf<MdSpan>()
    var cursor = 0
    for (m in matches) {
        if (m.start > cursor) out.add(MdSpan(span.text.substring(cursor, m.start), span.kind))
        out.add(MdSpan(span.text.substring(m.start, m.end), SpanStyleKind.LINK, m.ref))
        cursor = m.end
    }
    if (cursor < span.text.length) out.add(MdSpan(span.text.substring(cursor), span.kind))
    return out
}
