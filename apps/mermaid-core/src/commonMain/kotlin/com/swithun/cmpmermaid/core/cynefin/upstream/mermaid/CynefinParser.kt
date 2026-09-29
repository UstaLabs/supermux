package com.swithun.cmpmermaid.core.cynefin.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/cynefin/cynefin.langium,
 * packages/parser/src/language/common/common.langium,
 * packages/parser/src/language/common/valueConverter.ts, and
 * packages/mermaid/src/diagrams/cynefin/cynefinParser.ts -> populate.
 */
internal class CynefinParser(
    private val options: MermaidRenderOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<CynefinDb, MermaidError> {
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val cursor = Cursor(normalized, lineOffset)
        cursor.skipTrivia()
        when (val header = cursor.parseHeader()) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return header
        }

        var title: String? = null
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null
        val domains = mutableListOf<CynefinAstDomainBlock>()
        val transitions = mutableListOf<CynefinAstTransition>()

        while (true) {
            cursor.skipTrivia()
            if (cursor.isAtEnd()) {
                break
            }
            when {
                cursor.hasKeyword(TITLE_KEYWORD) -> {
                    title = cursor.parseInlineMetadata(TITLE_KEYWORD)
                }
                cursor.hasKeyword(ACCESSIBILITY_TITLE_KEYWORD) -> {
                    when (val parsed = cursor.parseColonMetadata(ACCESSIBILITY_TITLE_KEYWORD)) {
                        is GMResult.Ok -> accessibilityTitle = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword(ACCESSIBILITY_DESCRIPTION_KEYWORD) -> {
                    when (val parsed = cursor.parseAccessibilityDescription()) {
                        is GMResult.Ok -> accessibilityDescription = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                else -> {
                    val domain = when (val parsed = cursor.parseDomainName()) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    cursor.skipHorizontalWhitespace()
                    if (cursor.consume(TRANSITION)) {
                        val transition = when (val parsed = cursor.parseTransition(domain)) {
                            is GMResult.Ok -> parsed.value
                            is GMResult.Err -> return parsed
                        }
                        transitions += transition
                        if (transitions.size > options.maxEdges) {
                            return GMResult.Err(
                                MermaidError.ResourceLimit(
                                    resource = "Cynefin transitions",
                                    actual = transitions.size,
                                    maximum = options.maxEdges,
                                ),
                            )
                        }
                    } else {
                        val items = mutableListOf<String>()
                        while (true) {
                            cursor.skipTrivia()
                            if (!cursor.isStringStart()) {
                                break
                            }
                            when (val parsed = cursor.parseString()) {
                                is GMResult.Ok -> items += parsed.value
                                is GMResult.Err -> return parsed
                            }
                        }
                        domains += CynefinAstDomainBlock(
                            domain = domain,
                            items = items,
                        )
                    }
                }
            }
        }

        val db = CynefinDb(
            config = options.cynefin,
            diagramTitle = diagramTitle,
            sourceSeedIdentity = normalized,
        )
        accessibilityDescription?.takeIf(String::isNotEmpty)
            ?.let(db::setAccessibilityDescription)
        accessibilityTitle?.takeIf(String::isNotEmpty)
            ?.let(db::setAccessibilityTitle)
        title?.takeIf(String::isNotEmpty)?.let(db::setDiagramTitle)
        db.setDomains(domains)
        db.setTransitions(transitions)
        return GMResult.Ok(db)
    }

    private class Cursor(
        private val source: String,
        private val lineOffset: Int,
    ) {
        private var index: Int = 0

        fun isAtEnd(): Boolean = index >= source.length

        fun isStringStart(): Boolean =
            source.getOrNull(index) == '"' || source.getOrNull(index) == '\''

        fun parseHeader(): GMResult<Unit, MermaidError> {
            if (!source.startsWith(HEADER, index)) {
                return error("Expected 'cynefin-beta' diagram header")
            }
            index += HEADER.length
            if (source.getOrNull(index) == ':') {
                index += 1
                return GMResult.Ok(Unit)
            }
            val next = source.getOrNull(index)
            if (next != null && !next.isWhitespace()) {
                return error("Expected 'cynefin-beta' diagram header")
            }
            return GMResult.Ok(Unit)
        }

        fun hasKeyword(keyword: String): Boolean {
            if (!source.startsWith(keyword, index)) {
                return false
            }
            val next = source.getOrNull(index + keyword.length)
            return next == null || !next.isIdentifierPart()
        }

        fun parseInlineMetadata(keyword: String): String {
            index += keyword.length
            skipHorizontalWhitespace()
            return readLineValue()
        }

        fun parseColonMetadata(keyword: String): GMResult<String, MermaidError> {
            index += keyword.length
            skipHorizontalWhitespace()
            if (!consume(":")) {
                return error("Expected ':' after $keyword")
            }
            skipHorizontalWhitespace()
            return GMResult.Ok(readLineValue())
        }

        fun parseAccessibilityDescription(): GMResult<String, MermaidError> {
            index += ACCESSIBILITY_DESCRIPTION_KEYWORD.length
            skipHorizontalWhitespace()
            if (consume(":")) {
                skipHorizontalWhitespace()
                return GMResult.Ok(readLineValue())
            }
            skipWhitespace()
            if (!consume("{")) {
                return error("Expected ':' or '{' after accDescr")
            }
            val opening = index - 1
            val end = source.indexOf('}', startIndex = index)
            if (end < 0) {
                return errorAt(opening, "Unterminated accDescr block")
            }
            val value = normalizeMultiline(source.substring(index, end))
            index = end + 1
            skipHorizontalWhitespace()
            if (!isAtEnd() && source.getOrNull(index) != '\n' && !source.startsWith("%%", index)) {
                return error("Unexpected content after accDescr block")
            }
            return GMResult.Ok(value)
        }

        fun parseDomainName(): GMResult<CynefinDomainName, MermaidError> {
            val domain = CynefinDomainName.values().firstOrNull { candidate ->
                source.startsWith(candidate.sourceName, index) &&
                    source.getOrNull(index + candidate.sourceName.length)
                        ?.isIdentifierPart() != true
            } ?: return error("Expected a Cynefin domain or metadata statement")
            index += domain.sourceName.length
            return GMResult.Ok(domain)
        }

        fun parseTransition(
            from: CynefinDomainName,
        ): GMResult<CynefinAstTransition, MermaidError> {
            skipHorizontalWhitespace()
            val to = when (val parsed = parseDomainName()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            skipHorizontalWhitespace()
            val label = if (consume(":")) {
                skipHorizontalWhitespace()
                when (val parsed = parseString()) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
            } else {
                null
            }
            skipHorizontalWhitespace()
            if (source.startsWith(COMMENT_PREFIX, index)) {
                skipComment()
            }
            if (!isAtEnd() && source.getOrNull(index) != '\n') {
                return error("Expected end of line after Cynefin transition")
            }
            return GMResult.Ok(
                CynefinAstTransition(
                    from = from,
                    to = to,
                    label = label,
                ),
            )
        }

        fun parseString(): GMResult<String, MermaidError> {
            val quote = source.getOrNull(index)
            if (quote != '"' && quote != '\'') {
                return error("Expected a quoted Cynefin item label")
            }
            val opening = index
            index += 1
            val value = StringBuilder()
            while (!isAtEnd()) {
                val character = source[index]
                index += 1
                when {
                    character == quote -> return GMResult.Ok(value.toString())
                    character == '\\' -> {
                        val escaped = source.getOrNull(index)
                            ?: return errorAt(opening, "Unterminated quoted label")
                        index += 1
                        value.append(
                            when (escaped) {
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'v' -> '\u000B'
                                '0' -> '\u0000'
                                else -> escaped
                            },
                        )
                    }
                    character == '\n' -> {
                        return errorAt(opening, "Quoted labels cannot contain a newline")
                    }
                    else -> value.append(character)
                }
            }
            return errorAt(opening, "Unterminated quoted label")
        }

        fun consume(token: String): Boolean {
            if (!source.startsWith(token, index)) {
                return false
            }
            index += token.length
            return true
        }

        fun skipHorizontalWhitespace() {
            while (source.getOrNull(index) == ' ' || source.getOrNull(index) == '\t') {
                index += 1
            }
        }

        fun skipTrivia() {
            while (true) {
                skipWhitespace()
                if (!source.startsWith(COMMENT_PREFIX, index)) {
                    return
                }
                skipComment()
            }
        }

        fun error(detail: String): GMResult.Err<MermaidError> =
            errorAt(index, detail)

        private fun readLineValue(): String {
            val endOfLine = source.indexOf('\n', startIndex = index)
                .let { found -> if (found < 0) source.length else found }
            val comment = source.indexOf(COMMENT_PREFIX, startIndex = index)
                .takeIf { found -> found in index until endOfLine }
                ?: endOfLine
            val value = normalizeInline(source.substring(index, comment))
            index = endOfLine
            return value
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) {
                index += 1
            }
        }

        private fun skipComment() {
            val lineEnd = source.indexOf('\n', startIndex = index)
            index = if (lineEnd < 0) source.length else lineEnd + 1
        }

        private fun errorAt(
            location: Int,
            detail: String,
        ): GMResult.Err<MermaidError> {
            val line = lineOffset + source.take(location).count { character ->
                character == '\n'
            } + 1
            val previousNewline = if (location <= 0) {
                -1
            } else {
                source.lastIndexOf('\n', startIndex = location - 1)
            }
            val column = location - previousNewline
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Parse error on line $line, column $column: $detail",
                ),
            )
        }
    }

    private companion object {
        const val HEADER = "cynefin-beta"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        const val TRANSITION = "-->"
        const val COMMENT_PREFIX = "%%"

        fun Char.isIdentifierPart(): Boolean =
            this == '_' || this == '-' || this in 'A'..'Z' ||
                this in 'a'..'z' || this in '0'..'9'

        fun normalizeInline(value: String): String =
            value.trim().replace(Regex("""[\t ]{2,}"""), " ")

        fun normalizeMultiline(value: String): String =
            value
                .lineSequence()
                .map { line ->
                    line.trimStart().trimEnd().replace(Regex("""[\t ]{2,}"""), " ")
                }
                .dropWhile(String::isEmpty)
                .toList()
                .dropLastWhile(String::isEmpty)
                .joinToString("\n")
                .replace(Regex("""[\n\r]{2,}"""), "\n")
    }
}
