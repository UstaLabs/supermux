package dev.supermux.editor.plugins.view

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals

/** Zoom through a composed Editor: the keys zoom, the host's callback keeps the size, a host setting wins. */
@OptIn(ExperimentalTestApi::class)
class ZoomRoundTripTest {
    private val apple = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    @Test fun zoomKeysReachTheHostAndTheSettingsAndBack() = runComposeUiTest {
        // The host's stored size (per app), as :ui's UiPrefs keeps it.
        var stored = 15
        val view = EditorView(EditorState.create("fun f() {}\n", extensions = viewSettings(EditorSettings(fontSize = stored.toFloat()))))
        val report = ViewSettings.fontSizeReporter(view) { stored = it }
        setContent { Box(Modifier.size(400.dp, 300.dp)) { Editor(view, Modifier.fillMaxSize(), onFontSize = report) } }
        waitForIdle()
        assertEquals(15f, view.effectiveFontSize, "the stored size is not shown")
        val field = onNode(hasSetTextAction() and !SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        field.requestFocus()
        waitForIdle()
        val mod = if (apple) Key.MetaLeft else Key.CtrlLeft
        field.performKeyInput { withKeyDown(mod) { pressKey(Key.Equals) } }
        waitForIdle()
        assertEquals(16, stored, "Mod + was not persisted")
        assertEquals(16f, view.effectiveFontSize)
        assertEquals(16f, ViewSettings.current(view.state)!!.fontSize)
        field.performKeyInput { withKeyDown(mod) { pressKey(Key.Zero) } }
        waitForIdle()
        assertEquals(13, stored, "Mod 0 goes back to 13")
        assertEquals(13f, view.effectiveFontSize)
        // The host changes it (its settings screen): shown at once.
        ViewSettings.update(view) { it.copy(fontSize = 20f) }
        waitForIdle()
        assertEquals(20f, view.effectiveFontSize)
    }
}
