package dev.supermux.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The font contract, asserted on ADVANCES rather than on pixels: the default theme draws with a
 * face this package ships, that face gives every printable ASCII character the same advance, and
 * the cell the painter lays out on is measured from that same face.
 *
 * This is the suite for the bug it was written against. On the browser the theme's default
 * `FontFamily.Monospace` resolved to nothing — skiko's wasm font manager has no families — and
 * Skia drew with a PROPORTIONAL default while the painter kept placing cells at the advance of
 * `M`. Every assertion below fails under that arrangement: `i` and `W` measure differently, the
 * run no longer fits the cells it owns, and `TerminalPainter` falls back to drawing glyph by glyph
 * in a cell far wider than the glyph — which is what "the terminal looks stretched" was.
 *
 * It runs on the JVM, where Compose's resource loader is synchronous, but what it proves is a
 * property of the FONT FILE and of the wiring, and both are identical on every target.
 */
class TerminalFontTest {

    /**
     * Every printable ASCII character, [RUN] of them, between two `M`s.
     *
     * The bookends are not decoration: a measured line's right edge excludes TRAILING whitespace
     * (a text-layout rule, not a font one), so a run of spaces would measure 0 and say nothing
     * about the font. Subtracting the advance of [BOOKENDS] gives the run's own advance for every
     * character alike, space included — and interior spaces are exactly what a terminal line is
     * full of.
     */
    private val runs: List<String> = (' '..'~').map { "M" + it.toString().repeat(RUN) + "M" }

    private class Measured(
        val family: FontFamily,
        val cell: CellMetrics,
        val widths: Map<Char, Float>,
        val bold: Float,
        val italic: Float,
    )

    /** Measure the default theme the way the surface does: through the resolved theme. */
    @OptIn(ExperimentalTestApi::class)
    private fun measure(): Measured {
        var result: Measured? = null
        runComposeUiTest {
            setContent {
                val theme = rememberTerminalTheme(TerminalTheme())
                val measurer = rememberTextMeasurer(cacheSize = 0)
                val density = LocalDensity.current
                if (result == null) {
                    val bare = measurer.advance(BOOKENDS, theme, density)
                    result = Measured(
                        family = theme.fontFamily,
                        cell = measureCellMetrics(measurer, theme, density),
                        widths = runs.associate {
                            it[1] to measurer.advance(it, theme, density) - bare
                        },
                        bold = measurer.advance("M".repeat(RUN), theme, density, weight = FontWeight.Bold),
                        italic = measurer.advance("M".repeat(RUN), theme, density, style = FontStyle.Italic),
                    )
                }
            }
            waitForIdle()
        }
        return assertNotNull(result, "the composition never measured anything")
    }

    private fun TextMeasurer.advance(
        text: String,
        theme: TerminalTheme,
        density: Density,
        weight: FontWeight = FontWeight.Normal,
        style: FontStyle = FontStyle.Normal,
    ): Float = measure(
        text = text,
        style = TextStyle(
            fontFamily = theme.fontFamily,
            fontSize = theme.fontSize,
            fontWeight = weight,
            fontStyle = style,
        ),
        softWrap = false,
        maxLines = 1,
        density = density,
    ).getLineRight(0)

    @Test fun theDefaultThemeDrawsWithTheFaceThisPackageShips() {
        val measured = measure()
        assertTrue(
            measured.family !== FontFamily.Monospace,
            "the default theme was left asking the platform for a family name; on the browser " +
                "nothing answers it and Skia draws with a proportional fallback",
        )
    }

    @Test fun everyPrintableAsciiCharacterHasTheSameAdvance() {
        val measured = measure()
        val reference = assertNotNull(measured.widths['M'])
        val ragged = measured.widths.filterValues { abs(it - reference) > TOLERANCE }
        assertTrue(
            ragged.isEmpty(),
            "these characters are not the width of 'M' ($reference for $RUN of them): " +
                ragged.entries.joinToString { "'${it.key}'=${it.value}" },
        )
        // The two the eye catches first, named explicitly so a failure reads like the bug report.
        assertEquals(measured.widths['W']!!, measured.widths['i']!!, TOLERANCE, "'i' and 'W' differ")
        assertEquals(measured.widths['W']!!, measured.widths['l']!!, TOLERANCE, "'l' and 'W' differ")
    }

    @Test fun boldAndItalicKeepTheSameAdvanceAsRegular() {
        val measured = measure()
        val reference = assertNotNull(measured.widths['M'])
        assertEquals(reference, measured.bold, TOLERANCE, "SGR 1 would move every column after it")
        assertEquals(reference, measured.italic, TOLERANCE, "SGR 3 would move every column after it")
    }

    @Test fun theCellIsTheAdvanceOfTheFaceTheGlyphsAreDrawnWith() {
        val measured = measure()
        val advance = assertNotNull(measured.widths['M']) / RUN
        // The cell is that advance rounded to a whole pixel (CellMetrics), so it can never be more
        // than half a pixel away from it. A cell measured against a DIFFERENT face — the bug this
        // suite is about — is out by the difference between the two fonts, not by rounding.
        assertTrue(
            abs(measured.cell.width - advance) <= 0.5f + EPSILON,
            "the cell is ${measured.cell.width}px but the face advances $advance per character",
        )
    }

    @Test fun aWholeRunStillFitsTheCellsItOwns() {
        val measured = measure()
        // The painter draws a run in one go only while its advance is within half a pixel per
        // column of the cells it owns (TerminalPainter.TOLERANCE_PER_COLUMN); past that it splits
        // the run and draws each glyph at the left edge of its own cell, which is the "spaced out"
        // look. A proportional fallback misses this by whole cells.
        for ((char, width) in measured.widths) {
            val wanted = RUN * measured.cell.width
            assertTrue(
                abs(width - wanted) <= 0.5f * RUN,
                "a run of $RUN '$char' measures $width against $wanted cells' worth of grid",
            )
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun theFamilyIsTheSameInstanceAcrossRecompositions() {
        // Not a style point: the family is compared on every paint (`CacheSignature`) and keyed on
        // by the cell measurement, so a fresh-but-unequal instance per recomposition would
        // re-measure the cell and empty the text-layout cache on every frame the surface draws.
        val seen = mutableListOf<FontFamily>()
        runComposeUiTest {
            var tick by mutableStateOf(0)
            setContent {
                val theme = rememberTerminalTheme(TerminalTheme())
                // Read the state so this composable really does recompose.
                if (tick >= 0) seen += theme.fontFamily
            }
            waitForIdle()
            repeat(3) {
                tick++
                waitForIdle()
            }
        }
        assertTrue(seen.size >= 2, "the composable never recomposed: $seen")
        val first = seen.first()
        assertTrue(seen.all { it === first }, "the family changed identity across recompositions")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aHostsOwnFamilyIsUsedVerbatim() {
        var seen: FontFamily? = null
        runComposeUiTest {
            setContent {
                seen = rememberTerminalTheme(TerminalTheme(fontFamily = FontFamily.Serif)).fontFamily
            }
            waitForIdle()
        }
        assertSame(FontFamily.Serif, seen, "a host that named a family must get that family")
    }

    private companion object {
        /** Long enough that a per-glyph difference cannot hide in rounding. */
        const val RUN = 20

        /** The two `M`s every measured run is wrapped in; see [runs]. */
        const val BOOKENDS = "MM"

        /** Half a pixel over a whole run: font hinting may round a run end, a design may not. */
        const val TOLERANCE = 0.5f

        /** Float comparison slack, nothing more. */
        const val EPSILON = 0.001f
    }
}
