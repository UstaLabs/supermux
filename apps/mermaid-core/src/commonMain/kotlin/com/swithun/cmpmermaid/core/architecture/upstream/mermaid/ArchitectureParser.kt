package com.swithun.cmpmermaid.core.architecture.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Mermaid.js 12.0.0:
 * packages/parser/src/language/architecture/architecture.langium,
 * packages/parser/src/language/architecture/arch.langium,
 * packages/parser/src/language/architecture/valueConverter.ts, and
 * packages/mermaid/src/diagrams/architecture/architectureParser.ts -> populateDb.
 */
internal class ArchitectureParser(
    private val options: MermaidRenderOptions,
    private val frontmatterTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<ArchitectureDb, MermaidError> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')
        val parsed = ParsedArchitecture()
        var headerFound = false
        var lineIndex = 0

        while (lineIndex < lines.size) {
            val lineNumber = lineOffset + lineIndex + 1
            val rawLine = stripComment(lines[lineIndex])
            var content = rawLine.trim()
            if (content.isEmpty()) {
                lineIndex += 1
                continue
            }

            if (!headerFound) {
                if (
                    !content.startsWith(HEADER) ||
                    content.getOrNull(HEADER.length).isIdentifierPart()
                ) {
                    return parseError(
                        lineNumber,
                        firstContentColumn(rawLine),
                        "Expected '$HEADER' diagram header",
                    )
                }
                headerFound = true
                content = content.substring(HEADER.length).trimStart()
                if (content.isEmpty()) {
                    lineIndex += 1
                    continue
                }
            }

            when {
                content.hasKeyword("accDescr") -> {
                    val metadata = parseAccessibilityDescription(
                        lines = lines,
                        startLineIndex = lineIndex,
                        firstLineContent = content,
                    )
                    when (metadata) {
                        is GMResult.Ok -> {
                            parsed.accessibilityDescription = metadata.value.value
                            lineIndex = metadata.value.lastLineIndex
                        }
                        is GMResult.Err -> return metadata
                    }
                }
                content.hasKeyword("accTitle") -> {
                    when (
                        val value = parseColonMetadata(
                            content = content,
                            keyword = "accTitle",
                            line = lineNumber,
                        )
                    ) {
                        is GMResult.Ok -> parsed.accessibilityTitle = value.value
                        is GMResult.Err -> return value
                    }
                }
                content.hasKeyword("title") -> {
                    parsed.title = decode(content.substring("title".length).trim())
                }
                content.hasKeyword("group") -> {
                    when (val group = parseGroup(content, lineNumber)) {
                        is GMResult.Ok -> parsed.groups += Located(group.value, lineNumber)
                        is GMResult.Err -> return group
                    }
                }
                content.hasKeyword("service") -> {
                    when (val service = parseService(content, lineNumber)) {
                        is GMResult.Ok -> parsed.services += Located(service.value, lineNumber)
                        is GMResult.Err -> return service
                    }
                }
                content.hasKeyword("junction") -> {
                    when (val junction = parseJunction(content, lineNumber)) {
                        is GMResult.Ok -> parsed.junctions += Located(junction.value, lineNumber)
                        is GMResult.Err -> return junction
                    }
                }
                content.hasKeyword("align") -> {
                    when (val hint = parseLayoutHint(content, lineNumber)) {
                        is GMResult.Ok -> parsed.layoutHints += Located(hint.value, lineNumber)
                        is GMResult.Err -> return hint
                    }
                }
                else -> {
                    when (val edge = parseEdge(content, lineNumber)) {
                        is GMResult.Ok -> parsed.edges += Located(edge.value, lineNumber)
                        is GMResult.Err -> return edge
                    }
                    if (parsed.edges.size > options.maxEdges) {
                        return GMResult.Err(
                            MermaidError.ResourceLimit(
                                resource = "Architecture edges",
                                actual = parsed.edges.size,
                                maximum = options.maxEdges,
                            ),
                        )
                    }
                }
            }
            lineIndex += 1
        }

        if (!headerFound) {
            return parseError(
                lineOffset + 1,
                1,
                "Expected '$HEADER' diagram header",
            )
        }
        return populateDb(parsed)
    }

    private fun populateDb(
        parsed: ParsedArchitecture,
    ): GMResult<ArchitectureDb, MermaidError> {
        val db = ArchitectureDb(
            config = options.architecture,
            frontmatterTitle = frontmatterTitle,
        )
        parsed.accessibilityDescription?.let(db::setAccessibilityDescription)
        parsed.accessibilityTitle?.let(db::setAccessibilityTitle)
        parsed.title?.let(db::setDiagramTitle)

        parsed.groups.forEach { located ->
            when (val result = db.addGroup(located.value, located.line)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        parsed.services.forEach { located ->
            when (val result = db.addService(located.value, located.line)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        parsed.junctions.forEach { located ->
            when (val result = db.addJunction(located.value, located.line)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        parsed.edges.forEach { located ->
            when (val result = db.addEdge(located.value, located.line)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        parsed.layoutHints.forEach { located ->
            when (val result = db.addLayoutHint(located.value, located.line)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(db)
    }

    private fun parseGroup(
        content: String,
        line: Int,
    ): GMResult<ArchitectureGroup, MermaidError> {
        val cursor = LineCursor(content, line)
        cursor.consumeKnownKeyword("group")
        val id = when (val result = cursor.parseId("group id")) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val icon = when (val result = cursor.parseOptionalIcon()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val title = when (val result = cursor.parseOptionalTitle()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val parent = when (val result = cursor.parseOptionalParent()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return cursor.finish(
            ArchitectureGroup(
                id = id,
                icon = icon,
                title = title,
                parent = parent,
            ),
        )
    }

    private fun parseService(
        content: String,
        line: Int,
    ): GMResult<ArchitectureService, MermaidError> {
        val cursor = LineCursor(content, line)
        cursor.consumeKnownKeyword("service")
        val id = when (val result = cursor.parseId("service id")) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val iconOrText = when (val result = cursor.parseOptionalServiceIcon()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val title = when (val result = cursor.parseOptionalTitle()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val parent = when (val result = cursor.parseOptionalParent()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return cursor.finish(
            ArchitectureService(
                id = id,
                icon = iconOrText.icon,
                iconText = iconOrText.text,
                title = title,
                parent = parent,
            ),
        )
    }

    private fun parseJunction(
        content: String,
        line: Int,
    ): GMResult<ArchitectureJunction, MermaidError> {
        val cursor = LineCursor(content, line)
        cursor.consumeKnownKeyword("junction")
        val id = when (val result = cursor.parseId("junction id")) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val parent = when (val result = cursor.parseOptionalParent()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return cursor.finish(ArchitectureJunction(id = id, parent = parent))
    }

    private fun parseLayoutHint(
        content: String,
        line: Int,
    ): GMResult<ArchitectureLayoutHint, MermaidError> {
        val cursor = LineCursor(content, line)
        cursor.consumeKnownKeyword("align")
        val direction = when {
            cursor.consumeKeyword("row") -> ArchitectureAlignmentDirection.Row
            cursor.consumeKeyword("column") -> ArchitectureAlignmentDirection.Column
            else -> return cursor.error("Expected 'row' or 'column' after align")
        }
        val members = mutableListOf<String>()
        while (!cursor.isAtEnd()) {
            when (val member = cursor.parseId("align member")) {
                is GMResult.Ok -> members += member.value
                is GMResult.Err -> return member
            }
        }
        if (members.size < 2) {
            return cursor.error("An align directive requires at least two members")
        }
        return GMResult.Ok(
            ArchitectureLayoutHint(
                direction = direction,
                members = members,
            ),
        )
    }

    private fun parseEdge(
        content: String,
        line: Int,
    ): GMResult<ArchitectureEdge, MermaidError> {
        val cursor = LineCursor(content, line)
        val lhsId = when (val result = cursor.parseId("left-hand edge id")) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val lhsGroup = cursor.consumeGroupModifier()
        if (!cursor.consumeToken(":")) {
            return cursor.error("Expected ':' before left-hand edge direction")
        }
        val lhsDir = when (val result = cursor.parseDirection()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val lhsInto = cursor.consumeToken("<")
        val title = if (cursor.consumeToken("--")) {
            null
        } else {
            if (!cursor.consumeToken("-")) {
                return cursor.error("Expected '--' or '-[title]-' architecture edge")
            }
            val parsedTitle = when (val result = cursor.parseRequiredTitle()) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            if (!cursor.consumeToken("-")) {
                return cursor.error("Expected '-' after architecture edge title")
            }
            parsedTitle
        }
        val rhsInto = cursor.consumeToken(">")
        val rhsDir = when (val result = cursor.parseDirection()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        if (!cursor.consumeToken(":")) {
            return cursor.error("Expected ':' after right-hand edge direction")
        }
        val rhsId = when (val result = cursor.parseId("right-hand edge id")) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val rhsGroup = cursor.consumeGroupModifier()
        return cursor.finish(
            ArchitectureEdge(
                lhsId = lhsId,
                lhsDir = lhsDir,
                lhsInto = lhsInto,
                lhsGroup = lhsGroup,
                rhsId = rhsId,
                rhsDir = rhsDir,
                rhsInto = rhsInto,
                rhsGroup = rhsGroup,
                title = title,
            ),
        )
    }

    private fun parseColonMetadata(
        content: String,
        keyword: String,
        line: Int,
    ): GMResult<String, MermaidError> {
        val remainder = content.substring(keyword.length).trimStart()
        if (!remainder.startsWith(':')) {
            return parseError(line, keyword.length + 1, "Expected ':' after $keyword")
        }
        return GMResult.Ok(decode(remainder.substring(1).trim().normalizeInline()))
    }

    private fun parseAccessibilityDescription(
        lines: List<String>,
        startLineIndex: Int,
        firstLineContent: String,
    ): GMResult<ParsedMultilineMetadata, MermaidError> {
        val lineNumber = lineOffset + startLineIndex + 1
        val remainder = firstLineContent.substring("accDescr".length).trimStart()
        if (remainder.startsWith(':')) {
            return GMResult.Ok(
                ParsedMultilineMetadata(
                    value = decode(remainder.substring(1).trim().normalizeInline()),
                    lastLineIndex = startLineIndex,
                ),
            )
        }
        if (!remainder.startsWith('{')) {
            return parseError(
                lineNumber,
                "accDescr".length + 1,
                "Expected ':' or '{' after accDescr",
            )
        }

        val value = StringBuilder()
        var currentIndex = startLineIndex
        var current = remainder.substring(1)
        while (true) {
            val closing = current.indexOf('}')
            if (closing >= 0) {
                value.append(current.substring(0, closing))
                if (stripComment(current.substring(closing + 1)).isNotBlank()) {
                    return parseError(
                        lineOffset + currentIndex + 1,
                        closing + 2,
                        "Unexpected content after accDescr block",
                    )
                }
                break
            }
            value.append(current)
            currentIndex += 1
            if (currentIndex >= lines.size) {
                return parseError(
                    lineNumber,
                    1,
                    "Unterminated accDescr block",
                )
            }
            value.append('\n')
            current = lines[currentIndex]
        }
        return GMResult.Ok(
            ParsedMultilineMetadata(
                value = decode(normalizeMultiline(value.toString())),
                lastLineIndex = currentIndex,
            ),
        )
    }

    private fun normalizeMultiline(value: String): String {
        val normalizedLines = value.lines().map { line ->
            line.trim().normalizeInline()
        }
        return normalizedLines.joinToString("\n")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    private fun String.normalizeInline(): String =
        replace(Regex("[\\t ]{2,}"), " ")

    private fun decode(value: String): String =
        MermaidPreprocessor.decodeEntities(value)

    private fun stripComment(line: String): String {
        var quote: Char? = null
        var escaped = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            if (escaped) {
                escaped = false
            } else if (character == '\\' && quote != null) {
                escaped = true
            } else if (quote != null && character == quote) {
                quote = null
            } else if (quote == null && (character == '"' || character == '\'')) {
                quote = character
            } else if (
                quote == null &&
                character == '%' &&
                line.getOrNull(index + 1) == '%'
            ) {
                return line.substring(0, index)
            }
            index += 1
        }
        return line
    }

    private fun firstContentColumn(line: String): Int =
        line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 1 else it + 1 }

    private fun parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line,
            column = column,
            message = message,
        ),
    )

    private inner class LineCursor(
        private val source: String,
        private val line: Int,
    ) {
        private var index: Int = 0

        fun isAtEnd(): Boolean {
            skipWhitespace()
            return index >= source.length
        }

        fun consumeKnownKeyword(keyword: String) {
            index += keyword.length
        }

        fun consumeKeyword(keyword: String): Boolean {
            skipWhitespace()
            if (
                !source.startsWith(keyword, index) ||
                source.getOrNull(index + keyword.length).isIdentifierPart()
            ) {
                return false
            }
            index += keyword.length
            return true
        }

        fun parseId(label: String): GMResult<String, MermaidError> {
            skipWhitespace()
            val start = index
            if (!source.getOrNull(index).isIdStart()) {
                return error("Expected $label")
            }
            index += 1
            while (source.getOrNull(index).isIdPart()) {
                index += 1
            }
            val value = source.substring(start, index)
            if (value.endsWith('-') || value in RESERVED_KEYWORDS) {
                return errorAt(start, "Invalid reserved or malformed $label [$value]")
            }
            return GMResult.Ok(value)
        }

        fun parseOptionalIcon(): GMResult<String?, MermaidError> {
            skipWhitespace()
            if (source.getOrNull(index) != '(') {
                return GMResult.Ok(null)
            }
            val start = index
            index += 1
            val valueStart = index
            while (source.getOrNull(index).isIconPart()) {
                index += 1
            }
            if (valueStart == index || source.getOrNull(index) != ')') {
                return errorAt(start, "Invalid architecture icon")
            }
            val value = source.substring(valueStart, index)
            index += 1
            return GMResult.Ok(decode(value.trim()))
        }

        fun parseOptionalServiceIcon(): GMResult<ServiceIcon, MermaidError> {
            skipWhitespace()
            if (source.getOrNull(index) == '"' || source.getOrNull(index) == '\'') {
                return when (val text = parseQuotedString()) {
                    is GMResult.Ok -> GMResult.Ok(ServiceIcon(text = decode(text.value)))
                    is GMResult.Err -> text
                }
            }
            return when (val icon = parseOptionalIcon()) {
                is GMResult.Ok -> GMResult.Ok(ServiceIcon(icon = icon.value))
                is GMResult.Err -> icon
            }
        }

        fun parseOptionalTitle(): GMResult<String?, MermaidError> {
            skipWhitespace()
            if (source.getOrNull(index) != '[') {
                return GMResult.Ok(null)
            }
            return parseRequiredTitle()
        }

        fun parseRequiredTitle(): GMResult<String, MermaidError> {
            skipWhitespace()
            val opening = index
            if (source.getOrNull(index) != '[') {
                return error("Expected architecture title")
            }
            index += 1
            val valueStart = index
            var quote: Char? = null
            var escaped = false
            while (index < source.length) {
                val character = source[index]
                when {
                    escaped -> escaped = false
                    character == '\\' && quote != null -> escaped = true
                    quote != null && character == quote -> quote = null
                    quote == null && (character == '"' || character == '\'') ->
                        quote = character
                    quote == null && character == '[' ->
                        return errorAt(index, "Architecture titles cannot contain '['")
                    quote == null && character == ']' -> {
                        var value = source.substring(valueStart, index).trim()
                        index += 1
                        if (
                            value.length >= 2 &&
                            (
                                value.first() == '"' && value.last() == '"' ||
                                    value.first() == '\'' && value.last() == '\''
                                )
                        ) {
                            value = value.substring(1, value.length - 1)
                                .replace("\\\"", "\"")
                                .replace("\\'", "'")
                        }
                        return GMResult.Ok(decode(value.trim()))
                    }
                }
                index += 1
            }
            return errorAt(opening, "Unterminated architecture title")
        }

        fun parseOptionalParent(): GMResult<String?, MermaidError> {
            if (!consumeKeyword("in")) {
                return GMResult.Ok(null)
            }
            return parseId("parent group id")
        }

        fun parseDirection(): GMResult<ArchitectureDirection, MermaidError> {
            skipWhitespace()
            val direction = when (source.getOrNull(index)) {
                'L' -> ArchitectureDirection.L
                'R' -> ArchitectureDirection.R
                'T' -> ArchitectureDirection.T
                'B' -> ArchitectureDirection.B
                else -> return error("Expected architecture direction L, R, T, or B")
            }
            index += 1
            return GMResult.Ok(direction)
        }

        fun consumeGroupModifier(): Boolean = consumeToken("{group}")

        fun consumeToken(token: String): Boolean {
            skipWhitespace()
            if (!source.startsWith(token, index)) {
                return false
            }
            index += token.length
            return true
        }

        fun <T> finish(value: T): GMResult<T, MermaidError> {
            skipWhitespace()
            if (index != source.length) {
                return error("Unexpected architecture syntax")
            }
            return GMResult.Ok(value)
        }

        fun error(message: String): GMResult.Err<MermaidError> =
            errorAt(index, message)

        private fun parseQuotedString(): GMResult<String, MermaidError> {
            val quote = source.getOrNull(index)
                ?: return error("Expected quoted architecture icon text")
            val opening = index
            index += 1
            val value = StringBuilder()
            while (index < source.length) {
                val character = source[index]
                index += 1
                when {
                    character == quote -> return GMResult.Ok(value.toString())
                    character == '\\' -> {
                        val escaped = source.getOrNull(index)
                            ?: return errorAt(opening, "Unterminated quoted icon text")
                        index += 1
                        value.append(
                            when (escaped) {
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> escaped
                            },
                        )
                    }
                    else -> value.append(character)
                }
            }
            return errorAt(opening, "Unterminated quoted icon text")
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index) == ' ' || source.getOrNull(index) == '\t') {
                index += 1
            }
        }

        private fun errorAt(
            sourceIndex: Int,
            message: String,
        ): GMResult.Err<MermaidError> = parseError(
            line = line,
            column = sourceIndex + 1,
            message = message,
        )
    }

    private data class ParsedArchitecture(
        val groups: MutableList<Located<ArchitectureGroup>> = mutableListOf(),
        val services: MutableList<Located<ArchitectureService>> = mutableListOf(),
        val junctions: MutableList<Located<ArchitectureJunction>> = mutableListOf(),
        val edges: MutableList<Located<ArchitectureEdge>> = mutableListOf(),
        val layoutHints: MutableList<Located<ArchitectureLayoutHint>> = mutableListOf(),
        var title: String? = null,
        var accessibilityTitle: String? = null,
        var accessibilityDescription: String? = null,
    )

    private data class Located<T>(
        val value: T,
        val line: Int,
    )

    private data class ServiceIcon(
        val icon: String? = null,
        val text: String? = null,
    )

    private data class ParsedMultilineMetadata(
        val value: String,
        val lastLineIndex: Int,
    )

    private companion object {
        const val HEADER = "architecture-beta"

        val RESERVED_KEYWORDS = setOf(
            "architecture-beta",
            "group",
            "service",
            "junction",
            "in",
            "align",
            "row",
            "column",
            "title",
            "accTitle",
            "accDescr",
        )
    }
}

private fun String.hasKeyword(keyword: String): Boolean =
    startsWith(keyword) && !getOrNull(keyword.length).isIdentifierPart()

private fun Char?.isIdentifierPart(): Boolean =
    this != null && (isAsciiLetterOrDigit() || this == '_' || this == '-')

private fun Char?.isIdStart(): Boolean =
    this != null && (isAsciiLetterOrDigit() || this == '_')

private fun Char?.isIdPart(): Boolean =
    this != null && (isAsciiLetterOrDigit() || this == '_' || this == '-')

private fun Char?.isIconPart(): Boolean =
    this != null && (isAsciiLetterOrDigit() || this == '_' || this == '-' || this == ':')

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
