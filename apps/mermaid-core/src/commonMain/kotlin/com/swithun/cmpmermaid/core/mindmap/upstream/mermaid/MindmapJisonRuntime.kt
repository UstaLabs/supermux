package com.swithun.cmpmermaid.core.mindmap.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class MindmapJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface MindmapJisonCell {
    data class Goto(val state: Int) : MindmapJisonCell

    data class Shift(val state: Int) : MindmapJisonCell

    data class Reduce(val production: Int) : MindmapJisonCell

    object Accept : MindmapJisonCell
}

internal data class MindmapJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

internal class MindmapJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule == EOF_RULE) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Mindmap lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> =
        if (rule == EOF_RULE) {
            GMResult.Ok("".takeIf { input.isEmpty() })
        } else {
            when (val compiled = regex) {
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
                        message = "Invalid Mindmap lexer rule $rule: ${compiled.error}",
                    ),
                )
            }
        }

    private companion object {
        const val EOF_RULE = 20
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid 12 mindmap.jison.
 */
internal class MindmapJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<MindmapJisonToken, MermaidError> {
        if (grammarEofEmitted) {
            return token(PHYSICAL_EOF, "", line, column)
        }

        while (true) {
            val condition = conditionStack.lastOrNull() ?: INITIAL
            val rules = MindmapJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = MindmapJisonTables.lexerPatterns[rule].match(remaining)) {
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

            val rule = matchedRule ?: return lexError("Unrecognized Mindmap text")
            val text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> 6
                1 -> 8
                2 -> {
                    begin(CLASS)
                    null
                }
                3 -> {
                    popState()
                    16
                }
                4 -> {
                    popState()
                    null
                }
                5 -> {
                    begin(ICON)
                    null
                }
                6 -> 6
                7 -> 7
                8 -> 15
                9 -> {
                    popState()
                    null
                }
                in 10..17 -> {
                    begin(NODE)
                    19
                }
                18 -> 13
                19 -> 22
                20 -> {
                    grammarEofEmitted = true
                    11
                }
                21 -> {
                    begin(NSTR2)
                    null
                }
                22 -> 20
                23 -> {
                    popState()
                    null
                }
                24 -> {
                    begin(NSTR)
                    null
                }
                25 -> 20
                26 -> {
                    popState()
                    null
                }
                in 27..34 -> {
                    popState()
                    21
                }
                35, 36 -> 20
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty()) {
                    return lexError("Mindmap lexer rule $rule consumed no input")
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
    ): GMResult<MindmapJisonToken, MermaidError> = GMResult.Ok(
        MindmapJisonToken(
            symbol = symbol,
            name = MindmapJisonTables.terminalNames[symbol] ?: "\$end",
            text = text,
            line = tokenLine,
            column = tokenColumn,
        ),
    )

    private fun <T> lexError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(line, column + 1, message))

    private companion object {
        const val INITIAL = "INITIAL"
        const val CLASS = "CLASS"
        const val ICON = "ICON"
        const val NODE = "NODE"
        const val NSTR = "NSTR"
        const val NSTR2 = "NSTR2"
        const val PHYSICAL_EOF = 1
    }
}
