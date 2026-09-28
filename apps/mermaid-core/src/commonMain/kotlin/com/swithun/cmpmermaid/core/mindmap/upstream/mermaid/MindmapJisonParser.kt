package com.swithun.cmpmermaid.core.mindmap.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12 mindmap.jison
 * semantic actions.
 */
internal class MindmapJisonParser(
    private val padding: Float,
    private val maxNodeWidth: Float,
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<MindmapDb, MermaidError> {
        val db = MindmapDb(
            padding = padding,
            maxNodeWidth = maxNodeWidth,
            diagramTitle = diagramTitle,
        )
        val lexer = MindmapJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: MindmapJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next.withLineOffset()
                }
                else -> current
            }
            val action = MindmapJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is MindmapJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is MindmapJisonCell.Reduce -> {
                    val production = MindmapJisonTables.productions
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
                    val goto = MindmapJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? MindmapJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                MindmapJisonCell.Accept -> return GMResult.Ok(db)
                is MindmapJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: MindmapDb,
        token: MindmapJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        15 -> node(values, 1, token, production).flatMapValue { node ->
            string(values, 0, token, production, "indentation").flatMapValue { indentation ->
                db.addNode(
                    sourceLevel = indentation.length,
                    id = node.id,
                    description = node.description,
                    type = node.type,
                    line = token.line + lineOffset,
                    column = token.column + 1,
                ).mapValue { node }
            }
        }
        16 -> string(values, 1, token, production, "icon").flatMapValue { icon ->
            db.decorateNode(
                icon = icon,
                line = token.line + lineOffset,
                column = token.column + 1,
            ).mapValue { icon }
        }
        17 -> string(values, 1, token, production, "class").flatMapValue { classNames ->
            db.decorateNode(
                classNames = classNames,
                line = token.line + lineOffset,
                column = token.column + 1,
            ).mapValue { classNames }
        }
        19 -> node(values, 0, token, production).flatMapValue { node ->
            db.addNode(
                sourceLevel = 0,
                id = node.id,
                description = node.description,
                type = node.type,
                line = token.line + lineOffset,
                column = token.column + 1,
            ).mapValue { node }
        }
        20 -> string(values, 0, token, production, "icon").flatMapValue { icon ->
            db.decorateNode(
                icon = icon,
                line = token.line + lineOffset,
                column = token.column + 1,
            ).mapValue { icon }
        }
        21 -> string(values, 0, token, production, "class").flatMapValue { classNames ->
            db.decorateNode(
                classNames = classNames,
                line = token.line + lineOffset,
                column = token.column + 1,
            ).mapValue { classNames }
        }
        23, 24 -> GMResult.Ok(values.firstOrNull())
        25 -> string(values, 0, token, production, "node delimiter")
            .flatMapValue { start ->
                string(values, 1, token, production, "node description")
                    .flatMapValue { description ->
                        string(values, 2, token, production, "node delimiter")
                            .mapValue { end ->
                                ParsedNode(
                                    id = description,
                                    description = description,
                                    type = db.getType(start, end),
                                )
                            }
                    }
            }
        26 -> string(values, 0, token, production, "node identifier").mapValue { id ->
            ParsedNode(
                id = id,
                description = id,
                type = MindmapNodeType.Default,
            )
        }
        27 -> string(values, 0, token, production, "node identifier")
            .flatMapValue { id ->
                string(values, 1, token, production, "node delimiter")
                    .flatMapValue { start ->
                        string(values, 2, token, production, "node description")
                            .flatMapValue { description ->
                                string(values, 3, token, production, "node delimiter")
                                    .mapValue { end ->
                                        ParsedNode(
                                            id = id,
                                            description = description,
                                            type = db.getType(start, end),
                                        )
                                    }
                            }
                    }
            }
        else -> GMResult.Ok(values.firstOrNull())
    }

    private fun node(
        values: List<Any?>,
        index: Int,
        token: MindmapJisonToken,
        production: Int,
    ): GMResult<ParsedNode, MermaidError> {
        val value = values.getOrNull(index) as? ParsedNode
            ?: return parseError(
                token,
                "Mindmap parser production $production expected a node at value $index",
            )
        return GMResult.Ok(value)
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: MindmapJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return parseError(
                token,
                "Mindmap parser production $production expected $expected at value $index",
            )
        return GMResult.Ok(value)
    }

    private fun expectedMessage(
        state: Int,
        token: MindmapJisonToken,
    ): String {
        val expected = MindmapJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(MindmapJisonTables.terminalNames::get)
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
        token: MindmapJisonToken,
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

    private data class ParsedNode(
        val id: String,
        val description: String,
        val type: MindmapNodeType,
    )
}
