package com.swithun.cmpmermaid.core.requirement.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12.0.0
 * requirementDiagram.jison semantic actions.
 */
internal class RequirementJisonParser(
    private val diagramTitle: String? = null,
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<RequirementDb, MermaidError> {
        val db = RequirementDb(diagramTitle)
        val lexer = RequirementJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: RequirementJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next.withLineOffset()
                }
                else -> current
            }
            val action = RequirementJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is RequirementJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is RequirementJisonCell.Reduce -> {
                    val production = RequirementJisonTables.productions
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
                    val goto = RequirementJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? RequirementJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                RequirementJisonCell.Accept -> return GMResult.Ok(db)
                is RequirementJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: RequirementDb,
        token: RequirementJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        4 -> string(values, 1, token, production, "accessibility title").mapValue { raw ->
            raw.trim().also(db::setAccessibilityTitle)
        }
        5 -> string(values, 1, token, production, "accessibility description").mapValue { raw ->
            raw.trim().also(db::setAccessibilityDescription)
        }
        6 -> string(
            values,
            0,
            token,
            production,
            "multiline accessibility description",
        ).mapValue { raw ->
            raw.trim().also(db::setAccessibilityDescription)
        }
        7 -> GMResult.Ok(emptyList<Any?>())
        17 -> sideEffect(db.setDirection("TB"))
        18 -> sideEffect(db.setDirection("BT"))
        19 -> sideEffect(db.setDirection("RL"))
        20 -> sideEffect(db.setDirection("LR"))
        21 -> requirement(values, db, token, production, classIndex = null)
        22 -> requirement(values, db, token, production, classIndex = 3)
        23 -> string(values, 2, token, production, "requirement id").mapValue { value ->
            db.setNewRequirementId(value)
            value
        }
        24 -> string(values, 2, token, production, "requirement text").mapValue { value ->
            db.setNewRequirementText(value)
            value
        }
        25 -> value<RequirementRisk>(
            values,
            2,
            token,
            production,
            "requirement risk",
        ).mapValue { risk ->
            db.setNewRequirementRisk(risk)
            risk
        }
        26 -> value<RequirementVerifyMethod>(
            values,
            2,
            token,
            production,
            "verification method",
        ).mapValue { method ->
            db.setNewRequirementVerifyMethod(method)
            method
        }
        29 -> GMResult.Ok(RequirementType.Requirement)
        30 -> GMResult.Ok(RequirementType.Functional)
        31 -> GMResult.Ok(RequirementType.Interface)
        32 -> GMResult.Ok(RequirementType.Performance)
        33 -> GMResult.Ok(RequirementType.Physical)
        34 -> GMResult.Ok(RequirementType.DesignConstraint)
        35 -> GMResult.Ok(RequirementRisk.Low)
        36 -> GMResult.Ok(RequirementRisk.Medium)
        37 -> GMResult.Ok(RequirementRisk.High)
        38 -> GMResult.Ok(RequirementVerifyMethod.Analysis)
        39 -> GMResult.Ok(RequirementVerifyMethod.Demonstration)
        40 -> GMResult.Ok(RequirementVerifyMethod.Inspection)
        41 -> GMResult.Ok(RequirementVerifyMethod.Test)
        42 -> element(values, db, token, production, classIndex = null)
        43 -> element(values, db, token, production, classIndex = 3)
        44 -> string(values, 2, token, production, "element type").mapValue { value ->
            db.setNewElementType(value)
            value
        }
        45 -> string(values, 2, token, production, "element document reference")
            .mapValue { value ->
                db.setNewElementDocRef(value)
                value
            }
        48 -> relationship(
            values = values,
            typeIndex = 2,
            sourceIndex = 4,
            destinationIndex = 0,
            db = db,
            token = token,
            production = production,
        )
        49 -> relationship(
            values = values,
            typeIndex = 2,
            sourceIndex = 0,
            destinationIndex = 4,
            db = db,
            token = token,
            production = production,
        )
        50 -> GMResult.Ok(RequirementRelationshipType.Contains)
        51 -> GMResult.Ok(RequirementRelationshipType.Copies)
        52 -> GMResult.Ok(RequirementRelationshipType.Derives)
        53 -> GMResult.Ok(RequirementRelationshipType.Satisfies)
        54 -> GMResult.Ok(RequirementRelationshipType.Verifies)
        55 -> GMResult.Ok(RequirementRelationshipType.Refines)
        56 -> GMResult.Ok(RequirementRelationshipType.Traces)
        57 -> stringList(values, 1, token, production, "class names").flatMapValue { ids ->
            stringList(values, 2, token, production, "class styles").mapValue { styles ->
                db.defineClass(ids, styles)
                values.firstOrNull()
            }
        }
        58 -> setClasses(values, idIndex = 1, classIndex = 2, db, token, production)
        59 -> string(values, 0, token, production, "styled node").flatMapValue { id ->
            stringList(values, 2, token, production, "class names").mapValue { classes ->
                db.setClass(listOf(id), classes)
                id
            }
        }
        60, 62 -> string(values, 0, token, production, "identifier")
            .mapValue(::listOf)
        61, 63 -> stringList(values, 0, token, production, "identifiers")
            .flatMapValue { ids ->
                string(values, 2, token, production, "identifier").mapValue { id ->
                    ids + id
                }
            }
        64 -> stringList(values, 1, token, production, "styled nodes")
            .flatMapValue { ids ->
                stringList(values, 2, token, production, "styles").mapValue { styles ->
                    db.setCssStyle(ids, styles)
                    values.firstOrNull()
                }
            }
        65 -> string(values, 0, token, production, "style").mapValue(::listOf)
        66 -> stringList(values, 0, token, production, "styles")
            .flatMapValue { styles ->
                string(values, 2, token, production, "style").mapValue { style ->
                    styles + style
                }
            }
        68 -> string(values, 0, token, production, "style").flatMapValue { left ->
            string(values, 1, token, production, "style component").mapValue { right ->
                left + right
            }
        }
        else -> GMResult.Ok(values.firstOrNull())
    }

    private fun requirement(
        values: List<Any?>,
        db: RequirementDb,
        token: RequirementJisonToken,
        production: Int,
        classIndex: Int?,
    ): GMResult<Any?, MermaidError> =
        value<RequirementType>(values, 0, token, production, "requirement type")
            .flatMapValue { type ->
                string(values, 1, token, production, "requirement name").flatMapValue { name ->
                    val classes = classIndex?.let { index ->
                        when (
                            val parsed = stringList(
                                values,
                                index,
                                token,
                                production,
                                "class names",
                            )
                        ) {
                            is GMResult.Ok -> parsed.value
                            is GMResult.Err -> return parsed
                        }
                    }
                    val requirement = db.addRequirement(name, type)
                    if (classes != null) {
                        db.setClass(listOf(name), classes)
                    }
                    GMResult.Ok(requirement)
                }
            }

    private fun element(
        values: List<Any?>,
        db: RequirementDb,
        token: RequirementJisonToken,
        production: Int,
        classIndex: Int?,
    ): GMResult<Any?, MermaidError> =
        string(values, 1, token, production, "element name").flatMapValue { name ->
            val classes = classIndex?.let { index ->
                when (
                    val parsed = stringList(
                        values,
                        index,
                        token,
                        production,
                        "class names",
                    )
                ) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
            }
            val element = db.addElement(name)
            if (classes != null) {
                db.setClass(listOf(name), classes)
            }
            GMResult.Ok(element)
        }

    private fun relationship(
        values: List<Any?>,
        typeIndex: Int,
        sourceIndex: Int,
        destinationIndex: Int,
        db: RequirementDb,
        token: RequirementJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> =
        value<RequirementRelationshipType>(
            values,
            typeIndex,
            token,
            production,
            "relationship type",
        ).flatMapValue { type ->
            string(values, sourceIndex, token, production, "relationship source")
                .flatMapValue { source ->
                    string(
                        values,
                        destinationIndex,
                        token,
                        production,
                        "relationship destination",
                    ).mapValue { destination ->
                        db.addRelationship(type, source, destination)
                        type
                    }
                }
        }

    private fun setClasses(
        values: List<Any?>,
        idIndex: Int,
        classIndex: Int,
        db: RequirementDb,
        token: RequirementJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> =
        stringList(values, idIndex, token, production, "styled nodes").flatMapValue { ids ->
            stringList(values, classIndex, token, production, "class names").mapValue { classes ->
                db.setClass(ids, classes)
                ids
            }
        }

    private inline fun <reified T> value(
        values: List<Any?>,
        index: Int,
        token: RequirementJisonToken,
        production: Int,
        expected: String,
    ): GMResult<T, MermaidError> {
        val value = values.getOrNull(index) as? T
            ?: return semanticError(token, production, expected, index)
        return GMResult.Ok(value)
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: RequirementJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> = value(values, index, token, production, expected)

    private fun stringList(
        values: List<Any?>,
        index: Int,
        token: RequirementJisonToken,
        production: Int,
        expected: String,
    ): GMResult<List<String>, MermaidError> {
        val raw = values.getOrNull(index) as? List<*>
            ?: return semanticError(token, production, expected, index)
        if (raw.any { item -> item !is String }) {
            return semanticError(token, production, expected, index)
        }
        return GMResult.Ok(raw.filterIsInstance<String>())
    }

    private fun sideEffect(@Suppress("UNUSED_PARAMETER") unit: Unit): GMResult<Any?, MermaidError> =
        GMResult.Ok(null)

    private fun expectedMessage(
        state: Int,
        token: RequirementJisonToken,
    ): String {
        val expected = RequirementJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(RequirementJisonTables.terminalNames::get)
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

    private fun <T> semanticError(
        token: RequirementJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = parseError(
        token,
        "Requirement parser production $production expected $expected at value $index",
    )

    private fun <T> parseError(
        token: RequirementJisonToken,
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
