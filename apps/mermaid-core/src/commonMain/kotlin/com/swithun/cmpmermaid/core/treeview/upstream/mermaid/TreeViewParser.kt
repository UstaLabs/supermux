package com.swithun.cmpmermaid.core.treeview.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidTreeViewOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/treeView/treeView.langium,
 * packages/parser/src/language/treeView/valueConverter.ts, and
 * packages/mermaid/src/diagrams/treeView/parser.ts.
 */
internal class TreeViewParser(
    private val config: MermaidTreeViewOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<TreeViewDb, MermaidError> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val preprocessed = when (
            val result = TreeViewBoxDrawingPreprocessor.preprocess(normalized, lineOffset)
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val lines = preprocessed.text.split('\n')
        val headerIndex = lines.indexOfFirst { line ->
            val content = line.trim()
            content.isNotEmpty() && !content.startsWith(COMMENT_PREFIX)
        }
        if (headerIndex < 0 || lines[headerIndex].trim() != HEADER) {
            return parseError(
                line = sourceLine(headerIndex.coerceAtLeast(0), preprocessed),
                column = 1,
                message = "Expected '$HEADER' diagram header",
            )
        }

        val db = TreeViewDb(config = config, diagramTitle = diagramTitle)
        var metadataAllowed = true
        var lineIndex = headerIndex + 1
        while (lineIndex < lines.size) {
            val rawLine = lines[lineIndex]
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith(COMMENT_PREFIX)) {
                lineIndex += 1
                continue
            }

            when {
                metadataAllowed && hasKeyword(trimmed, TITLE_KEYWORD) -> {
                    val value = normalizeSingleLineMetadata(
                        stripInlineComment(trimmed.substring(TITLE_KEYWORD.length)),
                    )
                    when (val result = db.setDiagramTitle(value)) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return result
                    }
                }
                metadataAllowed && hasKeyword(trimmed, ACCESSIBILITY_TITLE_KEYWORD) -> {
                    val value = when (
                        val result = parseColonMetadata(
                            content = trimmed,
                            keyword = ACCESSIBILITY_TITLE_KEYWORD,
                            line = sourceLine(lineIndex, preprocessed),
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    when (val result = db.setAccessibilityTitle(value)) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return result
                    }
                }
                metadataAllowed && hasKeyword(trimmed, ACCESSIBILITY_DESCRIPTION_KEYWORD) -> {
                    val parsed = when (
                        val result = parseAccessibilityDescription(
                            lines = lines,
                            lineIndex = lineIndex,
                            preprocessed = preprocessed,
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    when (val result = db.setAccessibilityDescription(parsed.value)) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return result
                    }
                    lineIndex = parsed.lastLineIndex
                }
                else -> {
                    metadataAllowed = false
                    val parsed = when (
                        val result = parseNode(
                            rawLine = rawLine,
                            line = sourceLine(lineIndex, preprocessed),
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    when (
                        val result = db.addNode(
                            level = parsed.level,
                            name = parsed.name,
                            nodeType = parsed.nodeType,
                            cssClass = parsed.cssClass,
                            icon = parsed.icon,
                            description = parsed.description,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return result
                    }
                }
            }
            lineIndex += 1
        }
        return GMResult.Ok(db)
    }

    private fun parseNode(
        rawLine: String,
        line: Int,
    ): GMResult<ParsedNode, MermaidError> {
        val level = rawLine.indexOfFirst { character ->
            character != ' ' && character != '\t'
        }
        if (level < 0) {
            return parseError(line, 1, "Expected a TreeView node")
        }
        val content = rawLine.substring(level)
        var cursor: Int
        val rawName: String
        when (val quote = content.firstOrNull()) {
            '"', '\'' -> {
                val closing = content.indexOf(quote, startIndex = 1)
                if (closing < 0) {
                    return parseError(line, level + 1, "Unterminated quoted TreeView node name")
                }
                rawName = content.substring(1, closing)
                cursor = closing + 1
            }
            else -> {
                if (
                    content.startsWith(CLASS_ANNOTATION_PREFIX) ||
                    content.startsWith(ICON_ANNOTATION_PREFIX) ||
                    content.startsWith(DESCRIPTION_ANNOTATION_PREFIX)
                ) {
                    return parseError(line, level + 1, "Expected a TreeView node name")
                }
                val annotationStart = findAnnotationStart(content)
                cursor = annotationStart ?: content.length
                rawName = content.substring(0, cursor).trimEnd(' ', '\t')
            }
        }

        if (rawName.isEmpty() && content.firstOrNull() !in setOf('"', '\'')) {
            return parseError(line, level + 1, "Expected a TreeView node name")
        }

        var cssClass: String? = null
        var icon: String? = null
        var description: String? = null
        while (cursor < content.length) {
            cursor = skipHorizontalWhitespace(content, cursor)
            if (cursor >= content.length) {
                break
            }
            when {
                content.startsWith(CLASS_ANNOTATION_PREFIX, cursor) -> {
                    val annotationStart = cursor
                    cursor = skipHorizontalWhitespace(
                        content,
                        cursor + CLASS_ANNOTATION_PREFIX.length,
                    )
                    if (content.getOrNull(cursor)?.isClassStart() != true) {
                        return parseError(
                            line,
                            level + annotationStart + 1,
                            "Expected a class name after '$CLASS_ANNOTATION_PREFIX'",
                        )
                    }
                    val classStart = cursor
                    cursor += 1
                    while (content.getOrNull(cursor)?.isClassPart() == true) {
                        cursor += 1
                    }
                    cssClass = content.substring(classStart, cursor)
                }
                content.startsWith(ICON_ANNOTATION_PREFIX, cursor) -> {
                    val annotationStart = cursor
                    val close = content.indexOf(')', cursor + ICON_ANNOTATION_PREFIX.length)
                    if (close < 0) {
                        return parseError(
                            line,
                            level + annotationStart + 1,
                            "Unterminated icon() annotation",
                        )
                    }
                    val value = content.substring(
                        cursor + ICON_ANNOTATION_PREFIX.length,
                        close,
                    )
                    if (!ICON_NAME.matches(value)) {
                        return parseError(
                            line,
                            level + annotationStart + 1,
                            "Invalid icon() annotation",
                        )
                    }
                    icon = value.ifEmpty { NONE_ICON }
                    cursor = close + 1
                }
                content.startsWith(DESCRIPTION_ANNOTATION_PREFIX, cursor) -> {
                    val value = content
                        .substring(cursor + DESCRIPTION_ANNOTATION_PREFIX.length)
                        .trim()
                    description = value.ifEmpty { null }
                    cursor = content.length
                }
                else -> {
                    return parseError(
                        line,
                        level + cursor + 1,
                        "Unexpected content after TreeView node name",
                    )
                }
            }
        }

        val directory = rawName.endsWith('/')
        return GMResult.Ok(
            ParsedNode(
                level = level,
                name = if (directory) rawName.dropLast(1) else rawName,
                nodeType = if (directory) {
                    TreeViewNodeType.Directory
                } else {
                    TreeViewNodeType.File
                },
                cssClass = cssClass,
                icon = icon,
                description = description,
            ),
        )
    }

    private fun parseColonMetadata(
        content: String,
        keyword: String,
        line: Int,
    ): GMResult<String, MermaidError> {
        val separator = skipHorizontalWhitespace(content, keyword.length)
        if (content.getOrNull(separator) != ':') {
            return parseError(line, separator + 1, "Expected ':' after $keyword")
        }
        return GMResult.Ok(
            normalizeSingleLineMetadata(
                stripInlineComment(content.substring(separator + 1)),
            ),
        )
    }

    private fun parseAccessibilityDescription(
        lines: List<String>,
        lineIndex: Int,
        preprocessed: TreeViewPreprocessResult,
    ): GMResult<ParsedMetadata, MermaidError> {
        val content = lines[lineIndex].trim()
        var cursor = skipHorizontalWhitespace(
            content,
            ACCESSIBILITY_DESCRIPTION_KEYWORD.length,
        )
        return when (content.getOrNull(cursor)) {
            ':' -> GMResult.Ok(
                ParsedMetadata(
                    value = normalizeSingleLineMetadata(
                        stripInlineComment(content.substring(cursor + 1)),
                    ),
                    lastLineIndex = lineIndex,
                ),
            )
            '{' -> {
                val firstLine = content.substring(cursor + 1)
                val firstClose = firstLine.indexOf('}')
                if (firstClose >= 0) {
                    if (stripInlineComment(firstLine.substring(firstClose + 1)).isNotBlank()) {
                        return parseError(
                            sourceLine(lineIndex, preprocessed),
                            cursor + firstClose + 3,
                            "Unexpected content after accDescr block",
                        )
                    }
                    return GMResult.Ok(
                        ParsedMetadata(
                            value = normalizeMultilineMetadata(
                                firstLine.substring(0, firstClose),
                            ),
                            lastLineIndex = lineIndex,
                        ),
                    )
                }
                val body = StringBuilder().append(firstLine).append('\n')
                var currentLine = lineIndex
                while (currentLine < lines.lastIndex) {
                    currentLine += 1
                    val current = lines[currentLine]
                    val close = current.indexOf('}')
                    if (close >= 0) {
                        body.append(current.substring(0, close))
                        if (stripInlineComment(current.substring(close + 1)).isNotBlank()) {
                            return parseError(
                                sourceLine(currentLine, preprocessed),
                                close + 2,
                                "Unexpected content after accDescr block",
                            )
                        }
                        return GMResult.Ok(
                            ParsedMetadata(
                                value = normalizeMultilineMetadata(body.toString()),
                                lastLineIndex = currentLine,
                            ),
                        )
                    }
                    body.append(current).append('\n')
                }
                parseError(
                    sourceLine(lineIndex, preprocessed),
                    cursor + 1,
                    "Unterminated accDescr block",
                )
            }
            else -> parseError(
                sourceLine(lineIndex, preprocessed),
                cursor + 1,
                "Expected ':' or '{' after accDescr",
            )
        }
    }

    private fun sourceLine(
        outputLineIndex: Int,
        preprocessed: TreeViewPreprocessResult,
    ): Int = lineOffset + (
        preprocessed.lineMap[outputLineIndex + 1] ?: (outputLineIndex + 1)
        )

    private fun findAnnotationStart(content: String): Int? {
        for (index in 1 until content.length) {
            if (content[index] != ' ' && content[index] != '\t') {
                continue
            }
            val marker = skipHorizontalWhitespace(content, index)
            if (
                content.startsWith(CLASS_ANNOTATION_PREFIX, marker) ||
                content.startsWith(ICON_ANNOTATION_PREFIX, marker) ||
                content.startsWith(DESCRIPTION_ANNOTATION_PREFIX, marker)
            ) {
                return index
            }
        }
        return null
    }

    private fun normalizeSingleLineMetadata(value: String): String =
        value.trim().replace(HORIZONTAL_WHITESPACE_RUN, " ")

    private fun normalizeMultilineMetadata(value: String): String =
        value.lineSequence()
            .map { line -> line.trim().replace(HORIZONTAL_WHITESPACE_RUN, " ") }
            .dropWhile(String::isEmpty)
            .toList()
            .dropLastWhile(String::isEmpty)
            .fold(mutableListOf<String>()) { result, line ->
                if (line.isNotEmpty() || result.lastOrNull()?.isNotEmpty() == true) {
                    result += line
                }
                result
            }
            .joinToString("\n")

    private fun stripInlineComment(value: String): String =
        value.substringBefore(COMMENT_PREFIX)

    private fun hasKeyword(
        content: String,
        keyword: String,
    ): Boolean = content.startsWith(keyword) &&
        content.getOrNull(keyword.length)?.let { character ->
            character == ':' || character == '{' || character.isWhitespace()
        } != false

    private fun skipHorizontalWhitespace(
        value: String,
        start: Int,
    ): Int {
        var cursor = start
        while (value.getOrNull(cursor) == ' ' || value.getOrNull(cursor) == '\t') {
            cursor += 1
        }
        return cursor
    }

    private fun Char.isClassStart(): Boolean =
        this == '_' || this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isClassPart(): Boolean =
        isClassStart() || this == '-' || this in '0'..'9'

    private fun parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line,
            column = column,
            message = "TreeView parse error at $line:$column: $message",
        ),
    )

    private data class ParsedNode(
        val level: Int,
        val name: String,
        val nodeType: TreeViewNodeType,
        val cssClass: String?,
        val icon: String?,
        val description: String?,
    )

    private data class ParsedMetadata(
        val value: String,
        val lastLineIndex: Int,
    )

    private companion object {
        const val HEADER = "treeView-beta"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        const val COMMENT_PREFIX = "%%"
        const val CLASS_ANNOTATION_PREFIX = ":::"
        const val ICON_ANNOTATION_PREFIX = "icon("
        const val DESCRIPTION_ANNOTATION_PREFIX = "##"
        const val NONE_ICON = "none"
        val ICON_NAME = Regex("""^[A-Za-z0-9_-]*(?::[A-Za-z0-9_-]+)?$""")
        val HORIZONTAL_WHITESPACE_RUN = Regex("""[\t ]{2,}""")
    }
}
