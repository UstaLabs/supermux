package com.swithun.cmpmermaid.core.journey.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class JourneyJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface JourneyJisonCell {
    data class Goto(val state: Int) : JourneyJisonCell

    data class Shift(val state: Int) : JourneyJisonCell

    data class Reduce(val production: Int) : JourneyJisonCell

    object Accept : JourneyJisonCell
}

internal data class JourneyJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class JourneyJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Journey lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        0 -> GMResult.Ok(
            input.substringBefore('\n').takeIf {
                input.startsWith("%%") && !input.startsWith("%%{")
            },
        )
        8, 10 -> GMResult.Ok(input.substringBefore('\n'))
        13 -> GMResult.Ok(
            input.substringBefore('}').takeIf(String::isNotEmpty),
        )
        18 -> GMResult.Ok("".takeIf { input.isEmpty() })
        else -> when (val compiled = regex) {
            is GMResult.Ok -> GMResult.Ok(
                compiled.value
                    ?.find(input)
                    ?.takeIf { match -> match.range.first == 0 }
                    ?.value,
            )
            is GMResult.Err -> GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Invalid Journey lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(0, 8, 10, 13, 18)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid 12 journey.jison.
 */
internal class JourneyJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<JourneyJisonToken, MermaidError> {
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
            val rules = JourneyJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = JourneyJisonTables.lexerPatterns[rule].match(remaining)) {
                    is GMResult.Ok -> if (match.value != null) {
                        matchedRule = rule
                        matchedText = match.value
                    }
                    is GMResult.Err -> return match
                }
                if (matchedRule != null) {
                    break
                }
            }

            val rule = matchedRule ?: return lexError("Unrecognized Journey text")
            var text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0, 1, 3, 4 -> null
                2 -> 10
                5 -> 4
                6 -> 11
                7 -> {
                    pushState("acc_title")
                    12
                }
                8 -> {
                    popState()
                    13
                }
                9 -> {
                    pushState("acc_descr")
                    14
                }
                10 -> {
                    popState()
                    15
                }
                11 -> {
                    pushState("acc_descr_multiline")
                    null
                }
                12 -> {
                    popState()
                    null
                }
                13 -> 16
                14 -> 17
                15 -> 18
                16 -> 19
                17 -> INVALID_SYMBOL
                18 -> {
                    grammarEofEmitted = true
                    6
                }
                19 -> INVALID_SYMBOL
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty()) {
                    return lexError("Journey lexer rule $rule consumed no input")
                }
                continue
            }
            if (symbol == INVALID_SYMBOL) {
                text = text.ifEmpty { remaining.take(1) }
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

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

    private fun token(
        symbol: Int,
        text: String,
        tokenLine: Int,
        tokenColumn: Int,
    ): GMResult<JourneyJisonToken, MermaidError> = GMResult.Ok(
        JourneyJisonToken(
            symbol = symbol,
            name = JourneyJisonTables.terminalNames[symbol]
                ?: if (symbol == INVALID_SYMBOL) "INVALID" else "\$end",
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
        const val INVALID_SYMBOL = -1
    }
}
