package com.swithun.cmpmermaid.core.pie.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Direct Kotlin translation of Mermaid 12.0.0's pie.langium grammar and value converters.
 */
internal class PieParser(
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<PieDb, MermaidError> {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val headerLineIndex = lines.indexOfFirst { line ->
            stripComment(line).isNotBlank()
        }
        if (headerLineIndex < 0) {
            return parseError(1, 1, "Expected 'pie' diagram header")
        }

        val db = PieDb(diagramTitle)
        val header = when (val result = parseHeader(lines[headerLineIndex], headerLineIndex)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        db.setShowData(header.showData)

        var lineIndex = headerLineIndex
        if (header.statementStart != null) {
            lineIndex = when (
                val result = parseStatement(
                    lines = lines,
                    lineIndex = headerLineIndex,
                    start = header.statementStart,
                    db = db,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        }
        lineIndex += 1

        while (lineIndex < lines.size) {
            val content = stripComment(lines[lineIndex])
            val start = content.indexOfFirst { character -> !character.isHorizontalWhitespace() }
            if (start < 0) {
                lineIndex += 1
                continue
            }
            lineIndex = when (
                val result = parseStatement(
                    lines = lines,
                    lineIndex = lineIndex,
                    start = start,
                    db = db,
                )
            ) {
                is GMResult.Ok -> result.value + 1
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(db)
    }

    private fun parseHeader(
        line: String,
        lineIndex: Int,
    ): GMResult<Header, MermaidError> {
        val content = stripComment(line)
        var cursor = content.indexOfFirst { character -> !character.isHorizontalWhitespace() }
        if (cursor < 0 || !content.hasKeywordAt(cursor, PIE_KEYWORD)) {
            return parseError(lineIndex, cursor.coerceAtLeast(0), "Expected 'pie' diagram header")
        }
        cursor += PIE_KEYWORD.length
        cursor = content.skipHorizontalWhitespace(cursor)

        var showData = false
        if (content.hasKeywordAt(cursor, SHOW_DATA_KEYWORD)) {
            showData = true
            cursor += SHOW_DATA_KEYWORD.length
            cursor = content.skipHorizontalWhitespace(cursor)
        }
        return GMResult.Ok(
            Header(
                showData = showData,
                statementStart = cursor.takeIf { it < content.length },
            ),
        )
    }

    private fun parseStatement(
        lines: List<String>,
        lineIndex: Int,
        start: Int,
        db: PieDb,
    ): GMResult<Int, MermaidError> {
        val content = stripComment(lines[lineIndex])
        return when {
            content.hasKeywordAt(start, TITLE_KEYWORD) ->
                parseTitle(content, lineIndex, start, db)
            content.hasPrefixedTokenAt(
                start,
                ACCESSIBILITY_TITLE_KEYWORD,
                setOf(':'),
            ) ->
                parseAccessibilityTitle(content, lineIndex, start, db)
            content.hasPrefixedTokenAt(
                start,
                ACCESSIBILITY_DESCRIPTION_KEYWORD,
                setOf(':', '{'),
            ) ->
                parseAccessibilityDescription(lines, lineIndex, start, db)
            content[start] == '"' || content[start] == '\'' ->
                parseSection(content, lineIndex, start, db)
            else -> parseError(
                lineIndex,
                start,
                "Expected a pie section, title, accTitle, or accDescr",
            )
        }
    }

    private fun parseTitle(
        content: String,
        lineIndex: Int,
        start: Int,
        db: PieDb,
    ): GMResult<Int, MermaidError> {
        val end = start + TITLE_KEYWORD.length
        val value = content.substring(end).normalizeInlineText()
        db.setDiagramTitle(value)
        return GMResult.Ok(lineIndex)
    }

    private fun parseAccessibilityTitle(
        content: String,
        lineIndex: Int,
        start: Int,
        db: PieDb,
    ): GMResult<Int, MermaidError> {
        val valueStart = content.skipHorizontalWhitespace(
            start + ACCESSIBILITY_TITLE_KEYWORD.length,
        )
        if (content.getOrNull(valueStart) != ':') {
            return parseError(lineIndex, valueStart, "Expected ':' after accTitle")
        }
        db.setAccessibilityTitle(
            content.substring(valueStart + 1).normalizeInlineText(),
        )
        return GMResult.Ok(lineIndex)
    }

    private fun parseAccessibilityDescription(
        lines: List<String>,
        lineIndex: Int,
        start: Int,
        db: PieDb,
    ): GMResult<Int, MermaidError> {
        val line = lines[lineIndex]
        var cursor = line.skipWhitespace(start + ACCESSIBILITY_DESCRIPTION_KEYWORD.length)
        return when (line.getOrNull(cursor)) {
            ':' -> {
                db.setAccessibilityDescription(
                    stripComment(line.substring(cursor + 1)).normalizeInlineText(),
                )
                GMResult.Ok(lineIndex)
            }
            '{' -> {
                cursor += 1
                val body = StringBuilder()
                var currentLine = lineIndex
                var currentCursor = cursor
                while (currentLine < lines.size) {
                    val current = lines[currentLine]
                    val close = current.indexOf('}', startIndex = currentCursor)
                    if (close >= 0) {
                        body.append(current, currentCursor, close)
                        val trailing = stripComment(current.substring(close + 1))
                        if (trailing.isNotBlank()) {
                            return parseError(
                                currentLine,
                                close + 1,
                                "Unexpected content after accDescr block",
                            )
                        }
                        db.setAccessibilityDescription(normalizeMultilineText(body.toString()))
                        return GMResult.Ok(currentLine)
                    }
                    body.append(current.substring(currentCursor))
                    body.append('\n')
                    currentLine += 1
                    currentCursor = 0
                }
                parseError(
                    lineIndex,
                    cursor,
                    "Unterminated accDescr block",
                )
            }
            else -> parseError(
                lineIndex,
                cursor,
                "Expected ':' or '{' after accDescr",
            )
        }
    }

    private fun parseSection(
        content: String,
        lineIndex: Int,
        start: Int,
        db: PieDb,
    ): GMResult<Int, MermaidError> {
        val quote = content[start]
        val label = StringBuilder()
        var cursor = start + 1
        var closed = false
        while (cursor < content.length) {
            val character = content[cursor]
            when {
                character == quote -> {
                    closed = true
                    cursor += 1
                    break
                }
                character == '\\' -> {
                    val escaped = decodeEscape(content, cursor, lineIndex)
                    when (escaped) {
                        is GMResult.Ok -> {
                            label.append(escaped.value.value)
                            cursor = escaped.value.nextIndex
                        }
                        is GMResult.Err -> return escaped
                    }
                }
                else -> {
                    label.append(character)
                    cursor += 1
                }
            }
        }
        if (!closed) {
            return parseError(lineIndex, start, "Unterminated pie section label")
        }
        cursor = content.skipHorizontalWhitespace(cursor)
        if (content.getOrNull(cursor) != ':') {
            return parseError(lineIndex, cursor, "Expected ':' after pie section label")
        }
        cursor = content.skipHorizontalWhitespace(cursor + 1)
        val match = NUMBER.matchAt(content, cursor)
            ?: return parseError(lineIndex, cursor, "Expected a pie section number")
        val value = match.value.toDoubleOrNull()
            ?: return parseError(lineIndex, cursor, "Invalid pie section number '${match.value}'")
        val trailing = content.substring(match.range.last + 1)
        if (trailing.isNotBlank()) {
            return parseError(
                lineIndex,
                match.range.last + 1,
                "Unexpected content after pie section value",
            )
        }
        return when (
            val added = db.addSection(
                label = label.toString(),
                value = value,
                line = sourceLine(lineIndex),
                column = start + 1,
            )
        ) {
            is GMResult.Ok -> GMResult.Ok(lineIndex)
            is GMResult.Err -> added
        }
    }

    private fun decodeEscape(
        source: String,
        slashIndex: Int,
        lineIndex: Int,
    ): GMResult<DecodedEscape, MermaidError> {
        val escaped = source.getOrNull(slashIndex + 1)
            ?: return parseError(lineIndex, slashIndex, "Unterminated escape in pie section label")
        if (escaped == 'u') {
            val end = slashIndex + UNICODE_ESCAPE_LENGTH
            if (end > source.length) {
                return parseError(lineIndex, slashIndex, "Invalid Unicode escape in pie section label")
            }
            val digits = source.substring(slashIndex + 2, end)
            val codePoint = digits.toIntOrNull(16)
                ?: return parseError(
                    lineIndex,
                    slashIndex,
                    "Invalid Unicode escape in pie section label",
                )
            return GMResult.Ok(DecodedEscape(codePoint.toChar(), end))
        }
        val value = when (escaped) {
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            else -> escaped
        }
        return GMResult.Ok(DecodedEscape(value, slashIndex + 2))
    }

    private fun parseError(
        lineIndex: Int,
        columnIndex: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = sourceLine(lineIndex),
            column = columnIndex + 1,
            message = message,
        ),
    )

    private fun sourceLine(lineIndex: Int): Int = lineOffset + lineIndex + 1

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < line.lastIndex) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\' && quote != null) {
                escaped = true
            } else if (character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (quote == null && character == '%' && line[index + 1] == '%') {
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
        return next == null || next.isWhitespace() || next == '%'
    }

    private fun String.hasPrefixedTokenAt(
        index: Int,
        keyword: String,
        delimiters: Set<Char>,
    ): Boolean {
        if (index < 0 || !startsWith(keyword, startIndex = index)) {
            return false
        }
        val next = getOrNull(index + keyword.length)
        return next == null || next.isWhitespace() || next in delimiters
    }

    private fun String.skipHorizontalWhitespace(start: Int): Int {
        var index = start
        while (getOrNull(index).isHorizontalWhitespace()) {
            index += 1
        }
        return index
    }

    private fun String.skipWhitespace(start: Int): Int {
        var index = start
        while (getOrNull(index)?.isWhitespace() == true) {
            index += 1
        }
        return index
    }

    private fun Char?.isHorizontalWhitespace(): Boolean = this == ' ' || this == '\t'

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

    private data class Header(
        val showData: Boolean,
        val statementStart: Int?,
    )

    private data class DecodedEscape(
        val value: Char,
        val nextIndex: Int,
    )

    private companion object {
        const val PIE_KEYWORD = "pie"
        const val SHOW_DATA_KEYWORD = "showData"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        const val UNICODE_ESCAPE_LENGTH = 6
        val NUMBER = Regex("""-?(?:[0-9]+\.[0-9]+|0|[1-9][0-9]*)(?!\.)""")
        val HORIZONTAL_WHITESPACE_RUN = Regex("""[\t ]{2,}""")
        val MULTIPLE_NEWLINES = Regex("""[\n\r]{2,}""")
    }
}
