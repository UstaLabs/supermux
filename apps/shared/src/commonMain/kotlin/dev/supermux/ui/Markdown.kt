package dev.supermux.ui

enum class SpanStyleKind { PLAIN, BOLD, ITALIC, CODE }
data class MdSpan(val text: String, val kind: SpanStyleKind)

sealed interface MdBlock {
    data class Prose(val text: String) : MdBlock
    data class Code(val lang: String?, val code: String) : MdBlock
}

/**
 * Split markdown into prose vs fenced ``` code blocks.
 * Handles ```lang\n...\n``` fences; unterminated fence = treat rest as code.
 * Empty prose blocks are omitted.
 */
fun parseMarkdownBlocks(input: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val lines = input.split("\n")
    var inCode = false
    var codeLang: String? = null
    val buf = StringBuilder()

    for (line in lines) {
        if (!inCode && line.trimStart().startsWith("```")) {
            // Flush prose
            val prose = buf.toString()
            if (prose.isNotBlank()) result.add(MdBlock.Prose(prose.trimEnd('\n')))
            buf.clear()
            // Start code block — extract optional language tag
            val fence = line.trimStart()
            val tag = fence.removePrefix("```").trim()
            codeLang = if (tag.isEmpty()) null else tag
            inCode = true
        } else if (inCode && line.trimStart().startsWith("```")) {
            // End of code block
            result.add(MdBlock.Code(codeLang, buf.toString().trimEnd('\n')))
            buf.clear()
            codeLang = null
            inCode = false
        } else {
            if (buf.isNotEmpty()) buf.append('\n')
            buf.append(line)
        }
    }

    // Flush remaining buffer
    if (buf.isNotEmpty()) {
        if (inCode) {
            result.add(MdBlock.Code(codeLang, buf.toString()))
        } else {
            val prose = buf.toString()
            if (prose.isNotBlank()) result.add(MdBlock.Prose(prose))
        }
    }

    return result
}

/**
 * Minimal inline markdown → spans: **bold**, *italic* or _italic_, `code`.
 * Unmatched markers stay literal. Nested is out of scope.
 * Code wins inside backticks; otherwise bold is tried before italic.
 */
fun parseInlineMarkdown(input: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val buf = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (buf.isNotEmpty()) {
            spans.add(MdSpan(buf.toString(), SpanStyleKind.PLAIN))
            buf.clear()
        }
    }

    while (i < input.length) {
        val ch = input[i]

        // Backtick: code span
        if (ch == '`') {
            val end = input.indexOf('`', i + 1)
            if (end != -1) {
                flushPlain()
                spans.add(MdSpan(input.substring(i + 1, end), SpanStyleKind.CODE))
                i = end + 1
                continue
            }
        }

        // Double asterisk: bold
        if (ch == '*' && i + 1 < input.length && input[i + 1] == '*') {
            val end = input.indexOf("**", i + 2)
            if (end != -1) {
                flushPlain()
                spans.add(MdSpan(input.substring(i + 2, end), SpanStyleKind.BOLD))
                i = end + 2
                continue
            }
            // Unclosed ** — emit both asterisks literally and advance past them
            buf.append("**")
            i += 2
            continue
        }

        // Single asterisk: italic
        if (ch == '*') {
            val end = input.indexOf('*', i + 1)
            if (end != -1) {
                flushPlain()
                spans.add(MdSpan(input.substring(i + 1, end), SpanStyleKind.ITALIC))
                i = end + 1
                continue
            }
        }

        // Underscore: italic
        if (ch == '_') {
            val end = input.indexOf('_', i + 1)
            if (end != -1) {
                flushPlain()
                spans.add(MdSpan(input.substring(i + 1, end), SpanStyleKind.ITALIC))
                i = end + 1
                continue
            }
        }

        buf.append(ch)
        i++
    }

    flushPlain()
    return spans
}
