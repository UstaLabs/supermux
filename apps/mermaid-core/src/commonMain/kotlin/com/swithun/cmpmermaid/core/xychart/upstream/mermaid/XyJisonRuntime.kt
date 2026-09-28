package com.swithun.cmpmermaid.core.xychart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class XyJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface XyJisonCell {
    data class Goto(val state: Int) : XyJisonCell

    data class Shift(val state: Int) : XyJisonCell

    data class Reduce(val production: Int) : XyJisonCell

    object Accept : XyJisonCell
}

internal data class XyJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class XyJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid XY Chart lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        0 -> GMResult.Ok(
            input.substringBefore('\n').takeIf {
                input.startsWith("%%") && !input.startsWith("%%{")
            },
        )
        2, 3 -> GMResult.Ok(
            when {
                input.startsWith("\r\n") -> "\r\n"
                input.startsWith("\n") -> "\n"
                else -> null
            },
        )
        8, 10 -> GMResult.Ok(input.substringBefore('\n'))
        13 -> GMResult.Ok(
            input.substringBefore('}').takeIf(String::isNotEmpty),
        )
        26 -> GMResult.Ok(null)
        29 -> GMResult.Ok(
            input.takeWhile { it != '"' }.takeIf(String::isNotEmpty),
        )
        46 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid XY Chart lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(0, 2, 3, 8, 10, 13, 26, 29, 46)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid 12 xychart.jison.
 */
internal class XyJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<XyJisonToken, MermaidError> {
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
            val rules = XyJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = XyJisonTables.lexerPatterns[rule].match(remaining)) {
                    is GMResult.Ok -> if (match.value != null) {
                        matchedRule = rule
                        matchedText = match.value
                    }
                    is GMResult.Err -> return match
                }
                if (matchedRule != null) break
            }

            val rule = matchedRule ?: return lexError("Unrecognized XY Chart text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0, 1, 5 -> null
                2, 3, 4 -> {
                    if (rule == 2 || rule == 3) popState()
                    36
                }
                6 -> 10
                7 -> {
                    pushState("acc_title")
                    19
                }
                8 -> {
                    popState()
                    20
                }
                9 -> {
                    pushState("acc_descr")
                    21
                }
                10 -> {
                    popState()
                    22
                }
                11 -> {
                    pushState("acc_descr_multiline")
                    null
                }
                12 -> {
                    popState()
                    null
                }
                13 -> 23
                14, 15 -> 5
                16 -> 8
                17 -> {
                    pushState("axis_data")
                    12
                }
                18 -> {
                    pushState("axis_data")
                    14
                }
                19 -> {
                    pushState("axis_band_data")
                    24
                }
                20 -> 33
                21 -> {
                    pushState("data")
                    16
                }
                22 -> {
                    pushState("data")
                    18
                }
                23 -> {
                    pushState("data_inner")
                    24
                }
                24 -> 29
                25 -> {
                    popState()
                    26
                }
                26 -> {
                    popState()
                    null
                }
                27 -> {
                    pushState("string")
                    null
                }
                28 -> {
                    popState()
                    null
                }
                29 -> 30
                30 -> 24
                31 -> 26
                32 -> 44
                33 -> return lexError("Unexpected ':'")
                34 -> 45
                35 -> 28
                36 -> 46
                37 -> 47
                38 -> 49
                39 -> 51
                40 -> 48
                41 -> 42
                42 -> 50
                43 -> 43
                44 -> null
                45 -> 37
                46 -> {
                    grammarEofEmitted = true
                    38
                }
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty()) {
                    return lexError("XY Chart lexer rule $rule consumed no input")
                }
                continue
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
    ): GMResult<XyJisonToken, MermaidError> = GMResult.Ok(
        XyJisonToken(
            symbol = symbol,
            name = XyJisonTables.terminalNames[symbol] ?: "\$end",
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
    }
}
