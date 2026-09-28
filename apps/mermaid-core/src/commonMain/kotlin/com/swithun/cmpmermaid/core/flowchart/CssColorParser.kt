package com.swithun.cmpmermaid.core

import kotlin.math.abs
import kotlin.math.roundToInt

internal object CssColorParser {
    fun parse(source: String): SceneColor? {
        val value = source.trim().lowercase()
        return NAMED[value]
            ?: parseHex(value)
            ?: parseRgb(value)
            ?: parseHsl(value)
    }

    private fun parseHex(value: String): SceneColor? {
        if (!value.startsWith('#')) {
            return null
        }
        val hex = value.drop(1)
        val rgba = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("") + "ff"
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> hex + "ff"
            8 -> hex
            else -> return null
        }
        val packed = rgba.toLongOrNull(16) ?: return null
        val red = packed shr 24 and 0xFF
        val green = packed shr 16 and 0xFF
        val blue = packed shr 8 and 0xFF
        val alpha = packed and 0xFF
        return color(red.toInt(), green.toInt(), blue.toInt(), alpha.toInt())
    }

    private fun parseRgb(value: String): SceneColor? {
        val match = RGB.matchEntire(value) ?: return null
        val components = match.groupValues[2].split(',').map(String::trim)
        if (components.size !in 3..4) {
            return null
        }
        val red = parseRgbComponent(components[0]) ?: return null
        val green = parseRgbComponent(components[1]) ?: return null
        val blue = parseRgbComponent(components[2]) ?: return null
        val alpha = components.getOrNull(3)?.let(::parseAlpha) ?: 255
        return color(red, green, blue, alpha)
    }

    private fun parseHsl(value: String): SceneColor? {
        val match = HSL.matchEntire(value) ?: return null
        val components = match.groupValues[2].split(',').map(String::trim)
        if (components.size !in 3..4) {
            return null
        }
        val hue = components[0].removeSuffix("deg").toFloatOrNull() ?: return null
        val saturation = components[1].takeIf { it.endsWith('%') }
            ?.dropLast(1)
            ?.toFloatOrNull()
            ?.div(100f)
            ?: return null
        val lightness = components[2].takeIf { it.endsWith('%') }
            ?.dropLast(1)
            ?.toFloatOrNull()
            ?.div(100f)
            ?: return null
        val alpha = components.getOrNull(3)?.let(::parseAlpha) ?: 255
        val normalizedHue = ((hue % 360f) + 360f) % 360f / 60f
        val chroma = (1f - abs(2f * lightness - 1f)) * saturation
        val intermediate = chroma * (1f - abs(normalizedHue % 2f - 1f))
        val (redPrime, greenPrime, bluePrime) = when (normalizedHue.toInt()) {
            0 -> Triple(chroma, intermediate, 0f)
            1 -> Triple(intermediate, chroma, 0f)
            2 -> Triple(0f, chroma, intermediate)
            3 -> Triple(0f, intermediate, chroma)
            4 -> Triple(intermediate, 0f, chroma)
            else -> Triple(chroma, 0f, intermediate)
        }
        val offset = lightness - chroma / 2f
        return color(
            red = ((redPrime + offset) * 255f).roundToInt(),
            green = ((greenPrime + offset) * 255f).roundToInt(),
            blue = ((bluePrime + offset) * 255f).roundToInt(),
            alpha = alpha,
        )
    }

    private fun parseRgbComponent(value: String): Int? = if (value.endsWith('%')) {
        value.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).roundToInt().coerceIn(0, 255) }
    } else {
        value.toFloatOrNull()?.roundToInt()?.coerceIn(0, 255)
    }

    private fun parseAlpha(value: String): Int? = if (value.endsWith('%')) {
        value.dropLast(1).toFloatOrNull()?.let { (it * 2.55f).roundToInt().coerceIn(0, 255) }
    } else {
        value.toFloatOrNull()?.let { (it * 255f).roundToInt().coerceIn(0, 255) }
    }

    private fun color(
        red: Int,
        green: Int,
        blue: Int,
        alpha: Int = 255,
    ): SceneColor = SceneColor(
        ((alpha.coerceIn(0, 255).toLong() shl 24) or
            (red.coerceIn(0, 255).toLong() shl 16) or
            (green.coerceIn(0, 255).toLong() shl 8) or
            blue.coerceIn(0, 255).toLong()),
    )

    private val RGB = Regex("""^(rgba?)\((.+)\)$""")
    private val HSL = Regex("""^(hsla?)\((.+)\)$""")

    private val NAMED = mapOf(
        "transparent" to SceneColor(0x00000000),
        "black" to color(0, 0, 0),
        "silver" to color(192, 192, 192),
        "gray" to color(128, 128, 128),
        "grey" to color(128, 128, 128),
        "white" to color(255, 255, 255),
        "maroon" to color(128, 0, 0),
        "red" to color(255, 0, 0),
        "purple" to color(128, 0, 128),
        "fuchsia" to color(255, 0, 255),
        "magenta" to color(255, 0, 255),
        "green" to color(0, 128, 0),
        "lime" to color(0, 255, 0),
        "olive" to color(128, 128, 0),
        "yellow" to color(255, 255, 0),
        "navy" to color(0, 0, 128),
        "blue" to color(0, 0, 255),
        "teal" to color(0, 128, 128),
        "aqua" to color(0, 255, 255),
        "cyan" to color(0, 255, 255),
        "orange" to color(255, 165, 0),
        "pink" to color(255, 192, 203),
        "brown" to color(165, 42, 42),
        "gold" to color(255, 215, 0),
        "violet" to color(238, 130, 238),
        "indigo" to color(75, 0, 130),
        "coral" to color(255, 127, 80),
        "tomato" to color(255, 99, 71),
        "lightgreen" to color(144, 238, 144),
        "darkgreen" to color(0, 100, 0),
        "lightblue" to color(173, 216, 230),
        "darkblue" to color(0, 0, 139),
        "lightgray" to color(211, 211, 211),
        "lightgrey" to color(211, 211, 211),
        "darkgray" to color(169, 169, 169),
        "darkgrey" to color(169, 169, 169),
    )
}
