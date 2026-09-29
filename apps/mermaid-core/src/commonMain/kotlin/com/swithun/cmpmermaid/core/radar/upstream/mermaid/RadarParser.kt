package com.swithun.cmpmermaid.core.radar.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRadarOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/parser/src/language/radar/radar.langium,
 * packages/parser/src/language/common/valueConverter.ts, and
 * packages/mermaid/src/diagrams/radar/parser.ts -> populate.
 */
internal class RadarParser(
    private val config: MermaidRadarOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<RadarDb, MermaidError> {
        val cursor = Cursor(
            source = source.replace("\r\n", "\n").replace('\r', '\n'),
            lineOffset = lineOffset,
        )
        cursor.skipTrivia()
        when (val header = cursor.parseHeader()) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return header
        }

        var title: String? = null
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null
        val axes = mutableListOf<RadarAstAxis>()
        val curves = mutableListOf<RadarAstCurve>()
        val options = mutableListOf<RadarAstOption>()

        while (true) {
            cursor.skipTrivia()
            if (cursor.isAtEnd()) {
                break
            }
            when {
                cursor.hasKeyword(TITLE_KEYWORD) -> {
                    title = cursor.parseInlineMetadata(TITLE_KEYWORD)
                }
                cursor.hasKeyword(ACCESSIBILITY_TITLE_KEYWORD) -> {
                    when (val parsed = cursor.parseColonMetadata(ACCESSIBILITY_TITLE_KEYWORD)) {
                        is GMResult.Ok -> accessibilityTitle = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword(ACCESSIBILITY_DESCRIPTION_KEYWORD) -> {
                    when (val parsed = cursor.parseAccessibilityDescription()) {
                        is GMResult.Ok -> accessibilityDescription = parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword(AXIS_KEYWORD) -> {
                    when (val parsed = cursor.parseAxes()) {
                        is GMResult.Ok -> axes += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.hasKeyword(CURVE_KEYWORD) -> {
                    when (val parsed = cursor.parseCurves()) {
                        is GMResult.Ok -> curves += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                cursor.isOptionStart() -> {
                    when (val parsed = cursor.parseOptions()) {
                        is GMResult.Ok -> options += parsed.value
                        is GMResult.Err -> return parsed
                    }
                }
                else -> return cursor.error("Expected a Radar statement")
            }
        }

        val ast = RadarAst(
            title = title,
            accessibilityTitle = accessibilityTitle,
            accessibilityDescription = accessibilityDescription,
            axes = axes,
            curves = curves,
            options = options,
        )
        val db = RadarDb(config = config, diagramTitle = diagramTitle)
        ast.accessibilityDescription?.takeIf(String::isNotEmpty)
            ?.let(db::setAccessibilityDescription)
        ast.accessibilityTitle?.takeIf(String::isNotEmpty)
            ?.let(db::setAccessibilityTitle)
        ast.title?.takeIf(String::isNotEmpty)?.let(db::setDiagramTitle)
        db.setAxes(ast.axes)
        when (val result = db.setCurves(ast.curves)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        db.setOptions(ast.options)
        return GMResult.Ok(db)
    }

    private class Cursor(
        private val source: String,
        private val lineOffset: Int,
    ) {
        private var index: Int = 0

        fun isAtEnd(): Boolean = index >= source.length

        fun parseHeader(): GMResult<Unit, MermaidError> {
            if (!source.startsWith(HEADER, index)) {
                return error("Expected 'radar-beta' diagram header")
            }
            val afterHeader = index + HEADER.length
            val next = source.getOrNull(afterHeader)
            if (next != null && next.isIdentifierPart()) {
                return error("Expected 'radar-beta' diagram header")
            }
            index = afterHeader
            skipHorizontalWhitespace()
            if (source.getOrNull(index) == ':') {
                index += 1
            }
            return GMResult.Ok(Unit)
        }

        fun hasKeyword(keyword: String): Boolean {
            if (!source.startsWith(keyword, index)) {
                return false
            }
            val next = source.getOrNull(index + keyword.length)
            return next == null || !next.isIdentifierPart()
        }

        fun isOptionStart(): Boolean =
            OPTION_KEYWORDS.any(::hasKeyword)

        fun parseInlineMetadata(keyword: String): String {
            index += keyword.length
            skipHorizontalWhitespace()
            return readLineValue()
        }

        fun parseColonMetadata(
            keyword: String,
        ): GMResult<String, MermaidError> {
            index += keyword.length
            skipHorizontalWhitespace()
            if (source.getOrNull(index) != ':') {
                return error("Expected ':' after $keyword")
            }
            index += 1
            skipHorizontalWhitespace()
            return GMResult.Ok(readLineValue())
        }

        fun parseAccessibilityDescription(): GMResult<String, MermaidError> {
            index += ACCESSIBILITY_DESCRIPTION_KEYWORD.length
            skipHorizontalWhitespace()
            if (source.getOrNull(index) == ':') {
                index += 1
                skipHorizontalWhitespace()
                return GMResult.Ok(readLineValue())
            }
            skipWhitespace()
            if (source.getOrNull(index) != '{') {
                return error("Expected ':' or '{' after accDescr")
            }
            val opening = index
            index += 1
            val end = source.indexOf('}', startIndex = index)
            if (end < 0) {
                return errorAt(opening, "Unterminated accDescr block")
            }
            val value = normalizeMultiline(source.substring(index, end))
            index = end + 1
            skipHorizontalWhitespace()
            if (!isAtEnd() && source.getOrNull(index) != '\n' && !source.startsWith("%%", index)) {
                return error("Unexpected content after accDescr block")
            }
            return GMResult.Ok(value)
        }

        fun parseAxes(): GMResult<List<RadarAstAxis>, MermaidError> {
            index += AXIS_KEYWORD.length
            skipHorizontalWhitespace()
            val result = mutableListOf<RadarAstAxis>()
            while (true) {
                val location = index
                val name = when (val parsed = parseIdentifier("Expected an axis name")) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                skipHorizontalWhitespace()
                val label = if (source.getOrNull(index) == '[') {
                    index += 1
                    skipHorizontalWhitespace()
                    val parsed = when (val value = parseString()) {
                        is GMResult.Ok -> value.value
                        is GMResult.Err -> return value
                    }
                    skipHorizontalWhitespace()
                    if (source.getOrNull(index) != ']') {
                        return error("Expected ']' after axis label")
                    }
                    index += 1
                    parsed
                } else {
                    null
                }
                result += RadarAstAxis(
                    name = name,
                    label = label,
                    line = lineAt(location),
                    column = columnAt(location),
                )
                skipHorizontalWhitespace()
                if (source.getOrNull(index) != ',') {
                    break
                }
                index += 1
                skipHorizontalWhitespace()
                if (source.getOrNull(index) == '\n' || isAtEnd()) {
                    return error("Expected an axis name after ','")
                }
            }
            return GMResult.Ok(result)
        }

        fun parseCurves(): GMResult<List<RadarAstCurve>, MermaidError> {
            index += CURVE_KEYWORD.length
            skipHorizontalWhitespace()
            val result = mutableListOf<RadarAstCurve>()
            while (true) {
                val location = index
                val name = when (val parsed = parseIdentifier("Expected a curve name")) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                skipHorizontalWhitespace()
                val label = if (source.getOrNull(index) == '[') {
                    index += 1
                    skipHorizontalWhitespace()
                    val parsed = when (val value = parseString()) {
                        is GMResult.Ok -> value.value
                        is GMResult.Err -> return value
                    }
                    skipHorizontalWhitespace()
                    if (source.getOrNull(index) != ']') {
                        return error("Expected ']' after curve label")
                    }
                    index += 1
                    parsed
                } else {
                    null
                }
                skipHorizontalWhitespace()
                if (source.getOrNull(index) != '{') {
                    return error("Expected '{' with at least one curve entry")
                }
                val entries = when (val parsed = parseEntries()) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                result += RadarAstCurve(
                    name = name,
                    label = label,
                    entries = entries,
                    line = lineAt(location),
                    column = columnAt(location),
                )
                skipHorizontalWhitespace()
                if (source.getOrNull(index) != ',') {
                    break
                }
                index += 1
                skipHorizontalWhitespace()
                if (source.getOrNull(index) == '\n' || isAtEnd()) {
                    return error("Expected a curve after ','")
                }
            }
            return GMResult.Ok(result)
        }

        fun parseOptions(): GMResult<List<RadarAstOption>, MermaidError> {
            val result = mutableListOf<RadarAstOption>()
            while (true) {
                val parsed = when {
                    hasKeyword("showLegend") -> parseBooleanOption()
                    hasKeyword("ticks") -> parseNumberOption("ticks")
                    hasKeyword("max") -> parseNumberOption("max")
                    hasKeyword("min") -> parseNumberOption("min")
                    hasKeyword("graticule") -> parseGraticuleOption()
                    else -> return error("Expected a Radar option after ','")
                }
                when (parsed) {
                    is GMResult.Ok -> result += parsed.value
                    is GMResult.Err -> return parsed
                }
                skipHorizontalWhitespace()
                if (source.getOrNull(index) != ',') {
                    break
                }
                index += 1
                skipHorizontalWhitespace()
            }
            return GMResult.Ok(result)
        }

        fun skipTrivia() {
            while (true) {
                skipWhitespace()
                if (!source.startsWith("%%", index)) {
                    return
                }
                val lineEnd = source.indexOf('\n', startIndex = index)
                index = if (lineEnd < 0) source.length else lineEnd + 1
            }
        }

        fun error(message: String): GMResult.Err<MermaidError> =
            errorAt(index, message)

        private fun parseEntries(): GMResult<List<RadarAstEntry>, MermaidError> {
            index += 1
            skipTrivia()
            if (source.getOrNull(index) == '}') {
                return error("Expected at least one curve entry")
            }
            val detailed = !looksLikeNumber()
            val entries = mutableListOf<RadarAstEntry>()
            while (true) {
                val location = index
                val entry = if (detailed) {
                    val axisName = when (
                        val parsed = parseIdentifier("Expected an axis reference")
                    ) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    skipHorizontalWhitespace()
                    if (source.getOrNull(index) == ':') {
                        index += 1
                        skipHorizontalWhitespace()
                    }
                    val value = when (val parsed = parseNumber()) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    RadarAstEntry(
                        axisName = axisName,
                        value = value,
                        line = lineAt(location),
                        column = columnAt(location),
                    )
                } else {
                    val value = when (val parsed = parseNumber()) {
                        is GMResult.Ok -> parsed.value
                        is GMResult.Err -> return parsed
                    }
                    RadarAstEntry(
                        axisName = null,
                        value = value,
                        line = lineAt(location),
                        column = columnAt(location),
                    )
                }
                entries += entry
                skipTrivia()
                when (source.getOrNull(index)) {
                    '}' -> {
                        index += 1
                        return GMResult.Ok(entries)
                    }
                    ',' -> {
                        index += 1
                        skipTrivia()
                        if (source.getOrNull(index) == '}' || isAtEnd()) {
                            return error("Expected a curve entry after ','")
                        }
                        if (detailed == looksLikeNumber()) {
                            return error("Curve entries must all use the same entry form")
                        }
                    }
                    else -> return error("Expected ',' or '}' after curve entry")
                }
            }
        }

        private fun parseBooleanOption(): GMResult<RadarAstOption, MermaidError> {
            index += "showLegend".length
            skipHorizontalWhitespace()
            return when {
                hasKeyword("true") -> {
                    index += "true".length
                    GMResult.Ok(RadarAstOption.ShowLegend(true))
                }
                hasKeyword("false") -> {
                    index += "false".length
                    GMResult.Ok(RadarAstOption.ShowLegend(false))
                }
                else -> error("Expected true or false after showLegend")
            }
        }

        private fun parseNumberOption(
            name: String,
        ): GMResult<RadarAstOption, MermaidError> {
            index += name.length
            skipHorizontalWhitespace()
            return when (val parsed = parseNumber()) {
                is GMResult.Ok -> GMResult.Ok(
                    when (name) {
                        "ticks" -> RadarAstOption.Ticks(parsed.value)
                        "max" -> RadarAstOption.Maximum(parsed.value)
                        else -> RadarAstOption.Minimum(parsed.value)
                    },
                )
                is GMResult.Err -> parsed
            }
        }

        private fun parseGraticuleOption(): GMResult<RadarAstOption, MermaidError> {
            index += "graticule".length
            skipHorizontalWhitespace()
            return when {
                hasKeyword("circle") -> {
                    index += "circle".length
                    GMResult.Ok(RadarAstOption.Graticule(RadarGraticule.Circle))
                }
                hasKeyword("polygon") -> {
                    index += "polygon".length
                    GMResult.Ok(RadarAstOption.Graticule(RadarGraticule.Polygon))
                }
                else -> error("Expected circle or polygon after graticule")
            }
        }

        private fun parseIdentifier(
            message: String,
        ): GMResult<String, MermaidError> {
            val start = index
            if (source.getOrNull(index)?.isIdentifierStart() != true) {
                return error(message)
            }
            index += 1
            while (source.getOrNull(index)?.isIdentifierPart() == true) {
                index += 1
            }
            val value = source.substring(start, index)
            if (value.endsWith('-')) {
                return errorAt(index - 1, "Identifiers cannot end with '-'")
            }
            return GMResult.Ok(value)
        }

        private fun parseNumber(): GMResult<Double, MermaidError> {
            val start = index
            val first = source.getOrNull(index)
            if (first == null || first !in '0'..'9') {
                return error("Expected an unsigned number")
            }
            if (first == '0') {
                index += 1
                if (source.getOrNull(index) in '0'..'9') {
                    return errorAt(start, "Numbers cannot have leading zeroes")
                }
            } else {
                while (source.getOrNull(index) in '0'..'9') {
                    index += 1
                }
            }
            if (source.getOrNull(index) == '.') {
                index += 1
                if (source.getOrNull(index) !in '0'..'9') {
                    return error("Expected digits after the decimal point")
                }
                while (source.getOrNull(index) in '0'..'9') {
                    index += 1
                }
            }
            val value = source.substring(start, index).toDoubleOrNull()
                ?: return errorAt(start, "Invalid number")
            return GMResult.Ok(value)
        }

        private fun parseString(): GMResult<String, MermaidError> {
            val quote = source.getOrNull(index)
            if (quote != '"' && quote != '\'') {
                return error("Expected a quoted label")
            }
            val opening = index
            index += 1
            val value = StringBuilder()
            while (!isAtEnd()) {
                val character = source[index]
                index += 1
                when {
                    character == quote -> return GMResult.Ok(value.toString())
                    character == '\\' -> {
                        val escaped = source.getOrNull(index)
                            ?: return errorAt(opening, "Unterminated quoted label")
                        index += 1
                        value.append(
                            when (escaped) {
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'v' -> '\u000B'
                                '0' -> '\u0000'
                                else -> escaped
                            },
                        )
                    }
                    character == '\n' -> {
                        return errorAt(opening, "Quoted labels cannot contain a newline")
                    }
                    else -> value.append(character)
                }
            }
            return errorAt(opening, "Unterminated quoted label")
        }

        private fun readLineValue(): String {
            val endOfLine = source.indexOf('\n', startIndex = index)
                .let { found -> if (found < 0) source.length else found }
            val comment = source.indexOf("%%", startIndex = index)
                .takeIf { found -> found in index until endOfLine }
                ?: endOfLine
            val value = normalizeInline(source.substring(index, comment))
            index = endOfLine
            return value
        }

        private fun looksLikeNumber(): Boolean {
            val start = index
            val first = source.getOrNull(start) ?: return false
            if (first !in '0'..'9') {
                return false
            }
            var cursor = start
            while (source.getOrNull(cursor) in '0'..'9') {
                cursor += 1
            }
            if (source.getOrNull(cursor) == '.') {
                cursor += 1
                while (source.getOrNull(cursor) in '0'..'9') {
                    cursor += 1
                }
            }
            val next = source.getOrNull(cursor)
            return next == null ||
                next == ' ' ||
                next == '\t' ||
                next == '\n' ||
                next == ',' ||
                next == '}'
        }

        private fun skipHorizontalWhitespace() {
            while (source.getOrNull(index) == ' ' || source.getOrNull(index) == '\t') {
                index += 1
            }
        }

        private fun skipWhitespace() {
            while (source.getOrNull(index)?.isWhitespace() == true) {
                index += 1
            }
        }

        private fun lineAt(location: Int): Int =
            lineOffset + source.take(location).count { character -> character == '\n' } + 1

        private fun columnAt(location: Int): Int {
            val previousNewline = source.lastIndexOf('\n', startIndex = (location - 1).coerceAtLeast(0))
            return location - previousNewline
        }

        private fun errorAt(
            location: Int,
            detail: String,
        ): GMResult.Err<MermaidError> {
            val line = lineAt(location)
            val column = columnAt(location)
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Parse error on line $line, column $column: $detail",
                ),
            )
        }
    }

    private companion object {
        const val HEADER = "radar-beta"
        const val TITLE_KEYWORD = "title"
        const val ACCESSIBILITY_TITLE_KEYWORD = "accTitle"
        const val ACCESSIBILITY_DESCRIPTION_KEYWORD = "accDescr"
        const val AXIS_KEYWORD = "axis"
        const val CURVE_KEYWORD = "curve"
        val OPTION_KEYWORDS = listOf("showLegend", "ticks", "max", "min", "graticule")

        fun Char.isIdentifierStart(): Boolean =
            this == '_' || this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

        fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || this == '-'

        fun normalizeInline(value: String): String =
            value.trim().replace(Regex("""[\t ]{2,}"""), " ")

        fun normalizeMultiline(value: String): String =
            value
                .lineSequence()
                .map { line -> line.trimStart().trimEnd().replace(Regex("""[\t ]{2,}"""), " ") }
                .dropWhile(String::isEmpty)
                .toList()
                .dropLastWhile(String::isEmpty)
                .joinToString("\n")
                .replace(Regex("""[\n\r]{2,}"""), "\n")
    }
}
