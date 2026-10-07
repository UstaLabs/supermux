package com.swithun.cmpmermaid.core.block.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Portable matcher for Mermaid.js 12.0.0 block.jison lexer rules.
 */
internal class BlockJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Block lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        13 -> GMResult.Ok(input.substringBefore('"'))
        22, 28, 30, 32 -> GMResult.Ok(input.substringBefore('\n'))
        35 -> GMResult.Ok(input.substringBefore('}').takeIf(String::isNotEmpty))
        75 -> GMResult.Ok("".takeIf { input.isEmpty() })
        else -> when (val compiled = regex) {
            is GMResult.Ok -> try {
                GMResult.Ok(
                    compiled.value
                        ?.find(input)
                        ?.takeIf { match -> match.range.first == 0 }
                        ?.value,
                )
            } catch (failure: Exception) {
                GMResult.Err(
                    MermaidError.Parse(
                        line = 1,
                        column = 1,
                        message = "Block lexer rule $rule failed for ${input.length} characters: " +
                            (failure.message ?: "Unknown regular expression failure"),
                    ),
                )
            }
            is GMResult.Err -> GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Invalid Block lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private companion object {
        val PORTABLE_RULES = setOf(13, 22, 28, 30, 32, 35, 75)
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid 12 block.jison.
 */
internal class BlockJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false
    private var previousRule: Int? = null

    fun next(): GMResult<BlockJisonToken, MermaidError> {
        if (grammarEofEmitted) {
            return token(PHYSICAL_EOF, "", line, column)
        }

        while (true) {
            if (offset !in 0..input.length) {
                return lexError(
                    "Block lexer advanced beyond source after rule $previousRule: " +
                        "offset=$offset, size=${input.length}",
                )
            }
            val condition = conditionStack.lastOrNull() ?: INITIAL
            val rules = BlockJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = BlockJisonTables.lexerPatterns[rule].match(remaining)) {
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

            val rule = matchedRule ?: return lexError("Unrecognized Block text")
            var text = matchedText ?: return lexError("Jison lexer matched no text")
            if (text.length > remaining.length) {
                return lexError(
                    "Block lexer rule $rule matched ${text.length} characters " +
                        "from ${remaining.length} remaining characters",
                )
            }
            val tokenLine = line
            val tokenColumn = column
            advance(text)
            previousRule = rule

            val symbol = when (rule) {
                0 -> 10
                1 -> 29
                2 -> 10
                3, 4 -> null
                5 -> 5
                6 -> {
                    text = "-1"
                    28
                }
                7 -> {
                    text = text.replace(COLUMNS_PREFIX, "")
                    28
                }
                8 -> pushState("md_string").let { null }
                9 -> unresolvedToken("MD_STR")
                10 -> popState().let { null }
                11 -> pushState("string").let { null }
                12 -> popState().let { null }
                13 -> 18
                14 -> {
                    text = text.removePrefix("space:")
                    21
                }
                15 -> {
                    text = "1"
                    21
                }
                16 -> 42
                17 -> unresolvedToken("LINKSTYLE")
                18 -> unresolvedToken("INTERPOLATE")
                19 -> pushState("CLASSDEF").let { 39 }
                20 -> {
                    popState()
                    pushState("CLASSDEFID")
                    unresolvedToken("DEFAULT_CLASSDEF_ID")
                }
                21 -> {
                    popState()
                    pushState("CLASSDEFID")
                    40
                }
                22 -> popState().let { 41 }
                23 -> pushState("CLASS").let { 43 }
                24 -> {
                    popState()
                    pushState("CLASS_STYLE")
                    44
                }
                25 -> popState().let { 45 }
                26 -> pushState("STYLE_STMNT").let { 46 }
                27 -> {
                    popState()
                    pushState("STYLE_DEFINITION")
                    47
                }
                28 -> popState().let { 48 }
                29 -> pushState("acc_title").let { unresolvedToken("acc_title") }
                30 -> popState().let { unresolvedToken("acc_title_value") }
                31 -> pushState("acc_descr").let { unresolvedToken("acc_descr") }
                32 -> popState().let { unresolvedToken("acc_descr_value") }
                33 -> pushState("acc_descr_multiline").let { null }
                34 -> popState().let { null }
                35 -> unresolvedToken("acc_descr_multiline_value")
                36 -> 30
                in 37..53 -> popState().let { 36 }
                in 54..72 -> pushState("NODE").let { 35 }
                73 -> pushState("BLOCK_ARROW").let { 37 }
                74 -> 31
                75 -> {
                    grammarEofEmitted = true
                    8
                }
                76, 77 -> pushState("md_string").let { null }
                78 -> unresolvedToken("NODE_DESCR")
                79 -> popState().let { null }
                80, 81 -> pushState("string").let { null }
                82 -> unresolvedToken("NODE_DESCR")
                83 -> popState().let { null }
                84 -> pushState("ARROW_DIR").let { null }
                in 85..90 -> {
                    text = text.replace(LEADING_DIRECTION_SEPARATOR, "")
                    34
                }
                91 -> {
                    text = "]>"
                    popState()
                    popState()
                    38
                }
                in 92..95 -> 15
                in 96..98 -> pushState("LLABEL").let { 16 }
                99 -> pushState("md_string").let { null }
                100 -> pushState("string").let { 17 }
                in 101..103 -> popState().let { 15 }
                104 -> {
                    text = text.removePrefix(":")
                    27
                }
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                if (text.isEmpty()) {
                    return lexError("Block lexer rule $rule consumed no input")
                }
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun unresolvedToken(name: String): Int =
        BlockJisonTables.symbolIds[name] ?: INVALID_SYMBOL

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
    ): GMResult<BlockJisonToken, MermaidError> = GMResult.Ok(
        BlockJisonToken(
            symbol = symbol,
            name = BlockJisonTables.terminalNames[symbol]
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
        val COLUMNS_PREFIX = Regex("""columns\s+""")
        val LEADING_DIRECTION_SEPARATOR = Regex("""^,\s*""")
    }
}
