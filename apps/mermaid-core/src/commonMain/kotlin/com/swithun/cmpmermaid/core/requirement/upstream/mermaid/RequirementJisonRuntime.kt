package com.swithun.cmpmermaid.core.requirement.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class RequirementJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface RequirementJisonCell {
    data class Goto(val state: Int) : RequirementJisonCell

    data class Shift(val state: Int) : RequirementJisonCell

    data class Reduce(val production: Int) : RequirementJisonCell

    object Accept : RequirementJisonCell
}

internal data class RequirementJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class RequirementJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Requirement lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        2, 4 -> GMResult.Ok(input.substringBefore('\n'))
        7 -> GMResult.Ok(input.substringBefore('}'))
        16 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid Requirement lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(2, 4, 7, 16)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid 12.0.0
 * requirementDiagram.jison.
 */
internal class RequirementJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<RequirementJisonToken, MermaidError> {
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
            val rules = RequirementJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = RequirementJisonTables.lexerPatterns[rule].match(remaining)) {
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

            val rule = matchedRule ?: return lexError("Unrecognized Requirement diagram text")
            var text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> INVALID_SYMBOL
                1 -> {
                    begin("acc_title")
                    9
                }
                2 -> {
                    popState()
                    10
                }
                3 -> {
                    begin("acc_descr")
                    11
                }
                4 -> {
                    popState()
                    12
                }
                5 -> {
                    begin("acc_descr_multiline")
                    null
                }
                6 -> {
                    popState()
                    null
                }
                7 -> 13
                8 -> 21
                9 -> 22
                10 -> 23
                11 -> 24
                12 -> 5
                13, 14, 15 -> null
                16 -> {
                    grammarEofEmitted = true
                    8
                }
                17 -> 6
                18 -> 27
                19 -> 40
                20 -> 29
                21 -> 32
                22 -> 31
                23 -> 34
                24 -> 36
                25 -> 38
                in 26..39 -> 41 + (rule - 26)
                in 40..46 -> 65 + (rule - 40)
                47 -> 57
                48 -> 59
                49 -> {
                    begin("style")
                    77
                }
                50 -> 75
                51 -> 81
                52 -> 88
                // Upstream returns PERCENT while the grammar declares PCT.
                53 -> INVALID_SYMBOL
                54 -> 86
                55 -> 84
                56 -> null
                57 -> {
                    begin("string")
                    null
                }
                58 -> {
                    popState()
                    null
                }
                59 -> {
                    begin("style")
                    72
                }
                60 -> {
                    begin("style")
                    74
                }
                61 -> 61
                62 -> 64
                63 -> 63
                64 -> {
                    begin("string")
                    null
                }
                65 -> {
                    popState()
                    null
                }
                66 -> 90
                67 -> {
                    text = text.trim()
                    89
                }
                68 -> 75
                69 -> 80
                70 -> 76
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty()) {
                    return lexError("Requirement lexer rule $rule consumed no input")
                }
                continue
            }
            if (symbol == INVALID_SYMBOL) {
                text = text.ifEmpty { remaining.take(1) }
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
    ): GMResult<RequirementJisonToken, MermaidError> = GMResult.Ok(
        RequirementJisonToken(
            symbol = symbol,
            name = RequirementJisonTables.terminalNames[symbol]
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
