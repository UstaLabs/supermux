package com.swithun.cmpmermaid.core.kanban

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class KanbanNode(
    val id: String,
    val label: String,
    val level: Int,
    val parentId: String? = null,
    val ticket: String? = null,
    val priority: String? = null,
    val assigned: String? = null,
    val icon: String? = null,
    val cssClasses: String? = null,
)

internal data class KanbanSection(
    val node: KanbanNode,
    val items: List<KanbanNode>,
)

internal data class KanbanDocument(
    val sections: List<KanbanSection>,
)

/**
 * Kotlin translation of Mermaid 12.0.0 kanban/parser/kanban.jison and kanbanDb.ts.
 */
internal class KanbanParser(
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<KanbanDocument, MermaidError> {
        val lines = source.lines()
        val headerIndex = lines.indexOfFirst { line ->
            val text = line.trim()
            text.isNotEmpty() && !text.startsWith(COMMENT_PREFIX)
        }
        if (headerIndex < 0 || !lines[headerIndex].trim().equals("kanban", ignoreCase = true)) {
            return parseError(
                line = if (headerIndex < 0) 1 else headerIndex + 1,
                column = 1,
                message = "Expected kanban",
            )
        }

        val db = KanbanDb(::parseError)
        var lineIndex = headerIndex + 1
        while (lineIndex < lines.size) {
            val sourceLine = lines[lineIndex]
            val lineNumber = lineIndex + 1
            val uncommented = stripComment(sourceLine)
            if (uncommented.isBlank()) {
                lineIndex++
                continue
            }
            val indentation = uncommented.takeWhile(Char::isWhitespace).length
            val statement = uncommented.drop(indentation).trimEnd()

            when {
                statement.startsWith(ICON_PREFIX) -> {
                    val closing = statement.indexOf(')', ICON_PREFIX.length)
                    if (closing < 0 || statement.substring(closing + 1).isNotBlank()) {
                        return parseError(lineNumber, indentation + 1, "Invalid Kanban icon")
                    }
                    when (
                        val decorated = db.decorateNode(
                            icon = statement.substring(ICON_PREFIX.length, closing),
                            cssClasses = null,
                            line = lineNumber,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return decorated
                    }
                    lineIndex++
                }
                statement.startsWith(CLASS_PREFIX) -> {
                    val classes = statement.substring(CLASS_PREFIX.length)
                    if (classes.isBlank()) {
                        return parseError(
                            lineNumber,
                            indentation + 1,
                            "Expected Kanban CSS class",
                        )
                    }
                    when (
                        val decorated = db.decorateNode(
                            icon = null,
                            cssClasses = classes,
                            line = lineNumber,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return decorated
                    }
                    lineIndex++
                }
                else -> {
                    val metadataStart = findMetadataStart(statement)
                    val nodeSource = if (metadataStart >= 0) {
                        statement.substring(0, metadataStart).trimEnd()
                    } else {
                        statement
                    }
                    val parsedNode = when (
                        val parsed = parseNode(nodeSource, lineNumber, indentation + 1)
                    ) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    val metadata = if (metadataStart >= 0) {
                        when (
                            val parsed = collectMetadata(
                                lines = lines,
                                lineIndex = lineIndex,
                                column = indentation + metadataStart + 1,
                                firstStatement = statement,
                                metadataStart = metadataStart,
                            )
                        ) {
                            is GMResult.Ok -> {
                                lineIndex = parsed.value.lastLineIndex
                                parsed.value.metadata
                            }
                            is GMResult.Err -> return parsed
                        }
                    } else {
                        KanbanMetadata()
                    }
                    when (
                        val added = db.addNode(
                            level = indentation,
                            id = parsedNode.id,
                            description = parsedNode.description,
                            metadata = metadata,
                            line = lineNumber,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return added
                    }
                    lineIndex++
                }
            }
        }
        return db.getData()
    }

    private fun parseNode(
        source: String,
        line: Int,
        column: Int,
    ): GMResult<ParsedNode, MermaidError> {
        if (source.isBlank()) {
            return parseError(line, column, "Expected Kanban node")
        }
        val opener = NODE_DELIMITERS
            .mapNotNull { delimiter ->
                val index = source.indexOf(delimiter.open)
                index.takeIf { it >= 0 }?.let { delimiter to it }
            }
            .minWithOrNull(compareBy<Pair<NodeDelimiter, Int>>({ it.second }, { -it.first.open.length }))
        if (opener == null) {
            val value = source.trim()
            return GMResult.Ok(ParsedNode(value, value))
        }
        val delimiter = opener.first
        val openIndex = opener.second
        val closeIndex = findClosingDelimiter(
            source = source,
            start = openIndex + delimiter.open.length,
            close = delimiter.close,
        )
        if (closeIndex < 0) {
            return parseError(line, column + openIndex, "Unterminated Kanban node")
        }
        if (source.substring(closeIndex + delimiter.close.length).isNotBlank()) {
            return parseError(line, column + closeIndex, "Unexpected text after Kanban node")
        }
        val description = unquote(
            source.substring(openIndex + delimiter.open.length, closeIndex),
        )
        if (description.isEmpty()) {
            return parseError(line, column + openIndex, "Expected Kanban node label")
        }
        val explicitId = source.substring(0, openIndex).trim()
        return GMResult.Ok(
            ParsedNode(
                id = explicitId.ifEmpty { description },
                description = description,
            ),
        )
    }

    private fun collectMetadata(
        lines: List<String>,
        lineIndex: Int,
        column: Int,
        firstStatement: String,
        metadataStart: Int,
    ): GMResult<CollectedMetadata, MermaidError> {
        val raw = StringBuilder(firstStatement.substring(metadataStart + 2))
        var currentLineIndex = lineIndex
        var closing = findMetadataEnd(raw)
        while (closing < 0) {
            currentLineIndex++
            if (currentLineIndex >= lines.size) {
                return parseError(lineIndex + 1, column, "Unterminated Kanban metadata")
            }
            raw.append('\n').append(lines[currentLineIndex])
            closing = findMetadataEnd(raw)
        }
        val suffix = raw.substring(closing + 1)
        if (stripComment(suffix).isNotBlank()) {
            return parseError(
                currentLineIndex + 1,
                1,
                "Unexpected text after Kanban metadata",
            )
        }
        val content = raw.substring(0, closing)
        val yamlSource = if ('\n' in content) {
            content + "\n"
        } else {
            "{\n$content\n}"
        }
        val yaml = try {
            Yaml.default.parseToYamlNode(yamlSource)
        } catch (failure: Exception) {
            return parseError(
                lineIndex + 1,
                column,
                "Invalid Kanban metadata: ${failure.message ?: "invalid YAML"}",
            )
        }
        val map = yaml as? YamlMap
            ?: return parseError(lineIndex + 1, column, "Kanban metadata must be a map")
        fun scalar(key: String): String? {
            val value = map.entries.entries.firstOrNull { entry ->
                entry.key.content == key
            }?.value ?: return null
            return (value as? YamlScalar)?.content
        }
        val shape = scalar("shape")
        if (shape != null && (shape != shape.lowercase() || '_' in shape)) {
            return parseError(
                lineIndex + 1,
                column,
                "No such shape: $shape. Shape names should be lowercase.",
            )
        }
        return GMResult.Ok(
            CollectedMetadata(
                metadata = KanbanMetadata(
                    label = scalar("label"),
                    icon = scalar("icon"),
                    assigned = scalar("assigned"),
                    ticket = scalar("ticket"),
                    priority = scalar("priority"),
                ),
                lastLineIndex = currentLineIndex,
            ),
        )
    }

    private fun findMetadataStart(source: String): Int {
        var quote: Char? = null
        var squareDepth = 0
        var parenthesisDepth = 0
        var index = 0
        while (index < source.length - 1) {
            val char = source[index]
            when {
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '"' || char == '\'' -> quote = char
                char == '[' -> squareDepth++
                char == ']' -> squareDepth = (squareDepth - 1).coerceAtLeast(0)
                char == '(' -> parenthesisDepth++
                char == ')' -> parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                char == '@' &&
                    source[index + 1] == '{' &&
                    squareDepth == 0 &&
                    parenthesisDepth == 0 -> return index
            }
            index++
        }
        return -1
    }

    private fun findMetadataEnd(source: CharSequence): Int {
        var quote: Char? = null
        source.forEachIndexed { index, char ->
            when {
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '"' || char == '\'' -> quote = char
                char == '}' -> return index
            }
        }
        return -1
    }

    private fun findClosingDelimiter(
        source: String,
        start: Int,
        close: String,
    ): Int {
        var quoted = false
        var index = start
        while (index <= source.length - close.length) {
            if (source[index] == '"') {
                quoted = !quoted
            } else if (!quoted && source.startsWith(close, index)) {
                return index
            }
            index++
        }
        return -1
    }

    private fun unquote(source: String): String {
        val trimmed = source.trim()
        return when {
            trimmed.length >= 4 && trimmed.startsWith("\"`") && trimmed.endsWith("`\"") ->
                trimmed.substring(2, trimmed.length - 2)
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
    }

    private fun stripComment(source: String): String {
        var quote: Char? = null
        var index = 0
        while (index < source.length - 1) {
            val char = source[index]
            when {
                quote != null && char == quote -> quote = null
                quote != null -> Unit
                char == '"' || char == '\'' -> quote = char
                char == '%' && source[index + 1] == '%' -> return source.substring(0, index)
            }
            index++
        }
        return source
    }

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

    private data class ParsedNode(
        val id: String,
        val description: String,
    )

    private data class CollectedMetadata(
        val metadata: KanbanMetadata,
        val lastLineIndex: Int,
    )

    private data class NodeDelimiter(
        val open: String,
        val close: String,
    )

    private companion object {
        const val COMMENT_PREFIX = "%%"
        const val ICON_PREFIX = "::icon("
        const val CLASS_PREFIX = ":::"
        val NODE_DELIMITERS = listOf(
            NodeDelimiter("((", "))"),
            NodeDelimiter("{{", "}}"),
            NodeDelimiter("(-", ")"),
            NodeDelimiter("-)", "(-"),
            NodeDelimiter("))", "(("),
            NodeDelimiter("(", ")"),
            NodeDelimiter(")", "("),
            NodeDelimiter("[", "]"),
        )
    }
}

/**
 * Kotlin translation of Mermaid 12.0.0 kanbanDb.ts.
 */
private class KanbanDb(
    private val error: (Int, Int, String) -> GMResult<Nothing, MermaidError>,
) {
    private val nodes = mutableListOf<KanbanNode>()
    private val sections = mutableListOf<KanbanNode>()
    private var generatedId = 0

    fun addNode(
        level: Int,
        id: String,
        description: String,
        metadata: KanbanMetadata,
        line: Int,
    ): GMResult<Unit, MermaidError> {
        val nodeId = id.ifEmpty { "kbn${generatedId++}" }
        val baseNode = KanbanNode(
            id = nodeId,
            label = metadata.label ?: description,
            level = level,
            ticket = metadata.ticket,
            priority = metadata.priority,
            assigned = metadata.assigned,
            icon = metadata.icon,
        )
        val section = when (val result = getSection(level, line)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val node = if (section == null) {
            sections += baseNode
            baseNode
        } else {
            baseNode.copy(parentId = section.id)
        }
        nodes += node
        return GMResult.Ok(Unit)
    }

    fun decorateNode(
        icon: String?,
        cssClasses: String?,
        line: Int,
    ): GMResult<Unit, MermaidError> {
        val index = nodes.lastIndex
        if (index < 0) {
            return error(line, 1, "Kanban decoration must follow a node")
        }
        val previous = nodes[index]
        val sectionIndex = sections.indexOfLast { section -> section === previous }
        val decorated = previous.copy(
            icon = icon ?: previous.icon,
            cssClasses = cssClasses ?: previous.cssClasses,
        )
        nodes[index] = decorated
        if (sectionIndex >= 0) {
            sections[sectionIndex] = decorated
        }
        return GMResult.Ok(Unit)
    }

    fun getData(): GMResult<KanbanDocument, MermaidError> {
        if (sections.isEmpty()) {
            return error(1, 1, "Expected at least one Kanban section")
        }
        return GMResult.Ok(
            KanbanDocument(
                sections = sections.map { section ->
                    // Mermaid kanbanDb.getData deliberately keeps only ticket metadata
                    // for sections and flattens every deeper level into the section.
                    KanbanSection(
                        node = section.copy(
                            priority = null,
                            assigned = null,
                            icon = null,
                            cssClasses = null,
                        ),
                        items = nodes
                            .filter { node -> node.parentId == section.id }
                            .map { node -> node.copy(cssClasses = null) },
                    )
                },
            ),
        )
    }

    private fun getSection(
        level: Int,
        line: Int,
    ): GMResult<KanbanNode?, MermaidError> {
        if (nodes.isEmpty()) {
            return GMResult.Ok(null)
        }
        val sectionLevel = nodes.first().level
        var lastSection: KanbanNode? = null
        for (index in nodes.indices.reversed()) {
            val node = nodes[index]
            if (node.level == sectionLevel && lastSection == null) {
                lastSection = node
            }
            if (node.level < sectionLevel) {
                return error(
                    line,
                    1,
                    "Items without section detected, found section (\"${node.label}\")",
                )
            }
        }
        return GMResult.Ok(if (level == lastSection?.level) null else lastSection)
    }
}

private data class KanbanMetadata(
    val label: String? = null,
    val icon: String? = null,
    val assigned: String? = null,
    val ticket: String? = null,
    val priority: String? = null,
)
