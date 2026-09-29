package com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Deterministic Kotlin translation of Mermaid 12.0.0 gitGraph.langium and
 * gitGraphParser.ts.
 *
 * Upstream gitGraph.langium SHA-256:
 * 7983e2cb45fad0c62a81c6ebb40c942637ea7874cb6c324a99e84feae100b514
 */
internal class GitGraphParser(
    private val mainBranchName: String = "main",
    private val mainBranchOrder: Float = 0f,
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<GitGraphDb, MermaidError> {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val headerLineIndex = lines.indexOfFirst { line -> stripComment(line).isNotBlank() }
        if (headerLineIndex < 0) {
            return parseError(0, 0, "Expected 'gitGraph' diagram header")
        }

        val header = when (val parsed = parseHeader(lines[headerLineIndex], headerLineIndex)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val db = GitGraphDb(
            mainBranchName = mainBranchName,
            mainBranchOrder = mainBranchOrder,
            diagramTitle = diagramTitle,
        )
        db.setDirection(header.direction)

        if (header.statementStart != null) {
            when (
                val parsed = parseStatement(
                    lines = lines,
                    lineIndex = headerLineIndex,
                    start = header.statementStart,
                    db = db,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return parsed
            }
        }

        var lineIndex = headerLineIndex + 1
        while (lineIndex < lines.size) {
            val content = stripComment(lines[lineIndex])
            val start = content.indexOfFirst { character -> !character.isHorizontalWhitespace() }
            if (start < 0) {
                lineIndex += 1
                continue
            }
            lineIndex = when (
                val parsed = parseStatement(
                    lines = lines,
                    lineIndex = lineIndex,
                    start = start,
                    db = db,
                )
            ) {
                is GMResult.Ok -> parsed.value + 1
                is GMResult.Err -> return parsed
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
        if (cursor < 0 || !content.hasKeywordAt(cursor, HEADER)) {
            return parseError(lineIndex, cursor.coerceAtLeast(0), "Expected 'gitGraph' diagram header")
        }
        cursor += HEADER.length
        cursor = content.skipHorizontalWhitespace(cursor)

        var direction = GitGraphDirection.LR
        val directionStart = cursor
        val directionToken = GitGraphDirection.values().firstOrNull { candidate ->
            content.regionMatches(cursor, candidate.name, 0, candidate.name.length) &&
                content.getOrNull(cursor + candidate.name.length).let { next ->
                    next == ':' || next?.isHorizontalWhitespace() == true
                }
        }
        if (directionToken != null) {
            val colonIndex = content.skipHorizontalWhitespace(cursor + directionToken.name.length)
            if (content.getOrNull(colonIndex) != ':') {
                return parseError(
                    lineIndex,
                    colonIndex,
                    "Expected ':' after Git Graph direction '${directionToken.name}'",
                )
            }
            direction = directionToken
            cursor = content.skipHorizontalWhitespace(colonIndex + 1)
        } else {
            cursor = directionStart
            if (content.getOrNull(cursor) == ':') {
                cursor = content.skipHorizontalWhitespace(cursor + 1)
            }
        }

        return GMResult.Ok(
            Header(
                direction = direction,
                statementStart = cursor.takeIf { index -> index < content.length },
            ),
        )
    }

    private fun parseStatement(
        lines: List<String>,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        val content = stripComment(lines[lineIndex])
        return when {
            content.hasKeywordAt(start, COMMIT) -> parseCommit(content, lineIndex, start, db)
            content.hasKeywordAt(start, BRANCH) -> parseBranch(content, lineIndex, start, db)
            content.hasKeywordAt(start, MERGE) -> parseMerge(content, lineIndex, start, db)
            content.hasKeywordAt(start, CHECKOUT) ->
                parseCheckout(content, lineIndex, start, CHECKOUT, db)
            content.hasKeywordAt(start, SWITCH) ->
                parseCheckout(content, lineIndex, start, SWITCH, db)
            content.hasKeywordAt(start, CHERRY_PICK) ->
                parseCherryPick(content, lineIndex, start, db)
            content.hasKeywordAt(start, TITLE) -> {
                db.setDiagramTitle(content.substring(start + TITLE.length).normalizeInlineText())
                GMResult.Ok(lineIndex)
            }
            content.hasKeywordAt(start, ACCESSIBILITY_TITLE) ->
                parseAccessibilityTitle(content, lineIndex, start, db)
            content.hasKeywordAt(start, ACCESSIBILITY_DESCRIPTION) ->
                parseAccessibilityDescription(lines, lineIndex, start, db)
            else -> parseError(
                lineIndex,
                start,
                "Expected commit, branch, merge, checkout, switch, cherry-pick, " +
                    "title, accTitle, or accDescr",
            )
        }
    }

    private fun parseCommit(
        content: String,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        var cursor = content.skipHorizontalWhitespace(start + COMMIT.length)
        var id = ""
        var message = ""
        var type = GitGraphCommitType.Normal
        val tags = mutableListOf<String>()
        var hasTags = false

        while (cursor < content.length) {
            when {
                content.hasAttributeAt(cursor, "id:") -> {
                    val parsed = when (
                        val result = readRequiredString(
                            content,
                            content.skipHorizontalWhitespace(cursor + 3),
                            lineIndex,
                            "commit id",
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    id = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content.hasAttributeAt(cursor, "msg:") -> {
                    val parsed = when (
                        val result = readRequiredString(
                            content,
                            content.skipHorizontalWhitespace(cursor + 4),
                            lineIndex,
                            "commit message",
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    message = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content.hasAttributeAt(cursor, "tag:") -> {
                    val parsed = when (
                        val result = readRequiredString(
                            content,
                            content.skipHorizontalWhitespace(cursor + 4),
                            lineIndex,
                            "commit tag",
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    tags += parsed.value
                    hasTags = true
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content.hasAttributeAt(cursor, "type:") -> {
                    val parsed = when (
                        val result = readCommitType(
                            content,
                            content.skipHorizontalWhitespace(cursor + 5),
                            lineIndex,
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    type = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content[cursor] == '"' || content[cursor] == '\'' -> {
                    val parsed = when (val result = readString(content, cursor, lineIndex)) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    message = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                else -> return parseError(
                    lineIndex,
                    cursor,
                    "Expected a quoted commit message, id:, msg:, tag:, or type:",
                )
            }
        }
        return dbResult(
            result = db.commit(
                GitGraphCommitInput(
                    message = message,
                    id = id,
                    type = type,
                    tags = tags.takeIf { hasTags },
                ),
            ),
            lineIndex = lineIndex,
            column = start,
        )
    }

    private fun parseBranch(
        content: String,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        var cursor = content.skipHorizontalWhitespace(start + BRANCH.length)
        val name = when (val parsed = readReferenceOrString(content, cursor, lineIndex, "branch name")) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        cursor = content.skipHorizontalWhitespace(name.nextIndex)
        var order = 0f
        if (content.hasAttributeAt(cursor, "order:")) {
            cursor = content.skipHorizontalWhitespace(cursor + 6)
            val parsedOrder = readInteger(content, cursor)
                ?: return parseError(lineIndex, cursor, "Expected an integer branch order")
            order = parsedOrder.value.toFloat()
            cursor = content.skipHorizontalWhitespace(parsedOrder.nextIndex)
        }
        if (cursor < content.length) {
            return parseError(lineIndex, cursor, "Unexpected content after branch statement")
        }
        return dbResult(
            result = db.branch(GitGraphBranchInput(name.value, order)),
            lineIndex = lineIndex,
            column = start,
        )
    }

    private fun parseMerge(
        content: String,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        var cursor = content.skipHorizontalWhitespace(start + MERGE.length)
        val branch = when (
            val parsed = readReferenceOrString(content, cursor, lineIndex, "merge branch")
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        cursor = content.skipHorizontalWhitespace(branch.nextIndex)
        var id = ""
        var type: GitGraphCommitType? = null
        val tags = mutableListOf<String>()
        var hasTags = false

        while (cursor < content.length) {
            when {
                content.hasAttributeAt(cursor, "id:") -> {
                    val parsed = when (
                        val result = readRequiredString(
                            content,
                            content.skipHorizontalWhitespace(cursor + 3),
                            lineIndex,
                            "merge id",
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    id = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content.hasAttributeAt(cursor, "tag:") -> {
                    val parsed = when (
                        val result = readRequiredString(
                            content,
                            content.skipHorizontalWhitespace(cursor + 4),
                            lineIndex,
                            "merge tag",
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    tags += parsed.value
                    hasTags = true
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                content.hasAttributeAt(cursor, "type:") -> {
                    val parsed = when (
                        val result = readCommitType(
                            content,
                            content.skipHorizontalWhitespace(cursor + 5),
                            lineIndex,
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    type = parsed.value
                    cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
                }
                else -> return parseError(
                    lineIndex,
                    cursor,
                    "Expected merge id:, tag:, or type:",
                )
            }
        }
        return dbResult(
            result = db.merge(
                GitGraphMergeInput(
                    branch = branch.value,
                    id = id,
                    type = type,
                    tags = tags.takeIf { hasTags },
                ),
            ),
            lineIndex = lineIndex,
            column = start,
        )
    }

    private fun parseCheckout(
        content: String,
        lineIndex: Int,
        start: Int,
        keyword: String,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        val branch = when (
            val parsed = readReferenceOrString(
                source = content,
                start = content.skipHorizontalWhitespace(start + keyword.length),
                lineIndex = lineIndex,
                expected = "branch name",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val trailing = content.skipHorizontalWhitespace(branch.nextIndex)
        if (trailing < content.length) {
            return parseError(lineIndex, trailing, "Unexpected content after $keyword statement")
        }
        return dbResult(
            result = db.checkout(branch.value),
            lineIndex = lineIndex,
            column = start,
        )
    }

    private fun parseCherryPick(
        content: String,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        var cursor = content.skipHorizontalWhitespace(start + CHERRY_PICK.length)
        var id = ""
        var parent = ""
        val tags = mutableListOf<String>()
        var hasTags = false

        while (cursor < content.length) {
            val attribute = when {
                content.hasAttributeAt(cursor, "id:") -> "id:"
                content.hasAttributeAt(cursor, "tag:") -> "tag:"
                content.hasAttributeAt(cursor, "parent:") -> "parent:"
                else -> return parseError(
                    lineIndex,
                    cursor,
                    "Expected cherry-pick id:, tag:, or parent:",
                )
            }
            val parsed = when (
                val result = readRequiredString(
                    content,
                    content.skipHorizontalWhitespace(cursor + attribute.length),
                    lineIndex,
                    "cherry-pick ${attribute.removeSuffix(":")}",
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            when (attribute) {
                "id:" -> id = parsed.value
                "parent:" -> parent = parsed.value
                "tag:" -> {
                    tags += parsed.value
                    hasTags = true
                }
            }
            cursor = content.skipHorizontalWhitespace(parsed.nextIndex)
        }
        return dbResult(
            result = db.cherryPick(
                GitGraphCherryPickInput(
                    id = id,
                    parent = parent,
                    tags = tags.takeIf { hasTags && it.isNotEmpty() },
                ),
            ),
            lineIndex = lineIndex,
            column = start,
        )
    }

    private fun parseAccessibilityTitle(
        content: String,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        val colon = content.skipHorizontalWhitespace(start + ACCESSIBILITY_TITLE.length)
        if (content.getOrNull(colon) != ':') {
            return parseError(lineIndex, colon, "Expected ':' after accTitle")
        }
        db.setAccessibilityTitle(content.substring(colon + 1).normalizeInlineText())
        return GMResult.Ok(lineIndex)
    }

    private fun parseAccessibilityDescription(
        lines: List<String>,
        lineIndex: Int,
        start: Int,
        db: GitGraphDb,
    ): GMResult<Int, MermaidError> {
        val line = lines[lineIndex]
        var cursor = line.skipHorizontalWhitespace(start + ACCESSIBILITY_DESCRIPTION.length)
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
                        if (stripComment(current.substring(close + 1)).isNotBlank()) {
                            return parseError(
                                currentLine,
                                close + 1,
                                "Unexpected content after accDescr block",
                            )
                        }
                        db.setAccessibilityDescription(body.toString().normalizeMultilineText())
                        return GMResult.Ok(currentLine)
                    }
                    body.append(current.substring(currentCursor))
                    body.append('\n')
                    currentLine += 1
                    currentCursor = 0
                }
                parseError(lineIndex, cursor, "Unterminated accDescr block")
            }
            else -> parseError(lineIndex, cursor, "Expected ':' or '{' after accDescr")
        }
    }

    private fun readCommitType(
        source: String,
        start: Int,
        lineIndex: Int,
    ): GMResult<ParsedValue<GitGraphCommitType>, MermaidError> {
        val token = readBareToken(source, start)
            ?: return parseError(lineIndex, start, "Expected NORMAL, REVERSE, or HIGHLIGHT")
        val type = when (token.value) {
            "NORMAL" -> GitGraphCommitType.Normal
            "REVERSE" -> GitGraphCommitType.Reverse
            "HIGHLIGHT" -> GitGraphCommitType.Highlight
            else -> return parseError(
                lineIndex,
                start,
                "Expected NORMAL, REVERSE, or HIGHLIGHT",
            )
        }
        return GMResult.Ok(ParsedValue(type, token.nextIndex))
    }

    private fun readReferenceOrString(
        source: String,
        start: Int,
        lineIndex: Int,
        expected: String,
    ): GMResult<ParsedValue<String>, MermaidError> {
        if (source.getOrNull(start) == '"' || source.getOrNull(start) == '\'') {
            return readString(source, start, lineIndex)
        }
        val token = readReference(source, start)
            ?: return parseError(lineIndex, start, "Expected $expected")
        return GMResult.Ok(token)
    }

    private fun readRequiredString(
        source: String,
        start: Int,
        lineIndex: Int,
        expected: String,
    ): GMResult<ParsedValue<String>, MermaidError> {
        if (source.getOrNull(start) != '"' && source.getOrNull(start) != '\'') {
            return parseError(lineIndex, start, "Expected quoted $expected")
        }
        return readString(source, start, lineIndex)
    }

    private fun readString(
        source: String,
        start: Int,
        lineIndex: Int,
    ): GMResult<ParsedValue<String>, MermaidError> {
        val quote = source.getOrNull(start)
            ?: return parseError(lineIndex, start, "Expected quoted string")
        val value = StringBuilder()
        var cursor = start + 1
        while (cursor < source.length) {
            val character = source[cursor]
            when {
                character == quote -> {
                    return GMResult.Ok(ParsedValue(value.toString(), cursor + 1))
                }
                character != '\\' -> {
                    value.append(character)
                    cursor += 1
                }
                cursor + 1 >= source.length ->
                    return parseError(lineIndex, cursor, "Unterminated escape sequence")
                source[cursor + 1] == 'u' -> {
                    val end = cursor + 6
                    if (end > source.length) {
                        return parseError(lineIndex, cursor, "Incomplete Unicode escape")
                    }
                    val code = source.substring(cursor + 2, end).toIntOrNull(16)
                        ?: return parseError(lineIndex, cursor, "Invalid Unicode escape")
                    value.append(code.toChar())
                    cursor = end
                }
                else -> {
                    value.append(
                        when (val escaped = source[cursor + 1]) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            else -> escaped
                        },
                    )
                    cursor += 2
                }
            }
        }
        return parseError(lineIndex, start, "Unterminated quoted string")
    }

    private fun readReference(
        source: String,
        start: Int,
    ): ParsedValue<String>? {
        val first = source.getOrNull(start) ?: return null
        if (!first.isAsciiWord()) return null
        var cursor = start + 1
        while (cursor < source.length && source[cursor].isReferenceCharacter()) {
            cursor += 1
        }
        while (
            cursor > start + 1 &&
            (source[cursor - 1] == '.' || source[cursor - 1] == '/')
        ) {
            cursor -= 1
        }
        return ParsedValue(source.substring(start, cursor), cursor)
    }

    private fun readBareToken(
        source: String,
        start: Int,
    ): ParsedValue<String>? {
        if (start >= source.length || source[start].isHorizontalWhitespace()) return null
        var cursor = start
        while (cursor < source.length && !source[cursor].isHorizontalWhitespace()) {
            cursor += 1
        }
        return ParsedValue(source.substring(start, cursor), cursor)
    }

    private fun readInteger(
        source: String,
        start: Int,
    ): ParsedValue<Int>? {
        var cursor = start
        while (cursor < source.length && source[cursor].isDigit()) {
            cursor += 1
        }
        if (cursor == start) return null
        val text = source.substring(start, cursor)
        if (text.length > 1 && text[0] == '0') return null
        return text.toIntOrNull()?.let { value -> ParsedValue(value, cursor) }
    }

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < line.length - 1) {
            val character = line[index]
            when {
                escaped -> escaped = false
                character == '\\' && quote != null -> escaped = true
                quote != null && character == quote -> quote = null
                quote == null && (character == '"' || character == '\'') -> quote = character
                quote == null && character == '%' && line[index + 1] == '%' ->
                    return line.substring(0, index)
            }
            index += 1
        }
        return line
    }

    private fun dbResult(
        result: GMResult<Unit, MermaidError>,
        lineIndex: Int,
        column: Int,
    ): GMResult<Int, MermaidError> = when (result) {
        is GMResult.Ok -> GMResult.Ok(lineIndex)
        is GMResult.Err -> {
            val error = result.error
            if (error is MermaidError.Parse) {
                GMResult.Err(
                    error.copy(
                        line = lineIndex + lineOffset + 1,
                        column = column + 1,
                    ),
                )
            } else {
                GMResult.Err(error)
            }
        }
    }

    private fun <T> parseError(
        lineIndex: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = lineIndex + lineOffset + 1,
            column = column + 1,
            message = message,
        ),
    )

    private fun String.hasKeywordAt(
        start: Int,
        keyword: String,
    ): Boolean {
        if (!regionMatches(start, keyword, 0, keyword.length)) return false
        val next = getOrNull(start + keyword.length)
        return next == null || next.isHorizontalWhitespace() || next == ':'
    }

    private fun String.hasAttributeAt(
        start: Int,
        attribute: String,
    ): Boolean = regionMatches(start, attribute, 0, attribute.length)

    private fun String.skipHorizontalWhitespace(start: Int): Int {
        var index = start
        while (index < length && this[index].isHorizontalWhitespace()) {
            index += 1
        }
        return index
    }

    private fun String.normalizeInlineText(): String =
        trim().replace(Regex("[\\t ]{2,}"), " ")

    private fun String.normalizeMultilineText(): String = lineSequence()
        .joinToString("\n") { line ->
            line.trim().replace(Regex("[\\t ]{2,}"), " ")
        }
        .trim('\n', '\r')
        .replace(Regex("[\\n\\r]{2,}"), "\n")

    private fun Char.isHorizontalWhitespace(): Boolean = this == ' ' || this == '\t'

    private fun Char.isAsciiWord(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || isDigit() || this == '_'

    private fun Char.isReferenceCharacter(): Boolean =
        isAsciiWord() || this == '-' || this == '.' || this == '/'

    private data class Header(
        val direction: GitGraphDirection,
        val statementStart: Int?,
    )

    private data class ParsedValue<T>(
        val value: T,
        val nextIndex: Int,
    )

    private companion object {
        const val HEADER = "gitGraph"
        const val COMMIT = "commit"
        const val BRANCH = "branch"
        const val MERGE = "merge"
        const val CHECKOUT = "checkout"
        const val SWITCH = "switch"
        const val CHERRY_PICK = "cherry-pick"
        const val TITLE = "title"
        const val ACCESSIBILITY_TITLE = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION = "accDescr"
    }
}
