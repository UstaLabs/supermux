package dev.supermux.editor.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.supermux.editor.syntax.TokenClasses
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EditorThemeTest {
    private val themes = listOf("light" to EditorTheme.light(FontFamily.Monospace), "dark" to EditorTheme.dark(FontFamily.Monospace))

    @Test fun everyTokenClassOfTheSyntaxVocabularyIsColoured() {
        for ((name, theme) in themes) {
            for (cls in TokenClasses.ALL) {
                val style = assertNotNull(theme.tokens[cls], "$name theme has no style for $cls")
                assertTrue(style.color != Color.Unspecified, "$name theme's $cls has no colour")
            }
        }
    }

    @Test fun thePluginClassesAreStyledInBothThemes() {
        for ((name, theme) in themes) {
            assertEquals(theme.currentLine, theme.lineClassBackgrounds[EditorTheme.ACTIVE_LINE_CLASS], "$name: the active line")
            for (cls in listOf("matching-bracket", "nonmatching-bracket", "selection-match")) {
                val style = assertNotNull(theme.classStyles[cls], "$name theme has no style for $cls")
                assertTrue(style.background != Color.Unspecified || style.color != Color.Unspecified, "$name: $cls draws nothing")
            }
        }
    }

    @Test fun lightAndDarkAreDifferentThemes() {
        val light = themes[0].second
        val dark = themes[1].second
        assertNotEquals(light.background, dark.background)
        assertNotEquals(light.foreground, dark.foreground)
        assertNotEquals(light.tokens[TokenClasses.KEYWORD]?.color, dark.tokens[TokenClasses.KEYWORD]?.color)
        // Each is readable on its own background: the text is far from the background in luminance.
        for ((_, t) in themes) assertTrue(kotlin.math.abs(t.foreground.luminance() - t.background.luminance()) > 0.5f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun thePackagedFontLoadsAndIsMonospace() = runComposeUiTest {
        var family: FontFamily? by mutableStateOf(null)
        var widths: Pair<Float, Float>? by mutableStateOf(null)
        var themeFamily: FontFamily? by mutableStateOf(null)
        setContent {
            val f = packagedEditorFontFamily()
            family = f
            themeFamily = EditorTheme.default().fontFamily
            val measurer = rememberTextMeasurer()
            val style = TextStyle(fontFamily = f, fontSize = 40.sp)
            val px = with(LocalDensity.current) { 40.sp.toPx() }
            // Per em of font size, so the density does not matter.
            widths = measurer.measure("i".repeat(100), style).getLineRight(0) / px to
                measurer.measure("M".repeat(100), style).getLineRight(0) / px
        }
        waitForIdle()
        assertNotNull(family)
        val (i, m) = assertNotNull(widths)
        // JetBrains Mono: every glyph advances exactly 600/1000 em. A proportional fallback would make
        // the i's far narrower than the M's, and a system monospace (Menlo, DejaVu: 0.602 em) is off
        // by 0.2 em over 100 glyphs.
        assertEquals(60f, i, 0.05f, "the packaged face did not load (100 i's = $i em)")
        assertEquals(60f, m, 0.05f, "the packaged face did not load (100 M's = $m em)")
        // And it is the default theme's face.
        assertEquals(family, themeFamily)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
