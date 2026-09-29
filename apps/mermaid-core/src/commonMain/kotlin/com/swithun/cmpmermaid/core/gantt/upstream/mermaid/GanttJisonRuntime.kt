package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class GanttJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface GanttJisonCell {
    data class Goto(val state: Int) : GanttJisonCell

    data class Shift(val state: Int) : GanttJisonCell

    data class Reduce(val production: Int) : GanttJisonCell

    object Accept : GanttJisonCell
}

internal data class GanttJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class GanttJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Gantt lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        2, 4 -> GMResult.Ok(input.substringBefore('\n'))
        7 -> GMResult.Ok(input.substringBefore('}'))
        8 -> GMResult.Ok(
            input.substringBefore('\n').takeIf { input.startsWith("%%") },
        )
        16 -> GMResult.Ok(input.substringBefore('"'))
        20 -> GMResult.Ok(input.substringBefore('('))
        22 -> GMResult.Ok(input.substringBefore(')'))
        25 -> GMResult.Ok(input.takeWhile { it != ' ' && it != '\t' && it != '\n' })
        51 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid Gantt lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(2, 4, 7, 8, 16, 20, 22, 25, 51)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * Gantt parser. Rule order, conditions, and actions map directly to
 * packages/mermaid/src/diagrams/gantt/parser/gantt.jison.
 */
internal class GanttJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<GanttJisonToken, MermaidError> {
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
            val rules = GanttJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = GanttJisonTables.lexerPatterns[rule].match(remaining)) {
                    is GMResult.Ok -> if (match.value != null) {
                        matchedRule = rule
                        matchedText = match.value
                    }
                    is GMResult.Err -> return match
                }
                if (matchedRule != null) break
            }

            val rule = matchedRule ?: return lexError("Unrecognized Gantt diagram text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> return lexError("Unprocessed Mermaid directive")
                1 -> {
                    begin("acc_title")
                    31
                }
                2 -> {
                    popState()
                    32
                }
                3 -> {
                    begin("acc_descr")
                    33
                }
                4 -> {
                    popState()
                    34
                }
                5 -> {
                    begin("acc_descr_multiline")
                    null
                }
                6 -> {
                    popState()
                    null
                }
                7 -> 35
                in 8..10, 12, 13 -> null
                11 -> 10
                14 -> {
                    begin("href")
                    null
                }
                15 -> {
                    popState()
                    null
                }
                16 -> 43
                17 -> {
                    begin("callbackname")
                    null
                }
                18 -> {
                    popState()
                    null
                }
                19 -> {
                    popState()
                    begin("callbackargs")
                    null
                }
                20 -> 41
                21 -> {
                    popState()
                    null
                }
                22 -> 42
                23 -> {
                    begin("click")
                    null
                }
                24 -> {
                    popState()
                    null
                }
                25 -> 40
                26 -> 4
                27 -> 22
                28 -> 23
                29 -> 24
                30 -> 25
                31 -> 26
                32 -> 28
                33 -> 27
                34 -> 29
                in 35..41 -> 12 + (rule - 35)
                in 42..43 -> 20 + (rule - 42)
                44 -> return lexError("Unexpected standalone date")
                45 -> 30
                46 -> return lexError("Unsupported legacy accDescription statement")
                47 -> 36
                48 -> 38
                49 -> 39
                50 -> return lexError("Unexpected ':'")
                51 -> {
                    grammarEofEmitted = true
                    6
                }
                52 -> return lexError("Invalid Gantt token '$text'")
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty() && rule !in ZERO_LENGTH_STATE_RULES) {
                    return lexError("Gantt lexer rule $rule consumed no input")
                }
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun begin(condition: String) {
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

    private fun token(
        symbol: Int,
        text: String,
        tokenLine: Int,
        tokenColumn: Int,
    ): GMResult<GanttJisonToken, MermaidError> = GMResult.Ok(
        GanttJisonToken(
            symbol = symbol,
            name = GanttJisonTables.terminalNames[symbol] ?: "\$end",
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
        val ZERO_LENGTH_STATE_RULES = setOf(2, 4, 7, 16, 18, 20, 22, 25, 51)
    }
}
