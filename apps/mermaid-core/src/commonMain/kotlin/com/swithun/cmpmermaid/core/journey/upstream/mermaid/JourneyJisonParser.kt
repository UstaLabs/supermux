package com.swithun.cmpmermaid.core.journey.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12 journey.jison
 * semantic actions.
 */
internal class JourneyJisonParser(
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<JourneyDb, MermaidError> {
        val db = JourneyDb(diagramTitle)
        val lexer = JourneyJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: JourneyJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next.withLineOffset()
                }
                else -> current
            }
            val action = JourneyJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is JourneyJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is JourneyJisonCell.Reduce -> {
                    val production = JourneyJisonTables.productions
                        .getOrNull(action.production)
                        ?: return parseError(
                            token,
                            "Unknown production ${action.production}",
                        )
                    if (production.length >= states.size || production.length >= values.size) {
                        return parseError(
                            token,
                            "Invalid parser stack for production ${action.production}",
                        )
                    }
                    val start = values.size - production.length
                    val rightHandSide = values.subList(start, values.size).toList()
                    val reduced = when (
                        val result = reduce(
                            production = action.production,
                            values = rightHandSide,
                            db = db,
                            token = token,
                        )
                    ) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    repeat(production.length) {
                        states.removeAt(states.lastIndex)
                        values.removeAt(values.lastIndex)
                    }
                    val goto = JourneyJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? JourneyJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                JourneyJisonCell.Accept -> return GMResult.Ok(db)
                is JourneyJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: JourneyDb,
        token: JourneyJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        2, 6, 7 -> GMResult.Ok(emptyList<Any?>())
        3, 4, 5 -> GMResult.Ok(values.firstOrNull())
        8 -> string(values, 0, token, production, "title").mapValue { raw ->
            raw.drop(6).also(db::setDiagramTitle)
        }
        9 -> string(
            values,
            1,
            token,
            production,
            "accessibility title",
        ).mapValue { raw ->
            raw.trim().also(db::setAccessibilityTitle)
        }
        10 -> string(
            values,
            1,
            token,
            production,
            "accessibility description",
        ).mapValue { raw ->
            raw.trim().also(db::setAccessibilityDescription)
        }
        11 -> string(
            values,
            0,
            token,
            production,
            "multiline accessibility description",
        ).mapValue { raw ->
            raw.trim().also(db::setAccessibilityDescription)
        }
        12 -> string(values, 0, token, production, "section").mapValue { raw ->
            raw.drop(8).also(db::addSection)
        }
        13 -> string(values, 0, token, production, "task name").flatMapValue { name ->
            string(values, 1, token, production, "task data").mapValue { data ->
                db.addTask(name, data)
                "task"
            }
        }
        else -> GMResult.Ok(values.firstOrNull())
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: JourneyJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return parseError(
                token,
                "Journey parser production $production expected $expected at value $index",
            )
        return GMResult.Ok(value)
    }

    private fun expectedMessage(
        state: Int,
        token: JourneyJisonToken,
    ): String {
        val expected = JourneyJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(JourneyJisonTables.terminalNames::get)
            .filterNot { name -> name == "error" }
            .distinct()
            .sorted()
        return buildString {
            append("Unexpected ")
            append(token.name)
            if (token.text.isNotEmpty()) append(" '${token.text}'")
            if (expected.isNotEmpty()) append("; expected ${expected.joinToString()}")
        }
    }

    private fun <T> parseError(
        token: JourneyJisonToken,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line + lineOffset,
            column = token.column + 1,
            message = message,
        ),
    )

    private fun <T> GMResult<T, MermaidError>.withLineOffset(): GMResult<T, MermaidError> =
        when (this) {
            is GMResult.Ok -> this
            is GMResult.Err -> {
                val error = error
                if (error is MermaidError.Parse) {
                    GMResult.Err(error.copy(line = error.line + lineOffset))
                } else {
                    this
                }
            }
        }

    private inline fun <T, R> GMResult<T, MermaidError>.mapValue(
        transform: (T) -> R,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> GMResult.Ok(transform(value))
        is GMResult.Err -> this
    }

    private inline fun <T, R> GMResult<T, MermaidError>.flatMapValue(
        transform: (T) -> GMResult<R, MermaidError>,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> transform(value)
        is GMResult.Err -> this
    }
}
