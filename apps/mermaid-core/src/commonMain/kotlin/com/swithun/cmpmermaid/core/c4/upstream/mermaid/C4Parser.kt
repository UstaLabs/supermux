package com.swithun.cmpmermaid.core.c4.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/c4/parser/c4Diagram.jison.
 *
 * This is a portable Kotlin translation of the Jison lexer states and semantic
 * actions. C4 declarations remain line-oriented, while quoted commas and named
 * `$key="value"` attributes are tokenized independently of line splitting.
 */
internal class C4Parser(
    private val options: MermaidRenderOptions,
    private val frontmatterTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<C4Db, MermaidError> {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
        val db = C4Db(options.c4, frontmatterTitle)
        var headerFound = false
        var waitingForBoundaryBrace = false
        var index = 0

        while (index < lines.size) {
            val lineNumber = lineOffset + index + 1
            val raw = stripComment(lines[index])
            var content = raw.trim()
            if (content.isEmpty()) {
                index += 1
                continue
            }

            if (!headerFound) {
                val header = content.takeWhile { character -> !character.isWhitespace() }
                val type = C4DiagramType.fromSource(header)
                    ?: return parseError(
                        lineNumber,
                        firstContentColumn(raw),
                        "Expected a C4Context, C4Container, C4Component, " +
                            "C4Dynamic, or C4Deployment diagram header",
                    )
                db.setC4Type(type)
                headerFound = true
                content = content.substring(header.length).trim()
                if (content.isEmpty()) {
                    index += 1
                    continue
                }
            }

            if (waitingForBoundaryBrace) {
                if (content != "{") {
                    return parseError(lineNumber, 1, "Expected '{' after C4 boundary declaration")
                }
                waitingForBoundaryBrace = false
                index += 1
                continue
            }

            when {
                content == "}" -> {
                    if (!db.popBoundaryParseStack()) {
                        return parseError(lineNumber, 1, "Unexpected C4 boundary closing brace")
                    }
                }
                content.startsWith("accDescr") &&
                    content.substring("accDescr".length).trimStart().startsWith("{") -> {
                    val multiline = parseMultilineAccDescription(
                        lines = lines,
                        startIndex = index,
                        initial = content,
                    )
                    when (multiline) {
                        is GMResult.Ok -> {
                            db.setAccessibilityDescription(multiline.value.value)
                            index = multiline.value.lastLineIndex
                        }
                        is GMResult.Err -> return multiline
                    }
                }
                content.startsWith("accTitle") -> {
                    val value = parseColonValue(content, "accTitle", lineNumber)
                    when (value) {
                        is GMResult.Ok -> db.setTitle(value.value)
                        is GMResult.Err -> return value
                    }
                }
                content.startsWith("accDescr") -> {
                    val value = parseColonValue(content, "accDescr", lineNumber)
                    when (value) {
                        is GMResult.Ok -> db.setAccessibilityDescription(value.value)
                        is GMResult.Err -> return value
                    }
                }
                content.startsWith("accDescription") &&
                    content.getOrNull("accDescription".length)?.isWhitespace() == true -> {
                    db.setAccessibilityDescription(
                        content.substring("accDescription".length).trim(),
                    )
                }
                content.startsWith("title") &&
                    content.getOrNull("title".length)?.isWhitespace() == true -> {
                    db.setTitle(content.substring("title".length).trim())
                }
                content.startsWith("direction") -> {
                    val direction = content.substring("direction".length).trim()
                    if (direction !in DIRECTIONS) {
                        return parseError(lineNumber, 1, "Unknown C4 direction '$direction'")
                    }
                }
                else -> {
                    val statement = collectStatement(lines, index)
                    when (statement) {
                        is GMResult.Ok -> {
                            when (
                                val parsed = parseMacro(
                                    content = statement.value.content,
                                    line = lineNumber,
                                    db = db,
                                )
                            ) {
                                is GMResult.Ok -> {
                                    if (parsed.value.isBoundary && !parsed.value.hasOpeningBrace) {
                                        waitingForBoundaryBrace = true
                                    }
                                }
                                is GMResult.Err -> return parsed
                            }
                            index = statement.value.lastLineIndex
                        }
                        is GMResult.Err -> return statement
                    }
                }
            }
            if (db.getRelations().size > options.maxEdges) {
                return GMResult.Err(
                    MermaidError.ResourceLimit(
                        resource = "C4 relationships",
                        actual = db.getRelations().size,
                        maximum = options.maxEdges,
                    ),
                )
            }
            index += 1
        }

        if (!headerFound) {
            return parseError(
                lineOffset + 1,
                1,
                "Expected a C4 diagram header",
            )
        }
        if (waitingForBoundaryBrace) {
            return parseError(
                lineOffset + lines.size,
                1,
                "Expected '{' after C4 boundary declaration",
            )
        }
        if (!db.isAtGlobalBoundary()) {
            return parseError(
                lineOffset + lines.size,
                1,
                "Unclosed C4 boundary",
            )
        }
        return GMResult.Ok(db)
    }

    private fun parseMacro(
        content: String,
        line: Int,
        db: C4Db,
    ): GMResult<ParsedMacro, MermaidError> {
        val open = content.indexOf('(')
        if (open <= 0) {
            return parseError(line, 1, "Expected a C4 declaration")
        }
        val close = findClosingParenthesis(content, open)
            ?: return parseError(line, open + 1, "Unclosed C4 declaration")
        val trailing = content.substring(close + 1).trim()
        if (trailing.isNotEmpty() && trailing != "{") {
            return parseError(line, close + 2, "Unexpected text after C4 declaration")
        }
        val name = content.substring(0, open).trim()
        val attributes = when (
            val result = parseAttributes(content.substring(open + 1, close), line)
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val boundary = name in BOUNDARY_MACROS

        when (name) {
            "Person" -> db.addPersonOrSystem(C4ElementType.Person, attributes)
            "Person_Ext" -> db.addPersonOrSystem(C4ElementType.ExternalPerson, attributes)
            "System" -> db.addPersonOrSystem(C4ElementType.System, attributes)
            "SystemDb" -> db.addPersonOrSystem(C4ElementType.SystemDb, attributes)
            "SystemQueue" -> db.addPersonOrSystem(C4ElementType.SystemQueue, attributes)
            "System_Ext" -> db.addPersonOrSystem(C4ElementType.ExternalSystem, attributes)
            "SystemDb_Ext" -> db.addPersonOrSystem(C4ElementType.ExternalSystemDb, attributes)
            "SystemQueue_Ext" ->
                db.addPersonOrSystem(C4ElementType.ExternalSystemQueue, attributes)
            "Container" -> db.addContainer(C4ElementType.Container, attributes)
            "ContainerDb" -> db.addContainer(C4ElementType.ContainerDb, attributes)
            "ContainerQueue" -> db.addContainer(C4ElementType.ContainerQueue, attributes)
            "Container_Ext" -> db.addContainer(C4ElementType.ExternalContainer, attributes)
            "ContainerDb_Ext" -> db.addContainer(C4ElementType.ExternalContainerDb, attributes)
            "ContainerQueue_Ext" ->
                db.addContainer(C4ElementType.ExternalContainerQueue, attributes)
            "Component" -> db.addComponent(C4ElementType.Component, attributes)
            "ComponentDb" -> db.addComponent(C4ElementType.ComponentDb, attributes)
            "ComponentQueue" -> db.addComponent(C4ElementType.ComponentQueue, attributes)
            "Component_Ext" -> db.addComponent(C4ElementType.ExternalComponent, attributes)
            "ComponentDb_Ext" -> db.addComponent(C4ElementType.ExternalComponentDb, attributes)
            "ComponentQueue_Ext" ->
                db.addComponent(C4ElementType.ExternalComponentQueue, attributes)
            "Boundary" -> db.addPersonOrSystemBoundary(attributes)
            "Enterprise_Boundary" -> db.addPersonOrSystemBoundary(
                attributes.withInsertedType("ENTERPRISE"),
            )
            "System_Boundary" -> db.addPersonOrSystemBoundary(
                attributes.withInsertedType("SYSTEM"),
            )
            "Container_Boundary" -> db.addContainerBoundary(
                attributes.withInsertedType("CONTAINER"),
            )
            "Deployment_Node", "Node" -> db.addDeploymentNode("node", attributes)
            "Node_L" -> db.addDeploymentNode("nodeL", attributes)
            "Node_R" -> db.addDeploymentNode("nodeR", attributes)
            "Rel" -> db.addRel("rel", attributes, ignoreIndex = false)
            "BiRel" -> db.addRel("birel", attributes, ignoreIndex = false)
            "Rel_Up", "Rel_U" -> db.addRel("rel_u", attributes, ignoreIndex = false)
            "Rel_Down", "Rel_D" -> db.addRel("rel_d", attributes, ignoreIndex = false)
            "Rel_Left", "Rel_L" -> db.addRel("rel_l", attributes, ignoreIndex = false)
            "Rel_Right", "Rel_R" -> db.addRel("rel_r", attributes, ignoreIndex = false)
            "Rel_Back" -> db.addRel("rel_b", attributes, ignoreIndex = false)
            "RelIndex" -> db.addRel("rel", attributes, ignoreIndex = true)
            "UpdateElementStyle" -> db.updateElStyle(attributes)
            "UpdateRelStyle" -> db.updateRelStyle(attributes)
            "UpdateLayoutConfig" -> db.updateLayoutConfig(attributes)
            else -> return parseError(line, 1, "Unknown C4 declaration '$name'")
        }
        if (boundary && attributes.size < 2) {
            return parseError(line, open + 2, "$name requires an alias and label")
        }
        return GMResult.Ok(
            ParsedMacro(
                isBoundary = boundary,
                hasOpeningBrace = trailing == "{",
            ),
        )
    }

    private fun parseAttributes(
        source: String,
        line: Int,
    ): GMResult<List<C4Attribute>, MermaidError> {
        if (source.isBlank()) return GMResult.Ok(emptyList())
        val values = mutableListOf<String>()
        var quote = false
        var start = 0
        source.forEachIndexed { index, character ->
            when {
                character == '"' -> quote = !quote
                character == ',' && !quote -> {
                    values += source.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (quote) {
            return parseError(line, 1, "Unclosed quoted C4 attribute")
        }
        values += source.substring(start)

        val attributes = mutableListOf<C4Attribute>()
        values.forEach { raw ->
            val value = raw.trim()
            if (value.startsWith('$')) {
                val equals = value.indexOf('=')
                if (equals <= 1) {
                    return parseError(line, 1, "Invalid named C4 attribute '$value'")
                }
                val key = value.substring(1, equals).trim()
                val namedValue = unquote(value.substring(equals + 1).trim())
                if (key.isEmpty()) {
                    return parseError(line, 1, "C4 named attribute key cannot be empty")
                }
                attributes += C4Attribute.Named(key, decode(namedValue))
            } else {
                attributes += C4Attribute.Positional(decode(unquote(value)))
            }
        }
        return GMResult.Ok(attributes)
    }

    private fun collectStatement(
        lines: List<String>,
        startIndex: Int,
    ): GMResult<CollectedStatement, MermaidError> {
        val builder = StringBuilder()
        var index = startIndex
        var quote = false
        var depth = 0
        var sawOpen = false
        while (index < lines.size) {
            val line = stripComment(lines[index])
            if (builder.isNotEmpty()) builder.append(' ')
            builder.append(line.trim())
            line.forEach { character ->
                when {
                    character == '"' -> quote = !quote
                    quote -> Unit
                    character == '(' -> {
                        depth += 1
                        sawOpen = true
                    }
                    character == ')' -> depth -= 1
                }
            }
            if (depth < 0) {
                return parseError(
                    lineOffset + index + 1,
                    1,
                    "Unexpected ')' in C4 declaration",
                )
            }
            if (sawOpen && depth == 0 && !quote) {
                return GMResult.Ok(
                    CollectedStatement(builder.toString(), index),
                )
            }
            index += 1
        }
        return parseError(
            lineOffset + startIndex + 1,
            1,
            "Unclosed C4 declaration",
        )
    }

    private fun parseMultilineAccDescription(
        lines: List<String>,
        startIndex: Int,
        initial: String,
    ): GMResult<MultilineMetadata, MermaidError> {
        val opening = initial.indexOf('{')
        val builder = StringBuilder()
        var index = startIndex
        var content = initial.substring(opening + 1)
        while (true) {
            val closing = content.indexOf('}')
            if (closing >= 0) {
                if (builder.isNotEmpty() && closing > 0) builder.append('\n')
                builder.append(content.substring(0, closing))
                return GMResult.Ok(
                    MultilineMetadata(
                        value = builder.toString().trim(),
                        lastLineIndex = index,
                    ),
                )
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(content)
            index += 1
            if (index >= lines.size) {
                return parseError(
                    lineOffset + startIndex + 1,
                    opening + 1,
                    "Unclosed C4 accessibility description",
                )
            }
            content = lines[index]
        }
    }

    private fun parseColonValue(
        content: String,
        keyword: String,
        line: Int,
    ): GMResult<String, MermaidError> {
        val rest = content.substring(keyword.length).trimStart()
        if (!rest.startsWith(':')) {
            return parseError(line, keyword.length + 1, "Expected ':' after $keyword")
        }
        return GMResult.Ok(decode(rest.substring(1).trim()))
    }

    private fun findClosingParenthesis(
        source: String,
        open: Int,
    ): Int? {
        var quote = false
        var depth = 0
        for (index in open until source.length) {
            when {
                source[index] == '"' -> quote = !quote
                quote -> Unit
                source[index] == '(' -> depth += 1
                source[index] == ')' -> {
                    depth -= 1
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun List<C4Attribute>.withInsertedType(value: String): List<C4Attribute> =
        toMutableList().also { attributes ->
            val index = minOf(2, attributes.size)
            attributes.add(index, C4Attribute.Positional(value))
        }

    private fun stripComment(source: String): String {
        var quote = false
        var index = 0
        while (index < source.length - 1) {
            when {
                source[index] == '"' -> quote = !quote
                !quote && source[index] == '%' && source[index + 1] == '%' ->
                    return source.substring(0, index)
            }
            index += 1
        }
        return source
    }

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.lastIndex)
        } else {
            value
        }

    private fun decode(value: String): String = MermaidPreprocessor.decodeEntities(value)

    private fun firstContentColumn(line: String): Int =
        line.indexOfFirst { character -> !character.isWhitespace() }
            .let { index -> if (index < 0) 1 else index + 1 }

    private fun <T> parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line,
            column = column,
            message = message,
        ),
    )

    private data class ParsedMacro(
        val isBoundary: Boolean,
        val hasOpeningBrace: Boolean,
    )

    private data class CollectedStatement(
        val content: String,
        val lastLineIndex: Int,
    )

    private data class MultilineMetadata(
        val value: String,
        val lastLineIndex: Int,
    )

    private companion object {
        val DIRECTIONS = setOf("TB", "BT", "RL", "LR")
        val BOUNDARY_MACROS = setOf(
            "Boundary",
            "Enterprise_Boundary",
            "System_Boundary",
            "Container_Boundary",
            "Deployment_Node",
            "Node",
            "Node_L",
            "Node_R",
        )
    }
}
