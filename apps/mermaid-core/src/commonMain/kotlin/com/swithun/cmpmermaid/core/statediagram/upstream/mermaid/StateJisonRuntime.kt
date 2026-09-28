package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class StateJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface StateJisonCell {
    data class Goto(val state: Int) : StateJisonCell

    data class Shift(val state: Int) : StateJisonCell

    data class Reduce(val production: Int) : StateJisonCell

    object Accept : StateJisonCell
}

internal data class StateJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class StateJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid State lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        17, 19 -> GMResult.Ok(input.substringBefore('\n'))
        else -> when (val compiled = regex) {
            is GMResult.Ok -> GMResult.Ok(
                compiled.value
                    ?.find(input)
                    ?.takeIf { it.range.first == 0 }
                    ?.value,
            )
            is GMResult.Err -> GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Invalid State lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(17, 19)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * stateDiagram parser. Rule order, conditions, and actions map directly to
 * packages/mermaid/src/diagrams/state/parser/stateDiagram.jison.
 */
internal class StateJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<StateJisonToken, MermaidError> {
        if (grammarEofEmitted) {
            return token(
                symbol = PHYSICAL_EOF,
                text = "",
                tokenLine = line,
                tokenColumn = column,
            )
        }

        while (true) {
            val condition = conditionStack.lastOrNull() ?: INITIAL
            val rules = StateJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = StateJisonTables.lexerPatterns[rule].match(remaining)) {
                    is GMResult.Ok -> if (match.value != null) {
                        matchedRule = rule
                        matchedText = match.value
                    }
                    is GMResult.Err -> return match
                }
                if (matchedRule != null) break
            }

            val rule = matchedRule ?: return lexError("Unrecognized state diagram text")
            val sourceText = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(sourceText)
            var text = sourceText

            val symbol = when (rule) {
                0 -> 38
                1 -> 40
                2 -> 39
                3 -> 44
                4 -> 51
                5 -> 52
                6 -> 53
                7 -> 54
                8 -> 5
                9, 10, 11, 12 -> null
                13, 33 -> {
                    pushState("SCALE")
                    17
                }
                14, 34 -> 18
                15, 35 -> {
                    popState()
                    null
                }
                16 -> {
                    begin("acc_title")
                    33
                }
                17 -> {
                    popState()
                    symbol("acc_title_value")
                }
                18 -> {
                    begin("acc_descr")
                    35
                }
                19 -> {
                    popState()
                    symbol("acc_descr_value")
                }
                20 -> {
                    begin("acc_descr_multiline")
                    null
                }
                21 -> {
                    popState()
                    null
                }
                22 -> symbol("acc_descr_multiline_value")
                23 -> {
                    pushState("CLASSDEF")
                    41
                }
                24 -> {
                    popState()
                    pushState("CLASSDEFID")
                    44
                }
                25 -> {
                    popState()
                    pushState("CLASSDEFID")
                    42
                }
                26 -> {
                    popState()
                    43
                }
                27 -> {
                    pushState("CLASS")
                    48
                }
                28 -> {
                    popState()
                    pushState("CLASS_STYLE")
                    49
                }
                29 -> {
                    popState()
                    50
                }
                30 -> {
                    pushState("STYLE")
                    45
                }
                31 -> {
                    popState()
                    pushState("STYLEDEF_STYLES")
                    46
                }
                32 -> {
                    popState()
                    47
                }
                36 -> {
                    pushState("STATE")
                    null
                }
                37, 40 -> {
                    popState()
                    text = text.dropLast(8).trim()
                    25
                }
                38, 41 -> {
                    popState()
                    text = text.dropLast(8).trim()
                    26
                }
                39, 42 -> {
                    popState()
                    text = text.dropLast(10).trim()
                    27
                }
                43 -> 51
                44 -> 52
                45 -> 53
                46 -> 54
                47 -> {
                    pushState("STATE_STRING")
                    null
                }
                48 -> {
                    pushState("STATE_ID")
                    23
                }
                49 -> {
                    text = when (val processed = processId(text)) {
                        is GMResult.Ok -> processed.value ?: continue
                        is GMResult.Err -> return processed
                    }
                    popState()
                    24
                }
                50 -> {
                    popState()
                    null
                }
                51 -> 22
                52 -> return lexError(
                    "State name must be a single word. Found: \"${text.trim()}\"",
                )
                53 -> 19
                54 -> {
                    popState()
                    null
                }
                55 -> {
                    popState()
                    pushState("struct")
                    20
                }
                56 -> {
                    popState()
                    21
                }
                57 -> null
                58 -> {
                    begin("NOTE")
                    29
                }
                59 -> {
                    popState()
                    pushState("NOTE_ID")
                    59
                }
                60 -> {
                    popState()
                    pushState("NOTE_ID")
                    60
                }
                61 -> {
                    popState()
                    pushState("FLOATING_NOTE")
                    null
                }
                62 -> {
                    popState()
                    pushState("FLOATING_NOTE_ID")
                    23
                }
                63 -> null
                64 -> 31
                65 -> {
                    text = when (val processed = processId(text)) {
                        is GMResult.Ok -> processed.value ?: continue
                        is GMResult.Err -> return processed
                    }
                    popState()
                    24
                }
                66 -> {
                    text = when (val processed = processId(text)) {
                        is GMResult.Ok -> processed.value ?: continue
                        is GMResult.Err -> return processed
                    }
                    popState()
                    pushState("NOTE_TEXT")
                    24
                }
                67 -> {
                    popState()
                    text = text.drop(2.coerceAtMost(text.length)).trim()
                    31
                }
                68 -> {
                    popState()
                    text = text.dropLast(8.coerceAtMost(text.length)).trim()
                    31
                }
                69, 70 -> 6
                71 -> 16
                72 -> 57
                73 -> {
                    text = when (val processed = processId(text)) {
                        is GMResult.Ok -> processed.value ?: continue
                        is GMResult.Err -> return processed
                    }
                    24
                }
                74 -> {
                    text = text.trim()
                    14
                }
                75 -> 15
                76 -> 28
                77 -> 58
                78 -> {
                    grammarEofEmitted = true
                    5
                }
                79 -> return lexError("Invalid state diagram token '$text'")
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (sourceText.isEmpty() && rule !in ZERO_LENGTH_STATE_RULES) {
                    return lexError("State lexer rule $rule consumed no input")
                }
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun processId(text: String): GMResult<String?, MermaidError> {
        val comment = text.indexOf("%%")
        if (comment == 0) return GMResult.Ok(null)
        if (comment < 0) return GMResult.Ok(text)
        val suffix = text.substring(comment)
        if (suffix.isNotEmpty()) {
            unread(suffix)
        }
        return GMResult.Ok(text.substring(0, comment))
    }

    private fun symbol(name: String): Int? = StateJisonTables.symbolIds[name]

    private fun begin(condition: String) {
        conditionStack += condition
    }

    private fun pushState(condition: String) {
        conditionStack += condition
    }

    private fun popState() {
        if (conditionStack.size > 1) {
            conditionStack.removeAt(conditionStack.lastIndex)
        }
    }

    private fun unread(text: String) {
        offset = (offset - text.length).coerceAtLeast(0)
        val consumed = input.substring(0, offset)
        line = consumed.count { it == '\n' } + 1
        column = consumed.substringAfterLast('\n').length
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

    private fun token(
        symbol: Int,
        text: String,
        tokenLine: Int,
        tokenColumn: Int,
    ): GMResult<StateJisonToken, MermaidError> = GMResult.Ok(
        StateJisonToken(
            symbol = symbol,
            name = StateJisonTables.terminalNames[symbol] ?: "\$end",
            text = text,
            line = tokenLine,
            column = tokenColumn,
        ),
    )

    private fun <T> lexError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(line, column + 1, message))

    private companion object {
        const val INITIAL = "INITIAL"
        const val PHYSICAL_EOF = 1
        val ZERO_LENGTH_STATE_RULES = setOf(17, 19, 22, 26, 29, 32, 49, 51, 64, 65, 78)
    }
}
