package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder

internal data class QuadrantPointStyle(
    val radius: Float? = null,
    val color: SceneColor? = null,
    val strokeColor: SceneColor? = null,
    val strokeWidth: Float? = null,
) {
    fun merge(overrides: QuadrantPointStyle): QuadrantPointStyle = QuadrantPointStyle(
        radius = overrides.radius ?: radius,
        color = overrides.color ?: color,
        strokeColor = overrides.strokeColor ?: strokeColor,
        strokeWidth = overrides.strokeWidth ?: strokeWidth,
    )
}

internal data class QuadrantPoint(
    val text: String,
    val className: String?,
    val x: Float,
    val y: Float,
    val style: QuadrantPointStyle,
)

internal data class QuadrantDocument(
    val title: String?,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
    val xAxisLeftText: String,
    val xAxisRightText: String,
    val yAxisBottomText: String,
    val yAxisTopText: String,
    val quadrantTexts: List<String>,
    val points: List<QuadrantPoint>,
    val classes: Map<String, QuadrantPointStyle>,
)

/**
 * Kotlin translation of Mermaid 12.0.0 quadrant-chart/parser/quadrant.jison
 * and quadrantDb.ts.
 */
internal class QuadrantParser(
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<QuadrantDocument, MermaidError> {
        val statements = when (val result = statements(source)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val headerIndex = statements.indexOfFirst { statement -> statement.text.isNotBlank() }
        if (headerIndex < 0) {
            return parseError(1, 1, "Expected quadrantChart")
        }
        val first = statements[headerIndex]
        if (!first.text.trim().equals("quadrantChart", ignoreCase = true)) {
            return parseError(first.line, 1, "Expected quadrantChart")
        }

        var title = diagramTitle
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null
        var xAxisLeftText = ""
        var xAxisRightText = ""
        var yAxisBottomText = ""
        var yAxisTopText = ""
        val quadrantTexts = MutableList(4) { "" }
        val points = mutableListOf<QuadrantPoint>()
        val classes = linkedMapOf<String, QuadrantPointStyle>()

        statements.drop(headerIndex + 1).forEach { statement ->
            val text = statement.text.trim()
            if (text.isEmpty()) {
                return@forEach
            }
            when {
                text.startsWith("title", ignoreCase = true) &&
                    text.getOrNull(5)?.isWhitespace() == true -> {
                    title = decode(text.substring(5).trim())
                }
                text.startsWith("accTitle", ignoreCase = true) -> {
                    val raw = text.substringAfter(':', missingDelimiterValue = "")
                    if (raw.isEmpty()) {
                        return parseError(statement.line, 1, "Expected accTitle value")
                    }
                    accessibilityTitle = decodeText(raw)
                }
                text.startsWith("accDescr", ignoreCase = true) -> {
                    val raw = when {
                        ':' in text -> text.substringAfter(':')
                        '{' in text && '}' in text ->
                            text.substringAfter('{').substringBeforeLast('}')
                        else -> ""
                    }
                    if (raw.isEmpty()) {
                        return parseError(statement.line, 1, "Expected accDescr value")
                    }
                    accessibilityDescription = decodeText(raw)
                }
                text.startsWith("x-axis", ignoreCase = true) -> {
                    val labels = axisLabels(text.substring(6), statement.line)
                    when (labels) {
                        is GMResult.Ok -> {
                            xAxisLeftText = labels.value.first
                            xAxisRightText = labels.value.second
                        }
                        is GMResult.Err -> return labels
                    }
                }
                text.startsWith("y-axis", ignoreCase = true) -> {
                    val labels = axisLabels(text.substring(6), statement.line)
                    when (labels) {
                        is GMResult.Ok -> {
                            yAxisBottomText = labels.value.first
                            yAxisTopText = labels.value.second
                        }
                        is GMResult.Err -> return labels
                    }
                }
                QUADRANT_PREFIX.matches(text) -> {
                    val match = QUADRANT_PREFIX.find(text)
                        ?: return parseError(statement.line, 1, "Invalid quadrant label")
                    val index = match.groupValues[1].toInt() - 1
                    quadrantTexts[index] = decodeText(match.groupValues[2])
                }
                text.startsWith("classDef", ignoreCase = true) -> {
                    val match = CLASS_DEFINITION.matchEntire(text)
                        ?: return parseError(statement.line, 1, "Invalid classDef statement")
                    val style = when (val result = parseStyles(match.groupValues[2], statement.line)) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    classes[match.groupValues[1]] = style
                }
                else -> {
                    val point = when (val result = parsePoint(text, statement.line)) {
                        is GMResult.Ok -> result.value
                        is GMResult.Err -> return result
                    }
                    // Mermaid: QuadrantBuilder.addPoints prepends each parsed point.
                    points.add(0, point)
                }
            }
        }

        return GMResult.Ok(
            QuadrantDocument(
                title = title?.takeIf(String::isNotBlank),
                accessibilityTitle = accessibilityTitle,
                accessibilityDescription = accessibilityDescription,
                xAxisLeftText = xAxisLeftText,
                xAxisRightText = xAxisRightText,
                yAxisBottomText = yAxisBottomText,
                yAxisTopText = yAxisTopText,
                quadrantTexts = quadrantTexts,
                points = points,
                classes = classes,
            ),
        )
    }

    private fun parsePoint(
        source: String,
        line: Int,
    ): GMResult<QuadrantPoint, MermaidError> {
        val delimiter = POINT_DELIMITER.find(source)
            ?: return parseError(line, 1, "Invalid Quadrant statement")
        val rawIdentity = source.substring(0, delimiter.range.first).trim()
        val rawPoint = source.substring(delimiter.range.first + 1).trim()
        val coordinates = POINT_COORDINATES.matchEntire(rawPoint)
            ?: return parseError(line, delimiter.range.first + 1, "Invalid point coordinates")
        val x = coordinates.groupValues[1].toFloatOrNull()
        val y = coordinates.groupValues[2].toFloatOrNull()
        if (x == null || y == null || x !in 0f..1f || y !in 0f..1f) {
            return parseError(line, delimiter.range.first + 1, "Point coordinates must be 0 to 1")
        }
        val classDelimiter = rawIdentity.lastIndexOf(":::")
        val rawLabel = if (classDelimiter >= 0) {
            rawIdentity.substring(0, classDelimiter)
        } else {
            rawIdentity
        }
        val className = if (classDelimiter >= 0) {
            rawIdentity.substring(classDelimiter + 3).trim()
        } else {
            null
        }
        if (rawLabel.isBlank() || className?.matches(CLASS_NAME) == false) {
            return parseError(line, 1, "Invalid point label or class")
        }
        val style = when (val result = parseStyles(coordinates.groupValues[3], line)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return GMResult.Ok(
            QuadrantPoint(
                text = decodeText(rawLabel),
                className = className,
                x = x,
                y = y,
                style = style,
            ),
        )
    }

    private fun parseStyles(
        source: String,
        line: Int,
    ): GMResult<QuadrantPointStyle, MermaidError> {
        var result = QuadrantPointStyle()
        source.split(',').map(String::trim).filter(String::isNotEmpty).forEach { declaration ->
            val separator = declaration.indexOf(':')
            if (separator <= 0 || separator == declaration.lastIndex) {
                return parseError(line, 1, "Invalid point style '$declaration'")
            }
            val key = declaration.substring(0, separator).trim().lowercase()
            val value = declaration.substring(separator + 1).trim()
            result = when (key) {
                "radius" -> {
                    val radius = value.toIntOrNull()
                        ?: return parseError(line, 1, invalidStyle(key, value, "number"))
                    result.copy(radius = radius.toFloat())
                }
                "color" -> result.copy(
                    color = parseHexColor(value)
                        ?: return parseError(line, 1, invalidStyle(key, value, "hex code")),
                )
                "stroke-color" -> result.copy(
                    strokeColor = parseHexColor(value)
                        ?: return parseError(line, 1, invalidStyle(key, value, "hex code")),
                )
                "stroke-width" -> {
                    val width = PIXEL_SIZE.matchEntire(value)?.groupValues?.get(1)?.toFloatOrNull()
                        ?: return parseError(
                            line,
                            1,
                            invalidStyle(key, value, "number of pixels (eg. 10px)"),
                        )
                    result.copy(strokeWidth = width)
                }
                else -> return parseError(line, 1, "Style named $key is not supported")
            }
        }
        return GMResult.Ok(result)
    }

    private fun axisLabels(
        source: String,
        line: Int,
    ): GMResult<Pair<String, String>, MermaidError> {
        val delimiter = source.indexOf("-->")
        val left = decodeText(if (delimiter >= 0) source.substring(0, delimiter) else source)
        if (left.isEmpty()) {
            return parseError(line, 1, "Expected axis label")
        }
        if (delimiter < 0) {
            return GMResult.Ok(left to "")
        }
        val right = decodeText(source.substring(delimiter + 3))
        return if (right.isEmpty()) {
            GMResult.Ok("$left ⟶ " to "")
        } else {
            GMResult.Ok(left to right)
        }
    }

    private fun statements(source: String): GMResult<List<Statement>, MermaidError> {
        val result = mutableListOf<Statement>()
        var inAccDescription = false
        val accDescription = StringBuilder()
        var accDescriptionLine = 0
        source.lineSequence().forEachIndexed { index, sourceLine ->
            val lineNumber = index + 1
            if (inAccDescription) {
                val closing = sourceLine.indexOf('}')
                if (closing >= 0) {
                    accDescription.append(sourceLine.substring(0, closing))
                    result += Statement(
                        line = accDescriptionLine,
                        text = "accDescr {${accDescription}}",
                    )
                    accDescription.clear()
                    inAccDescription = false
                    splitStatements(sourceLine.substring(closing + 1), lineNumber, result)
                } else {
                    if (accDescription.isNotEmpty()) accDescription.append('\n')
                    accDescription.append(sourceLine)
                }
                return@forEachIndexed
            }
            val cleaned = stripComment(sourceLine)
            val accStart = ACC_DESCRIPTION_START.find(cleaned)
            if (accStart != null && '}' !in cleaned.substring(accStart.range.last + 1)) {
                inAccDescription = true
                accDescriptionLine = lineNumber
                accDescription.append(cleaned.substring(accStart.range.last + 1))
            } else {
                splitStatements(cleaned, lineNumber, result)
            }
        }
        if (inAccDescription) {
            return parseError(accDescriptionLine, 1, "Unterminated accDescr block")
        }
        return GMResult.Ok(result)
    }

    private fun splitStatements(
        line: String,
        lineNumber: Int,
        output: MutableList<Statement>,
    ) {
        var quoted = false
        var markdown = false
        var start = 0
        line.forEachIndexed { index, char ->
            when {
                char == '"' && !markdown -> quoted = !quoted
                char == '`' && quoted -> markdown = !markdown
                char == ';' && !quoted && !markdown &&
                    !isHtmlEntityTerminator(line, index) -> {
                    output += Statement(lineNumber, line.substring(start, index))
                    start = index + 1
                }
            }
        }
        output += Statement(lineNumber, line.substring(start))
    }

    private fun isHtmlEntityTerminator(
        line: String,
        semicolonIndex: Int,
    ): Boolean {
        val ampersandIndex = line.lastIndexOf('&', semicolonIndex)
        if (ampersandIndex < 0) {
            return false
        }
        return HTML_ENTITY.matches(line.substring(ampersandIndex, semicolonIndex + 1))
    }

    private fun stripComment(line: String): String {
        var quoted = false
        var index = 0
        while (index < line.length - 1) {
            if (line[index] == '"') {
                quoted = !quoted
            }
            if (!quoted && line[index] == '%' && line[index + 1] == '%') {
                return line.substring(0, index)
            }
            index++
        }
        return line
    }

    private fun decodeText(source: String): String {
        val trimmed = source.trim()
        val unquoted = when {
            trimmed.length >= 4 && trimmed.startsWith("\"`") && trimmed.endsWith("`\"") ->
                trimmed.substring(2, trimmed.length - 2)
            trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"') ->
                trimmed.substring(1, trimmed.length - 1)
            else -> trimmed
        }
        return decode(unquoted)
    }

    private fun decode(source: String): String = MermaidHtmlEntityDecoder.decode(source)

    private fun parseHexColor(source: String): SceneColor? =
        if (HEX_COLOR.matches(source)) {
            CssColorParser.parse(
                if (source.startsWith('#')) source else "#$source",
            )
        } else {
            null
        }

    private fun invalidStyle(
        key: String,
        value: String,
        type: String,
    ): String = "Value for $key $value is invalid, please use a valid $type"

    private fun <T> parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line + lineOffset,
            column = column,
            message = message,
        ),
    )

    private data class Statement(
        val line: Int,
        val text: String,
    )

    companion object {
        private val QUADRANT_PREFIX =
            Regex("""(?i)^quadrant-([1-4])\s+(.+)$""")
        private val CLASS_DEFINITION =
            Regex("""(?i)^classDef\s+([A-Za-z0-9_]+)\s+(.+)$""")
        private val CLASS_NAME = Regex("""[A-Za-z0-9_]+""")
        private val POINT_DELIMITER = Regex(""":\s*\[""")
        private val POINT_COORDINATES =
            Regex("""^\[\s*(1|0(?:\.\d+)?)\s*,\s*(1|0(?:\.\d+)?)\s*]\s*(.*)$""")
        private val HEX_COLOR = Regex("""#?(?:[0-9A-Fa-f]{3}|[0-9A-Fa-f]{6})""")
        private val PIXEL_SIZE = Regex("""(\d+)px""")
        private val ACC_DESCRIPTION_START = Regex("""(?i)accDescr\s*\{""")
        private val HTML_ENTITY =
            Regex("""&(?:#[0-9]+|#x[0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]+);""")
    }
}
