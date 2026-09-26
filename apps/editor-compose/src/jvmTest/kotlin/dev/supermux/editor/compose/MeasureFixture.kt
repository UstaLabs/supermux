package dev.supermux.editor.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import dev.supermux.editor.core.EditorState
import kotlin.test.assertNotNull

/** A real TextMeasurer with the packaged face, taken from a real composition. */
internal class Measure(val measurer: TextMeasurer, val font: FontFamily, val density: Density) {
    val theme: EditorTheme get() = EditorTheme.dark(font)

    fun layouts(wrapWidthPx: Int? = null): LineLayouts =
        LineLayouts(measurer).also { it.configure(theme, density, wrapWidthPx) }

    fun geometry(state: () -> EditorState, wrapWidthPx: Int? = null): Geometry {
        val layouts = layouts(wrapWidthPx)
        return Geometry(state, HeightMap(state().doc.lineCount, layouts.lineHeightPx), layouts)
    }
}

@OptIn(ExperimentalTestApi::class)
internal fun withMeasure(body: (Measure) -> Unit) = runComposeUiTest {
    var captured: Measure? by mutableStateOf(null)
    setContent {
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val font = packagedEditorFontFamily()
        val density = LocalDensity.current
        captured = Measure(measurer, font, density)
    }
    waitForIdle()
    body(assertNotNull(captured))
}
