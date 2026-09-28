package dev.supermux.editor.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A view that outlives its surface (M5: one view per document, panes borrow it) reports an EMPTY
 * viewport while nothing shows it, so a host waiting for "laid out" waits for the next surface.
 */
@OptIn(ExperimentalTestApi::class)
class ViewportDetachTest {
    @Test fun theViewportIsEmptyWhileNoSurfaceShowsTheView() = runComposeUiTest {
        val view = EditorView(EditorState.create((0 until 200).joinToString("\n") { "line $it" }))
        var shown by mutableStateOf(true)
        setContent { if (shown) Editor(view, Modifier.size(300.dp, 200.dp)) }
        waitForIdle()
        assertFalse(view.viewport.value.isEmpty())

        shown = false
        waitForIdle()
        assertTrue(view.viewport.value.isEmpty())

        shown = true
        waitForIdle()
        assertFalse(view.viewport.value.isEmpty())
    }
}
