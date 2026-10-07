package com.swithun.cmpmermaid.core.xychart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneColor

/**
 * Kotlin port of Mermaid 12.0.0 xychart.jison semantic actions.
 */
internal class XyJisonParser(
    private val diagramTitle: String? = null,
    private val plotColorPalette: List<SceneColor>,
    private val initialOrientation: String = "vertical",
    private val lineOffset: Int = 0,
) {
    fun parse(source: String): GMResult<XyDb, MermaidError> {
        val db = XyDb(
            diagramTitle = diagramTitle,
            plotColorPalette = plotColorPalette,
            initialOrientation = initialOrientation,
        )
        val lexer = XyJisonLexer(source)
        val states = mutableListOf(0)
        val values = mutableListOf<Any?>(null)
        var lookahead: XyJisonToken? = null

        while (true) {
            val state = states.last()
            val token = when (val current = lookahead) {
                null -> when (val next = lexer.next()) {
                    is GMResult.Ok -> next.value.also { lookahead = it }
                    is GMResult.Err -> return next.withLineOffset()
                }
                else -> current
            }
            val action = XyJisonTables.states
                .getOrNull(state)
                ?.get(token.symbol)
                ?: return parseError(token, expectedMessage(state, token))

            when (action) {
                is XyJisonCell.Shift -> {
                    states += action.state
                    values += token.text
                    lookahead = null
                }
                is XyJisonCell.Reduce -> {
                    val production = XyJisonTables.productions
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
                    val goto = XyJisonTables.states
                        .getOrNull(states.last())
                        ?.get(production.symbol)
                    val nextState = (goto as? XyJisonCell.Goto)?.state
                        ?: return parseError(
                            token,
                            "Missing goto for production ${action.production}",
                        )
                    states += nextState
                    values += reduced
                }
                XyJisonCell.Accept -> return GMResult.Ok(db)
                is XyJisonCell.Goto ->
                    return parseError(token, "Unexpected parser goto action")
            }
        }
    }

    private fun reduce(
        production: Int,
        values: List<Any?>,
        db: XyDb,
        token: XyJisonToken,
    ): GMResult<Any?, MermaidError> = when (production) {
        5 -> string(values, 0, token, production, "chart orientation").mapValue { value ->
            db.setOrientation(value)
            value
        }
        6 -> GMResult.Ok(null)
        9 -> text(values, 1, token, production).mapValue { value ->
            db.setDiagramTitle(value.text.trim())
            value
        }
        12 -> points(values, 1, token, production).flatMapValue { data ->
            db.setLineData(XyText(""), data).mapValue { data }
        }
        13 -> text(values, 1, token, production).flatMapValue { title ->
            points(values, 2, token, production).flatMapValue { data ->
                db.setLineData(title, data).mapValue { data }
            }
        }
        14 -> points(values, 1, token, production).flatMapValue { data ->
            db.setBarData(XyText(""), data).mapValue { data }
        }
        15 -> text(values, 1, token, production).flatMapValue { title ->
            points(values, 2, token, production).flatMapValue { data ->
                db.setBarData(title, data).mapValue { data }
            }
        }
        16 -> string(values, 1, token, production, "accessibility title").mapValue { value ->
            val trimmed = value.trim()
            db.setAccessibilityTitle(trimmed)
            trimmed
        }
        17, 18 -> string(
            values,
            if (production == 17) 1 else 0,
            token,
            production,
            "accessibility description",
        ).mapValue { value ->
            val trimmed = value.trim()
            db.setAccessibilityDescription(trimmed)
            trimmed
        }
        19 -> points(values, 1, token, production)
        20 -> point(values, 0, token, production).flatMapValue { first ->
            points(values, 2, token, production).mapValue { tail ->
                listOf(first) + tail
            }
        }
        21 -> point(values, 0, token, production).mapValue(::listOf)
        22 -> number(values, 0, token, production).flatMapValue { value ->
            string(values, 1, token, production, "point label").mapValue { label ->
                XyParsedDataPoint(value, label)
            }
        }
        23 -> number(values, 0, token, production).mapValue { value ->
            XyParsedDataPoint(value, "")
        }
        24 -> text(values, 0, token, production).mapValue { value ->
            db.setXAxisTitle(value)
            value
        }
        25 -> text(values, 0, token, production).mapValue { value ->
            db.setXAxisTitle(value)
            value
        }
        26 -> {
            db.setXAxisTitle(XyText(""))
            GMResult.Ok(null)
        }
        27 -> texts(values, 0, token, production).mapValue { categories ->
            db.setXAxisBand(categories)
            categories
        }
        28 -> number(values, 0, token, production).flatMapValue { min ->
            number(values, 2, token, production).flatMapValue { max ->
                db.setXAxisRangeData(min, max).mapValue { max }
            }
        }
        29 -> texts(values, 1, token, production)
        30 -> text(values, 0, token, production).flatMapValue { first ->
            texts(values, 2, token, production).mapValue { tail ->
                listOf(first) + tail
            }
        }
        31 -> text(values, 0, token, production).mapValue(::listOf)
        32 -> text(values, 0, token, production).mapValue { value ->
            db.setYAxisTitle(value)
            value
        }
        33 -> text(values, 0, token, production).mapValue { value ->
            db.setYAxisTitle(value)
            value
        }
        34 -> {
            db.setYAxisTitle(XyText(""))
            GMResult.Ok(null)
        }
        35 -> number(values, 0, token, production).flatMapValue { min ->
            number(values, 2, token, production).flatMapValue { max ->
                db.setYAxisRangeData(min, max).mapValue { max }
            }
        }
        39, 40, 41 -> string(
            values,
            0,
            token,
            production,
            "text",
        ).mapValue { value ->
            XyText(text = value, markdown = production == 41)
        }
        42 -> string(values, 0, token, production, "text token")
        43 -> string(values, 0, token, production, "text").flatMapValue { left ->
            string(values, 1, token, production, "text token").mapValue { right ->
                left + right
            }
        }
        else -> GMResult.Ok(values.firstOrNull())
    }

    private fun string(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
        expected: String,
    ): GMResult<String, MermaidError> {
        val value = values.getOrNull(index) as? String
            ?: return semanticError(token, production, expected, index)
        return GMResult.Ok(value)
    }

    private fun text(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
    ): GMResult<XyText, MermaidError> {
        val value = values.getOrNull(index) as? XyText
            ?: return semanticError(token, production, "text", index)
        return GMResult.Ok(value)
    }

    private fun point(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
    ): GMResult<XyParsedDataPoint, MermaidError> {
        val value = values.getOrNull(index) as? XyParsedDataPoint
            ?: return semanticError(token, production, "data point", index)
        return GMResult.Ok(value)
    }

    private fun points(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
    ): GMResult<List<XyParsedDataPoint>, MermaidError> {
        val raw = values.getOrNull(index) as? List<*>
            ?: return semanticError(token, production, "data points", index)
        if (raw.any { value -> value !is XyParsedDataPoint }) {
            return semanticError(token, production, "data points", index)
        }
        return GMResult.Ok(raw.filterIsInstance<XyParsedDataPoint>())
    }

    private fun texts(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
    ): GMResult<List<XyText>, MermaidError> {
        val raw = values.getOrNull(index) as? List<*>
            ?: return semanticError(token, production, "text list", index)
        if (raw.any { value -> value !is XyText }) {
            return semanticError(token, production, "text list", index)
        }
        return GMResult.Ok(raw.filterIsInstance<XyText>())
    }

    private fun number(
        values: List<Any?>,
        index: Int,
        token: XyJisonToken,
        production: Int,
    ): GMResult<Double, MermaidError> =
        string(values, index, token, production, "number").flatMapValue { value ->
            val parsed = value.toDoubleOrNull()
            if (parsed == null || !parsed.isFinite()) {
                parseError(token, "Invalid XY Chart number '$value'")
            } else {
                GMResult.Ok(parsed)
            }
        }

    private fun expectedMessage(
        state: Int,
        token: XyJisonToken,
    ): String {
        val expected = XyJisonTables.states
            .getOrNull(state)
            .orEmpty()
            .keys
            .mapNotNull(XyJisonTables.terminalNames::get)
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
        token: XyJisonToken,
        production: Int,
        expected: String,
        index: Int,
    ): GMResult<T, MermaidError> = parseError(
        token,
        "XY Chart parser production $production expected $expected at value $index",
    )

    private fun <T> parseError(
        token: XyJisonToken,
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
