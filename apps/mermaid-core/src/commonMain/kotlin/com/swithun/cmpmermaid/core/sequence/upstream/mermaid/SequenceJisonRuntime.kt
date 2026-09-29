package com.swithun.cmpmermaid.core.sequence.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

internal data class SequenceJisonProduction(
    val symbol: Int,
    val length: Int,
)

internal sealed interface SequenceJisonCell {
    data class Goto(val state: Int) : SequenceJisonCell

    data class Shift(val state: Int) : SequenceJisonCell

    data class Reduce(val production: Int) : SequenceJisonCell

    object Accept : SequenceJisonCell
}

internal data class SequenceJisonToken(
    val symbol: Int,
    val name: String,
    val text: String,
    val line: Int,
    val column: Int,
)

/**
 * Executes the regular expressions emitted by Mermaid's Sequence Jison lexer.
 * The manual cases are direct translations of lookahead-heavy rules that are
 * not consistently accepted by Android's ICU regular expression engine.
 */
internal class SequenceJisonLexerPattern(
    private val rule: Int,
    source: String,
) {
    private val regex = if (rule in PORTABLE_RULES) {
        GMResult.Ok(null)
    } else {
        try {
            GMResult.Ok(Regex(source, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            GMResult.Err(failure.message ?: "Invalid Sequence lexer pattern $rule")
        }
    }

    fun match(input: String): GMResult<String?, MermaidError> = when (rule) {
        2 -> GMResult.Ok(
            input.takeWhile { it.isWhitespace() && it != '\n' }
                .takeIf(String::isNotEmpty),
        )
        6 -> GMResult.Ok(matchNumber(input))
        9 -> GMResult.Ok("}".takeIf {
            input.startsWith("}") &&
                input.drop(1).matchesPrefix(Regex("""\s+as\s""", RegexOption.IGNORE_CASE))
        })
        11 -> GMResult.Ok(matchBefore(input, "@{", ID_DELIMITERS))
        12 -> GMResult.Ok(matchBeforeAlias(input))
        13 -> GMResult.Ok(matchActorAtLineEnd(input))
        22 -> GMResult.Ok("")
        49, 51 -> GMResult.Ok(input.substringBefore('\n'))
        60 -> GMResult.Ok(matchActor(input))
        92 -> GMResult.Ok("".takeIf { input.isEmpty() })
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
                    message = "Invalid Sequence lexer rule $rule: ${compiled.error}",
                ),
            )
        }
    }

    private fun matchNumber(input: String): String? {
        val candidate = NUMBER.find(input)
            ?.takeIf { it.range.first == 0 }
            ?.value
            ?: return null
        return candidate.takeIf {
            input.getOrNull(candidate.length)?.let(Char::isWhitespace) == true
        }
    }

    private fun matchBefore(
        input: String,
        suffix: String,
        delimiters: Set<Char>,
    ): String? {
        val suffixIndex = input.indexOf(suffix)
        if (suffixIndex <= 0) {
            return null
        }
        val candidate = input.substring(0, suffixIndex)
        return candidate.takeIf { value -> value.none(delimiters::contains) }
    }

    private fun matchBeforeAlias(input: String): String? {
        val alias = ALIAS_SUFFIX.find(input) ?: return null
        if (alias.range.first <= 0) {
            return null
        }
        val candidate = input.substring(0, alias.range.first)
        return candidate.takeIf { value ->
            value.none { it in ID_DELIMITERS || it.isWhitespace() }
        }
    }

    private fun matchActorAtLineEnd(input: String): String? {
        val terminator = input.indexOfFirst { it == '\n' || it == ';' || it == '#' }
            .let { if (it < 0) input.length else it }
        val candidate = input.substring(0, terminator)
        if (candidate.isEmpty() || candidate.any { it in ID_DELIMITERS }) {
            return null
        }
        return candidate
    }

    private fun matchActor(input: String): String? {
        var index = 0
        while (index < input.length) {
            val character = input[index]
            if (character in ACTOR_DELIMITERS) {
                break
            }
            if (index == 0 && character == '-') {
                break
            }
            if (
                character == '-' &&
                ARROW_PREFIXES.any { arrow -> input.startsWith(arrow, index) }
            ) {
                break
            }
            index += 1
        }
        return input.substring(0, index).takeIf { it.isNotEmpty() }
    }

    private fun String.matchesPrefix(regex: Regex): Boolean =
        regex.find(this)?.range?.first == 0

    private companion object {
        val PORTABLE_RULES = setOf(2, 6, 9, 11, 12, 13, 22, 49, 51, 60, 92)
        val NUMBER = Regex("""(?:[0-9]+(?:\.[0-9]{1,2})?|\.[0-9]{1,2})""")
        val ALIAS_SUFFIX = Regex("""\s+as\s""", RegexOption.IGNORE_CASE)
        val ID_DELIMITERS = setOf('<', '>', ':', '\n', ',', ';', '@')
        val ACTOR_DELIMITERS = setOf('/', '\\', '+', '(', ')', '<', '>', ':', '\n', ',', ';')
        val ARROW_PREFIXES = listOf(
            "--|\\",
            "--|/",
            "--\\\\",
            "--//",
            "/|--",
            "\\|--",
            "//--",
            "\\\\--",
            "-|\\",
            "-|/",
            "-\\\\",
            "-//",
            "/|-",
            "\\|-",
            "//-",
            "\\\\-",
            "<<-->>",
            "<<->>",
            "-->>",
            "->>",
            "-->",
            "->",
            "--x",
            "-x",
            "--)",
            "-)",
        )
    }
}

/**
 * Kotlin port of the Jison 0.4.18 lexer runtime used by Mermaid's generated
 * Sequence parser. Rule order, start conditions and actions map directly to
 * src/diagrams/sequence/parser/sequenceDiagram.jison.
 */
internal class SequenceJisonLexer(
    source: String,
) {
    private val input = source
    private val conditionStack = mutableListOf(INITIAL)
    private var offset = 0
    private var line = 1
    private var column = 0
    private var grammarEofEmitted = false

    fun next(): GMResult<SequenceJisonToken, MermaidError> {
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
            val rules = SequenceJisonTables.lexerConditions[condition]
                ?: return lexError("Unknown Jison lexer condition '$condition'")
            val remaining = input.substring(offset)
            var matchedRule: Int? = null
            var matchedText: String? = null
            for (rule in rules) {
                when (val match = SequenceJisonTables.lexerPatterns[rule].match(remaining)) {
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

            val rule = matchedRule ?: return lexError("Unrecognized sequence diagram text")
            var text = matchedText ?: return lexError("Jison lexer matched no text")
            val tokenLine = line
            val tokenColumn = column
            advance(text)

            val symbol = when (rule) {
                0 -> 5
                1, 2, 3, 4, 5 -> null
                6 -> 20
                7 -> {
                    begin("CONFIG")
                    75
                }
                8 -> 76
                9 -> {
                    popState()
                    begin("ALIAS")
                    77
                }
                10 -> {
                    popState()
                    popState()
                    77
                }
                11 -> {
                    text = text.trim()
                    73
                }
                12 -> {
                    text = text.trim()
                    begin("ALIAS")
                    73
                }
                13 -> {
                    text = text.trim()
                    popState()
                    73
                }
                14 -> {
                    popState()
                    10
                }
                15 -> {
                    text = text.trim()
                    popState()
                    10
                }
                16 -> {
                    begin("LINE")
                    15
                }
                17 -> {
                    begin("ID")
                    51
                }
                18 -> {
                    begin("ID")
                    53
                }
                19 -> 14
                20 -> {
                    begin("ID")
                    54
                }
                21 -> {
                    popState()
                    popState()
                    begin("LINE")
                    52
                }
                22 -> {
                    popState()
                    popState()
                    5
                }
                23 -> beginLine(37)
                24 -> beginLine(38)
                25 -> beginLine(39)
                26 -> beginLine(40)
                27 -> beginLine(50)
                28 -> beginLine(42)
                29 -> beginLine(44)
                30 -> beginLine(49)
                31 -> beginLine(45)
                32 -> beginLine(48)
                33 -> beginLine(47)
                34 -> {
                    popState()
                    16
                }
                35 -> 17
                36 -> 67
                37 -> 68
                38 -> 61
                39 -> 62
                40 -> 63
                41 -> 64
                42 -> 59
                43 -> 56
                44 -> {
                    begin("ID")
                    22
                }
                45 -> {
                    begin("ID")
                    24
                }
                46 -> 30
                47 -> 31
                48 -> {
                    begin("acc_title")
                    32
                }
                49 -> {
                    popState()
                    33
                }
                50 -> {
                    begin("acc_descr")
                    34
                }
                51 -> {
                    popState()
                    35
                }
                52 -> {
                    begin("acc_descr_multiline")
                    null
                }
                53 -> {
                    popState()
                    null
                }
                54 -> 36
                55 -> 6
                56 -> 19
                57 -> 21
                58 -> 66
                59 -> 5
                60 -> {
                    text = text.trim()
                    73
                }
                61 -> 80
                62 -> 97
                63 -> 98
                64 -> 99
                65 -> 78
                66 -> 79
                67 -> 100
                68 -> 101
                69 -> 102
                70 -> 103
                71 -> 85
                72 -> 86
                73 -> 87
                74 -> 88
                75 -> 93
                76 -> 94
                77 -> 95
                78 -> 96
                79 -> 81
                80 -> 82
                81 -> 83
                82 -> 84
                83 -> 89
                84 -> 90
                85 -> 91
                86 -> 92
                87, 88 -> 104
                89 -> 70
                90 -> 71
                91 -> 72
                92 -> {
                    grammarEofEmitted = true
                    5
                }
                93 -> 10
                else -> return lexError("Unknown Jison lexer action $rule")
            }
            if (symbol == null) {
                continue
            }
            return token(symbol, text, tokenLine, tokenColumn)
        }
    }

    private fun beginLine(symbol: Int): Int {
        begin("LINE")
        return symbol
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
    ): GMResult<SequenceJisonToken, MermaidError> = GMResult.Ok(
        SequenceJisonToken(
            symbol = symbol,
            name = SequenceJisonTables.terminalNames[symbol] ?: "\$end",
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
