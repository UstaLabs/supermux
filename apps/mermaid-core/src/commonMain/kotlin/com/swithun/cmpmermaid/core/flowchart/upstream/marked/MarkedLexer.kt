package com.swithun.cmpmermaid.core.flowchart.upstream.marked

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * The token fields consumed by Mermaid 12.0.0 handle-markdown-text.ts.
 *
 * This is intentionally a projection of Marked's token model rather than a
 * second Markdown AST. Unsupported token types retain their upstream `raw`
 * value because that is exactly what Mermaid's renderer consumes.
 */
internal data class MarkedToken(
    val type: MarkedTokenType,
    var raw: String,
    var text: String = "",
    val tokens: MutableList<MarkedToken> = mutableListOf(),
)

internal enum class MarkedTokenType(val sourceName: String) {
    Space("space"),
    Code("code"),
    Heading("heading"),
    HorizontalRule("hr"),
    Blockquote("blockquote"),
    List("list"),
    Html("html"),
    Definition("def"),
    Table("table"),
    Paragraph("paragraph"),
    Text("text"),
    Escape("escape"),
    Link("link"),
    Image("image"),
    Strong("strong"),
    Emphasis("em"),
    CodeSpan("codespan"),
    Break("br"),
    Delete("del"),
}

internal data class MarkedRegexSource(
    val source: String,
    val ignoreCase: Boolean,
)

/**
 * Kotlin translation of Marked 16.4.2 Tokenizer.ts and Lexer.ts using the
 * generated upstream regex table in [MarkedGeneratedRules].
 *
 * Marked extensions and parser/renderer fields that Mermaid never configures
 * or reads are deliberately not projected here.
 */
internal object MarkedLexer {
    fun lex(source: String): GMResult<List<MarkedToken>, MermaidError> = when (val rules = COMPILED_RULES) {
        is GMResult.Ok -> Runtime(rules.value).lex(source)
        is GMResult.Err -> rules
    }

    private val COMPILED_RULES: GMResult<MarkedRuleSet, MermaidError> by lazy {
        when (val block = compileRules("block", MarkedGeneratedRules.block)) {
            is GMResult.Err -> block
            is GMResult.Ok -> when (val inline = compileRules("inline", MarkedGeneratedRules.inline)) {
                is GMResult.Err -> inline
                is GMResult.Ok -> GMResult.Ok(MarkedRuleSet(block.value, inline.value))
            }
        }
    }

    private fun compileRules(
        group: String,
        sources: Map<String, MarkedRegexSource>,
    ): GMResult<Map<String, Regex>, MermaidError> {
        val compiled = mutableMapOf<String, Regex>()
        sources.forEach { (name, source) ->
            if (name == "blockSkip") {
                return@forEach
            }
            val options = if (source.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val regex = try {
                Regex(source.source, options)
            } catch (exception: IllegalArgumentException) {
                return GMResult.Err(
                    MermaidError.Parse(
                        line = 1,
                        column = 1,
                        message = "Unable to compile Marked $group rule '$name': ${exception.message}",
                    ),
                )
            }
            compiled[name] = regex
        }
        return GMResult.Ok(compiled)
    }

    private data class MarkedRuleSet(
        val block: Map<String, Regex>,
        val inline: Map<String, Regex>,
    )

    private data class LinkDefinition(
        val href: String,
        val title: String?,
    )

    private class Runtime(
        private val rules: MarkedRuleSet,
    ) {
        private val links = mutableMapOf<String, LinkDefinition>()
        private val inlineQueue = mutableListOf<MarkedToken>()
        private var inLink = false
        private var inRawBlock = false

        fun lex(source: String): GMResult<List<MarkedToken>, MermaidError> {
            val tokens = mutableListOf<MarkedToken>()
            val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
            when (val blockResult = blockTokens(normalized, tokens)) {
                is GMResult.Err -> return blockResult
                is GMResult.Ok -> Unit
            }
            inlineQueue.forEach { token ->
                when (val inlineResult = inlineTokens(token.text)) {
                    is GMResult.Err -> return inlineResult
                    is GMResult.Ok -> token.tokens += inlineResult.value
                }
            }
            return GMResult.Ok(tokens)
        }

        private fun blockTokens(
            input: String,
            tokens: MutableList<MarkedToken>,
        ): GMResult<Unit, MermaidError> {
            var source = input
            while (source.isNotEmpty()) {
                if (match(rules.block, "newline", source)?.let { match ->
                    val raw = match.value
                    source = source.drop(raw.length)
                    val last = tokens.lastOrNull()
                    if (raw.length == 1 && last != null) {
                        last.raw += "\n"
                    } else {
                        tokens += MarkedToken(MarkedTokenType.Space, raw)
                    }
                    true
                } == true) continue

                if (match(rules.block, "code", source)?.let { match ->
                    val raw = match.value
                    val text = raw
                        .replace(CODE_REMOVE_INDENT, "")
                        .trimEnd('\n')
                    source = source.drop(raw.length)
                    val last = tokens.lastOrNull()
                    if (last?.type == MarkedTokenType.Paragraph || last?.type == MarkedTokenType.Text) {
                        last.raw += (if (last.raw.endsWith('\n')) "" else "\n") + raw
                        last.text += "\n$text"
                    } else {
                        tokens += MarkedToken(MarkedTokenType.Code, raw, text)
                    }
                    true
                } == true) continue

                if (match(rules.block, "fences", source)?.let { match ->
                    val raw = match.value
                    source = source.drop(raw.length)
                    tokens += MarkedToken(
                        type = MarkedTokenType.Code,
                        raw = raw,
                        text = match.group(3).orEmpty(),
                    )
                    true
                } == true) continue

                if (match(rules.block, "heading", source)?.let { match ->
                    val raw = match.value
                    source = source.drop(raw.length)
                    tokens += MarkedToken(
                        type = MarkedTokenType.Heading,
                        raw = raw,
                        text = normalizeHeadingText(match.group(2).orEmpty()),
                    )
                    true
                } == true) continue

                if (match(rules.block, "hr", source)?.let { match ->
                    val raw = match.value.trimEnd('\n')
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(MarkedTokenType.HorizontalRule, raw)
                    true
                } == true) continue

                if (match(rules.block, "blockquote", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(MarkedTokenType.Blockquote, match.value.trimEnd('\n'))
                    true
                } == true) continue

                when (val list = tokenizeList(source)) {
                    is GMResult.Err -> return list
                    is GMResult.Ok -> {
                        val token = list.value
                        if (token != null) {
                            source = source.drop(token.raw.length)
                            tokens += token
                            continue
                        }
                    }
                }

                if (match(rules.block, "html", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(MarkedTokenType.Html, match.value, match.value)
                    true
                } == true) continue

                if (match(rules.block, "def", source)?.let { match ->
                    val raw = match.value
                    val tag = match.group(1)
                        .orEmpty()
                        .lowercase()
                        .replace(MULTIPLE_SPACE, " ")
                    val href = match.group(2)
                        .orEmpty()
                        .removeSurrounding("<", ">")
                        .replace(ESCAPED_PUNCTUATION, "$1")
                    val title = match.group(3)
                        ?.let { value -> value.substring(1, value.length - 1) }
                        ?.replace(ESCAPED_PUNCTUATION, "$1")
                    source = source.drop(raw.length)
                    if (!links.containsKey(tag)) {
                        links[tag] = LinkDefinition(href, title)
                    }
                    tokens += MarkedToken(MarkedTokenType.Definition, raw)
                    true
                } == true) continue

                if (match(rules.block, "table", source)?.let { match ->
                    if (match.group(2).orEmpty().contains('|') || match.group(2).orEmpty().contains(':')) {
                        source = source.drop(match.value.length)
                        tokens += MarkedToken(MarkedTokenType.Table, match.value)
                        true
                    } else {
                        false
                    }
                } == true) continue

                if (match(rules.block, "lheading", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(
                        type = MarkedTokenType.Heading,
                        raw = match.value,
                        text = match.group(1).orEmpty(),
                    )
                    true
                } == true) continue

                if (match(rules.block, "paragraph", source)?.let { match ->
                    val raw = match.value
                    val captured = match.group(1).orEmpty()
                    val text = if (captured.endsWith('\n')) captured.dropLast(1) else captured
                    source = source.drop(raw.length)
                    val token = MarkedToken(MarkedTokenType.Paragraph, raw, text)
                    tokens += token
                    inlineQueue += token
                    true
                } == true) continue

                if (match(rules.block, "text", source)?.let { match ->
                    val raw = match.value
                    source = source.drop(raw.length)
                    val last = tokens.lastOrNull()
                    if (last?.type == MarkedTokenType.Text) {
                        last.raw += (if (last.raw.endsWith('\n')) "" else "\n") + raw
                        last.text += "\n$raw"
                    } else {
                        val token = MarkedToken(MarkedTokenType.Text, raw, raw)
                        tokens += token
                        inlineQueue += token
                    }
                    true
                } == true) continue

                return parseError("Marked block lexer made no progress", source)
            }
            return GMResult.Ok(Unit)
        }

        /**
         * Direct translation of Tokenizer.list. Item child tokens are omitted
         * because Mermaid only consumes the list token's raw source.
         */
        private fun tokenizeList(source: String): GMResult<MarkedToken?, MermaidError> {
            val first = match(rules.block, "list", source) ?: return GMResult.Ok(null)
            var remaining = source
            var capture = first
            var bullet = capture.group(1).orEmpty().trim()
            val ordered = bullet.length > 1
            bullet = if (ordered) {
                """\d{1,9}\${bullet.last()}"""
            } else {
                Regex.escape(bullet)
            }
            val itemRegex = compileDynamic(
                "list item",
                """^( {0,3}$bullet)((?:[\t ][^\n]*)?(?:\n|${'$'}))""",
            )
            if (itemRegex is GMResult.Err) {
                return itemRegex
            }
            val itemPattern = (itemRegex as GMResult.Ok).value
            var listRaw = ""
            var itemCount = 0
            while (remaining.isNotEmpty()) {
                capture = itemPattern.find(remaining)?.takeIf { it.range.first == 0 } ?: break
                if (match(rules.block, "hr", remaining) != null) {
                    break
                }
                var raw = capture.value
                remaining = remaining.drop(raw.length)
                var line = capture.group(2)
                    .orEmpty()
                    .substringBefore('\n')
                    .replace(LEADING_TABS) { value -> " ".repeat(3 * value.value.length) }
                var nextLine = remaining.substringBefore('\n')
                var blankLine = line.isBlank()
                var indent: Int
                if (blankLine) {
                    indent = capture.group(1).orEmpty().length + 1
                } else {
                    indent = line.indexOfFirst { it != ' ' }.let { if (it < 0) line.length else it }
                    if (indent > 4) {
                        indent = 1
                    }
                    indent += capture.group(1).orEmpty().length
                }
                var endEarly = false
                if (blankLine && nextLine.matches(BLANK_LINE)) {
                    raw += "$nextLine\n"
                    remaining = remaining.drop(nextLine.length + 1)
                    endEarly = true
                }
                if (!endEarly) {
                    val maxIndent = (indent - 1).coerceIn(0, 3)
                    val nextBullet = compileDynamic(
                        "next list bullet",
                        """^ {0,$maxIndent}(?:[*+-]|\d{1,9}[.)])((?:[ \t][^\n]*)?(?:\n|${'$'}))""",
                    )
                    val horizontalRule = compileDynamic(
                        "list horizontal rule",
                        """^ {0,$maxIndent}((?:- *){3,}|(?:_ *){3,}|(?:\* *){3,})(?:\n+|${'$'})""",
                    )
                    val fences = compileDynamic(
                        "list fence",
                        """^ {0,$maxIndent}(?:```|~~~)""",
                    )
                    val heading = compileDynamic(
                        "list heading",
                        """^ {0,$maxIndent}#""",
                    )
                    val html = compileDynamic(
                        "list HTML",
                        """^ {0,$maxIndent}<(?:[a-z].*>|!--)""",
                        ignoreCase = true,
                    )
                    val dynamicRules = listOf(nextBullet, horizontalRule, fences, heading, html)
                    val failure = dynamicRules.filterIsInstance<GMResult.Err<MermaidError>>().firstOrNull()
                    if (failure != null) {
                        return failure
                    }
                    val patterns = dynamicRules.filterIsInstance<GMResult.Ok<Regex>>().map { it.value }
                    while (remaining.isNotEmpty()) {
                        val rawLine = remaining.substringBefore('\n')
                        nextLine = rawLine
                        val nextLineWithoutTabs = nextLine.replace("\t", "    ")
                        if (patterns.any { it.containsMatchAtStart(nextLine) }) {
                            break
                        }
                        val contentIndent = nextLineWithoutTabs.indexOfFirst { it != ' ' }
                            .let { if (it < 0) nextLineWithoutTabs.length else it }
                        if (contentIndent >= indent || nextLine.isBlank()) {
                            Unit
                        } else {
                            if (blankLine) {
                                break
                            }
                            val previousIndent = line
                                .replace("\t", "    ")
                                .indexOfFirst { it != ' ' }
                                .let { if (it < 0) line.length else it }
                            if (previousIndent >= 4) {
                                break
                            }
                            if (patterns.drop(2).take(2).any { it.containsMatchAtStart(line) }) {
                                break
                            }
                            if (patterns[1].containsMatchAtStart(line)) {
                                break
                            }
                        }
                        if (!blankLine && nextLine.isBlank()) {
                            blankLine = true
                        }
                        raw += "$rawLine\n"
                        remaining = if (remaining.length > rawLine.length) {
                            remaining.drop(rawLine.length + 1)
                        } else {
                            ""
                        }
                        line = if (nextLineWithoutTabs.length >= indent) {
                            nextLineWithoutTabs.drop(indent)
                        } else {
                            ""
                        }
                    }
                }
                listRaw += raw
                itemCount += 1
            }
            if (itemCount == 0) {
                return GMResult.Ok(null)
            }
            return GMResult.Ok(
                MarkedToken(
                    type = MarkedTokenType.List,
                    raw = listRaw.trimEnd(),
                ),
            )
        }

        private fun inlineTokens(input: String): GMResult<List<MarkedToken>, MermaidError> {
            var source = input
            val tokens = mutableListOf<MarkedToken>()
            val maskedSource = maskInlineSource(input)
            var keepPreviousCharacter = false
            var previousCharacter = ""

            while (source.isNotEmpty()) {
                if (!keepPreviousCharacter) {
                    previousCharacter = ""
                }
                keepPreviousCharacter = false

                if (match(rules.inline, "escape", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(
                        type = MarkedTokenType.Escape,
                        raw = match.value,
                        text = match.group(1).orEmpty(),
                    )
                    true
                } == true) continue

                if (match(rules.inline, "tag", source)?.let { match ->
                    val raw = match.value
                    if (!inLink && raw.startsWith("<a ", ignoreCase = true)) {
                        inLink = true
                    } else if (inLink && raw.startsWith("</a>", ignoreCase = true)) {
                        inLink = false
                    }
                    if (!inRawBlock && RAW_BLOCK_START.containsMatchAtStart(raw)) {
                        inRawBlock = true
                    } else if (inRawBlock && RAW_BLOCK_END.containsMatchAtStart(raw)) {
                        inRawBlock = false
                    }
                    source = source.drop(raw.length)
                    tokens += MarkedToken(MarkedTokenType.Html, raw, raw)
                    true
                } == true) continue

                when (val link = tokenizeLink(source)) {
                    is GMResult.Err -> return link
                    is GMResult.Ok -> {
                        val token = link.value
                        if (token != null) {
                            source = source.drop(token.raw.length)
                            tokens += token
                            continue
                        }
                    }
                }

                when (val reference = tokenizeReferenceLink(source)) {
                    is GMResult.Err -> return reference
                    is GMResult.Ok -> {
                        val token = reference.value
                        if (token != null) {
                            source = source.drop(token.raw.length)
                            val last = tokens.lastOrNull()
                            if (token.type == MarkedTokenType.Text && last?.type == MarkedTokenType.Text) {
                                last.raw += token.raw
                                last.text += token.text
                            } else {
                                tokens += token
                            }
                            continue
                        }
                    }
                }

                when (val emphasis = tokenizeEmphasis(source, maskedSource, previousCharacter)) {
                    is GMResult.Err -> return emphasis
                    is GMResult.Ok -> {
                        val token = emphasis.value
                        if (token != null) {
                            source = source.drop(token.raw.length)
                            tokens += token
                            continue
                        }
                    }
                }

                if (match(rules.inline, "code", source)?.let { match ->
                    val raw = match.value
                    var text = match.group(2).orEmpty().replace('\n', ' ')
                    if (text.any { it != ' ' } && text.startsWith(' ') && text.endsWith(' ')) {
                        text = text.substring(1, text.length - 1)
                    }
                    source = source.drop(raw.length)
                    tokens += MarkedToken(MarkedTokenType.CodeSpan, raw, text)
                    true
                } == true) continue

                if (match(rules.inline, "br", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(MarkedTokenType.Break, match.value)
                    true
                } == true) continue

                if (match(rules.inline, "del", source)?.let { match ->
                    val raw = match.value
                    val text = match.group(2).orEmpty()
                    when (val nested = inlineTokens(text)) {
                        is GMResult.Err -> return nested
                        is GMResult.Ok -> tokens += MarkedToken(
                            type = MarkedTokenType.Delete,
                            raw = raw,
                            text = text,
                            tokens = nested.value.toMutableList(),
                        )
                    }
                    source = source.drop(raw.length)
                    true
                } == true) continue

                if (match(rules.inline, "autolink", source)?.let { match ->
                    source = source.drop(match.value.length)
                    tokens += MarkedToken(MarkedTokenType.Link, match.value, match.group(1).orEmpty())
                    true
                } == true) continue

                if (!inLink) {
                    if (match(rules.inline, "url", source)?.let { match ->
                        var raw = match.value
                        if (match.group(2) == null) {
                            var previous: String
                            do {
                                previous = raw
                                raw = rules.inline["_backpedal"]
                                    ?.find(raw)
                                    ?.value
                                    .orEmpty()
                            } while (previous != raw)
                        }
                        source = source.drop(raw.length)
                        tokens += MarkedToken(MarkedTokenType.Link, raw, raw)
                        true
                    } == true) continue
                }

                if (match(rules.inline, "text", source)?.let { match ->
                    val raw = match.value
                    source = source.drop(raw.length)
                    if (!raw.endsWith('_')) {
                        previousCharacter = raw.takeLastCodePoint()
                    }
                    keepPreviousCharacter = true
                    val last = tokens.lastOrNull()
                    if (last?.type == MarkedTokenType.Text) {
                        last.raw += raw
                        last.text += raw
                    } else {
                        tokens += MarkedToken(MarkedTokenType.Text, raw, raw)
                    }
                    true
                } == true) continue

                return parseError("Marked inline lexer made no progress", source)
            }
            return GMResult.Ok(tokens)
        }

        private fun tokenizeLink(source: String): GMResult<MarkedToken?, MermaidError> {
            val capture = match(rules.inline, "link", source) ?: return GMResult.Ok(null)
            var raw = capture.value
            var href = capture.group(2).orEmpty()
            val lastParenthesis = findClosingBracket(href, '(', ')')
            if (lastParenthesis == -2) {
                return GMResult.Ok(null)
            }
            if (lastParenthesis > -1) {
                val start = if (raw.startsWith('!')) 5 else 4
                val linkLength = start + capture.group(1).orEmpty().length + lastParenthesis
                href = href.substring(0, lastParenthesis)
                raw = raw.substring(0, linkLength).trim()
            }
            href = href.trim()
            if (href.startsWith('<') && !href.endsWith('>')) {
                return GMResult.Ok(null)
            }
            val text = capture.group(1)
                .orEmpty()
                .replace(ESCAPED_BRACKETS, "$1")
            return when (val nested = inlineTokens(text)) {
                is GMResult.Err -> nested
                is GMResult.Ok -> GMResult.Ok(
                    MarkedToken(
                        type = if (raw.startsWith('!')) MarkedTokenType.Image else MarkedTokenType.Link,
                        raw = raw,
                        text = text,
                        tokens = nested.value.toMutableList(),
                    ),
                )
            }
        }

        private fun tokenizeReferenceLink(source: String): GMResult<MarkedToken?, MermaidError> {
            val capture = match(rules.inline, "reflink", source)
                ?: match(rules.inline, "nolink", source)
                ?: return GMResult.Ok(null)
            val linkKey = (capture.group(2) ?: capture.group(1).orEmpty())
                .replace(MULTIPLE_SPACE, " ")
                .lowercase()
            if (links[linkKey] == null) {
                val text = capture.value.first().toString()
                return GMResult.Ok(MarkedToken(MarkedTokenType.Text, text, text))
            }
            val text = capture.group(1)
                .orEmpty()
                .replace(ESCAPED_BRACKETS, "$1")
            return when (val nested = inlineTokens(text)) {
                is GMResult.Err -> nested
                is GMResult.Ok -> GMResult.Ok(
                    MarkedToken(
                        type = if (capture.value.startsWith('!')) MarkedTokenType.Image else MarkedTokenType.Link,
                        raw = capture.value,
                        text = text,
                        tokens = nested.value.toMutableList(),
                    ),
                )
            }
        }

        private fun tokenizeEmphasis(
            source: String,
            maskedSource: String,
            previousCharacter: String,
        ): GMResult<MarkedToken?, MermaidError> {
            var capture = match(rules.inline, "emStrongLDelim", source) ?: return GMResult.Ok(null)
            if (
                capture.group(3) != null &&
                previousCharacter.isNotEmpty() &&
                previousCharacter.first().isLetterOrDigit()
            ) {
                return GMResult.Ok(null)
            }
            val nextCharacter = capture.group(1) ?: capture.group(2).orEmpty()
            if (
                nextCharacter.isNotEmpty() &&
                previousCharacter.isNotEmpty() &&
                match(rules.inline, "punctuation", previousCharacter) == null
            ) {
                return GMResult.Ok(null)
            }

            val leftLength = capture.value.codePointCount() - 1
            var delimiterTotal = leftLength
            var middleDelimiterTotal = 0
            val rightRuleName = if (capture.value.startsWith('*')) {
                "emStrongRDelimAst"
            } else {
                "emStrongRDelimUnd"
            }
            val rightRule = rules.inline[rightRuleName] ?: return GMResult.Err(
                MermaidError.Parse(1, 1, "Missing Marked inline rule '$rightRuleName'"),
            )
            val maskedOffset = (maskedSource.length - source.length + leftLength)
                .coerceIn(0, maskedSource.length)
            val maskedTail = maskedSource.substring(maskedOffset)
            var searchOffset = 0
            while (searchOffset <= maskedTail.length) {
                capture = rightRule.find(maskedTail, searchOffset) ?: break
                val rightDelimiter = (1..6)
                    .firstNotNullOfOrNull { index -> capture.group(index) }
                if (rightDelimiter == null) {
                    searchOffset = capture.nextSearchOffset()
                    continue
                }
                val rightLength = rightDelimiter.codePointCount()
                if (capture.group(3) != null || capture.group(4) != null) {
                    delimiterTotal += rightLength
                    searchOffset = capture.nextSearchOffset()
                    continue
                }
                if (
                    (capture.group(5) != null || capture.group(6) != null) &&
                    leftLength % 3 != 0 &&
                    (leftLength + rightLength) % 3 == 0
                ) {
                    middleDelimiterTotal += rightLength
                    searchOffset = capture.nextSearchOffset()
                    continue
                }
                delimiterTotal -= rightLength
                if (delimiterTotal > 0) {
                    searchOffset = capture.nextSearchOffset()
                    continue
                }
                val effectiveRightLength = minOf(
                    rightLength,
                    rightLength + delimiterTotal + middleDelimiterTotal,
                )
                val firstCodePointLength = capture.value.firstCodePointLength()
                val rawEnd = leftLength + capture.range.first + firstCodePointLength + effectiveRightLength
                if (rawEnd !in 1..source.length) {
                    return parseError("Marked emphasis range exceeded source", source)
                }
                val raw = source.substring(0, rawEnd)
                val delimiterLength = if (minOf(leftLength, effectiveRightLength) % 2 == 1) 1 else 2
                if (raw.length < delimiterLength * 2) {
                    return parseError("Marked emphasis delimiter was incomplete", source)
                }
                val text = raw.substring(delimiterLength, raw.length - delimiterLength)
                return when (val nested = inlineTokens(text)) {
                    is GMResult.Err -> nested
                    is GMResult.Ok -> GMResult.Ok(
                        MarkedToken(
                            type = if (delimiterLength == 1) {
                                MarkedTokenType.Emphasis
                            } else {
                                MarkedTokenType.Strong
                            },
                            raw = raw,
                            text = text,
                            tokens = nested.value.toMutableList(),
                        ),
                    )
                }
            }
            return GMResult.Ok(null)
        }

        private fun maskInlineSource(source: String): String {
            var masked = source
            masked = replaceMatchesPreservingLength(
                masked,
                rules.inline["reflinkSearch"],
            ) { match ->
                val key = match.value
                    .substringAfterLast('[')
                    .dropLast(1)
                if (links.containsKey(key)) mask(match.value) else match.value
            }
            masked = replaceMatchesPreservingLength(
                masked,
                rules.inline["anyPunctuation"],
            ) { "++" }

            val result = StringBuilder(masked.length)
            var cursor = 0
            while (cursor < masked.length) {
                val remaining = masked.substring(cursor)
                val link = if (remaining.startsWith('[') || remaining.startsWith("![")) {
                    match(rules.inline, "link", remaining)
                } else {
                    null
                }
                val code = if (
                    remaining.startsWith('`') &&
                    (cursor == 0 || masked[cursor - 1] != '`')
                ) {
                    match(rules.inline, "code", remaining)
                } else {
                    null
                }
                val htmlLength = if (remaining.startsWith('<') && !remaining.startsWith("< ")) {
                    remaining.indexOf('>').takeIf { it >= 0 }?.plus(1)
                } else {
                    null
                }
                val length = link?.value?.length ?: code?.value?.length ?: htmlLength
                if (length != null && length >= 2) {
                    result.append(mask(masked.substring(cursor, cursor + length)))
                    cursor += length
                } else {
                    result.append(masked[cursor])
                    cursor += 1
                }
            }
            return result.toString()
        }

        private fun normalizeHeadingText(value: String): String {
            var text = value.trim()
            if (text.endsWith('#')) {
                val withoutHashes = text.trimEnd('#')
                if (withoutHashes.isEmpty() || withoutHashes.endsWith(' ')) {
                    text = withoutHashes.trim()
                }
            }
            return text
        }

        private fun match(
            group: Map<String, Regex>,
            name: String,
            source: String,
        ): MatchResult? = group[name]
            ?.find(source)
            ?.takeIf { it.range.first == 0 && it.value.isNotEmpty() }

        private fun parseError(
            message: String,
            remaining: String,
        ): GMResult.Err<MermaidError> = GMResult.Err(
            MermaidError.Parse(
                line = 1,
                column = inputColumn(remaining),
                message = "$message at '${remaining.take(24)}'",
            ),
        )

        private fun inputColumn(remaining: String): Int =
            (remaining.substringBefore('\n').length + 1).coerceAtLeast(1)
    }

    private fun compileDynamic(
        name: String,
        source: String,
        ignoreCase: Boolean = false,
    ): GMResult<Regex, MermaidError> = try {
        GMResult.Ok(
            Regex(
                source,
                if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet(),
            ),
        )
    } catch (exception: IllegalArgumentException) {
        GMResult.Err(
            MermaidError.Parse(
                line = 1,
                column = 1,
                message = "Unable to compile Marked $name rule: ${exception.message}",
            ),
        )
    }

    private fun replaceMatchesPreservingLength(
        source: String,
        regex: Regex?,
        replacement: (MatchResult) -> String,
    ): String {
        if (regex == null) {
            return source
        }
        val output = StringBuilder(source.length)
        var cursor = 0
        regex.findAll(source).forEach { match ->
            output.append(source, cursor, match.range.first)
            output.append(replacement(match))
            cursor = match.range.last + 1
        }
        output.append(source, cursor, source.length)
        return output.toString()
    }

    private fun mask(source: String): String =
        if (source.length < 2) source else "[${"a".repeat(source.length - 2)}]"

    private fun findClosingBracket(
        source: String,
        opening: Char,
        closing: Char,
    ): Int {
        if (closing !in source) {
            return -1
        }
        var level = 0
        var index = 0
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index += 2
                opening -> {
                    level += 1
                    index += 1
                }
                closing -> {
                    level -= 1
                    if (level < 0) {
                        return index
                    }
                    index += 1
                }
                else -> index += 1
            }
        }
        return if (level > 0) -2 else -1
    }

    private fun MatchResult.group(index: Int): String? =
        if (index < groups.size) groups[index]?.value else null

    private fun MatchResult.nextSearchOffset(): Int =
        if (value.isEmpty()) range.first + 1 else range.last + 1

    private fun String.codePointCount(): Int {
        var count = 0
        var index = 0
        while (index < length) {
            index += if (
                this[index].isHighSurrogate() &&
                getOrNull(index + 1)?.isLowSurrogate() == true
            ) {
                2
            } else {
                1
            }
            count += 1
        }
        return count
    }

    private fun String.firstCodePointLength(): Int =
        if (firstOrNull()?.isHighSurrogate() == true && getOrNull(1)?.isLowSurrogate() == true) 2 else 1

    private fun String.takeLastCodePoint(): String {
        if (isEmpty()) {
            return ""
        }
        val lastIndex = lastIndex
        return if (this[lastIndex].isLowSurrogate() && getOrNull(lastIndex - 1)?.isHighSurrogate() == true) {
            substring(lastIndex - 1)
        } else {
            substring(lastIndex)
        }
    }

    private fun Regex.containsMatchAtStart(source: String): Boolean =
        find(source)?.range?.first == 0

    private val CODE_REMOVE_INDENT = Regex("""^(?: {1,4}| {0,3}\t)""", setOf(RegexOption.MULTILINE))
    private val MULTIPLE_SPACE = Regex("""\s+""")
    private val ESCAPED_PUNCTUATION = Regex("""\\([\p{P}\p{S}])""")
    private val ESCAPED_BRACKETS = Regex("""\\([\[\]])""")
    private val LEADING_TABS = Regex("""^\t+""")
    private val BLANK_LINE = Regex("""^[ \t]*${'$'}""")
    private val RAW_BLOCK_START = Regex("""^<(pre|code|kbd|script)(\s|>)""", RegexOption.IGNORE_CASE)
    private val RAW_BLOCK_END = Regex("""^</(pre|code|kbd|script)(\s|>)""", RegexOption.IGNORE_CASE)
}
