package com.swithun.cmpmermaid.core.sequence.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12
 * sequenceDiagram.jison semantic actions.
 */
internal class SequenceJisonParser(
    private val diagramTitle: String? = null,
) {
    fun parse(source: String): GMResult<SequenceDb, MermaidError> {
        val db = SequenceDb()
        diagramTitle?.let(db::setDiagramTitle)
        val lexer = SequenceJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: SequenceJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = SequenceJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is SequenceJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is SequenceJisonCell.Reduce -> {
                    val production = SequenceJisonTables.productions
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
                    val goto = SequenceJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? SequenceJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                SequenceJisonCell.Accept -> return GMResult.Ok(db)
                is SequenceJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: SequenceDb,
        token: SequenceJisonToken,
    ): GMResult<Any?, MermaidError> {
        return when (production) {
        3 -> when (val document = actionList(values.getOrNull(1), token, production, 1)) {
            is GMResult.Ok -> when (val applied = db.apply(document.value)) {
                is GMResult.Ok -> GMResult.Ok(document.value)
                is GMResult.Err -> applied
            }
            is GMResult.Err -> document
        }
        4, 10 -> GMResult.Ok(mutableListOf<SequenceAction>())
        5, 11 -> appendActions(values, token, production)
        6, 7, 12, 13 -> GMResult.Ok(values.lastOrNull())
        8, 9, 14 -> GMResult.Ok(mutableListOf<SequenceAction>())
        16 -> updateParticipant(
            raw = values.getOrNull(1),
            token = token,
            production = production,
        ) {
            copy(operation = SequenceParticipantOperation.Create)
        }
        17 -> wrapSection(
            body = values.getOrNull(2),
            start = SequenceAction.BoxStart(
                db.parseBoxData(values.getOrNull(1) as? String ?: ""),
            ),
            end = SequenceAction.BoxEnd,
            token = token,
            production = production,
        )
        19 -> autoNumber(
            start = values.getOrNull(1),
            step = values.getOrNull(2),
            token = token,
            production = production,
        )
        20 -> autoNumber(
            start = values.getOrNull(1),
            step = "1",
            token = token,
            production = production,
        )
        21 -> GMResult.Ok(SequenceAction.AutoNumber(visible = false))
        22 -> GMResult.Ok(SequenceAction.AutoNumber(visible = true))
        23 -> participantSignal(
            actor = values.getOrNull(1),
            lineType = SequenceLineType.ACTIVE_START,
            token = token,
            production = production,
        )
        24 -> participantSignal(
            actor = values.getOrNull(1),
            lineType = SequenceLineType.ACTIVE_END,
            token = token,
            production = production,
        )
        30 -> title(values.lastOrNull(), prefixLength = 6, db, token, production)
        31 -> title(values.lastOrNull(), prefixLength = 7, db, token, production)
        32 -> accessibility(
            raw = values.lastOrNull(),
            setter = db::setAccessibilityTitle,
            token = token,
            production = production,
        )
        33, 34 -> accessibility(
            raw = values.lastOrNull(),
            setter = db::setAccessibilityDescription,
            token = token,
            production = production,
        )
        35 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.LOOP_START,
            endType = SequenceLineType.LOOP_END,
            db = db,
            token = token,
            production = production,
        )
        36 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.RECT_START,
            endType = SequenceLineType.RECT_END,
            db = db,
            token = token,
            production = production,
        )
        37 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.OPT_START,
            endType = SequenceLineType.OPT_END,
            db = db,
            token = token,
            production = production,
        )
        38 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.ALT_START,
            endType = SequenceLineType.ALT_END,
            db = db,
            token = token,
            production = production,
        )
        39 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.PAR_START,
            endType = SequenceLineType.PAR_END,
            db = db,
            token = token,
            production = production,
        )
        40 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.PAR_OVER_START,
            endType = SequenceLineType.PAR_END,
            db = db,
            token = token,
            production = production,
        )
        41 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.CRITICAL_START,
            endType = SequenceLineType.CRITICAL_END,
            db = db,
            token = token,
            production = production,
        )
        42 -> controlSection(
            body = values.getOrNull(2),
            title = values.getOrNull(1),
            startType = SequenceLineType.BREAK_START,
            endType = SequenceLineType.BREAK_END,
            db = db,
            token = token,
            production = production,
        )
        44 -> appendControlSection(
            values = values,
            lineType = SequenceLineType.CRITICAL_OPTION,
            db = db,
            token = token,
            production = production,
        )
        46 -> appendControlSection(
            values = values,
            lineType = SequenceLineType.PAR_AND,
            db = db,
            token = token,
            production = production,
        )
        48 -> appendControlSection(
            values = values,
            lineType = SequenceLineType.ALT_ELSE,
            db = db,
            token = token,
            production = production,
        )
        49, 51, 54, 56 -> participantWithDescription(
            raw = values.getOrNull(1),
            description = values.getOrNull(3),
            draw = if (production == 51 || production == 56) "actor" else "participant",
            token = token,
            production = production,
        )
        50, 52, 55, 57 -> updateParticipant(
            raw = values.getOrNull(1),
            token = token,
            production = production,
        ) {
            copy(
                draw = if (production == 52 || production == 57) "actor" else "participant",
                operation = SequenceParticipantOperation.Add,
            )
        }
        53 -> updateParticipant(
            raw = values.getOrNull(1),
            token = token,
            production = production,
        ) {
            copy(operation = SequenceParticipantOperation.Destroy)
        }
        58 -> note(
            actorValue = values.getOrNull(2),
            placementValue = values.getOrNull(1),
            textValue = values.getOrNull(3),
            token = token,
            production = production,
        )
        59 -> noteOver(
            actorValue = values.getOrNull(2),
            textValue = values.getOrNull(3),
            token = token,
            production = production,
        )
        60 -> actorMetadataAction(
            actorValue = values.getOrNull(1),
            textValue = values.getOrNull(2),
            create = { actor, text ->
                SequenceAction.Links(actor.actor, text, single = false)
            },
            token = token,
            production = production,
        )
        61 -> actorMetadataAction(
            actorValue = values.getOrNull(1),
            textValue = values.getOrNull(2),
            create = { actor, text ->
                SequenceAction.Links(actor.actor, text, single = true)
            },
            token = token,
            production = production,
        )
        62 -> actorMetadataAction(
            actorValue = values.getOrNull(1),
            textValue = values.getOrNull(2),
            create = { actor, text ->
                SequenceAction.Properties(actor.actor, text)
            },
            token = token,
            production = production,
        )
        63 -> actorMetadataAction(
            actorValue = values.getOrNull(1),
            textValue = values.getOrNull(2),
            create = { actor, text ->
                SequenceAction.Details(actor.actor, text)
            },
            token = token,
            production = production,
        )
        66 -> actorPair(
            first = values.getOrNull(0),
            second = values.getOrNull(2),
            token = token,
            production = production,
        )
        67 -> GMResult.Ok(values.firstOrNull())
        68 -> GMResult.Ok(SequencePlacement.LEFT_OF)
        69 -> GMResult.Ok(SequencePlacement.RIGHT_OF)
        in 70..75 -> signal(production, values, token)
        76 -> actorWithConfig(values, token, production)
        77 -> {
            val config = values.getOrNull(1) as? String
                ?: return semanticError(token, production, "config content", 1)
            GMResult.Ok(config.trim())
        }
        78 -> {
            val actor = values.firstOrNull() as? String
                ?: return semanticError(token, production, "actor id", 0)
            GMResult.Ok(SequenceParticipantAction(actor = actor))
        }
        in 79..104 -> GMResult.Ok(lineTypeForProduction(production))
        105 -> {
            val source = values.firstOrNull() as? String
                ?: return semanticError(token, production, "message text", 0)
            val trimmed = source.trim()
            GMResult.Ok(db.parseMessage(trimmed.drop(1)))
        }
            else -> GMResult.Ok(values.firstOrNull())
        }
    }

    private fun appendActions(
        values: List<Any?>,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val target = when (val parsed = actionList(values.getOrNull(0), token, production, 0)) {
            is GMResult.Ok -> parsed.value.toMutableList()
            is GMResult.Err -> return parsed
        }
        when (val actions = actionList(values.getOrNull(1), token, production, 1)) {
            is GMResult.Ok -> target += actions.value
            is GMResult.Err -> return actions
        }
        return GMResult.Ok(target)
    }

    private fun wrapSection(
        body: Any?,
        start: SequenceAction,
        end: SequenceAction,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val actions = when (val parsed = actionList(body, token, production, 2)) {
            is GMResult.Ok -> parsed.value.toMutableList()
            is GMResult.Err -> return parsed
        }
        actions.add(0, start)
        actions += end
        return GMResult.Ok(actions)
    }

    private fun controlSection(
        body: Any?,
        title: Any?,
        startType: Int,
        endType: Int,
        db: SequenceDb,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val source = title as? String
            ?: return semanticError(token, production, "section title", 1)
        return wrapSection(
            body = body,
            start = SequenceAction.Signal(
                message = db.parseMessage(source),
                lineType = startType,
            ),
            end = SequenceAction.Signal(lineType = endType),
            token = token,
            production = production,
        )
    }

    private fun appendControlSection(
        values: List<Any?>,
        lineType: Int,
        db: SequenceDb,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val target = when (val parsed = actionList(values.getOrNull(0), token, production, 0)) {
            is GMResult.Ok -> parsed.value.toMutableList()
            is GMResult.Err -> return parsed
        }
        val source = values.getOrNull(2) as? String
            ?: return semanticError(token, production, "section title", 2)
        target += SequenceAction.Signal(
            message = db.parseMessage(source),
            lineType = lineType,
        )
        when (val tail = actionList(values.getOrNull(3), token, production, 3)) {
            is GMResult.Ok -> target += tail.value
            is GMResult.Err -> return tail
        }
        return GMResult.Ok(target)
    }

    private fun participantWithDescription(
        raw: Any?,
        description: Any?,
        draw: String,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val source = description as? String
            ?: return semanticError(token, production, "participant description", 3)
        return updateParticipant(raw, token, production) {
            copy(
                description = SequenceDb().parseMessage(source),
                draw = draw,
                operation = SequenceParticipantOperation.Add,
            )
        }
    }

    private fun updateParticipant(
        raw: Any?,
        token: SequenceJisonToken,
        production: Int,
        update: SequenceParticipantAction.() -> SequenceParticipantAction,
    ): GMResult<Any?, MermaidError> {
        val participant = raw as? SequenceParticipantAction
            ?: return semanticError(token, production, "participant", 1)
        return GMResult.Ok(participant.update())
    }

    private fun autoNumber(
        start: Any?,
        step: Any?,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val startNumber = (start as? String)?.toDoubleOrNull()
            ?: return semanticError(token, production, "sequence start number", 1)
        val stepNumber = (step as? String)?.toDoubleOrNull()
            ?: return semanticError(token, production, "sequence step number", 2)
        return GMResult.Ok(
            SequenceAction.AutoNumber(
                start = startNumber,
                step = stepNumber,
                visible = true,
            ),
        )
    }

    private fun participantSignal(
        actor: Any?,
        lineType: Int,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val participant = actor as? SequenceParticipantAction
            ?: return semanticError(token, production, "participant", 1)
        return GMResult.Ok(
            SequenceAction.Signal(
                from = participant.actor,
                lineType = lineType,
            ),
        )
    }

    private fun title(
        raw: Any?,
        prefixLength: Int,
        db: SequenceDb,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val source = raw as? String
            ?: return semanticError(token, production, "title", 0)
        val value = source.drop(prefixLength)
        db.setDiagramTitle(value)
        return GMResult.Ok(mutableListOf<SequenceAction>())
    }

    private fun accessibility(
        raw: Any?,
        setter: (String) -> Unit,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val value = (raw as? String)?.trim()
            ?: return semanticError(token, production, "accessibility text", 1)
        setter(value)
        return GMResult.Ok(mutableListOf<SequenceAction>())
    }

    private fun note(
        actorValue: Any?,
        placementValue: Any?,
        textValue: Any?,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val actor = actorValue as? SequenceParticipantAction
            ?: return semanticError(token, production, "note participant", 2)
        val placement = placementValue as? Int
            ?: return semanticError(token, production, "note placement", 1)
        val text = textValue as? SequenceText
            ?: return semanticError(token, production, "note text", 3)
        return GMResult.Ok(
            mutableListOf(
                SequenceAction.Participant(actor),
                SequenceAction.Note(listOf(actor.actor), placement, text),
            ),
        )
    }

    private fun noteOver(
        actorValue: Any?,
        textValue: Any?,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val actors = when (actorValue) {
            is SequenceParticipantAction -> listOf(actorValue, actorValue)
            is List<*> -> actorValue.filterIsInstance<SequenceParticipantAction>()
            else -> emptyList()
        }
        if (actors.isEmpty()) {
            return semanticError(token, production, "note participants", 2)
        }
        val pair = if (actors.size == 1) listOf(actors[0], actors[0]) else actors.take(2)
        val text = textValue as? SequenceText
            ?: return semanticError(token, production, "note text", 3)
        return GMResult.Ok(
            buildList {
                pair.distinctBy(SequenceParticipantAction::actor).forEach {
                    add(SequenceAction.Participant(it))
                }
                add(
                    SequenceAction.Note(
                        actors = pair.map(SequenceParticipantAction::actor),
                        placement = SequencePlacement.OVER,
                        message = text,
                    ),
                )
            }.toMutableList(),
        )
    }

    private fun actorMetadataAction(
        actorValue: Any?,
        textValue: Any?,
        create: (SequenceParticipantAction, SequenceText) -> SequenceAction,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val actor = actorValue as? SequenceParticipantAction
            ?: return semanticError(token, production, "participant", 1)
        val text = textValue as? SequenceText
            ?: return semanticError(token, production, "metadata text", 2)
        return GMResult.Ok(
            mutableListOf(
                SequenceAction.Participant(actor),
                create(actor, text),
            ),
        )
    }

    private fun actorPair(
        first: Any?,
        second: Any?,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val from = first as? SequenceParticipantAction
            ?: return semanticError(token, production, "participant", 0)
        val to = second as? SequenceParticipantAction
            ?: return semanticError(token, production, "participant", 2)
        return GMResult.Ok(listOf(from, to))
    }

    private fun signal(
        production: Int,
        values: List<Any?>,
        token: SequenceJisonToken,
    ): GMResult<Any?, MermaidError> {
        val fromIndex = 0
        val typeIndex = if (production in 73..74) 2 else 1
        val toIndex = when (production) {
            74 -> 4
            75 -> 2
            else -> 3
        }
        val textIndex = when (production) {
            74 -> 5
            75 -> 3
            else -> 4
        }
        val from = values.getOrNull(fromIndex) as? SequenceParticipantAction
            ?: return semanticError(token, production, "source participant", fromIndex)
        val to = values.getOrNull(toIndex) as? SequenceParticipantAction
            ?: return semanticError(token, production, "destination participant", toIndex)
        val lineType = values.getOrNull(typeIndex) as? Int
            ?: return semanticError(token, production, "signal type", typeIndex)
        val text = values.getOrNull(textIndex) as? SequenceText
            ?: return semanticError(token, production, "signal text", textIndex)
        val centralConnection = when (production) {
            72 -> SequenceLineType.CENTRAL_CONNECTION
            73 -> SequenceLineType.CENTRAL_CONNECTION_REVERSE
            74 -> SequenceLineType.CENTRAL_CONNECTION_DUAL
            else -> 0
        }
        return GMResult.Ok(
            buildList {
                add(SequenceAction.Participant(from))
                add(SequenceAction.Participant(to))
                add(
                    SequenceAction.Signal(
                        from = from.actor,
                        to = to.actor,
                        message = text,
                        lineType = lineType,
                        activate = production == 70 || production == 72 || production == 74,
                        centralConnection = centralConnection,
                    ),
                )
                when (production) {
                    70 -> add(
                        SequenceAction.Signal(
                            from = to.actor,
                            lineType = SequenceLineType.ACTIVE_START,
                        ),
                    )
                    71 -> add(
                        SequenceAction.Signal(
                            from = from.actor,
                            lineType = SequenceLineType.ACTIVE_END,
                        ),
                    )
                    72 -> add(
                        SequenceAction.Signal(
                            from = to.actor,
                            lineType = SequenceLineType.CENTRAL_CONNECTION,
                        ),
                    )
                    73 -> add(
                        SequenceAction.Signal(
                            from = from.actor,
                            lineType = SequenceLineType.CENTRAL_CONNECTION_REVERSE,
                        ),
                    )
                    74 -> {
                        add(
                            SequenceAction.Signal(
                                from = to.actor,
                                lineType = SequenceLineType.CENTRAL_CONNECTION,
                            ),
                        )
                        add(
                            SequenceAction.Signal(
                                from = from.actor,
                                lineType = SequenceLineType.CENTRAL_CONNECTION_REVERSE,
                            ),
                        )
                    }
                }
            }.toMutableList(),
        )
    }

    private fun actorWithConfig(
        values: List<Any?>,
        token: SequenceJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val actor = values.getOrNull(0) as? String
            ?: return semanticError(token, production, "actor id", 0)
        val config = values.getOrNull(1) as? String
            ?: return semanticError(token, production, "actor config", 1)
        return GMResult.Ok(
            SequenceParticipantAction(
                actor = actor,
                operation = SequenceParticipantOperation.Add,
                config = config,
            ),
        )
    }

    private fun actionList(
        value: Any?,
        token: SequenceJisonToken,
        production: Int,
        index: Int,
    ): GMResult<List<SequenceAction>, MermaidError> = when (value) {
        null -> GMResult.Ok(emptyList())
        is SequenceAction -> GMResult.Ok(listOf(value))
        is SequenceParticipantAction -> GMResult.Ok(listOf(SequenceAction.Participant(value)))
        is List<*> -> {
            val actions = mutableListOf<SequenceAction>()
            value.forEach { item ->
                when (val nested = actionList(item, token, production, index)) {
                    is GMResult.Ok -> actions += nested.value
                    is GMResult.Err -> return nested
                }
            }
            GMResult.Ok(actions)
        }
        is String -> GMResult.Ok(emptyList())
        else -> semanticError(token, production, "sequence action", index)
    }

    private fun lineTypeForProduction(production: Int): Int = when (production) {
        79 -> SequenceLineType.SOLID_OPEN
        80 -> SequenceLineType.DOTTED_OPEN
        81 -> SequenceLineType.SOLID
        82 -> SequenceLineType.SOLID_TOP
        83 -> SequenceLineType.SOLID_BOTTOM
        84 -> SequenceLineType.STICK_TOP
        85 -> SequenceLineType.STICK_BOTTOM
        86 -> SequenceLineType.SOLID_TOP_DOTTED
        87 -> SequenceLineType.SOLID_BOTTOM_DOTTED
        88 -> SequenceLineType.STICK_TOP_DOTTED
        89 -> SequenceLineType.STICK_BOTTOM_DOTTED
        90 -> SequenceLineType.SOLID_ARROW_TOP_REVERSE
        91 -> SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE
        92 -> SequenceLineType.STICK_ARROW_TOP_REVERSE
        93 -> SequenceLineType.STICK_ARROW_BOTTOM_REVERSE
        94 -> SequenceLineType.SOLID_ARROW_TOP_REVERSE_DOTTED
        95 -> SequenceLineType.SOLID_ARROW_BOTTOM_REVERSE_DOTTED
        96 -> SequenceLineType.STICK_ARROW_TOP_REVERSE_DOTTED
        97 -> SequenceLineType.STICK_ARROW_BOTTOM_REVERSE_DOTTED
        98 -> SequenceLineType.BIDIRECTIONAL_SOLID
        99 -> SequenceLineType.DOTTED
        100 -> SequenceLineType.BIDIRECTIONAL_DOTTED
        101 -> SequenceLineType.SOLID_CROSS
        102 -> SequenceLineType.DOTTED_CROSS
        103 -> SequenceLineType.SOLID_POINT
        else -> SequenceLineType.DOTTED_POINT
    }

    private fun expectedMessage(
        state: Int,
        token: SequenceJisonToken,
    ): String {
        val expected = SequenceJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(SequenceJisonTables.terminalNames::get)
            .filterNot { it == "error" }
            .joinToString()
        return "Unexpected '${token.name}'${if (expected.isEmpty()) "" else "; expected $expected"}"
    }

    private fun <T> semanticError(
        token: SequenceJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> =
        parseError(token, "Production $production expected $expected at value $index")

    private fun <T> parseError(
        token: SequenceJisonToken,
        message: String,
    ): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(token.line, token.column + 1, message))
}
