package com.swithun.cmpmermaid.core.packet.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPacketOptions

/**
 * Kotlin translation of Mermaid 12.0.0 parser's packet.langium grammar,
 * common value converter, and packet/parser.ts -> populate.
 */
internal class PacketParser(
    private val config: MermaidPacketOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<PacketDb, MermaidError> {
        if (config.bitsPerRow < 1) {
            return GMResult.Err(
                MermaidError.Configuration("Mermaid Packet bitsPerRow must be at least 1"),
            )
        }
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val headerIndex = lines.indexOfFirst { line -> stripComment(line).isNotBlank() }
        if (headerIndex < 0) {
            return parseError(1, 1, "Expected 'packet' or 'packet-beta' diagram header")
        }
        val header = stripComment(lines[headerIndex]).trimStart()
        val keyword = when {
            header.hasKeywordAt(0, PACKET_BETA_KEYWORD) -> PACKET_BETA_KEYWORD
            header.hasKeywordAt(0, PACKET_KEYWORD) -> PACKET_KEYWORD
            else -> return parseError(
                headerIndex + 1,
                1,
                "Expected 'packet' or 'packet-beta' diagram header",
            )
        }
        val db = PacketDb(config = config, diagramTitle = diagramTitle)
        val blocks = mutableListOf<PacketBlock>()
        val headerRemainder = header.substring(keyword.length)
        if (headerRemainder.isNotBlank()) {
            when (
                val parsed = parseLine(
                    rawLine = headerRemainder,
                    lineIndex = headerIndex,
                    db = db,
                    blocks = blocks,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return parsed
            }
        }

        var lineIndex = headerIndex + 1
        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val uncommented = stripComment(rawLine)
            val start = uncommented.indexOfFirst { character ->
                character != ' ' && character != '\t'
            }
            if (start < 0) {
                lineIndex += 1
                continue
            }
            if (
                uncommented.hasTokenAt(
                    start,
                    ACCESSIBILITY_DESCRIPTION_KEYWORD,
                    setOf(':', '{'),
                )
            ) {
                val afterKeyword = uncommented.skipHorizontalWhitespace(
                    start + ACCESSIBILITY_DESCRIPTION_KEYWORD.length,
                )
                if (uncommented.getOrNull(afterKeyword) == '{') {
                    val result = parseAccessibilityDescriptionBlock(
                        lines = lines,
                        lineIndex = lineIndex,
                        openingIndex = afterKeyword,
                        db = db,
                    )
                    when (result) {
                        is GMResult.Ok -> {
                            lineIndex = result.value + 1
                            continue
                        }
                        is GMResult.Err -> return result
                    }
                }
            }
            when (
                val parsed = parseLine(
                    rawLine = uncommented,
                    lineIndex = lineIndex,
                    db = db,
                    blocks = blocks,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return parsed
            }
            lineIndex += 1
        }

        return when (val populated = db.populate(blocks)) {
            is GMResult.Ok -> GMResult.Ok(db)
            is GMResult.Err -> populated
        }
    }

    private fun parseLine(
        rawLine: String,
        lineIndex: Int,
        db: PacketDb,
        blocks: MutableList<PacketBlock>,
    ): GMResult<Unit, MermaidError> {
        val content = stripComment(rawLine)
        val start = content.indexOfFirst { character ->
            character != ' ' && character != '\t'
        }
        if (start < 0) {
            return GMResult.Ok(Unit)
        }
        return when {
            content.hasKeywordAt(start, TITLE_KEYWORD) ->
                parseTitle(content, start, db)
            content.hasTokenAt(start, ACCESSIBILITY_TITLE_KEYWORD, setOf(':')) ->
                parseAccessibilityTitle(content, lineIndex, start, db)
            content.hasTokenAt(
                start,
                ACCESSIBILITY_DESCRIPTION_KEYWORD,
                setOf(':', '{'),
            ) ->
                parseAccessibilityDescription(content, lineIndex, start, db)
            else -> when (val block = parseBlock(content, lineIndex, start)) {
                is GMResult.Ok -> {
                    blocks += block.value
                    GMResult.Ok(Unit)
                }
                is GMResult.Err -> block
            }
        }
    }

    private fun parseTitle(
        content: String,
        start: Int,
        db: PacketDb,
    ): GMResult<Unit, MermaidError> {
        db.setDiagramTitle(content.substring(start + TITLE_KEYWORD.length).normalizeInlineText())
        return GMResult.Ok(Unit)
    }

    private fun parseAccessibilityTitle(
        content: String,
        lineIndex: Int,
        start: Int,
        db: PacketDb,
    ): GMResult<Unit, MermaidError> {
        val separator = content.skipHorizontalWhitespace(
            start + ACCESSIBILITY_TITLE_KEYWORD.length,
        )
        if (content.getOrNull(separator) != ':') {
            return parseError(lineIndex + 1, separator + 1, "Expected ':' after accTitle")
        }
        db.setAccessibilityTitle(content.substring(separator + 1).normalizeInlineText())
        return GMResult.Ok(Unit)
    }

    private fun parseAccessibilityDescription(
        content: String,
        lineIndex: Int,
        start: Int,
        db: PacketDb,
    ): GMResult<Unit, MermaidError> {
        val separator = content.skipHorizontalWhitespace(
            start + ACCESSIBILITY_DESCRIPTION_KEYWORD.length,
        )
        if (content.getOrNull(separator) != ':') {
            return parseError(
                lineIndex + 1,
                separator + 1,
                "Expected ':' or '{' after accDescr",
            )
        }
        db.setAccessibilityDescription(content.substring(separator + 1).normalizeInlineText())
        return GMResult.Ok(Unit)
    }

    private fun parseAccessibilityDescriptionBlock(
        lines: List<String>,
        lineIndex: Int,
        openingIndex: Int,
        db: PacketDb,
    ): GMResult<Int, MermaidError> {
        val body = StringBuilder()
        var currentLine = lineIndex
        var cursor = openingIndex + 1
        while (currentLine < lines.size) {
            val current = lines[currentLine]
            val close = current.indexOf('}', startIndex = cursor)
            if (close >= 0) {
                body.append(current, cursor, close)
                if (stripComment(current.substring(close + 1)).isNotBlank()) {
                    return parseError(
                        currentLine + 1,
                        close + 2,
                        "Unexpected content after accDescr block",
                    )
                }
                db.setAccessibilityDescription(normalizeMultilineText(body.toString()))
                return GMResult.Ok(currentLine)
            }
            body.append(current.substring(cursor))
            body.append('\n')
            currentLine += 1
            cursor = 0
        }
        return parseError(
            lineIndex + 1,
            openingIndex + 1,
            "Unterminated accDescr block",
        )
    }

    private fun parseBlock(
        content: String,
        lineIndex: Int,
        start: Int,
    ): GMResult<PacketBlock, MermaidError> {
        var cursor = start
        var explicitStart: Long? = null
        var explicitEnd: Long? = null
        var bitCount: Long? = null
        if (content.getOrNull(cursor) == '+') {
            cursor = content.skipHorizontalWhitespace(cursor + 1)
            val parsed = parseInteger(content, cursor, lineIndex)
            when (parsed) {
                is GMResult.Ok -> {
                    bitCount = parsed.value.value
                    cursor = parsed.value.nextIndex
                }
                is GMResult.Err -> return parsed
            }
        } else {
            val parsed = parseInteger(content, cursor, lineIndex)
            when (parsed) {
                is GMResult.Ok -> {
                    explicitStart = parsed.value.value
                    cursor = parsed.value.nextIndex
                }
                is GMResult.Err -> return parsed
            }
            cursor = content.skipHorizontalWhitespace(cursor)
            if (content.getOrNull(cursor) == '-') {
                cursor = content.skipHorizontalWhitespace(cursor + 1)
                val end = parseInteger(content, cursor, lineIndex)
                when (end) {
                    is GMResult.Ok -> {
                        explicitEnd = end.value.value
                        cursor = end.value.nextIndex
                    }
                    is GMResult.Err -> return end
                }
            }
        }
        cursor = content.skipHorizontalWhitespace(cursor)
        if (content.getOrNull(cursor) != ':') {
            return parseError(lineIndex + 1, cursor + 1, "Expected ':' after packet bit range")
        }
        cursor = content.skipHorizontalWhitespace(cursor + 1)
        val label = when (val parsed = parseString(content, cursor, lineIndex)) {
            is GMResult.Ok -> {
                cursor = parsed.value.nextIndex
                parsed.value.value
            }
            is GMResult.Err -> return parsed
        }
        cursor = content.skipHorizontalWhitespace(cursor)
        if (cursor != content.length) {
            return parseError(
                lineIndex + 1,
                cursor + 1,
                "Unexpected content after packet block",
            )
        }
        return GMResult.Ok(
            PacketBlock(
                start = explicitStart,
                end = explicitEnd,
                bits = bitCount,
                label = label,
                line = sourceLine(lineIndex),
                column = start + 1,
            ),
        )
    }

    private fun parseInteger(
        content: String,
        start: Int,
        lineIndex: Int,
    ): GMResult<ParsedLong, MermaidError> {
        val match = INTEGER.matchAt(content, start)
            ?: return parseError(lineIndex + 1, start + 1, "Expected a packet bit number")
        val value = match.value.toLongOrNull()
            ?: return parseError(lineIndex + 1, start + 1, "Packet bit number is too large")
        return GMResult.Ok(ParsedLong(value, match.range.last + 1))
    }

    private fun parseString(
        content: String,
        start: Int,
        lineIndex: Int,
    ): GMResult<ParsedString, MermaidError> {
        val quote = content.getOrNull(start)
        if (quote != '"' && quote != '\'') {
            return parseError(lineIndex + 1, start + 1, "Expected a quoted packet label")
        }
        val value = StringBuilder()
        var cursor = start + 1
        while (cursor < content.length) {
            val character = content[cursor]
            if (character == quote) {
                return GMResult.Ok(ParsedString(value.toString(), cursor + 1))
            }
            if (character == '\\') {
                val escaped = content.getOrNull(cursor + 1)
                    ?: return parseError(
                        lineIndex + 1,
                        cursor + 1,
                        "Unterminated escape in packet label",
                    )
                value.append(decodeEscape(escaped))
                cursor += 2
            } else {
                value.append(character)
                cursor += 1
            }
        }
        return parseError(lineIndex + 1, start + 1, "Unterminated packet label")
    }

    private fun decodeEscape(character: Char): Char = when (character) {
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'v' -> '\u000B'
        '0' -> '\u0000'
        else -> character
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < line.lastIndex) {
            val character = line[index]
            when {
                escaped -> escaped = false
                character == '\\' && quote != null -> escaped = true
                character == quote -> quote = null
                quote == null && (character == '"' || character == '\'') -> quote = character
                quote == null && character == '%' && line[index + 1] == '%' ->
                    return line.substring(0, index)
            }
            index += 1
        }
        return line
    }

    private fun String.hasKeywordAt(
        index: Int,
        keyword: String,
    ): Boolean {
        if (index < 0 || !startsWith(keyword, startIndex = index)) {
            return false
        }
        val next = getOrNull(index + keyword.length)
        return next == null || next == ' ' || next == '\t'
    }

    private fun String.hasTokenAt(
        index: Int,
        keyword: String,
        delimiters: Set<Char>,
    ): Boolean {
        if (index < 0 || !startsWith(keyword, startIndex = index)) {
            return false
        }
        val next = getOrNull(index + keyword.length)
        return next == null || next == ' ' || next == '\t' || next in delimiters
    }

    private fun String.skipHorizontalWhitespace(start: Int): Int {
        var index = start
        while (getOrNull(index) == ' ' || getOrNull(index) == '\t') {
            index += 1
        }
        return index
    }

    private fun String.normalizeInlineText(): String =
        trim().replace(HORIZONTAL_WHITESPACE_RUN, " ")

    private fun normalizeMultilineText(value: String): String =
        value
            .lineSequence()
            .map { line -> line.trim().replace(HORIZONTAL_WHITESPACE_RUN, " ") }
            .dropWhile(String::isEmpty)
            .toList()
            .dropLastWhile(String::isEmpty)
            .joinToString("\n")
            .replace(MULTIPLE_NEWLINES, "\n")

    private fun <T> parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line + lineOffset,
            column = column,
            message = message,
        ),
    )

    private fun sourceLine(lineIndex: Int): Int = lineOffset + lineIndex + 1

    private data class ParsedLong(
        val value: Long,
        val nextIndex: Int,
    )

    private data class ParsedString(
        val value: String,
        val nextIndex: Int,
    )

    private companion object {
        const val PACKET_KEYWORD = "packet"
        const val PACKET_BETA_KEYWORD = "packet-beta"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        val INTEGER = Regex("""(?:0|[1-9][0-9]*)(?![0-9.])""")
        val HORIZONTAL_WHITESPACE_RUN = Regex("""[\t ]{2,}""")
        val MULTIPLE_NEWLINES = Regex("""[\n\r]{2,}""")
    }
}
