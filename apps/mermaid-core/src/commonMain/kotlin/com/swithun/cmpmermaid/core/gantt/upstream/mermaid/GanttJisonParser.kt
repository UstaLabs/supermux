package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import com.swithun.cmpmermaid.core.flatMap
import com.swithun.cmpmermaid.core.map

/**
 * Kotlin port of the Jison 0.4.18 LALR runtime plus Mermaid 12 gantt.jison
 * semantic actions.
 */
internal class GanttJisonParser(
    private val diagramTitle: String? = null,
    private val securityLevel: MermaidSecurityLevel = MermaidSecurityLevel.Strict,
    private val defaultWeekday: String = "sunday",
) {
    fun parse(source: String): GMResult<GanttDb, MermaidError> {
        val db = GanttDb(
            diagramTitle = diagramTitle,
            securityLevel = securityLevel,
            defaultWeekday = defaultWeekday,
        )
        val lexer = GanttJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: GanttJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next
                }
                else -> current
            }
            val action = GanttJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is GanttJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is GanttJisonCell.Reduce -> {
                    val production = GanttJisonTables.productions
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
                    val goto = GanttJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? GanttJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                GanttJisonCell.Accept -> return GMResult.Ok(db)
                is GanttJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: GanttDb,
        token: GanttJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        2, 6, 7 -> GMResult.Ok(mutableListOf<String>())
        3 -> document(values, token, production)
        4, 5, 25, 26, 32 -> GMResult.Ok(values.lastOrNull())
        8 -> sideEffect(db.setWeekday(GanttWeekday.Monday))
        9 -> sideEffect(db.setWeekday(GanttWeekday.Tuesday))
        10 -> sideEffect(db.setWeekday(GanttWeekday.Wednesday))
        11 -> sideEffect(db.setWeekday(GanttWeekday.Thursday))
        12 -> sideEffect(db.setWeekday(GanttWeekday.Friday))
        13 -> sideEffect(db.setWeekday(GanttWeekday.Saturday))
        14 -> sideEffect(db.setWeekday(GanttWeekday.Sunday))
        15 -> sideEffect(db.setWeekend(GanttWeekday.Friday))
        16 -> sideEffect(db.setWeekend(GanttWeekday.Saturday))
        17 -> directiveValue(values, 0, "dateFormat", token, production).map { value ->
            db.setDateFormat(value)
            value
        }
        18 -> {
            db.enableInclusiveEndDates()
            GMResult.Ok("")
        }
        19 -> {
            db.enableTopAxis()
            GMResult.Ok("")
        }
        20 -> directiveValue(values, 0, "axisFormat", token, production).map { value ->
            db.setAxisFormat(value)
            value
        }
        21 -> directiveValue(values, 0, "tickInterval", token, production).map { value ->
            db.setTickInterval(value)
            value
        }
        22 -> directiveValue(values, 0, "excludes", token, production).map { value ->
            db.setExcludes(value)
            value
        }
        23 -> directiveValue(values, 0, "includes", token, production).map { value ->
            db.setIncludes(value)
            value
        }
        24 -> directiveValue(values, 0, "todayMarker", token, production).map { value ->
            db.setTodayMarker(value)
            value
        }
        27 -> directiveValue(values, 0, "title", token, production).map { value ->
            db.setDiagramTitle(value)
            value
        }
        28 -> string(values, 1, token, production, "accessibility title").map { value ->
            db.setAccessibilityTitle(value)
            value.trim()
        }
        29 -> string(values, 1, token, production, "accessibility description").map { value ->
            db.setAccessibilityDescription(value)
            value.trim()
        }
        30 -> string(values, 0, token, production, "accessibility description").map { value ->
            db.setAccessibilityDescription(value)
            value.trim()
        }
        31 -> directiveValue(values, 0, "section", token, production).map { value ->
            db.addSection(value)
            value
        }
        33 -> string(values, 0, token, production, "task description").flatMap { description ->
            string(values, 1, token, production, "task data").flatMap { data ->
                db.addTask(description, data).map { "task" }
            }
        }
        in 34..40 -> clickStatement(production, values, db, token)
        in 41..47 -> GMResult.Ok(
            values.filterIsInstance<String>().joinToString(" "),
        )
        else -> GMResult.Ok(values.firstOrNull())
    }

    private fun document(
        values: List<Any?>,
        token: GanttJisonToken,
        production: Int,
    ): GMResult<Any?, MermaidError> {
        val rawDocument = values.getOrNull(0) as? List<*>
            ?: return semanticError(token, production, "document", 0)
        if (rawDocument.any { value -> value !is String }) {
            return semanticError(token, production, "document", 0)
        }
        val document = rawDocument.filterIsInstance<String>().toMutableList()
        val value = values.getOrNull(1)
        if (value is String && value.isNotEmpty()) {
            document += value
        }
        return GMResult.Ok(document)
    }

    private fun clickStatement(
        production: Int,
        values: List<Any?>,
        db: GanttDb,
        token: GanttJisonToken,
    ): GMResult<Any?, MermaidError> {
        val idIndex: Int
        val callbackIndex: Int?
        val callbackArgsIndex: Int?
        val linkIndex: Int?
        when (production) {
            34 -> {
                idIndex = 0
                callbackIndex = 1
                callbackArgsIndex = null
                linkIndex = null
            }
            35 -> {
                idIndex = 0
                callbackIndex = 1
                callbackArgsIndex = 2
                linkIndex = null
            }
            36 -> {
                idIndex = 0
                callbackIndex = 1
                callbackArgsIndex = null
                linkIndex = 2
            }
            37 -> {
                idIndex = 0
                callbackIndex = 1
                callbackArgsIndex = 2
                linkIndex = 3
            }
            38 -> {
                idIndex = 0
                callbackIndex = 2
                callbackArgsIndex = null
                linkIndex = 1
            }
            39 -> {
                idIndex = 0
                callbackIndex = 2
                callbackArgsIndex = 3
                linkIndex = 1
            }
            else -> {
                idIndex = 0
                callbackIndex = null
                callbackArgsIndex = null
                linkIndex = 1
            }
        }
        val ids = string(values, idIndex, token, production, "click task id")
            .getOrReturn { return it }
        callbackIndex?.let { index ->
            val callback = string(values, index, token, production, "callback")
                .getOrReturn { return it }
            val args = callbackArgsIndex?.let { argsIndex ->
                string(values, argsIndex, token, production, "callback arguments")
                    .getOrReturn { return it }
            }
            db.setClickEvent(ids, callback, args)
        }
        linkIndex?.let { index ->
            val link = string(values, index, token, production, "task link")
                .getOrReturn { return it }
            db.setLink(ids, link)
        }
        return GMResult.Ok(ids)
    }

    private fun directiveValue(
        values: List<Any?>,
        index: Int,
        name: String,
        token: GanttJisonToken,
        production: Int,
    ): GMResult<String, MermaidError> = string(
        values,
        index,
        token,
        production,
        name,
    ).map { value ->
        value.removePrefix(name).trim()
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: GanttJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return semanticError(token, production, expected, index)
        return GMResult.Ok(value)
    }

    private fun sideEffect(@Suppress("UNUSED_PARAMETER") unit: Unit): GMResult<Any?, MermaidError> =
        GMResult.Ok(null)

    private fun expectedMessage(
        state: Int,
        token: GanttJisonToken,
    ): String {
        val expected = GanttJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(GanttJisonTables.terminalNames::get)
            .filterNot { it == "error" }
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
        token: GanttJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = parseError(
        token,
        "Gantt parser production $production expected $expected at value $index",
    )

    private fun <T> parseError(
        token: GanttJisonToken,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = token.line,
            column = token.column + 1,
            message = message,
        ),
    )

    private inline fun <T> GMResult<T, MermaidError>.getOrReturn(
        onError: (GMResult.Err<MermaidError>) -> Nothing,
    ): T = when (this) {
        is GMResult.Ok -> value
        is GMResult.Err -> onError(this)
    }

}
