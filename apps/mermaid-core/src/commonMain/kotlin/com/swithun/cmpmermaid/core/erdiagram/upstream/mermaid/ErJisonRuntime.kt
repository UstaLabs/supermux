package com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class ErJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface ErJisonCell {
    data class Goto(val state: Int) : ErJisonCell

    data class Shift(val state: Int) : ErJisonCell

    data class Reduce(val production: Int) : ErJisonCell

    object Accept : ErJisonCell
}

internal data class ErJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class ErJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid ER lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        1, 3 -> GMResult.Ok(input.substringBefore('\n'))
        6 -> GMResult.Ok(input.substringBefore('}'))
        13 -> GMResult.Ok(input.takeRestrictedQuotedEntity())
        25 -> GMResult.Ok(input.takeErAttributeWord())
        27 -> GMResult.Ok(
            input.takeWhile { it != '`' }.takeIf(String::isNotEmpty),
        )
        78 -> GMResult.Ok(
            input.takeWhile(::isStyleText).takeIf(String::isNotEmpty),
        )
        80 -> GMResult.Ok(
            input.takeWhile(::isUnicodeText).takeIf(String::isNotEmpty),
        )
        82 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid ER lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private fun String.takeErAttributeWord(): String? {
        val first = firstOrNull()?.takeIf(::isAttributeFirst) ?: return null
        return buildString {
            append(first)
            this@takeErAttributeWord.drop(1)
                .takeWhile(::isAttributeRest)
                .forEach(::append)
        }
    }

    private fun String.takeRestrictedQuotedEntity(): String? {
        if (firstOrNull() != '"') return null
        var index = 1
        while (index < length) {
            when (val character = this[index]) {
                '"' -> return substring(0, index + 1).takeIf { index > 1 }
                '%', '\r', '\n', '\u000B', '\b', '\\' -> return null
                else -> index += 1
            }
        }
        return null
    }

    private fun isAttributeFirst(character: Char): Boolean =
        character == '*' ||
            character == '_' ||
            character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character.code >= 0x00C0

    private fun isAttributeRest(character: Char): Boolean =
        isAttributeFirst(character) ||
            character in '0'..'9' ||
            character in "-[]().,"

    private fun isStyleText(character: Char): Boolean =
        character.code >= 0x0080 ||
            character == '_' ||
            character == '-' ||
            character == '*' ||
            character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9'

    private fun isUnicodeText(character: Char): Boolean =
        isStyleText(character) || character == '.'

    private companion object {
        val PORTABLE_RULES = setOf(1, 3, 6, 13, 25, 27, 78, 80, 82)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * erDiagram parser. Rule order, conditions, and actions map directly to
 * packages/mermaid/src/diagrams/er/parser/erDiagram.jison.
 */
internal class ErJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<ErJisonToken, MermaidError> {
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
            val rules = ErJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = ErJisonTables.lexerPatterns[rule].match(remaining)) {
                    is GMResult.Ok -> if (match.value != null) {
                        matchedRule = rule
                        matchedText = match.value
                    }
                    is GMResult.Err -> return match
                }
                if (matchedRule != null) break
            }

            val rule = matchedRule ?: return lexError("Unrecognized ER diagram text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> {
                    begin("acc_title")
                    23
                }
                1 -> {
                    popState()
                    symbol("acc_title_value")
                }
                2 -> {
                    begin("acc_descr")
                    25
                }
                3 -> {
                    popState()
                    symbol("acc_descr_value")
                }
                4 -> {
                    begin("acc_descr_multiline")
                    null
                }
                5 -> {
                    popState()
                    null
                }
                6 -> symbol("acc_descr_multiline_value")
                7 -> 37
                8 -> 38
                9 -> 39
                10 -> 40
                11 -> null
                12 -> 9
                13 -> 53
                14 -> 76
                15 -> 4
                16 -> {
                    begin("block")
                    16
                }
                17, 18 -> 52
                19 -> 45
                20 -> 14
                21 -> 12
                22 -> null
                23 -> 65
                24, 25 -> 61
                26 -> {
                    begin("block_bq")
                    null
                }
                27 -> 61
                28 -> {
                    popState()
                    null
                }
                29 -> 66
                30 -> null
                31 -> {
                    popState()
                    18
                }
                32 -> symbol(text)
                    ?: return lexError("Invalid ER attribute token '$text'")
                33 -> 19
                34 -> 20
                35 -> {
                    begin("style")
                    47
                }
                36 -> {
                    popState()
                    9
                }
                37 -> null
                38 -> 12
                39 -> 45
                40 -> 52
                41 -> {
                    begin("style")
                    41
                }
                42 -> 46
                43 -> 34
                44 -> 33
                45 -> 69
                46, 47, 48 -> 71
                49, 50 -> 69
                51, 52, 53, 54, 55 -> 70
                56 -> 71
                57 -> 70
                58 -> 71
                59, 60 -> 72
                61 -> 54
                62, 63, 64 -> 72
                65 -> 55
                66 -> 51
                67 -> 72
                68 -> 69
                69 -> 70
                70 -> 71
                71 -> 73
                72, 75, 76, 77 -> 74
                73, 74 -> 75
                78 -> 44
                79 -> 50
                80 -> 43
                81 -> symbol(text)
                    ?: return lexError("Invalid ER token '$text'")
                82 -> {
                    grammarEofEmitted = true
                    6
                }
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty() && rule !in ZERO_LENGTH_STATE_RULES) {
                    return lexError("ER lexer rule $rule consumed no input")
                }
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun symbol(name: String): Int? = ErJisonTables.symbolIds[name]

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
    ): GMResult<ErJisonToken, MermaidError> = GMResult.Ok(
        ErJisonToken(
            symbol = symbol,
            name = ErJisonTables.terminalNames[symbol] ?: "\$end",
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
        val ZERO_LENGTH_STATE_RULES = setOf(1, 3, 6, 82)
    }
}
