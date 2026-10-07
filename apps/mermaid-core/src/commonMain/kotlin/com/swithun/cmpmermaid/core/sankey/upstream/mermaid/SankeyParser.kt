package com.swithun.cmpmermaid.core.sankey.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSankeyOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/sankey/parser/sankey.jison,
 * sankeyUtils.ts -> prepareTextForParsing, and sankeyDB.ts population.
 */
internal class SankeyParser(
    private val config: MermaidSankeyOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<SankeyDb, MermaidError> {
        val prepared = prepareTextForParsing(source)
        val cursor = Cursor(prepared, lineOffset)
        when (val header = cursor.parseHeader()) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return header
        }
        val db = SankeyDb(config = config, diagramTitle = diagramTitle)
        var recordCount = 0
        while (!cursor.isAtEnd()) {
            val fields = when (val record = cursor.parseRecord()) {
                is GMResult.Ok -> record.value
                is GMResult.Err -> return record
            }
            val value = parseFloat(fields[2])
            val sourceNode = db.findOrCreateNode(fields[0].trim())
            val targetNode = db.findOrCreateNode(fields[1].trim())
            db.addLink(sourceNode, targetNode, value)
            recordCount += 1
            if (cursor.isAtEnd()) {
                break
            }
            when (val newline = cursor.consumeNewline()) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return newline
            }
        }
        if (recordCount == 0) {
            return cursor.error("Expected a Sankey CSV record")
        }
        return GMResult.Ok(db)
    }

    private fun prepareTextForParsing(text: String): String =
        text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim { character -> character != '\n' && character.isWhitespace() }
            .replace(NEWLINE_RUN, "\n")
            .trim()

    private fun parseFloat(source: String): Double {
        val trimmed = source.trimStart()
        val token = JAVASCRIPT_FLOAT.find(trimmed)?.value ?: return Double.NaN
        return when (token) {
            "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> token.toDoubleOrNull() ?: Double.NaN
        }
    }

    private class Cursor(
        private val source: String,
        private val lineOffset: Int,
    ) {
        private var index: Int = 0

        fun isAtEnd(): Boolean = index >= source.length

        fun parseHeader(): GMResult<Unit, MermaidError> {
            val header = when {
                source.regionMatches(index, SANKEY_BETA, 0, SANKEY_BETA.length, ignoreCase = true) ->
                    SANKEY_BETA
                source.regionMatches(index, SANKEY, 0, SANKEY.length, ignoreCase = true) ->
                    SANKEY
                else -> return error("Expected 'sankey' or 'sankey-beta' diagram header")
            }
            index += header.length
            if (source.getOrNull(index) != '\n') {
                return error("Expected a newline after the Sankey diagram header")
            }
            index += 1
            return GMResult.Ok(Unit)
        }

        fun parseRecord(): GMResult<List<String>, MermaidError> {
            val fields = mutableListOf<String>()
            repeat(FIELD_COUNT) { fieldIndex ->
                val field = when (val parsed = parseField()) {
                    is GMResult.Ok -> parsed.value
                    is GMResult.Err -> return parsed
                }
                fields += field
                if (fieldIndex < FIELD_COUNT - 1) {
                    if (source.getOrNull(index) != ',') {
                        return error("Expected ',' between Sankey CSV fields")
                    }
                    index += 1
                }
            }
            val trailing = source.getOrNull(index)
            if (trailing != null && trailing != '\n') {
                return error("Sankey CSV records must contain exactly three fields")
            }
            return GMResult.Ok(fields)
        }

        fun consumeNewline(): GMResult<Unit, MermaidError> {
            if (source.getOrNull(index) != '\n') {
                return error("Expected a newline between Sankey CSV records")
            }
            index += 1
            if (isAtEnd()) {
                return error("Expected a Sankey CSV record after newline")
            }
            return GMResult.Ok(Unit)
        }

        fun <T> error(detail: String): GMResult<T, MermaidError> {
            val line = lineOffset + source.take(index).count { character -> character == '\n' } + 1
            val previousNewline = source.lastIndexOf(
                '\n',
                startIndex = (index - 1).coerceAtLeast(0),
            )
            val column = index - previousNewline
            return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Parse error on line $line, column $column: $detail",
                ),
            )
        }

        private fun parseField(): GMResult<String, MermaidError> =
            if (source.getOrNull(index) == '"') {
                parseEscapedField()
            } else {
                parseUnescapedField()
            }

        private fun parseEscapedField(): GMResult<String, MermaidError> {
            val opening = index
            index += 1
            val value = StringBuilder()
            while (!isAtEnd()) {
                val character = source[index]
                if (character == '"') {
                    if (source.getOrNull(index + 1) == '"') {
                        value.append('"')
                        index += 2
                    } else {
                        index += 1
                        return GMResult.Ok(value.toString())
                    }
                } else {
                    value.append(character)
                    index += 1
                }
            }
            index = opening
            return error("Unterminated quoted Sankey CSV field")
        }

        private fun parseUnescapedField(): GMResult<String, MermaidError> {
            val start = index
            while (true) {
                val character = source.getOrNull(index) ?: break
                if (character == ',' || character == '\n') {
                    break
                }
                if (!character.isSankeyTextData()) {
                    return error("Invalid character in unquoted Sankey CSV field")
                }
                index += 1
            }
            return GMResult.Ok(source.substring(start, index))
        }
    }

    private companion object {
        const val SANKEY = "sankey"
        const val SANKEY_BETA = "sankey-beta"
        const val FIELD_COUNT = 3
        val NEWLINE_RUN = Regex("""\n+""")
        val JAVASCRIPT_FLOAT = Regex(
            """^[+-]?(?:Infinity|(?:(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?))""",
        )

        fun Char.isSankeyTextData(): Boolean =
            code in 0x20..0x21 || code in 0x23..0x2B || code in 0x2D..0x7E
    }
}
