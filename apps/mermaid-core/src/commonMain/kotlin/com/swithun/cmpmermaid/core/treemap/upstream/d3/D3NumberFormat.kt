package com.swithun.cmpmermaid.core.treemap.upstream.d3

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * Kotlin translation of d3-format 3.1.2:
 * src/{formatSpecifier,locale,formatTypes,formatGroup,formatTrim}.js.
 */
internal object D3NumberFormat {
    fun formatter(source: String): ((Double) -> String)? {
        val match = SPECIFIER.matchEntire(source) ?: return null
        var fill = match.groups[1]?.value ?: " "
        var align = match.groups[2]?.value ?: ">"
        val sign = match.groups[3]?.value ?: "-"
        val symbol = match.groups[4]?.value ?: ""
        var zero = match.groups[5] != null
        val width = match.groups[6]?.value?.toIntOrNull()
        var comma = match.groups[7] != null
        var precision = match.groups[8]?.value?.drop(1)?.toIntOrNull()
        var trim = match.groups[9] != null
        var type = match.groups[10]?.value.orEmpty()
        if (type == "n") {
            comma = true
            type = "g"
        } else if (type !in FORMAT_TYPES) {
            if (precision == null) {
                precision = 12
            }
            trim = true
            type = "g"
        }
        if (zero || (fill == "0" && align == "=")) {
            zero = true
            fill = "0"
            align = "="
        }
        val resolvedPrecision = if (precision == null) {
            6
        } else if (type in SIGNIFICANT_TYPES) {
            precision.coerceIn(1, 21)
        } else {
            precision.coerceIn(0, 20)
        }
        val basePrefix = when {
            symbol == "$" -> "$"
            symbol == "#" && type in setOf("b", "o", "x", "X") ->
                "0${type.lowercase()}"
            else -> ""
        }
        val baseSuffix = if (type == "%" || type == "p") "%" else ""

        return { input ->
            if (type == "c") {
                val value = formatType(input, resolvedPrecision, type)
                align(
                    valuePrefix = basePrefix,
                    value = "",
                    valueSuffix = value + baseSuffix,
                    fill = fill,
                    align = align,
                    width = width,
                    comma = comma,
                    zero = zero,
                )
            } else {
                val negative = input < 0.0 ||
                    (input == 0.0 && 1.0 / input == Double.NEGATIVE_INFINITY)
                var value = if (input.isNaN()) {
                    "NaN"
                } else {
                    formatType(abs(input), resolvedPrecision, type)
                }
                if (trim) {
                    value = trimZeros(value)
                }
                val roundedToZero = value.toDoubleOrNull() == 0.0
                val showNegative = negative && !(roundedToZero && sign != "+")
                val valuePrefix = (
                    if (showNegative) {
                        if (sign == "(") "(" else "\u2212"
                    } else if (sign == "-" || sign == "(") {
                        ""
                    } else {
                        sign
                    }
                    ) + basePrefix
                val siSuffix = if (type == "s" && input.isFinite()) {
                    siPrefix(input)
                } else {
                    ""
                }
                var valueSuffix = siSuffix + baseSuffix +
                    if (showNegative && sign == "(") ")" else ""
                if (type in MAYBE_SUFFIX_TYPES) {
                    val firstNonDigit = value.indexOfFirst { character -> character !in '0'..'9' }
                    if (firstNonDigit >= 0) {
                        valueSuffix = if (value[firstNonDigit] == '.') {
                            "." + value.substring(firstNonDigit + 1) + valueSuffix
                        } else {
                            value.substring(firstNonDigit) + valueSuffix
                        }
                        value = value.substring(0, firstNonDigit)
                    }
                }
                align(
                    valuePrefix = valuePrefix,
                    value = value,
                    valueSuffix = valueSuffix,
                    fill = fill,
                    align = align,
                    width = width,
                    comma = comma,
                    zero = zero,
                )
            }
        }
    }

    private fun formatType(
        value: Double,
        precision: Int,
        type: String,
    ): String = when (type) {
        "%" -> fixed(value * 100.0, precision)
        "b" -> jsRound(value).toString(radix = 2)
        "c" -> jsNumberToString(value)
        "d" -> jsRound(value).toString()
        "e" -> exponential(value, precision)
        "f" -> fixed(value, precision)
        "g" -> precision(value, precision)
        "o" -> jsRound(value).toString(radix = 8)
        "p" -> rounded(value * 100.0, precision)
        "r" -> rounded(value, precision)
        "s" -> prefixAuto(value, precision)
        "X" -> jsRound(value).toString(radix = 16).uppercase()
        "x" -> jsRound(value).toString(radix = 16)
        else -> precision(value, precision)
    }

    private fun align(
        valuePrefix: String,
        value: String,
        valueSuffix: String,
        fill: String,
        align: String,
        width: Int?,
        comma: Boolean,
        zero: Boolean,
    ): String {
        var digits = value
        if (comma && !zero) {
            digits = group(digits, Int.MAX_VALUE)
        }
        val length = valuePrefix.length + digits.length + valueSuffix.length
        var padding = if (width != null && length < width) {
            fill.repeat(width - length)
        } else {
            ""
        }
        if (comma && zero) {
            digits = group(
                padding + digits,
                if (padding.isNotEmpty() && width != null) {
                    width - valueSuffix.length
                } else {
                    Int.MAX_VALUE
                },
            )
            padding = ""
        }
        return when (align) {
            "<" -> valuePrefix + digits + valueSuffix + padding
            "=" -> valuePrefix + padding + digits + valueSuffix
            "^" -> {
                val half = padding.length / 2
                padding.substring(0, half) +
                    valuePrefix +
                    digits +
                    valueSuffix +
                    padding.substring(half)
            }
            else -> padding + valuePrefix + digits + valueSuffix
        }
    }

    private fun group(value: String, width: Int): String {
        var index = value.length
        var length = 0
        val groups = mutableListOf<String>()
        while (index > 0) {
            var size = 3
            if (length + size + 1 > width) {
                size = max(1, width - length)
            }
            val start = (index - size).coerceAtLeast(0)
            groups += value.substring(start, index)
            index = start
            length += size + 1
            if (length > width) {
                break
            }
        }
        return groups.asReversed().joinToString(",")
    }

    private fun fixed(
        value: Double,
        digits: Int,
    ): String {
        if (!value.isFinite()) {
            return jsNumberToString(value)
        }
        if (value >= 1e21) {
            return jsNumberToString(value)
        }
        val factor = 10.0.pow(digits.coerceAtMost(15))
        val rounded = floor(value * factor + 0.5) / factor
        val whole = floor(rounded)
        if (digits == 0) {
            return whole.toLong().toString()
        }
        val fraction = ((rounded - whole) * factor + 0.5)
            .toLong()
            .toString()
            .padStart(digits.coerceAtMost(15), '0')
        return whole.toLong().toString() + "." + fraction +
            "0".repeat((digits - 15).coerceAtLeast(0))
    }

    private fun exponential(
        value: Double,
        fractionDigits: Int,
    ): String {
        if (!value.isFinite() || value == 0.0) {
            return if (value == 0.0) {
                fixed(0.0, fractionDigits) + "e+0"
            } else {
                jsNumberToString(value)
            }
        }
        var exponent = floor(log10(value)).toInt()
        var coefficient = value / 10.0.pow(exponent)
        val factor = 10.0.pow(fractionDigits.coerceAtMost(15))
        coefficient = floor(coefficient * factor + 0.5) / factor
        if (coefficient >= 10.0) {
            coefficient /= 10.0
            exponent += 1
        }
        return fixed(coefficient, fractionDigits) +
            "e" +
            if (exponent >= 0) "+$exponent" else exponent.toString()
    }

    private fun precision(
        value: Double,
        significantDigits: Int,
    ): String {
        if (!value.isFinite() || value == 0.0) {
            return if (value == 0.0) fixed(0.0, significantDigits - 1) else jsNumberToString(value)
        }
        val exponent = floor(log10(value)).toInt()
        return if (exponent < -6 || exponent >= significantDigits) {
            exponential(value, significantDigits - 1)
        } else {
            fixed(value, (significantDigits - exponent - 1).coerceAtLeast(0))
        }
    }

    private fun rounded(
        value: Double,
        significantDigits: Int,
    ): String {
        if (!value.isFinite() || value == 0.0) {
            return jsNumberToString(value)
        }
        val exponent = floor(log10(value)).toInt()
        val decimals = significantDigits - exponent - 1
        return if (decimals >= 0) {
            fixed(value, decimals)
        } else {
            val factor = 10.0.pow(-decimals)
            jsNumberToString(floor(value / factor + 0.5) * factor)
        }
    }

    private fun prefixAuto(
        value: Double,
        significantDigits: Int,
    ): String {
        if (!value.isFinite() || value == 0.0) {
            return precision(value, significantDigits)
        }
        val exponent = floor(log10(value)).toInt()
        val prefixExponent = (
            floor(exponent / 3.0)
                .toInt()
                .coerceIn(-8, 8) * 3
            )
        return rounded(value / 10.0.pow(prefixExponent), significantDigits)
    }

    private fun siPrefix(value: Double): String {
        if (!value.isFinite() || value == 0.0) {
            return ""
        }
        val exponent = floor(log10(abs(value))).toInt()
        val prefixIndex = floor(exponent / 3.0).toInt().coerceIn(-8, 8) + 8
        return SI_PREFIXES[prefixIndex]
    }

    private fun trimZeros(source: String): String {
        val exponentIndex = source.indexOfFirst { character -> character == 'e' || character == 'E' }
        val coefficient = if (exponentIndex >= 0) source.substring(0, exponentIndex) else source
        val exponent = if (exponentIndex >= 0) source.substring(exponentIndex) else ""
        if ('.' !in coefficient) {
            return source
        }
        val trimmed = coefficient.trimEnd('0').trimEnd('.')
        return trimmed + exponent
    }

    private fun jsRound(value: Double): Long = floor(value + 0.5).toLong()

    private fun jsNumberToString(value: Double): String = when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "Infinity"
        value == Double.NEGATIVE_INFINITY -> "-Infinity"
        value == 0.0 -> "0"
        value % 1.0 == 0.0 && value in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble() ->
            value.toLong().toString()
        else -> value.toString().replace("E+", "e+").replace("E", "e")
    }

    private val SPECIFIER = Regex(
        """^(?:(.)?([<>=^]))?([+\-( ])?([$#])?(0)?(\d+)?(,)?(\.\d+)?(~)?([a-z%])?$""",
        RegexOption.IGNORE_CASE,
    )
    private val FORMAT_TYPES = setOf("%", "b", "c", "d", "e", "f", "g", "o", "p", "r", "s", "X", "x")
    private val SIGNIFICANT_TYPES = setOf("g", "p", "r", "s")
    private val MAYBE_SUFFIX_TYPES = setOf("d", "e", "f", "g", "p", "r", "s", "%")
    private val SI_PREFIXES = listOf(
        "y",
        "z",
        "a",
        "f",
        "p",
        "n",
        "\u00B5",
        "m",
        "",
        "k",
        "M",
        "G",
        "T",
        "P",
        "E",
        "Z",
        "Y",
    )
}
