package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class JisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface JisonCell {
    data class Goto(val state: Int) : JisonCell

    data class Shift(val state: Int) : JisonCell

    data class Reduce(val production: Int) : JisonCell

    object Accept : JisonCell
}

internal data class JisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

/**
 * Executes Mermaid's generated lexer rules without relying on lookahead
 * support in the platform regex engine. Android's ICU regex implementation
 * rejects several expressions emitted by Jison 0.4.18, while JVM accepts
 * them. The special cases below are direct equivalents of those rules in
 * flow.jison; every other rule still uses the generated regular expression.
 */
internal class JisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) null else Regex(source)

    fun match(input: String): String? = when (rule) {
        1, 3 -> input.substringBefore('\n')
        61 -> matchLinkId(input)
        72 -> matchSingleUnlessFollowedBy(input, '-', '-')
        75 -> matchSingleUnlessFollowedBy(input, '=', '=')
        78 -> matchSingleUnlessFollowedBy(input, '.', '-')
        94 -> "/]".takeIf(input::startsWith)
        95 -> matchTrapezoidText(input)
        106 -> matchNodeString(input)
        else -> regex?.find(input)?.takeIf { it.range.first == 0 }?.value
    }

    private fun matchLinkId(input: String): String? {
        var lastValidAt = -1
        var index = 0
        while (index < input.length && !input[index].isWhitespace() && input[index] != '"') {
            if (
                input[index] == '@' &&
                input.getOrNull(index + 1)?.let { next -> next != '{' && next != '"' } == true
            ) {
                lastValidAt = index
            }
            index += 1
        }
        return lastValidAt.takeIf { it >= 0 }?.let { input.substring(0, it + 1) }
    }

    private fun matchSingleUnlessFollowedBy(
        input: String,
        guarded: Char,
        forbiddenNext: Char,
    ): String? {
        val first = input.firstOrNull() ?: return null
        return when {
            first != guarded -> first.toString()
            input.getOrNull(1) != forbiddenNext -> first.toString()
            else -> null
        }
    }

    private fun matchTrapezoidText(input: String): String? {
        val first = input.firstOrNull() ?: return null
        if (first == '/' || first == '\\') {
            return first.toString().takeIf { input.getOrNull(1) != ']' }
        }
        val end = input.indexOfFirst { it in TRAPEZOID_DELIMITERS }
            .let { if (it < 0) input.length else it }
        return input.substring(0, end).takeIf(String::isNotEmpty)
    }

    private fun matchNodeString(input: String): String? {
        var index = 0
        while (index < input.length) {
            val current = input[index]
            when {
                current.isAsciiNodeCharacter() -> index += 1
                current == '-' &&
                    input.getOrNull(index + 1)?.let { next -> next !in NODE_HYPHEN_TERMINATORS } == true ->
                    index += 1
                else -> break
            }
        }
        return input.substring(0, index).takeIf(String::isNotEmpty)
    }

    private fun Char.isAsciiNodeCharacter(): Boolean =
        this in 'A'..'Z' ||
            this in 'a'..'z' ||
            this in '0'..'9' ||
            this in ASCII_NODE_PUNCTUATION

    private companion object {
        val PORTABLE_RULES = setOf(1, 3, 61, 72, 75, 78, 94, 95, 106)
        val TRAPEZOID_DELIMITERS = setOf('\\', '[', ']', '(', ')', '{', '}', '/')
        val ASCII_NODE_PUNCTUATION = setOf(
            '!', '"', '#', '$', '%', '&', '\'', '*', '+', '.', '`', '?', '\\', '_', '/',
        )
        val NODE_HYPHEN_TERMINATORS = setOf('>', '-', '.')
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * Flowchart parser. Rule ordering, start conditions and actions correspond to
 * packages/mermaid/src/diagrams/flowchart/parser/flow.jison.
 */
internal class FlowJisonLexer(
    source: String,
    private val firstGraph: () -> Boolean,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<JisonToken, MermaidError> {
        if (grammarEofEmitted) {
            return GMResult.Ok(
                JisonToken(
                    symbol = PHYSICAL_EOF,
                    name = "\$end",
                    text = "",
                    line = line,
                    column = column,
                ),
            )
        }

        while (true) {
            val condition = conditionStack.lastOrNull() ?: INITIAL
            val rules = FlowJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                val match = FlowJisonTables.lexerPatterns[rule].match(remaining)
                if (match != null) {
                    matchedRule = rule
                    matchedText = match
                    break
                }
            }

            val rule = matchedRule ?: return lexError("Unrecognized flowchart text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            when (val action = performAction(rule, text)) {
                is GMResult.Err -> return action
                is GMResult.Ok -> {
                    val result = action.value ?: continue
                    if (rule == GRAMMAR_EOF_RULE) {
                        grammarEofEmitted = true
                    }
                    return GMResult.Ok(
                        JisonToken(
                            symbol = result.symbol,
                            name = result.name,
                            text = result.text,
                            line = tokenLine,
                            column = tokenColumn,
                        ),
                    )
                }
            }
        }
    }

    private fun performAction(
        rule: Int,
        matchedText: String,
    ): GMResult<LexerAction?, MermaidError> {
        var text = matchedText
        val token = when (rule) {
            0 -> {
                pushState("acc_title")
                token("acc_title", text)
            }
            1 -> {
                popState()
                token("acc_title_value", text)
            }
            2 -> {
                pushState("acc_descr")
                token("acc_descr", text)
            }
            3 -> {
                popState()
                token("acc_descr_value", text)
            }
            4 -> {
                pushState("acc_descr_multiline")
                null
            }
            5 -> {
                popState()
                null
            }
            6 -> token("acc_descr_multiline_value", text)
            7 -> {
                pushState("shapeData")
                text = ""
                token("SHAPE_DATA", text)
            }
            8 -> {
                pushState("shapeDataStr")
                token("SHAPE_DATA", text)
            }
            9 -> {
                popState()
                token("SHAPE_DATA", text)
            }
            10 -> token("SHAPE_DATA", text.replace(NEWLINE_WITH_INDENT, "<br/>"))
            11 -> token("SHAPE_DATA", text)
            12 -> {
                popState()
                null
            }
            13 -> {
                pushState("callbackname")
                null
            }
            14 -> {
                popState()
                null
            }
            15 -> {
                popState()
                pushState("callbackargs")
                null
            }
            16 -> token("CALLBACKNAME", text)
            17 -> {
                popState()
                null
            }
            18 -> token("CALLBACKARGS", text)
            19 -> token("MD_STR", text)
            20 -> {
                popState()
                null
            }
            21 -> {
                pushState("md_string")
                null
            }
            22 -> token("STR", text)
            23 -> {
                popState()
                null
            }
            24 -> {
                pushState("string")
                null
            }
            25 -> token("STYLE", text)
            26 -> token("DEFAULT", text)
            27 -> token("LINKSTYLE", text)
            28 -> token("INTERPOLATE", text)
            29 -> token("CLASSDEF", text)
            30 -> token("CLASS", text)
            31 -> token("HREF", text)
            32 -> {
                pushState("click")
                null
            }
            33 -> {
                popState()
                null
            }
            34 -> token("CLICK", text)
            in 35..38 -> {
                if (firstGraph()) {
                    pushState("dir")
                }
                token("GRAPH", text)
            }
            39 -> token("subgraph", text)
            40 -> token("end", text)
            in 41..44 -> token("LINK_TARGET", text)
            45 -> {
                popState()
                token("NODIR", text)
            }
            in 46..55 -> {
                popState()
                token("DIR", text)
            }
            56 -> token("direction_tb", text)
            57 -> token("direction_bt", text)
            58 -> token("direction_rl", text)
            59 -> token("direction_lr", text)
            60 -> token("direction_td", text)
            61 -> token("LINK_ID", text)
            62 -> token("NUM", text)
            63 -> token("BRKT", text)
            64 -> token("STYLE_SEPARATOR", text)
            65 -> token("COLON", text)
            66 -> token("AMP", text)
            67 -> token("SEMI", text)
            68 -> token("COMMA", text)
            69 -> token("MULT", text)
            70 -> {
                popState()
                token("LINK", text)
            }
            71 -> {
                pushState("edgeText")
                token("START_LINK", text)
            }
            72 -> token("EDGE_TEXT", text)
            73 -> {
                popState()
                token("LINK", text)
            }
            74 -> {
                pushState("thickEdgeText")
                token("START_LINK", text)
            }
            75 -> token("EDGE_TEXT", text)
            76 -> {
                popState()
                token("LINK", text)
            }
            77 -> {
                pushState("dottedEdgeText")
                token("START_LINK", text)
            }
            78 -> token("EDGE_TEXT", text)
            79 -> token("LINK", text)
            80 -> {
                popState()
                token("-)", text)
            }
            81 -> token("TEXT", text)
            82 -> {
                pushState("ellipseText")
                token("(-", text)
            }
            83 -> {
                popState()
                token("STADIUMEND", text)
            }
            84 -> {
                pushState("text")
                token("STADIUMSTART", text)
            }
            85 -> {
                popState()
                token("SUBROUTINEEND", text)
            }
            86 -> {
                pushState("text")
                token("SUBROUTINESTART", text)
            }
            87 -> token("VERTEX_WITH_PROPS_START", text)
            88 -> {
                pushState("text")
                token("TAGEND", text)
            }
            89 -> {
                popState()
                token("CYLINDEREND", text)
            }
            90 -> {
                pushState("text")
                token("CYLINDERSTART", text)
            }
            91 -> {
                popState()
                token("DOUBLECIRCLEEND", text)
            }
            92 -> {
                pushState("text")
                token("DOUBLECIRCLESTART", text)
            }
            93 -> {
                popState()
                token("TRAPEND", text)
            }
            94 -> {
                popState()
                token("INVTRAPEND", text)
            }
            95 -> token("TEXT", text)
            96 -> {
                pushState("trapText")
                token("TRAPSTART", text)
            }
            97 -> {
                pushState("trapText")
                token("INVTRAPSTART", text)
            }
            98 -> token("TAGSTART", text)
            99 -> token("TAGEND", text)
            100 -> token("DOWN", text)
            101 -> unresolvedToken("SEP", text)
            102 -> token("UP", text)
            103 -> token("MULT", text)
            104 -> token("BRKT", text)
            105 -> token("AMP", text)
            106 -> token("NODE_STRING", text)
            107 -> token("MINUS", text)
            108 -> token("UNICODE_TEXT", text)
            109 -> {
                popState()
                token("PIPE", text)
            }
            110 -> {
                pushState("text")
                token("PIPE", text)
            }
            111 -> {
                popState()
                token("PE", text)
            }
            112 -> {
                pushState("text")
                token("PS", text)
            }
            113 -> {
                popState()
                token("SQE", text)
            }
            114 -> {
                pushState("text")
                token("SQS", text)
            }
            115 -> {
                popState()
                token("DIAMOND_STOP", text)
            }
            116 -> {
                pushState("text")
                token("DIAMOND_START", text)
            }
            117 -> token("TEXT", text)
            118 -> unresolvedToken("QUOTE", text)
            119 -> token("NEWLINE", text)
            120 -> token("SPACE", text)
            121 -> token("EOF", text)
            else -> return lexError("Unknown Jison lexer action $rule")
        }
        return GMResult.Ok(token)
    }

    private fun token(name: String, text: String): LexerAction =
        LexerAction(
            symbol = FlowJisonTables.symbolIds[name] ?: UNRESOLVED_TOKEN,
            name = name,
            text = text,
        )

    private fun unresolvedToken(name: String, text: String): LexerAction =
        LexerAction(symbol = UNRESOLVED_TOKEN, name = name, text = text)

    private fun pushState(condition: String) {
        conditionStack += condition
    }

    private fun popState() {
        if (conditionStack.size > 1) {
            conditionStack.removeAt(conditionStack.lastIndex)
        }
    }

    private fun advance(text: String) {
        offset += text.length
        text.forEach { character ->
            if (character == '\n') {
                line += 1
                column = 0
            } else {
                column += 1
            }
        }
    }

    private fun <T> lexError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(line, column + 1, message))

    private data class LexerAction(
        val symbol: Int,
        val name: String,
        val text: String,
    )

    private companion object {
        const val INITIAL = "INITIAL"
        const val PHYSICAL_EOF = 1
        const val UNRESOLVED_TOKEN = -1
        const val GRAMMAR_EOF_RULE = 121
        val NEWLINE_WITH_INDENT = Regex("""\n\s*""")
    }
}
