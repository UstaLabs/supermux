package com.swithun.cmpmermaid.core.venn.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidVennOptions

/**
 * Deterministic Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/venn/parser/venn.jison.
 */
internal class VennParser(
    private val config: MermaidVennOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
    private val maximumStatements: Int,
) {
    fun parse(source: String): GMResult<VennDb, MermaidError> {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val headerIndex = lines.indexOfFirst { line -> stripComment(line).isNotBlank() }
        if (headerIndex < 0) {
            return parseError(1, 1, "Expected 'venn-beta' diagram header")
        }
        if (!stripComment(lines[headerIndex]).trim().equals(HEADER, ignoreCase = true)) {
            return parseError(headerIndex + 1, 1, "Expected 'venn-beta' diagram header")
        }

        val db = VennDb(config = config, diagramTitle = diagramTitle)
        var indentMode = false
        var statementCount = 0
        lines.drop(headerIndex + 1).forEachIndexed { relativeIndex, sourceLine ->
            val lineIndex = headerIndex + relativeIndex + 2
            if (sourceLine.firstOrNull()?.let(::isHorizontalWhitespace) == false) {
                indentMode = false
            }
            val content = stripComment(sourceLine)
            if (content.isBlank()) {
                return@forEachIndexed
            }
            statementCount += 1
            if (statementCount > maximumStatements) {
                return GMResult.Err(
                    MermaidError.ResourceLimit(
                        resource = "Venn statements",
                        actual = statementCount,
                        maximum = maximumStatements,
                    ),
                )
            }
            val indentation = content.takeWhile(::isHorizontalWhitespace).length
            val statement = content.drop(indentation).trimEnd()
            val keyword = statement.takeWhile { character -> !character.isWhitespace() }
            val remainder = statement.drop(keyword.length).trimStart()
            when (keyword.lowercase()) {
                "title" -> {
                    if (remainder.isEmpty()) {
                        return parseError(lineIndex, indentation + 1, "Expected a Venn title")
                    }
                    db.setDiagramTitle(remainder.substringBefore('#').substringBefore(';').trim())
                }
                "set" -> {
                    when (
                        val parsed = parseSubset(
                            source = remainder,
                            union = false,
                            line = lineIndex,
                            column = indentation + keyword.length + 2,
                            db = db,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return parsed
                    }
                    indentMode = true
                }
                "union" -> {
                    when (
                        val parsed = parseSubset(
                            source = remainder,
                            union = true,
                            line = lineIndex,
                            column = indentation + keyword.length + 2,
                            db = db,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return parsed
                    }
                    indentMode = true
                }
                "text" -> {
                    val indented = indentMode && indentation > 0
                    when (
                        val parsed = parseText(
                            source = remainder,
                            currentSets = db.currentSets.takeIf { indented },
                            line = lineIndex,
                            column = indentation + keyword.length + 2,
                            db = db,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return parsed
                    }
                }
                "style" -> {
                    when (
                        val parsed = parseStyle(
                            source = remainder,
                            line = lineIndex,
                            column = indentation + keyword.length + 2,
                            db = db,
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return parsed
                    }
                }
                else -> return parseError(
                    lineIndex,
                    indentation + 1,
                    "Expected title, set, union, text, or style",
                )
            }
        }
        return GMResult.Ok(db)
    }

    private fun parseSubset(
        source: String,
        union: Boolean,
        line: Int,
        column: Int,
        db: VennDb,
    ): GMResult<Unit, MermaidError> {
        val scanner = Scanner(source)
        val identifiers = when (val parsed = scanner.identifierList()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(line, column + parsed.error, "Expected set identifier")
        }
        if (!union && identifiers.size != 1) {
            return parseError(line, column, "set requires single identifier")
        }
        if (union && identifiers.size < 2) {
            return parseError(line, column, "union requires multiple identifiers")
        }
        val label = when (val parsed = scanner.optionalBracketLabel()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                "Invalid Venn bracket label",
            )
        }
        val size = when (val parsed = scanner.optionalSize()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                "Expected numeric Venn size",
            )
        }
        if (!scanner.atEnd()) {
            return parseError(line, column + scanner.position, "Unexpected text in Venn statement")
        }
        if (union) {
            when (
                val validation = db.validateUnionIdentifiers(
                    identifierList = identifiers,
                    line = line,
                    column = column,
                    lineOffset = lineOffset,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return validation
            }
        }
        db.addSubsetData(identifiers, label, size)
        return GMResult.Ok(Unit)
    }

    private fun parseText(
        source: String,
        currentSets: List<String>?,
        line: Int,
        column: Int,
        db: VennDb,
    ): GMResult<Unit, MermaidError> {
        val scanner = Scanner(source)
        val sets = if (currentSets != null) {
            currentSets
        } else {
            when (val parsed = scanner.identifierList()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parseError(
                    line,
                    column + parsed.error,
                    "Expected text target identifiers",
                )
            }
        }
        val id = when (val parsed = scanner.textIdentifier()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                if (currentSets == null) "Expected Venn text identifier" else "text requires set",
            )
        }
        val label = when (val parsed = scanner.optionalBracketLabel()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                "Invalid Venn text bracket label",
            )
        }
        if (!scanner.atEnd()) {
            return parseError(line, column + scanner.position, "Unexpected text in Venn text node")
        }
        db.addTextData(sets, id, label)
        return GMResult.Ok(Unit)
    }

    private fun parseStyle(
        source: String,
        line: Int,
        column: Int,
        db: VennDb,
    ): GMResult<Unit, MermaidError> {
        val scanner = Scanner(source)
        val identifiers = when (val parsed = scanner.identifierList()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                "Expected style target identifiers",
            )
        }
        val declarations = when (val parsed = scanner.styleDeclarations()) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parseError(
                line,
                column + parsed.error,
                "Invalid Venn style declaration",
            )
        }
        db.addStyleData(identifiers, declarations)
        return GMResult.Ok(Unit)
    }

    private fun stripComment(line: String): String {
        var index = 0
        while (index < line.lastIndex) {
            if (
                line[index] == '%' &&
                line[index + 1] == '%' &&
                line.getOrNull(index + 2) != '{'
            ) {
                return line.substring(0, index)
            }
            index += 1
        }
        return line
    }

    private fun <T> parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line + lineOffset,
            column = column.coerceAtLeast(1),
            message = "Parse error on line ${line + lineOffset}, " +
                "column ${column.coerceAtLeast(1)}: $message",
        ),
    )

    private class Scanner(
        private val source: String,
    ) {
        var position: Int = 0
            private set

        fun identifierList(): GMResult<List<String>, Int> {
            val identifiers = mutableListOf<String>()
            when (val first = identifier()) {
                is GMResult.Ok -> identifiers += first.value
                is GMResult.Err -> return first
            }
            while (true) {
                skipWhitespace()
                if (source.getOrNull(position) != ',') {
                    break
                }
                position += 1
                when (val next = identifier()) {
                    is GMResult.Ok -> identifiers += next.value
                    is GMResult.Err -> return next
                }
            }
            return GMResult.Ok(identifiers)
        }

        fun textIdentifier(): GMResult<String, Int> {
            skipWhitespace()
            val start = position
            quoted()?.let { return GMResult.Ok(it) }
            NUMBER.find(source, position)
                ?.takeIf { match -> match.range.first == position }
                ?.let { match ->
                    position = match.range.last + 1
                    return GMResult.Ok(match.value)
                }
            BARE_IDENTIFIER.find(source, position)
                ?.takeIf { match -> match.range.first == position }
                ?.let { match ->
                    position = match.range.last + 1
                    return GMResult.Ok(match.value)
                }
            return GMResult.Err(start)
        }

        fun optionalBracketLabel(): GMResult<String?, Int> {
            skipWhitespace()
            if (source.getOrNull(position) != '[') {
                return GMResult.Ok(null)
            }
            val start = position
            position += 1
            val quoted = source.getOrNull(position) == '"'
            if (quoted) {
                position += 1
                val closeQuote = source.indexOf('"', position)
                if (closeQuote < 0 || source.getOrNull(closeQuote + 1) != ']') {
                    return GMResult.Err(start)
                }
                val label = source.substring(position, closeQuote)
                position = closeQuote + 2
                return GMResult.Ok(label)
            }
            val close = source.indexOf(']', position)
            if (close < 0) {
                return GMResult.Err(start)
            }
            val label = source.substring(position, close).trim()
            if (label.isEmpty() || '"' in label) {
                return GMResult.Err(start)
            }
            position = close + 1
            return GMResult.Ok(label)
        }

        fun optionalSize(): GMResult<Double?, Int> {
            skipWhitespace()
            if (source.getOrNull(position) != ':') {
                return GMResult.Ok(null)
            }
            position += 1
            skipWhitespace()
            val start = position
            val match = NUMBER.find(source, position)
                ?.takeIf { result -> result.range.first == position }
                ?: return GMResult.Err(start)
            position = match.range.last + 1
            return match.value.toDoubleOrNull()
                ?.let { value -> GMResult.Ok(value) }
                ?: GMResult.Err(start)
        }

        fun styleDeclarations(): GMResult<List<Pair<String, String>>, Int> {
            skipWhitespace()
            if (position >= source.length) {
                return GMResult.Err(position)
            }
            val declarations = mutableListOf<Pair<String, String>>()
            splitStyleFields(source.substring(position)).forEach { field ->
                val separator = field.indexOf(':')
                if (separator <= 0 || separator == field.lastIndex) {
                    return GMResult.Err(position)
                }
                val key = field.substring(0, separator).trim()
                val value = field.substring(separator + 1).trim()
                if (!BARE_IDENTIFIER.matches(key) || value.isEmpty()) {
                    return GMResult.Err(position)
                }
                declarations += key to normalizeStyleValue(value)
            }
            position = source.length
            return GMResult.Ok(declarations)
        }

        fun atEnd(): Boolean {
            skipWhitespace()
            return position >= source.length
        }

        private fun identifier(): GMResult<String, Int> {
            skipWhitespace()
            val start = position
            quoted()?.let { return GMResult.Ok(it) }
            BARE_IDENTIFIER.find(source, position)
                ?.takeIf { match -> match.range.first == position }
                ?.let { match ->
                    position = match.range.last + 1
                    return GMResult.Ok(match.value)
                }
            return GMResult.Err(start)
        }

        private fun quoted(): String? {
            if (source.getOrNull(position) != '"') {
                return null
            }
            val start = position
            val close = source.indexOf('"', startIndex = position + 1)
            if (close < 0) {
                return null
            }
            position = close + 1
            return source.substring(start, position)
        }

        private fun skipWhitespace() {
            while (source.getOrNull(position)?.let(::isHorizontalWhitespace) == true) {
                position += 1
            }
        }
    }

    private companion object {
        const val HEADER = "venn-beta"
        val BARE_IDENTIFIER = Regex("""[A-Za-z_][A-Za-z0-9\-_]*""")
        val NUMBER = Regex("""[+-]?(?:\d+(?:\.\d+)?|\.\d+)""")

        fun isHorizontalWhitespace(character: Char): Boolean =
            character == ' ' || character == '\t'

        fun normalizeStyleValue(value: String): String {
            val trimmed = value.trim()
            return if (
                trimmed.length >= 2 &&
                trimmed.startsWith('"') &&
                trimmed.endsWith('"')
            ) {
                trimmed.substring(1, trimmed.lastIndex)
            } else {
                trimmed
            }
        }

        fun splitStyleFields(source: String): List<String> {
            val fields = mutableListOf<String>()
            var quote = false
            var parenthesisDepth = 0
            var start = 0
            source.forEachIndexed { index, character ->
                when (character) {
                    '"' -> quote = !quote
                    '(' -> if (!quote) parenthesisDepth += 1
                    ')' -> if (!quote && parenthesisDepth > 0) parenthesisDepth -= 1
                    ',' -> if (!quote && parenthesisDepth == 0) {
                        fields += source.substring(start, index).trim()
                        start = index + 1
                    }
                }
            }
            fields += source.substring(start).trim()
            return fields
        }
    }
}
