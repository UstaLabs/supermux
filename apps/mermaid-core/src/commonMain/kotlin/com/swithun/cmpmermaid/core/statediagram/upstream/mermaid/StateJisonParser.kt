package com.swithun.cmpmermaid.core.statediagram.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.flatMap
import com.swithun.cmpmermaid.core.map

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12
 * stateDiagram.jison semantic actions.
 */
internal class StateJisonParser(
    private val diagramTitle: String? = null,
) {
    fun parse(source: String): GMResult<StateDb, MermaidError> {
        val db = StateDb(diagramTitle)
        val lexer = StateJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: StateJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = StateJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is StateJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is StateJisonCell.Reduce -> {
                    val production = StateJisonTables.productions
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
                    val goto = StateJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? StateJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                StateJisonCell.Accept -> return GMResult.Ok(db)
                is StateJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: StateDb,
        token: StateJisonToken,
    ): GMResult<Any?, MermaidError> {
        return when (production) {
        3 -> document(values.getOrNull(1), token, production).map { document ->
            db.setRootDocument(document)
            document
        }
        4 -> GMResult.Ok(mutableListOf<StateStatement>())
        5 -> document(values.getOrNull(0), token, production).flatMap { document ->
            when (val line = values.getOrNull(1)) {
                LINE_BREAK -> GMResult.Ok(document)
                is StateStatement -> GMResult.Ok(document.apply { add(line) })
                else -> semanticError(token, production, "state statement", 1)
            }
        }
        6, 7, 9, 10, 11, 12, 28, 48, 49 ->
            GMResult.Ok(values.firstOrNull())
        8 -> GMResult.Ok(LINE_BREAK)
        13 -> state(values.getOrNull(0), token, production, 0).flatMap { state ->
            string(values, 1, token, production, "state description").map { description ->
                state.descriptions = mutableListOf(db.trimColon(description))
                state
            }
        }
        14 -> relation(values, null, db, token, production)
        15 -> relation(values, 3, db, token, production)
        16 -> GMResult.Ok(StateIgnoredStatement("hide empty description"))
        17 -> GMResult.Ok(
            StateIgnoredStatement(
                values.joinToString(" ") { it as? String ?: "" }.trim(),
            ),
        )
        18 -> string(values, 0, token, production, "state id").map(::StateIgnoredStatement)
        19 -> string(values, 0, token, production, "state id").flatMap { id ->
            document(values.getOrNull(2), token, production).map { nested ->
                StateNodeStatement(
                    id = id,
                    document = nested,
                )
            }
        }
        20 -> {
            val description = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "state description", 0)
            val rawId = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "state id", 2)
            val parts = rawId.split(':')
            val descriptions = mutableListOf(description.trim())
            if (parts.size > 1) {
                descriptions += parts[1]
            }
            GMResult.Ok(
                StateNodeStatement(
                    id = parts.first(),
                    descriptions = descriptions,
                ),
            )
        }
        21 -> {
            val description = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "state description", 0)
            val id = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "state id", 2)
            document(values.getOrNull(4), token, production).map { nested ->
                StateNodeStatement(
                    id = id,
                    descriptions = mutableListOf(description),
                    document = nested,
                )
            }
        }
        22 -> typedState(values, StateNodeType.Fork, token, production)
        23 -> typedState(values, StateNodeType.Join, token, production)
        24 -> typedState(values, StateNodeType.Choice, token, production)
        25 -> GMResult.Ok(
            StateNodeStatement(
                id = db.getDividerId(),
                type = StateNodeType.Divider,
            ),
        )
        26 -> {
            val position = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "note position", 1)
            val id = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "note state", 2)
            val text = values.getOrNull(3) as? String
                ?: return semanticError(token, production, "note text", 3)
            GMResult.Ok(
                StateNodeStatement(
                    id = id.trim(),
                    note = StateNote(
                        position = position.trim(),
                        text = text.trim(),
                    ),
                ),
            )
        }
        27 -> GMResult.Ok(StateIgnoredStatement("floating note"))
        29 -> metadata(values, 1, db::setAccessibilityTitle, token, production)
        30, 31 -> metadata(
            values,
            values.lastIndex,
            db::setAccessibilityDescription,
            token,
            production,
        )
        32 -> click(values, 1, 2, 3, token, production)
        33 -> click(values, 1, 3, null, token, production)
        34, 35 -> twoStrings(
            values,
            1,
            2,
            token,
            production,
            "class id",
            "class styles",
        ) { id, styles ->
            StateClassDefStatement(id.trim(), styles.trim())
        }
        36 -> twoStrings(
            values,
            1,
            2,
            token,
            production,
            "state ids",
            "state styles",
        ) { ids, styles ->
            StateStyleStatement(ids.trim(), styles.trim())
        }
        37 -> twoStrings(
            values,
            1,
            2,
            token,
            production,
            "state ids",
            "CSS class",
        ) { ids, cssClass ->
            StateApplyClassStatement(ids.trim(), cssClass.trim())
        }
        38 -> GMResult.Ok(StateDirectionStatement("TB"))
        39 -> GMResult.Ok(StateDirectionStatement("BT"))
        40 -> GMResult.Ok(StateDirectionStatement("RL"))
        41 -> GMResult.Ok(StateDirectionStatement("LR"))
        42, 43 -> GMResult.Ok(values.firstOrNull())
        44, 45 -> string(values, 0, token, production, "state id").map { id ->
            StateNodeStatement(id = id.trim())
        }
        46, 47 -> {
            val id = values.getOrNull(0) as? String
                ?: return semanticError(token, production, "state id", 0)
            val cssClass = values.getOrNull(2) as? String
                ?: return semanticError(token, production, "CSS class", 2)
            GMResult.Ok(
                StateNodeStatement(
                    id = id.trim(),
                    classes = mutableListOf(cssClass.trim()),
                ),
            )
        }
            else -> GMResult.Ok(values.firstOrNull())
        }
    }

    private fun relation(
        values: List<Any?>,
        descriptionIndex: Int?,
        db: StateDb,
        token: StateJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(0) as? StateNodeStatement
            ?: return semanticError(token, production, "relation source", 0)
        val secondIndex = if (descriptionIndex == null) 2 else 2
        val second = values.getOrNull(secondIndex) as? StateNodeStatement
            ?: return semanticError(token, production, "relation target", secondIndex)
        val description = descriptionIndex?.let { index ->
            val raw = values.getOrNull(index) as? String
                ?: return semanticError(token, production, "relation description", index)
            db.trimColon(raw)
        }
        return GMResult.Ok(StateRelationStatement(first, second, description))
    }

    private fun typedState(
        values: List<Any?>,
        type: StateNodeType,
        token: StateJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> =
        string(values, 0, token, production, "state id").map { id ->
            StateNodeStatement(id = id, type = type)
        }

    private fun metadata(
        values: List<Any?>,
        index: Int,
        setter: (String) -> Unit,
        token: StateJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> =
        string(values, index, token, production, "accessibility metadata").map { value ->
            value.trim().also(setter)
            StateIgnoredStatement(value)
        }

    private fun click(
        values: List<Any?>,
        stateIndex: Int,
        urlIndex: Int,
        tooltipIndex: Int?,
        token: StateJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val state = values.getOrNull(stateIndex) as? StateNodeStatement
            ?: return semanticError(token, production, "click state", stateIndex)
        val url = values.getOrNull(urlIndex) as? String
            ?: return semanticError(token, production, "click URL", urlIndex)
        val tooltip = tooltipIndex?.let { index ->
            values.getOrNull(index) as? String
                ?: return semanticError(token, production, "click tooltip", index)
        }.orEmpty()
        return GMResult.Ok(StateClickStatement(state, url, tooltip))
    }

    private fun document(
        value: Any?,
        token: StateJisonToken,
        production: Int,
    ): GMResult<MutableList<StateStatement>, MermaidError> {
        val document = value as? MutableList<*>
            ?: return semanticError(token, production, "state document", 0)
        if (document.any { it !is StateStatement }) {
            return semanticError(token, production, "state document item", 0)
        }
        return GMResult.Ok(document.filterIsInstanceTo(mutableListOf()))
    }

    private fun state(
        value: Any?,
        token: StateJisonToken,
        production: Int,
        index: Int,
    ): GMResult<StateNodeStatement, MermaidError> {
        val state = value as? StateNodeStatement
            ?: return semanticError(token, production, "state", index)
        return GMResult.Ok(state)
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: StateJisonToken,
        production: Int,
        name: String,
    ): GMResult<String, MermaidError> =
        (values.getOrNull(index) as? String)
            ?.let { GMResult.Ok(it) }
            ?: semanticError(token, production, name, index)

    private fun twoStrings(
        values: List<Any?>,
        firstIndex: Int,
        secondIndex: Int,
        token: StateJisonToken,
        production: Int,
        firstName: String,
        secondName: String,
        transform: (String, String) -> StateStatement,
    ): GMResult<Any?, MermaidError> {
        val first = values.getOrNull(firstIndex) as? String
            ?: return semanticError(token, production, firstName, firstIndex)
        val second = values.getOrNull(secondIndex) as? String
            ?: return semanticError(token, production, secondName, secondIndex)
        return GMResult.Ok(transform(first, second))
    }

    private fun <T> semanticError(
        token: StateJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line,
            column = token.column + 1,
            message = "State parser production $production expected $expected at value $index",
        ),
    )

    private fun parseError(
        token: StateJisonToken,
        message: String,
    ): GMResult<StateDb, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line,
            column = token.column + 1,
            message = message,
        ),
    )

    private fun expectedMessage(
        state: Int,
        token: StateJisonToken,
    ): String {
        val expected = StateJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(StateJisonTables.terminalNames::get)
            .filterNot { it == "error" || it == "\$end" }
            .distinct()
            .sorted()
        return "Unexpected ${token.name} '${token.text}'. Expected: ${expected.joinToString()}"
    }

    private companion object {
        const val LINE_BREAK = "nl"
    }
}
