package dev.supermux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.terminal.compose.resources.Res
import dev.supermux.terminal.compose.resources.jetbrains_mono_bold
import dev.supermux.terminal.compose.resources.jetbrains_mono_italic
import dev.supermux.terminal.compose.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * The monospace face this package SHIPS — JetBrains Mono 2.304, as a Compose Multiplatform font
 * resource inside the artifact (see `THIRD-PARTY-NOTICES.md` for its OFL-1.1 notice).
 *
 * **Why a font is in here at all.** A terminal is the one surface where the font is not decoration
 * but geometry: [measureCellMetrics] takes the cell's advance from the configured family and the
 * painter then places every glyph on that grid. Asking the platform for the family NAME
 * `FontFamily.Monospace` works wherever a system font manager answers — the desktop JVM, Android,
 * iOS — and silently does not in the browser: Compose's wasm build maps the generic families to a
 * list of names (`Menlo`, `Consolas`, `DejaVu Sans Mono`, `Courier`, `monospace`) and skiko's wasm
 * font manager has NO families registered at all, so every one of them misses and Skia draws with
 * a proportional default instead. The grid stays correct (that is what the per-cell fallback in
 * `TerminalPainter.text` is for), the glyphs do not: an `i` sits in an `M`-sized cell and a screen
 * of text looks spaced out. Shipping the file is the only answer that does not depend on what the
 * host machine happens to have installed.
 *
 * Three faces, and deliberately only three: Regular for ordinary cells, Bold for SGR 1 and Italic
 * for SGR 3, which is what the painter ever asks for by weight and style. A bold-italic cell
 * resolves to the Italic face — every face here has the same 600/1000 em advance for every
 * character it covers, so which one wins can never move a column. Adding a fourth file would cost
 * another ~270 KB in the web bundle to change the slant of a rare combination.
 *
 * Coverage is a terminal's: all of ASCII, Latin-1 and Latin Extended-A, box drawing (U+2500–257F)
 * and block elements (U+2580–259F) complete, most of Greek/Cyrillic and a handful of the Powerline
 * private-use glyphs. CJK, emoji and Braille are NOT in it and fall back to whatever the platform
 * has — expected, and harmless: the painter gives a wide cluster its own run spanning exactly the
 * cells the engine gave it.
 *
 * While the resource is still loading, Compose hands back a placeholder that resolves to the
 * platform default; the first paint can therefore be measured against it and is re-measured (and
 * the grid re-sized) as soon as the real face arrives. On the JVM the resource loads synchronously
 * and that window does not exist.
 */
@Composable
fun packagedTerminalFontFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal)
    val bold = Font(Res.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal)
    val italic = Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic)
    // Remembered on the three fonts, not rebuilt per recomposition: the family is compared by VALUE
    // on every paint (`CacheSignature`) and keyed on by the cell measurement, so a fresh-but-equal
    // instance each frame would re-measure the cell and empty the layout cache continuously.
    return remember(regular, bold, italic) { FontFamily(regular, bold, italic) }
}

/**
 * [theme] as the surface will actually draw it: a theme that left [TerminalTheme.fontFamily] at its
 * default gets [packagedTerminalFontFamily] instead.
 *
 * [FontFamily.Monospace] is the DEFAULT value of that field and is read here as "whatever this
 * package's monospace is", which is now a font it ships rather than a name it hopes the platform
 * resolves. Any other family — including one a host builds from its own font resources — is passed
 * through untouched, which is how a host overrides the face.
 *
 * [Terminal] calls this itself. It is public because anything that derives geometry from the same
 * theme must derive it from the same FONT: a companion overlay that measured `theme` raw would
 * compute a different cell width and draw its glyphs off the grid.
 */
@Composable
fun rememberTerminalTheme(theme: TerminalTheme): TerminalTheme {
    if (theme.fontFamily !== FontFamily.Monospace) return theme
    val packaged = packagedTerminalFontFamily()
    return remember(theme, packaged) { theme.copy(fontFamily = packaged) }
}
