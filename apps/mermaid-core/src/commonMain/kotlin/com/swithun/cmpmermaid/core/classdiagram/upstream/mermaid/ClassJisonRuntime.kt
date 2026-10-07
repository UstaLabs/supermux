package com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class ClassJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface ClassJisonCell {
    data class Goto(val state: Int) : ClassJisonCell

    data class Shift(val state: Int) : ClassJisonCell

    data class Reduce(val production: Int) : ClassJisonCell

    object Accept : ClassJisonCell
}

internal data class ClassJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

/**
 * Executes the regular expressions emitted by Mermaid's Class Jison lexer.
 * The Unicode rule is translated to Kotlin character predicates because the
 * upstream generated expression exceeds Android ICU's reliable pattern size.
 */
internal class ClassJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Class lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        4 -> GMResult.Ok(
            input
                .takeIf { it.startsWith("%%") && !it.startsWith("%%{") }
                ?.let { source ->
                    val lineEnd = source.indexOf('\n')
                    if (lineEnd < 0) source else source.substring(0, lineEnd + 1)
                },
        )
        7, 9 -> GMResult.Ok(input.substringBefore('\n'))
        12 -> GMResult.Ok(input.substringBefore('}'))
        21 -> GMResult.Ok(input.takeWhile { it != '(' })
        25 -> GMResult.Ok(input.takeWhile { it != '"' })
        49 -> GMResult.Ok(input.takeWhile { it != '{' && it != '}' && it != '\n' })
        60 -> GMResult.Ok(input.takeWhile { it != '~' })
        95 -> GMResult.Ok(
            input.takeWhile(Char::isLetter).takeIf(String::isNotEmpty),
        )
        98 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid Class lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(4, 7, 9, 12, 21, 25, 49, 60, 95, 98)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * classDiagram parser. Rule order, start conditions and actions map directly
 * to src/diagrams/class/parser/classDiagram.jison.
 */
internal class ClassJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<ClassJisonToken, MermaidError> {
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
            val rules = ClassJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = ClassJisonTables.lexerPatterns[rule].match(remaining)) {
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

            val rule = matchedRule ?: return lexError("Unrecognized class diagram text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> 62
                1 -> 63
                2 -> 64
                3 -> 65
                4, 5 -> null
                6 -> {
                    begin("acc_title")
                    33
                }
                7 -> {
                    popState()
                    symbol("acc_title_value")
                }
                8 -> {
                    begin("acc_descr")
                    35
                }
                9 -> {
                    popState()
                    symbol("acc_descr_value")
                }
                10 -> {
                    begin("acc_descr_multiline")
                    null
                }
                11 -> {
                    popState()
                    null
                }
                12 -> symbol("acc_descr_multiline_value")
                13 -> 8
                14 -> null
                15, 16 -> 7
                17 -> symbol("EDGE_STATE")
                18 -> {
                    begin("callback_name")
                    null
                }
                19 -> {
                    popState()
                    null
                }
                20 -> {
                    popState()
                    begin("callback_args")
                    null
                }
                21 -> 79
                22 -> {
                    popState()
                    null
                }
                23 -> 80
                24 -> {
                    popState()
                    null
                }
                25 -> 13
                26 -> {
                    begin("string")
                    null
                }
                27 -> 82
                28 -> 57
                29 -> {
                    begin("namespace")
                    42
                }
                30 -> {
                    popState()
                    8
                }
                31 -> null
                32 -> {
                    begin("namespace-body")
                    39
                }
                33 -> {
                    popState()
                    unread(text)
                    null
                }
                34 -> {
                    popState()
                    41
                }
                35 -> return lexError("Unexpected end of namespace")
                36 -> 8
                37 -> null
                38 -> symbol("EDGE_STATE")
                39 -> {
                    begin("class")
                    48
                }
                40 -> {
                    popState()
                    8
                }
                41 -> null
                42 -> {
                    popState()
                    popState()
                    41
                }
                43 -> {
                    begin("class-body")
                    39
                }
                44 -> {
                    popState()
                    41
                }
                45 -> return lexError("Unexpected end of class body")
                46 -> symbol("EDGE_STATE")
                47 -> return lexError("Nested class bodies are not valid")
                48, 49 -> if (rule == 49) 51 else null
                50 -> 83
                51 -> 75
                52 -> 76
                53 -> 78
                54 -> 54
                55 -> 56
                56 -> 46
                57 -> 47
                58 -> 81
                59 -> {
                    popState()
                    null
                }
                60 -> 20
                61 -> {
                    begin("generic")
                    null
                }
                62 -> {
                    popState()
                    null
                }
                63 -> 103
                64 -> {
                    begin("bqstring")
                    null
                }
                65, 66, 67, 68 -> 77
                69, 70 -> 69
                71, 72 -> 71
                73 -> 70
                74 -> 68
                75 -> 72
                76 -> 73
                77 -> 74
                78 -> 22
                79 -> 44
                80 -> 100
                81 -> 18
                82 -> symbol("PLUS")
                83 -> 87
                84 -> 61
                85, 86 -> 89
                87 -> 90
                88, 89 -> symbol("EQUALS")
                90 -> 60
                91 -> 12
                92 -> 14
                93 -> symbol("PUNCTUATION")
                94 -> 86
                95 -> 102
                96, 97 -> 50
                98 -> {
                    grammarEofEmitted = true
                    9
                }
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty() && rule !in ZERO_LENGTH_STATE_RULES) {
                    return lexError("Class lexer rule $rule consumed no input")
                }
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun symbol(name: String): Int? = ClassJisonTables.symbolIds[name]

    private fun begin(condition: String) {
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
    ): GMResult<ClassJisonToken, MermaidError> = GMResult.Ok(
        ClassJisonToken(
            symbol = symbol,
            name = ClassJisonTables.terminalNames[symbol] ?: "\$end",
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
        val ZERO_LENGTH_STATE_RULES = setOf(7, 9, 12, 21, 25, 49, 60)
    }
}
