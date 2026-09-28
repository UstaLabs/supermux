package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class AgentflowJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val location: AgentflowJisonLocation,
)

/**
 * Portable matcher for Mermaid.js 12.0.0 agentflow.jison lexer rules.
 *
 * Jison emits a few JavaScript lookahead expressions that are not accepted by
 * every Kotlin target regex engine. Those rules are translated directly.
 */
internal class AgentflowJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) null else Regex(source)

    fun match(input: String): String? = when (rule) {
        1, 3 -> input.substringBefore('\n')
        60 -> matchLinkId(input)
        71 -> matchSingleUnlessFollowedBy(input, '-', '-')
        89 -> "/]".takeIf(input::startsWith)
        90 -> matchTrapezoidText(input)
        102 -> matchNodeString(input)
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
        val end = input.indexOfFirst { it in TRAPEZOID_DELIMITERS || it == '%' }
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
                current == '=' && input.getOrNull(index + 1) != '=' -> index += 1
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
        val PORTABLE_RULES = setOf(1, 3, 60, 71, 89, 90, 102)
        val TRAPEZOID_DELIMITERS = setOf('\\', '[', ']', '(', ')', '{', '}', '/')
        val ASCII_NODE_PUNCTUATION = setOf(
            '!', '"', '#', '$', '%', '&', '\'', '*', '+', '.', '`', '?', '\\', '_', '/',
        )
        val NODE_HYPHEN_TERMINATORS = setOf('>', '-', '.')
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid.js 12.0.0
 * agentflow.jison.
 */
internal class AgentflowJisonLexer(
    source: String,
    private val firstGraph: () -> Boolean,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<AgentflowJisonToken, MermaidError> {
        if (grammarEofEmitted) {
            val location = currentLocation()
            return GMResult.Ok(
                AgentflowJisonToken(
                    symbol = PHYSICAL_EOF,
                    name = "\$end",
                    text = "",
                    location = location,
                ),
            )
        }

        while (true) {
            val condition = conditionStack.lastOrNull() ?: INITIAL
            val rules = AgentflowJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                val match = AgentflowJisonTables.lexerPatterns[rule].match(remaining)
                if (match != null) {
                    matchedRule = rule
                    matchedText = match
                    break
                }
            }

            val rule = matchedRule ?: return lexError("Unrecognized agentflow text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val startLine = line
            val startColumn = column
            val startIndex = offset
            advance(text)

            when (val action = performAction(rule, text)) {
                is GMResult.Err -> return action
                is GMResult.Ok -> {
                    val result = action.value ?: continue
                    if (rule == GRAMMAR_EOF_RULE) {
                        grammarEofEmitted = true
                    }
                    return GMResult.Ok(
                        AgentflowJisonToken(
                            symbol = result.symbol,
                            name = result.name,
                            text = result.text,
                            location = AgentflowJisonLocation(
                                firstLine = startLine,
                                firstColumn = startColumn,
                                lastLine = line,
                                lastColumn = column,
                                startIndex = startIndex,
                                endIndex = offset,
                            ),
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
            0 -> pushTokenState("acc_title", "acc_title", text)
            1 -> popTokenState("acc_title_value", text)
            2 -> pushTokenState("acc_descr", "acc_descr", text)
            3 -> popTokenState("acc_descr_value", text)
            4 -> pushState("acc_descr_multiline").let { null }
            5 -> popState().let { null }
            6 -> token("acc_descr_multiline_value", text)
            7 -> {
                pushState("shapeData")
                text = ""
                token("SHAPE_DATA", text)
            }
            8 -> pushTokenState("shapeDataStr", "SHAPE_DATA", text)
            9 -> popTokenState("SHAPE_DATA", text)
            10 -> token("SHAPE_DATA", text.replace(NEWLINE_WITH_INDENT, "<br/>"))
            11 -> token("SHAPE_DATA", text)
            12 -> popState().let { null }
            13 -> pushState("callbackname").let { null }
            14 -> popState().let { null }
            15 -> {
                popState()
                pushState("callbackargs")
                null
            }
            16 -> token("CALLBACKNAME", text)
            17 -> popState().let { null }
            18 -> token("CALLBACKARGS", text)
            19 -> token("MD_STR", text)
            20 -> popState().let { null }
            21 -> pushState("md_string").let { null }
            22 -> token("STR", text)
            23 -> popState().let { null }
            24 -> pushState("string").let { null }
            25 -> token("STYLE", text)
            26 -> token("DEFAULT", text)
            27 -> token("LINKSTYLE", text)
            28 -> token("INTERPOLATE", text)
            29 -> token("CLASSDEF", text)
            30 -> token("CLASS", text)
            31 -> token("HREF", text)
            32 -> pushState("click").let { null }
            33 -> popState().let { null }
            34 -> token("CLICK", text)
            35 -> {
                if (firstGraph()) {
                    pushState("dir")
                }
                token("GRAPH", text)
            }
            36 -> token("flow", text)
            37 -> token("connector", text)
            38 -> token("global", text)
            39 -> token("end", text)
            in 40..43 -> token("LINK_TARGET", text)
            44 -> popTokenState("NODIR", text)
            in 45..54 -> popTokenState("DIR", text)
            55 -> token("direction_tb", text)
            56 -> token("direction_bt", text)
            57 -> token("direction_rl", text)
            58 -> token("direction_lr", text)
            59 -> token("direction_td", text)
            60 -> token("LINK_ID", text)
            61 -> token("NUM", text)
            62 -> token("BRKT", text)
            63 -> token("STYLE_SEPARATOR", text)
            64 -> token("COLON", text)
            65 -> token("AMP", text)
            66 -> token("SEMI", text)
            67 -> token("COMMA", text)
            68 -> token("MULT", text)
            69 -> popTokenState("LINK", text)
            70 -> pushTokenState("edgeText", "START_LINK", text)
            71 -> token("EDGE_TEXT", text)
            72 -> token("LINK", text)
            73 -> null
            74 -> popTokenState("-)", text)
            75 -> token("TEXT", text)
            76 -> pushTokenState("ellipseText", "(-", text)
            77 -> popTokenState("STADIUMEND", text)
            78 -> pushTokenState("text", "STADIUMSTART", text)
            79 -> popTokenState("SUBROUTINEEND", text)
            80 -> pushTokenState("text", "SUBROUTINESTART", text)
            81 -> token("VERTEX_WITH_PROPS_START", text)
            82 -> pushTokenState("text", "TAGEND", text)
            83 -> popTokenState("CYLINDEREND", text)
            84 -> pushTokenState("text", "CYLINDERSTART", text)
            85 -> popTokenState("DOUBLECIRCLEEND", text)
            86 -> pushTokenState("text", "DOUBLECIRCLESTART", text)
            87 -> null
            88 -> popTokenState("TRAPEND", text)
            89 -> popTokenState("INVTRAPEND", text)
            90 -> token("TEXT", text)
            91 -> pushTokenState("trapText", "TRAPSTART", text)
            92 -> pushTokenState("trapText", "INVTRAPSTART", text)
            93 -> token("TAGSTART", text)
            94 -> token("TAGEND", text)
            95 -> token("UP", text)
            96 -> unresolvedToken("SEP", text)
            97 -> token("DOWN", text)
            98 -> token("MULT", text)
            99 -> token("BRKT", text)
            100 -> token("AMP", text)
            101 -> null
            102 -> token("NODE_STRING", text)
            103 -> token("MINUS", text)
            104 -> token("UNICODE_TEXT", text)
            105 -> popTokenState("PIPE", text)
            106 -> pushTokenState("text", "PIPE", text)
            107 -> popTokenState("PE", text)
            108 -> pushTokenState("text", "PS", text)
            109 -> popTokenState("SQE", text)
            110 -> pushTokenState("text", "SQS", text)
            111 -> popTokenState("DIAMOND_STOP", text)
            112 -> pushTokenState("text", "DIAMOND_START", text)
            113 -> null
            114 -> token("TEXT", text)
            115 -> unresolvedToken("QUOTE", text)
            116 -> token("NEWLINE", text)
            117 -> token("SPACE", text)
            118 -> token("EOF", text)
            else -> return lexError("Unknown Jison lexer action $rule")
        }
        return GMResult.Ok(token)
    }

    private fun pushTokenState(
        condition: String,
        name: String,
        text: String,
    ): LexerAction {
        pushState(condition)
        return token(name, text)
    }

    private fun popTokenState(name: String, text: String): LexerAction {
        popState()
        return token(name, text)
    }

    private fun token(name: String, text: String): LexerAction =
        LexerAction(
            symbol = AgentflowJisonTables.symbolIds[name] ?: UNRESOLVED_TOKEN,
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

    private fun currentLocation(): AgentflowJisonLocation =
        AgentflowJisonLocation(
            firstLine = line,
            firstColumn = column,
            lastLine = line,
            lastColumn = column,
            startIndex = offset,
            endIndex = offset,
        )

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
        const val GRAMMAR_EOF_RULE = 118
        val NEWLINE_WITH_INDENT = Regex("""\n\s*""")
    }
}
