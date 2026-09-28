package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

/**
 * Translation of the HTML character-reference decoding performed when
 * Mermaid 12 assigns a sanitized label through HTMLElement.innerHTML.
 */
internal object MermaidHtmlEntityDecoder {
    fun decode(source: String): String {
        if ('&' !in source) {
            return source
        }
        val output = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '&') {
                output.append(source[cursor])
                cursor++
                continue
            }
            val reference = decodeReference(source, cursor)
            if (reference == null) {
                output.append('&')
                cursor++
            } else {
                output.append(reference.value)
                cursor = reference.nextIndex
            }
        }
        return output.toString()
    }

    private fun decodeReference(
        source: String,
        ampersandIndex: Int,
    ): DecodedReference? {
        val bodyStart = ampersandIndex + 1
        if (bodyStart >= source.length) {
            return null
        }
        return if (source[bodyStart] == '#') {
            decodeNumericReference(source, bodyStart + 1)
        } else {
            decodeNamedReference(source, bodyStart)
        }
    }

    private fun decodeNumericReference(
        source: String,
        numberStart: Int,
    ): DecodedReference? {
        val hexadecimal = source.getOrNull(numberStart) in setOf('x', 'X')
        val digitsStart = numberStart + if (hexadecimal) 1 else 0
        var cursor = digitsStart
        while (cursor < source.length && source[cursor].isDigitForRadix(hexadecimal)) {
            cursor++
        }
        if (cursor == digitsStart) {
            return null
        }
        val parsed = source
            .substring(digitsStart, cursor)
            .toLongOrNull(if (hexadecimal) 16 else 10)
        val codePoint = normalizeNumericCodePoint(parsed)
        val nextIndex = cursor + if (source.getOrNull(cursor) == ';') 1 else 0
        return DecodedReference(
            value = codePointToString(codePoint),
            nextIndex = nextIndex,
        )
    }

    private fun decodeNamedReference(
        source: String,
        nameStart: Int,
    ): DecodedReference? {
        val limit = (nameStart + Html5NamedEntities.MAX_NAME_LENGTH).coerceAtMost(source.length)
        for (end in limit downTo nameStart + 1) {
            val value = Html5NamedEntities.valueOf(source.substring(nameStart, end))
            if (value != null) {
                return DecodedReference(value = value, nextIndex = end)
            }
        }
        return null
    }

    private fun Char.isDigitForRadix(hexadecimal: Boolean): Boolean =
        if (hexadecimal) digitToIntOrNull(16) != null else this in '0'..'9'

    private fun normalizeNumericCodePoint(source: Long?): Int {
        if (source == null || source == 0L || source > 0x10FFFFL) {
            return REPLACEMENT_CHARACTER
        }
        val codePoint = source.toInt()
        if (codePoint in 0xD800..0xDFFF) {
            return REPLACEMENT_CHARACTER
        }
        return WINDOWS_1252_REPLACEMENTS[codePoint] ?: codePoint
    }

    private fun codePointToString(codePoint: Int): String = when (codePoint) {
        in 0..0xFFFF -> codePoint.toChar().toString()
        else -> {
            val normalized = codePoint - 0x10000
            val high = (0xD800 + (normalized shr 10)).toChar()
            val low = (0xDC00 + (normalized and 0x3FF)).toChar()
            "$high$low"
        }
    }

    private data class DecodedReference(
        val value: String,
        val nextIndex: Int,
    )

    private const val REPLACEMENT_CHARACTER = 0xFFFD
    private val WINDOWS_1252_REPLACEMENTS = mapOf(
        0x80 to 0x20AC,
        0x82 to 0x201A,
        0x83 to 0x0192,
        0x84 to 0x201E,
        0x85 to 0x2026,
        0x86 to 0x2020,
        0x87 to 0x2021,
        0x88 to 0x02C6,
        0x89 to 0x2030,
        0x8A to 0x0160,
        0x8B to 0x2039,
        0x8C to 0x0152,
        0x8E to 0x017D,
        0x91 to 0x2018,
        0x92 to 0x2019,
        0x93 to 0x201C,
        0x94 to 0x201D,
        0x95 to 0x2022,
        0x96 to 0x2013,
        0x97 to 0x2014,
        0x98 to 0x02DC,
        0x99 to 0x2122,
        0x9A to 0x0161,
        0x9B to 0x203A,
        0x9C to 0x0153,
        0x9E to 0x017E,
        0x9F to 0x0178,
    )
}
