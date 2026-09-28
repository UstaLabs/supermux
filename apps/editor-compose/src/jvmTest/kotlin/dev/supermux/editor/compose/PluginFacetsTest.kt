package dev.supermux.editor.compose

import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The editor-level facets M4a's plugins set: the viewport as state, the line-number toggle. */
@OptIn(ExperimentalTestApi::class)
class PluginFacetsTest {
    private val text = (0 until 500).joinToString("\n") { "line $it" }

    @Test fun theViewportReachesTheStateOfAPluginThatAsksForIt() {
        editorTest(EditorState.create(text, extensions = EditorViewport.extension)) { f ->
            waitForIdle()
            val shown = EditorViewport.of(f.view.state)!!
            assertTrue(!shown.isEmpty(), "no viewport in the state after the first paint")
            assertEquals(f.view.viewport.value, shown)
            assertEquals(0, shown.first)
            f.controller.scroll.scrollBy(0f, 2000f)
            waitForIdle()
            val scrolled = EditorViewport.of(f.view.state)!!
            assertTrue(scrolled.first > 0, "the viewport in the state did not follow the scroll: $scrolled")
            assertEquals(f.view.viewport.value, scrolled)
        }
        // Without the field, no transaction is made for it.
        editorTest(EditorState.create(text)) { f ->
            var n = 0
            f.view.addListener { n++ }
            f.controller.scroll.scrollBy(0f, 2000f)
            waitForIdle()
            assertEquals(0, n)
        }
    }

    @Test fun theLineNumbersFacetOverridesTheParameterAndReconfigures() {
        val numbers = Compartment("numbers")
        editorTest(EditorState.create(text, extensions = numbers.of(lineNumbersFacet.of(false)))) { f ->
            assertEquals(0f, f.controller.numbersRight, "line numbers shown although the facet says no")
            f.view.dispatch(TransactionSpec(effects = listOf(numbers.reconfigure(lineNumbersFacet.of(true)))))
            waitForIdle()
            assertTrue(f.controller.numbersRight > 0f, "the reconfigure did not bring the line numbers back")
        }
        editorTest(EditorState.create(text, extensions = lineNumbersFacet.of(true)), showLineNumbers = false) { f ->
            assertTrue(f.controller.numbersRight > 0f)
        }
    }

    @Test fun wrapTabSizeThemeAndFontSizeReconfigureLive() {
        val wrap = Compartment("wrap")
        val tab = Compartment("tab")
        val mode = Compartment("mode")
        val size = Compartment("size")
        val long = "\tx " + "word ".repeat(200)
        val state = EditorState.create(long + "\n" + text, extensions = dev.supermux.editor.core.extensionOf(
            wrap.of(lineWrappingFacet.of(false)), tab.of(tabSizeFacet.of(4)), mode.of(themeModeFacet.of(EditorThemeMode.DARK)), size.of(fontSizeFacet.of(13f)),
        ))
        editorTest(state, lineWrap = true) { f ->
            assertEquals(false, f.controller.lineWrap, "the facet did not override Editor(lineWrap = true)")
            val dark = EditorTheme.dark(f.theme!!.fontFamily)
            assertEquals(dark.background, f.controller.theme!!.background)
            val xTab4 = f.geometry.rectFor(1).left
            f.view.dispatch(TransactionSpec(effects = listOf(
                wrap.reconfigure(lineWrappingFacet.of(true)), tab.reconfigure(tabSizeFacet.of(8)),
                mode.reconfigure(themeModeFacet.of(EditorThemeMode.LIGHT)), size.reconfigure(fontSizeFacet.of(18f)),
            )))
            waitForIdle()
            assertEquals(true, f.controller.lineWrap)
            assertEquals(0f, f.controller.maxScrollX())
            assertEquals(EditorTheme.light(f.theme!!.fontFamily).background, f.controller.theme!!.background)
            assertEquals(18f, f.controller.theme!!.fontSizeSp)
            // A tab stop twice as far (in cells of the new size).
            val cell = f.controller.geometry.layouts.charWidthPx
            assertEquals(8 * cell, f.geometry.rectFor(1).left, cell / 2, "the tab size did not relayout (was $xTab4)")
            // The user zooms; a new setting replaces the zoom; Mod 0 goes back to the theme's size.
            f.view.zoomTo(20f)
            waitForIdle()
            assertEquals(20f, f.controller.theme!!.fontSizeSp)
            f.view.dispatch(TransactionSpec(effects = listOf(size.reconfigure(fontSizeFacet.of(16f)))))
            waitForIdle()
            assertEquals(16f, f.controller.theme!!.fontSizeSp)
            f.view.resetZoom()
            waitForIdle()
            assertEquals(EditorZoom.DEFAULT, f.controller.theme!!.fontSizeSp)
            // The document and the selection were never touched.
            assertEquals(long + "\n" + text, f.view.state.doc.toString())
        }
    }

    @Test fun aThemeModeKeepsTheHostsOwnClasses() {
        val mode = Compartment("mode")
        val diffTint = androidx.compose.ui.graphics.Color(0x3300FF00)
        val host: (EditorTheme) -> EditorTheme = { t ->
            t.copy(
                lineClassBackgrounds = t.lineClassBackgrounds + ("diff-add" to diffTint),
                classStyles = t.classStyles + ("search-match" to androidx.compose.ui.text.SpanStyle(background = diffTint)),
            )
        }
        editorTest(EditorState.create(text, extensions = mode.of(themeModeFacet.of(EditorThemeMode.DARK))), theme = host) { f ->
            val font = f.theme!!.fontFamily
            for (m in listOf(EditorThemeMode.DARK, EditorThemeMode.LIGHT)) {
                f.view.dispatch(TransactionSpec(effects = listOf(mode.reconfigure(themeModeFacet.of(m)))))
                waitForIdle()
                val shown = f.controller.theme!!
                val palette = if (m == EditorThemeMode.DARK) EditorTheme.dark(font) else EditorTheme.light(font)
                assertEquals(palette.background, shown.background, "$m: not the palette")
                assertEquals(diffTint, shown.lineClassBackgrounds["diff-add"], "$m: the host's diff-add class was dropped")
                assertTrue(shown.classStyles.containsKey("search-match"), "$m: the host's search-match class was dropped")
                // The palette's own classes stay the palette's (not the host palette's active line).
                assertEquals(palette.currentLine, shown.lineClassBackgrounds[EditorTheme.ACTIVE_LINE_CLASS], "$m: active line")
            }
        }
    }

    @Test fun aHostsLightAndDarkThemesArePickedByTheMode() {
        val mode = Compartment("mode")
        val light = EditorTheme.light(androidx.compose.ui.text.font.FontFamily.Monospace).copy(background = androidx.compose.ui.graphics.Color(0xFFFFEEDD))
        val dark = EditorTheme.dark(androidx.compose.ui.text.font.FontFamily.Monospace).copy(background = androidx.compose.ui.graphics.Color(0xFF112233))
        editorTest(EditorState.create(text, extensions = mode.of(themeModeFacet.of(EditorThemeMode.LIGHT))), lightTheme = light, darkTheme = dark) { f ->
            assertEquals(light.background, f.controller.theme!!.background)
            f.view.dispatch(TransactionSpec(effects = listOf(mode.reconfigure(themeModeFacet.of(EditorThemeMode.DARK)))))
            waitForIdle()
            assertEquals(dark.background, f.controller.theme!!.background)
        }
    }
}
