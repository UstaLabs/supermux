package com.swithun.cmpmermaid.core.railroad.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRailroadOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/railroad, railroad-ebnf, railroad-abnf, railroad-peg;
 * packages/mermaid/src/diagrams/railroad/parser parser implementations.
 */
internal class RailroadParser(
    private val config: MermaidRailroadOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<RailroadDb, MermaidError> {
        val cursor = Cursor(
            source = source.replace("\r\n", "\n").replace('\r', '\n'),
            lineOffset = lineOffset,
        )
        val notation = when (val result = cursor.parseHeader()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val document = when (val result = cursor.parseDocument(notation)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val db = RailroadDb(config = config, diagramTitle = diagramTitle)
        document.title?.let { title ->
            when (val result = db.setDiagramTitle(title)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        document.accessibilityTitle?.let { title ->
            when (val result = db.setAccessibilityTitle(title)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        document.accessibilityDescription?.let { description ->
            when (val result = db.setAccessibilityDescription(description)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        document.rules.forEach { rule ->
            when (val result = db.addRule(rule)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(db)
    }

    private data class RailroadDocument(
        val title: String?,
        val accessibilityTitle: String?,
        val accessibilityDescription: String?,
        val rules: List<RailroadRule>,
    )

    private class Cursor(
        private val source: String,
        private val lineOffset: Int,
    ) {
        private var index: Int = 0
        private lateinit var notation: RailroadNotation

        fun parseHeader(): GMResult<RailroadNotation, MermaidError> {
            skipWhitespace()
            val start = index
            while (source.getOrNull(index)?.let(::isHeaderCharacter) == true) {
                index += 1
            }
            val header = source.substring(start, index)
            val parsed = RailroadNotation.fromHeader(header)
                ?: return errorAt(
                    start,
                    "Expected a Railroad diagram header",
                )
            val next = source.getOrNull(index)
            if (next != null && !next.isWhitespace() && !source.startsWith("%%", index)) {
                return errorAt(start, "Expected a Railroad diagram header")
            }
            notation = parsed
            return GMResult.Ok(parsed)
        }

        fun parseDocument(
            notation: RailroadNotation,
        ): GMResult<RailroadDocument, MermaidError> {
            this.notation = notation
            var title: String? = null
            var accessibilityTitle: String? = null
            var accessibilityDescription: String? = null
            val rules = mutableListOf<RailroadRule>()

            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (isAtEnd()) {
                    break
                }
                when {
                    rules.isEmpty() && hasKeyword(TITLE_KEYWORD) -> {
                        title = parseTitle()
                    }
                    rules.isEmpty() && hasKeyword(ACCESSIBILITY_TITLE_KEYWORD) -> {
                        when (val parsed = parseAccessibilityTitle()) {
                            is GMResult.Ok -> accessibilityTitle = parsed.value
                            is GMResult.Err -> return parsed
                        }
                    }
                    rules.isEmpty() && hasKeyword(ACCESSIBILITY_DESCRIPTION_KEYWORD) -> {
                        when (val parsed = parseAccessibilityDescription()) {
                            is GMResult.Ok -> accessibilityDescription = parsed.value
                            is GMResult.Err -> return parsed
                        }
                    }
                    else -> {
                        when (val rule = parseRule(notation)) {
                            is GMResult.Ok -> rules += rule.value
                            is GMResult.Err -> return rule
                        }
                    }
                }
            }
            return GMResult.Ok(
                RailroadDocument(
                    title = title,
                    accessibilityTitle = accessibilityTitle,
                    accessibilityDescription = accessibilityDescription,
                    rules = rules,
                ),
            )
        }

        private fun parseRule(
            notation: RailroadNotation,
        ): GMResult<RailroadRule, MermaidError> {
            val name = when (
                val parsed = parseIdentifier(
                    abnf = notation == RailroadNotation.Abnf,
                    message = "Expected a Railroad rule name",
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            val assignment = when (notation) {
                RailroadNotation.Peg -> "<-"
                RailroadNotation.Ebnf -> when {
                    source.startsWith("::=", index) -> "::="
                    else -> "="
                }
                RailroadNotation.Ir,
                RailroadNotation.Abnf,
                -> "="
            }
            when (val expected = expect(assignment, "Expected '$assignment' after rule name")) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return expected
            }
            val definition = when (notation) {
                RailroadNotation.Ir -> parseIrExpression()
                RailroadNotation.Ebnf -> parseEbnfChoice()
                RailroadNotation.Abnf -> parseAbnfAlternation()
                RailroadNotation.Peg -> parsePegOrderedChoice()
            }
            val node = when (definition) {
                is GMResult.Ok -> definition.value
                is GMResult.Err -> return definition
            }
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            when (val terminator = expect(";", "Expected ';' after Railroad rule")) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return terminator
            }
            return GMResult.Ok(RailroadRule(name = name, definition = node))
        }

        private fun parseIrExpression(): GMResult<RailroadAstNode, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            val constructor = when (
                val parsed = parseIdentifier(
                    abnf = false,
                    message = "Expected a Railroad expression",
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            when (val opening = expectAfterTrivia("(", "Expected '(' after '$constructor'")) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return opening
            }
            return when (constructor) {
                "terminal", "nonterminal", "special" -> {
                    val value = when (val parsed = parseString(allowSingleQuote = true)) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    when (val closing = expectAfterTrivia(")", "Expected ')' after '$constructor'")) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return closing
                    }
                    GMResult.Ok(
                        when (constructor) {
                            "terminal" -> RailroadAstNode.Terminal(value)
                            "nonterminal" -> RailroadAstNode.NonTerminal(value)
                            else -> RailroadAstNode.Special(value)
                        },
                    )
                }
                "optional", "oneOrMore", "zeroOrMore" -> {
                    val child = when (val parsed = parseIrExpression()) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    when (val closing = expectAfterTrivia(")", "Expected ')' after '$constructor'")) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return closing
                    }
                    GMResult.Ok(
                        when (constructor) {
                            "optional" -> RailroadAstNode.Optional(child)
                            "oneOrMore" -> RailroadAstNode.Repetition(child, min = 1, max = null)
                            else -> RailroadAstNode.Repetition(child, min = 0, max = null)
                        },
                    )
                }
                "sequence", "choice" -> {
                    val children = mutableListOf<RailroadAstNode>()
                    while (true) {
                        when (val child = parseIrExpression()) {
                            is GMResult.Ok -> children += child.value
                            is GMResult.Err -> return child
                        }
                        when (val skipped = skipTrivia()) {
                            is GMResult.Ok -> Unit
                            is GMResult.Err -> return skipped
                        }
                        if (source.getOrNull(index) != ',') {
                            break
                        }
                        index += 1
                    }
                    when (val closing = expectAfterTrivia(")", "Expected ')' after '$constructor'")) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return closing
                    }
                    GMResult.Ok(
                        collapse(
                            nodes = children,
                            sequence = constructor == "sequence",
                        ),
                    )
                }
                else -> errorAt(
                    index - constructor.length,
                    "Unsupported Railroad expression '$constructor'",
                )
            }
        }

        private fun parseEbnfChoice(): GMResult<RailroadAstNode, MermaidError> {
            val alternatives = mutableListOf<RailroadAstNode>()
            when (val first = parseEbnfSequence()) {
                is GMResult.Ok -> alternatives += first.value
                is GMResult.Err -> return first
            }
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (source.getOrNull(index) != '|') {
                    break
                }
                index += 1
                when (val alternative = parseEbnfSequence()) {
                    is GMResult.Ok -> alternatives += alternative.value
                    is GMResult.Err -> return alternative
                }
            }
            return GMResult.Ok(collapse(alternatives, sequence = false))
        }

        private fun parseEbnfSequence(): GMResult<RailroadAstNode, MermaidError> {
            val elements = mutableListOf<RailroadAstNode>()
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (source.getOrNull(index) == ',') {
                    if (elements.isEmpty()) {
                        return error("Expected an EBNF expression before ','")
                    }
                    index += 1
                    when (val skipped = skipTrivia()) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return skipped
                    }
                }
                if (!startsEbnfPrimary()) {
                    break
                }
                when (val term = parseEbnfTerm()) {
                    is GMResult.Ok -> elements += term.value
                    is GMResult.Err -> return term
                }
            }
            if (elements.isEmpty()) {
                return error("Expected an EBNF expression")
            }
            return GMResult.Ok(collapse(elements, sequence = true))
        }

        private fun parseEbnfTerm(): GMResult<RailroadAstNode, MermaidError> {
            var node = when (val primary = parseEbnfPrimary()) {
                is GMResult.Ok -> primary.value
                is GMResult.Err -> return primary
            }
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                node = when {
                    source.getOrNull(index) == '?' && !looksLikeEbnfSpecial() -> {
                        index += 1
                        RailroadAstNode.Optional(node)
                    }
                    source.getOrNull(index) == '*' -> {
                        index += 1
                        RailroadAstNode.Repetition(node, min = 0, max = null)
                    }
                    source.getOrNull(index) == '+' -> {
                        index += 1
                        RailroadAstNode.Repetition(node, min = 1, max = null)
                    }
                    source.getOrNull(index) == '-' -> {
                        index += 1
                        val exception = when (val parsed = parseEbnfPrimary()) {
                            is GMResult.Ok -> parsed.value
                            is GMResult.Err -> return parsed
                        }
                        RailroadAstNode.Sequence(
                            listOf(node, RailroadAstNode.Terminal("-"), exception),
                        )
                    }
                    else -> return GMResult.Ok(node)
                }
            }
        }

        private fun parseEbnfPrimary(): GMResult<RailroadAstNode, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            return when (source.getOrNull(index)) {
                '"', '\'' -> parseString(allowSingleQuote = true).map(RailroadAstNode::Terminal)
                '(' -> parseDelimitedEbnf('(', ')') { it }
                '[' -> parseDelimitedEbnf('[', ']') { RailroadAstNode.Optional(it) }
                '{' -> parseDelimitedEbnf('{', '}') {
                    RailroadAstNode.Repetition(it, min = 0, max = null)
                }
                '?' -> parseEbnfSpecial()
                else -> parseIdentifier(
                    abnf = false,
                    message = "Expected an EBNF primary expression",
                ).map(RailroadAstNode::NonTerminal)
            }
        }

        private fun parseDelimitedEbnf(
            opening: Char,
            closing: Char,
            transform: (RailroadAstNode) -> RailroadAstNode,
        ): GMResult<RailroadAstNode, MermaidError> {
            index += 1
            val inner = when (val parsed = parseEbnfChoice()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            when (val end = expectAfterTrivia("$closing", "Expected '$closing'")) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return end
            }
            return GMResult.Ok(transform(inner))
        }

        private fun parseEbnfSpecial(): GMResult<RailroadAstNode, MermaidError> {
            val opening = index
            index += 1
            val close = source.indexOf('?', startIndex = index)
            if (close < 0 || source.substring(index, close).isBlank()) {
                return errorAt(opening, "Unterminated EBNF special sequence")
            }
            val text = source.substring(index, close).trim()
            index = close + 1
            return GMResult.Ok(RailroadAstNode.Special(text))
        }

        private fun parseAbnfAlternation(): GMResult<RailroadAstNode, MermaidError> {
            val alternatives = mutableListOf<RailroadAstNode>()
            when (val first = parseAbnfConcatenation()) {
                is GMResult.Ok -> alternatives += first.value
                is GMResult.Err -> return first
            }
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (source.getOrNull(index) != '/') {
                    break
                }
                index += 1
                when (val alternative = parseAbnfConcatenation()) {
                    is GMResult.Ok -> alternatives += alternative.value
                    is GMResult.Err -> return alternative
                }
            }
            return GMResult.Ok(collapse(alternatives, sequence = false))
        }

        private fun parseAbnfConcatenation(): GMResult<RailroadAstNode, MermaidError> {
            val elements = mutableListOf<RailroadAstNode>()
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (!startsAbnfElement()) {
                    break
                }
                when (val element = parseAbnfElement()) {
                    is GMResult.Ok -> elements += element.value
                    is GMResult.Err -> return element
                }
            }
            if (elements.isEmpty()) {
                return error("Expected an ABNF expression")
            }
            return GMResult.Ok(collapse(elements, sequence = true))
        }

        private fun parseAbnfElement(): GMResult<RailroadAstNode, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            val repeat = when (val parsed = parseAbnfRepeat()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val inner = when (val parsed = parseAbnfPrimary()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val bounds = repeat ?: return GMResult.Ok(inner)
            if (bounds.first == 0 && bounds.second == 1) {
                return GMResult.Ok(RailroadAstNode.Optional(inner))
            }
            return GMResult.Ok(
                RailroadAstNode.Repetition(
                    element = inner,
                    min = bounds.first,
                    max = bounds.second,
                ),
            )
        }

        private fun parseAbnfRepeat(): GMResult<Pair<Int, Int?>?, MermaidError> {
            val start = index
            while (source.getOrNull(index)?.isDigit() == true) {
                index += 1
            }
            val leading = source.substring(start, index)
            if (source.getOrNull(index) == '*') {
                index += 1
                val maximumStart = index
                while (source.getOrNull(index)?.isDigit() == true) {
                    index += 1
                }
                val trailing = source.substring(maximumStart, index)
                val minimum = when (val value = parseRepeatNumber(leading, 0, start)) {
                    is GMResult.Ok -> value.value
                    is GMResult.Err -> return value
                }
                val maximum = if (trailing.isEmpty()) {
                    null
                } else {
                    when (val value = parseRepeatNumber(trailing, 0, maximumStart)) {
                        is GMResult.Ok -> value.value
                        is GMResult.Err -> return value
                    }
                }
                return GMResult.Ok(minimum to maximum)
            }
            if (leading.isEmpty()) {
                return GMResult.Ok(null)
            }
            val exact = when (val value = parseRepeatNumber(leading, 0, start)) {
                is GMResult.Ok -> value.value
                is GMResult.Err -> return value
            }
            return GMResult.Ok(exact to exact)
        }

        private fun parseRepeatNumber(
            sourceValue: String,
            emptyDefault: Int,
            location: Int,
        ): GMResult<Int, MermaidError> {
            if (sourceValue.isEmpty()) {
                return GMResult.Ok(emptyDefault)
            }
            val value = sourceValue.toIntOrNull()
                ?: return resourceErrorAt(
                    location = location,
                    resource = "Railroad repetition",
                    message = "Railroad repetition exceeds the supported integer range",
                )
            return GMResult.Ok(value)
        }

        private fun parseAbnfPrimary(): GMResult<RailroadAstNode, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            return when (source.getOrNull(index)) {
                '"' -> parseString(allowSingleQuote = false).map(RailroadAstNode::Terminal)
                '%' -> parseAbnfNumericValue().map(RailroadAstNode::Terminal)
                '(' -> parseDelimitedAbnf('(', ')') { it }
                '[' -> parseDelimitedAbnf('[', ']') { RailroadAstNode.Optional(it) }
                else -> parseIdentifier(
                    abnf = true,
                    message = "Expected an ABNF primary expression",
                ).map(RailroadAstNode::NonTerminal)
            }
        }

        private fun parseDelimitedAbnf(
            opening: Char,
            closing: Char,
            transform: (RailroadAstNode) -> RailroadAstNode,
        ): GMResult<RailroadAstNode, MermaidError> {
            index += 1
            val inner = when (val parsed = parseAbnfAlternation()) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            when (val end = expectAfterTrivia("$closing", "Expected '$closing'")) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return end
            }
            return GMResult.Ok(transform(inner))
        }

        private fun parseAbnfNumericValue(): GMResult<String, MermaidError> {
            val start = index
            index += 1
            val base = source.getOrNull(index)
            if (base == null || base.lowercaseChar() !in setOf('x', 'd', 'b')) {
                return errorAt(start, "Expected an ABNF numeric value")
            }
            index += 1
            val digitsStart = index
            while (source.getOrNull(index)?.isHexDigit() == true) {
                index += 1
            }
            if (index == digitsStart) {
                return errorAt(start, "Expected digits in ABNF numeric value")
            }
            while (source.getOrNull(index) == '-' || source.getOrNull(index) == '.') {
                index += 1
                val componentStart = index
                while (source.getOrNull(index)?.isHexDigit() == true) {
                    index += 1
                }
                if (index == componentStart) {
                    return errorAt(start, "Expected digits in ABNF numeric value")
                }
            }
            return GMResult.Ok(source.substring(start, index))
        }

        private fun parsePegOrderedChoice(): GMResult<RailroadAstNode, MermaidError> {
            val alternatives = mutableListOf<RailroadAstNode>()
            when (val first = parsePegSequence()) {
                is GMResult.Ok -> alternatives += first.value
                is GMResult.Err -> return first
            }
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (source.getOrNull(index) != '/') {
                    break
                }
                index += 1
                when (val alternative = parsePegSequence()) {
                    is GMResult.Ok -> alternatives += alternative.value
                    is GMResult.Err -> return alternative
                }
            }
            return GMResult.Ok(collapse(alternatives, sequence = false))
        }

        private fun parsePegSequence(): GMResult<RailroadAstNode, MermaidError> {
            val elements = mutableListOf<RailroadAstNode>()
            while (true) {
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
                if (!startsPegPrimary()) {
                    break
                }
                when (val element = parsePegPrefix()) {
                    is GMResult.Ok -> elements += element.value
                    is GMResult.Err -> return element
                }
            }
            if (elements.isEmpty()) {
                return error("Expected a PEG expression")
            }
            return GMResult.Ok(collapse(elements, sequence = true))
        }

        private fun parsePegPrefix(): GMResult<RailroadAstNode, MermaidError> {
            val prefix = source.getOrNull(index).takeIf { character ->
                character == '&' || character == '!'
            }
            if (prefix != null) {
                index += 1
                when (val skipped = skipTrivia()) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return skipped
                }
            }
            var node = when (val primary = parsePegPrimary()) {
                is GMResult.Ok -> primary.value
                is GMResult.Err -> return primary
            }
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            node = when (source.getOrNull(index)) {
                '?' -> RailroadAstNode.Optional(node).also { index += 1 }
                '*' -> RailroadAstNode.Repetition(node, min = 0, max = null).also { index += 1 }
                '+' -> RailroadAstNode.Repetition(node, min = 1, max = null).also { index += 1 }
                else -> node
            }
            if (prefix != null) {
                node = RailroadAstNode.Special("$prefix${node.toPegLabel()}")
            }
            return GMResult.Ok(node)
        }

        private fun parsePegPrimary(): GMResult<RailroadAstNode, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            return when (source.getOrNull(index)) {
                '"', '\'' -> parseString(allowSingleQuote = true).map(RailroadAstNode::Terminal)
                '(' -> {
                    index += 1
                    val inner = when (val parsed = parsePegOrderedChoice()) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    when (val end = expectAfterTrivia(")", "Expected ')'")) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return end
                    }
                    GMResult.Ok(inner)
                }
                '.' -> {
                    index += 1
                    GMResult.Ok(RailroadAstNode.Special("."))
                }
                else -> parseIdentifier(
                    abnf = false,
                    message = "Expected a PEG primary expression",
                ).map(RailroadAstNode::NonTerminal)
            }
        }

        private fun parseTitle(): String {
            index += TITLE_KEYWORD.length
            skipHorizontalWhitespace()
            val raw = readLineValue()
            return decodeTitle(raw)
        }

        private fun parseAccessibilityTitle(): GMResult<String, MermaidError> {
            index += ACCESSIBILITY_TITLE_KEYWORD.length
            skipHorizontalWhitespace()
            if (source.getOrNull(index) != ':') {
                return error("Expected ':' after accTitle")
            }
            index += 1
            skipHorizontalWhitespace()
            return GMResult.Ok(normalizeInline(readLineValue()))
        }

        private fun parseAccessibilityDescription(): GMResult<String, MermaidError> {
            index += ACCESSIBILITY_DESCRIPTION_KEYWORD.length
            skipHorizontalWhitespace()
            if (source.getOrNull(index) == ':') {
                index += 1
                skipHorizontalWhitespace()
                return GMResult.Ok(normalizeInline(readLineValue()))
            }
            skipWhitespace()
            if (source.getOrNull(index) != '{') {
                return error("Expected ':' or '{' after accDescr")
            }
            val opening = index
            index += 1
            val close = source.indexOf('}', startIndex = index)
            if (close < 0) {
                return errorAt(opening, "Unterminated accDescr block")
            }
            val value = normalizeMultiline(source.substring(index, close))
            index = close + 1
            skipHorizontalWhitespace()
            if (
                !isAtEnd() &&
                source.getOrNull(index) != '\n' &&
                !source.startsWith("%%", index)
            ) {
                return error("Unexpected content after accDescr block")
            }
            return GMResult.Ok(value)
        }

        private fun parseString(
            allowSingleQuote: Boolean,
        ): GMResult<String, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            val quote = source.getOrNull(index)
            if (quote != '"' && !(allowSingleQuote && quote == '\'')) {
                return error("Expected a quoted Railroad string")
            }
            val opening = index
            index += 1
            val value = StringBuilder()
            while (!isAtEnd()) {
                val character = source[index]
                index += 1
                when {
                    character == quote -> return GMResult.Ok(value.toString())
                    character == '\n' -> return errorAt(
                        opening,
                        "Railroad strings cannot contain a newline",
                    )
                    character == '\\' && notation != RailroadNotation.Abnf -> {
                        val escaped = source.getOrNull(index)
                            ?: return errorAt(opening, "Unterminated Railroad string")
                        index += 1
                        value.append(
                            when (escaped) {
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
            return errorAt(opening, "Unterminated Railroad string")
        }

        private fun parseIdentifier(
            abnf: Boolean,
            message: String,
        ): GMResult<String, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            val start = index
            val first = source.getOrNull(index)
            val validStart = if (abnf) {
                first?.isAsciiLetter() == true
            } else {
                first?.isRailroadIdentifierStart() == true
            }
            if (!validStart) {
                return error(message)
            }
            index += 1
            while (
                source.getOrNull(index)?.let { character ->
                    if (abnf) {
                        character.isAsciiLetter() || character.isDigit() || character == '-'
                    } else {
                        character.isRailroadIdentifierPart()
                    }
                } == true
            ) {
                index += 1
            }
            return GMResult.Ok(source.substring(start, index))
        }

        private fun expectAfterTrivia(
            token: String,
            message: String,
        ): GMResult<Unit, MermaidError> {
            when (val skipped = skipTrivia()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return skipped
            }
            return expect(token, message)
        }

        private fun expect(
            token: String,
            message: String,
        ): GMResult<Unit, MermaidError> {
            if (!source.startsWith(token, index)) {
                return error(message)
            }
            index += token.length
            return GMResult.Ok(Unit)
        }

        private fun skipTrivia(): GMResult<Unit, MermaidError> {
            while (true) {
                skipWhitespace()
                when {
                    source.startsWith("%%", index) -> skipLine()
                    notation == RailroadNotation.Peg && source.getOrNull(index) == '#' ->
                        skipLine()
                    notation in setOf(RailroadNotation.Ir, RailroadNotation.Ebnf) &&
                        source.startsWith("/*", index) -> {
                        val opening = index
                        val close = source.indexOf("*/", startIndex = index + 2)
                        if (close < 0) {
                            return errorAt(opening, "Unterminated Railroad block comment")
                        }
                        index = close + 2
                    }
                    notation == RailroadNotation.Ebnf && source.startsWith("(*", index) -> {
                        val opening = index
                        val close = source.indexOf("*)", startIndex = index + 2)
                        if (close < 0) {
                            return errorAt(opening, "Unterminated EBNF comment")
                        }
                        index = close + 2
                    }
                    else -> return GMResult.Ok(Unit)
                }
            }
        }

        private fun startsEbnfPrimary(): Boolean {
            val character = source.getOrNull(index)
            return character == '"' ||
                character == '\'' ||
                character == '(' ||
                character == '[' ||
                character == '{' ||
                looksLikeEbnfSpecial() ||
                character?.isRailroadIdentifierStart() == true
        }

        private fun looksLikeEbnfSpecial(): Boolean {
            if (source.getOrNull(index) != '?') {
                return false
            }
            val close = source.indexOf('?', startIndex = index + 1)
            return close > index + 1 &&
                ';' !in source.substring(index + 1, close) &&
                source.substring(index + 1, close).isNotBlank()
        }

        private fun startsAbnfElement(): Boolean {
            val character = source.getOrNull(index)
            return character == '*' ||
                character == '"' ||
                character == '%' ||
                character == '(' ||
                character == '[' ||
                character?.isDigit() == true ||
                character?.isAsciiLetter() == true
        }

        private fun startsPegPrimary(): Boolean {
            val character = source.getOrNull(index)
            return character == '&' ||
                character == '!' ||
                character == '"' ||
                character == '\'' ||
                character == '(' ||
                character == '.' ||
                character?.isRailroadIdentifierStart() == true
        }

        private fun hasKeyword(keyword: String): Boolean {
            if (!source.startsWith(keyword, index)) {
                return false
            }
            val next = source.getOrNull(index + keyword.length)
            return next == null || !next.isRailroadIdentifierPart()
        }

        private fun readLineValue(): String {
            val lineEnd = source.indexOf('\n', startIndex = index)
                .let { found -> if (found < 0) source.length else found }
            val comment = source.indexOf("%%", startIndex = index)
                .takeIf { found -> found in index until lineEnd }
                ?: lineEnd
            val value = normalizeInline(source.substring(index, comment))
            index = lineEnd
            return value
        }

        private fun decodeTitle(source: String): String {
            val normalized = normalizeInline(source)
            val quote = normalized.firstOrNull()
            return if (
                normalized.length >= 2 &&
                (quote == '"' || quote == '\'') &&
                normalized.last() == quote
            ) {
                decodeEscaped(normalized.substring(1, normalized.lastIndex))
            } else {
                normalized
            }
        }

        private fun decodeEscaped(source: String): String {
            val result = StringBuilder()
            var cursor = 0
            while (cursor < source.length) {
                val character = source[cursor]
                if (character == '\\' && cursor + 1 < source.length) {
                    val escaped = source[cursor + 1]
                    result.append(
                        when (escaped) {
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            else -> escaped
                        },
                    )
                    cursor += 2
                } else {
                    result.append(character)
                    cursor += 1
                }
            }
            return result.toString()
        }

        private fun skipLine() {
            val lineEnd = source.indexOf('\n', startIndex = index)
            index = if (lineEnd < 0) source.length else lineEnd + 1
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) {
                index += 1
            }
        }

        private fun skipHorizontalWhitespace() {
            while (source.getOrNull(index) == ' ' || source.getOrNull(index) == '\t') {
                index += 1
            }
        }

        private fun isAtEnd(): Boolean = index >= source.length

        private fun <T> error(message: String): GMResult<T, MermaidError> =
            errorAt(index, message)

        private fun <T> errorAt(
            location: Int,
            detail: String,
        ): GMResult<T, MermaidError> {
            val line = lineOffset + source.take(location).count { character ->
                character == '\n'
            } + 1
            val previousNewline = source.lastIndexOf(
                '\n',
                startIndex = (location - 1).coerceAtLeast(0),
            )
            val column = location - previousNewline
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Parse error on line $line, column $column: $detail",
                ),
            )
        }

        private fun <T> resourceErrorAt(
            location: Int,
            resource: String,
            message: String,
        ): GMResult<T, MermaidError> = GMResult.Err(
            MermaidError.ResourceLimit(
                resource = resource,
                actual = Int.MAX_VALUE,
                maximum = Int.MAX_VALUE,
                message = "${messageAt(location)}: $message",
            ),
        )

        private fun messageAt(location: Int): String {
            val line = lineOffset + source.take(location).count { character ->
                character == '\n'
            } + 1
            val previousNewline = source.lastIndexOf(
                '\n',
                startIndex = (location - 1).coerceAtLeast(0),
            )
            return "Parse error on line $line, column ${location - previousNewline}"
        }

        private fun collapse(
            nodes: List<RailroadAstNode>,
            sequence: Boolean,
        ): RailroadAstNode = if (nodes.size == 1) {
            nodes.first()
        } else if (sequence) {
            RailroadAstNode.Sequence(nodes)
        } else {
            RailroadAstNode.Choice(nodes)
        }

        private fun RailroadAstNode.toPegLabel(): String = when (this) {
            is RailroadAstNode.Terminal -> "\"$value\""
            is RailroadAstNode.NonTerminal -> name
            is RailroadAstNode.Special -> text
            else -> "(...)"
        }

        private inline fun <T, R> GMResult<T, MermaidError>.map(
            transform: (T) -> R,
        ): GMResult<R, MermaidError> = when (this) {
            is GMResult.Ok -> GMResult.Ok(transform(value))
            is GMResult.Err -> this
        }

        private fun Char.isAsciiLetter(): Boolean =
            this in 'A'..'Z' || this in 'a'..'z'

        private fun Char.isRailroadIdentifierStart(): Boolean =
            isAsciiLetter() || this == '_'

        private fun Char.isRailroadIdentifierPart(): Boolean =
            isRailroadIdentifierStart() || isDigit() || this == '-'

        private fun Char.isHexDigit(): Boolean =
            isDigit() || this in 'A'..'F' || this in 'a'..'f'

        private companion object {
            const val TITLE_KEYWORD = "title"
            const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
            const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"

            fun isHeaderCharacter(character: Char): Boolean =
                character.isLetter() || character == '-'

            fun normalizeInline(value: String): String =
                value.trim().replace(Regex("""[\t ]{2,}"""), " ")

            fun normalizeMultiline(value: String): String =
                value
                    .lineSequence()
                    .map { line ->
                        line.trim().replace(Regex("""[\t ]{2,}"""), " ")
                    }
                    .dropWhile(String::isEmpty)
                    .toList()
                    .dropLastWhile(String::isEmpty)
                    .joinToString("\n")
                    .replace(Regex("""[\n\r]{2,}"""), "\n")
        }
    }
}
