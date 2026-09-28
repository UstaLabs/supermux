package com.swithun.cmpmermaid.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.swithun.cmpmermaid.compose.generated.resources.Res
import com.swithun.cmpmermaid.compose.generated.resources.arimo_bold
import com.swithun.cmpmermaid.compose.generated.resources.arimo_bolditalic
import com.swithun.cmpmermaid.compose.generated.resources.arimo_italic
import com.swithun.cmpmermaid.compose.generated.resources.arimo_regular
import com.swithun.cmpmermaid.compose.generated.resources.droid_sans_fallback
import com.swithun.cmpmermaid.compose.generated.resources.droid_sans_mono
import com.swithun.cmpmermaid.compose.generated.resources.noto_sans_symbols2_regular
import org.jetbrains.compose.resources.Font

fun interface MermaidFontFamilyResolver {
    fun resolve(cssFontFamily: String): FontFamily?
}

/**
 * Returns the bundled CJK fallback used by the Mermaid renderer.
 *
 * The font resource is loaded lazily when text actually references this family.
 */
@Composable
fun rememberMermaidCjkFontFamily(): FontFamily {
    val font = Font(
        Res.font.droid_sans_fallback,
        FontWeight.Normal,
        FontStyle.Normal,
    )
    return remember(font) {
        FontFamily(font)
    }
}

/**
 * Returns the bundled symbol fallback used for Unicode glyphs missing from Arimo.
 */
@Composable
fun rememberMermaidSymbolFontFamily(): FontFamily {
    val font = Font(
        Res.font.noto_sans_symbols2_regular,
        FontWeight.Normal,
        FontStyle.Normal,
    )
    return remember(font) {
        FontFamily(font)
    }
}

/**
 * Returns the bundled monospace family used for HTML code-style spans.
 */
@Composable
internal fun rememberMermaidMonospaceFontFamily(): FontFamily {
    val font = Font(
        Res.font.droid_sans_mono,
        FontWeight.Normal,
        FontStyle.Normal,
    )
    return remember(font) {
        FontFamily(font)
    }
}

@Composable
internal fun rememberMermaidFontFamilyResolver(
    customResolver: MermaidFontFamilyResolver?,
): MermaidFontFamilyResolver {
    val arialCompatible = FontFamily(
        Font(Res.font.arimo_regular, FontWeight.Normal, FontStyle.Normal),
        Font(Res.font.arimo_regular, FontWeight.Medium, FontStyle.Normal),
        Font(Res.font.arimo_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.arimo_italic, FontWeight.Normal, FontStyle.Italic),
        Font(Res.font.arimo_italic, FontWeight.Medium, FontStyle.Italic),
        Font(Res.font.arimo_bolditalic, FontWeight.Bold, FontStyle.Italic),
    )
    return remember(customResolver, arialCompatible) {
        MermaidFontFamilyResolver { cssFontFamily ->
            customResolver?.resolve(cssFontFamily)
                ?: resolveBundledFontFamily(cssFontFamily, arialCompatible)
        }
    }
}

internal fun parseCssFontFamilies(source: String): List<String> {
    val families = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var escaped = false

    fun flush() {
        val family = current
            .toString()
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
        if (family.isNotEmpty()) {
            families += family.lowercase()
        }
        current.clear()
    }

    source.forEach { character ->
        when {
            escaped -> {
                current.append(character)
                escaped = false
            }
            character == '\\' -> escaped = true
            quote != null && character == quote -> {
                current.append(character)
                quote = null
            }
            quote == null && (character == '"' || character == '\'') -> {
                current.append(character)
                quote = character
            }
            quote == null && character == ',' -> flush()
            else -> current.append(character)
        }
    }
    if (escaped) {
        current.append('\\')
    }
    flush()
    return families
}

private fun resolveBundledFontFamily(
    cssFontFamily: String,
    arialCompatible: FontFamily,
): FontFamily {
    parseCssFontFamilies(cssFontFamily).forEach { family ->
        when (family) {
            "recursive variable",
            "arial",
            "arialmt",
            "trebuchet ms",
            "verdana" -> return arialCompatible

            "roboto",
            "sans-serif",
            "ui-sans-serif",
            "system-ui" -> return FontFamily.SansSerif

            "courier",
            "courier new",
            "monospace",
            "ui-monospace" -> return FontFamily.Monospace

            "georgia",
            "times",
            "times new roman",
            "serif",
            "ui-serif" -> return FontFamily.Serif

            "cursive" -> return FontFamily.Cursive
        }
    }
    return FontFamily.Default
}
