package dev.supermux.desktop.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The native fallback draws a reason line ("Native editor (…)") above an editable field.
 *
 * It used to draw both into one Box, so the reason landed at the default TopStart — painted straight
 * over the FIRST LINE of the file. Nothing caught it: no test asserted geometry, and the old
 * EditorPanel kept an empty-state overlay across the surface until a file was opened, so a human
 * never saw it either. A `file` pane draws its document immediately, which is what finally exposed it
 * on a real launch.
 *
 * These assert the two do not share space. A Box-based regression fails the first one.
 */
@OptIn(ExperimentalTestApi::class)
class NativeFallbackLayoutTest {

    @Test
    fun theReasonLineSitsAboveTheEditorRatherThanOnTopOfIt() = runComposeUiTest {
        setContent {
            Box(Modifier.size(600.dp, 400.dp)) {
                EditorSurface(
                    kcefState = KcefState.Error("no browser here"),
                    content = "line one\nline two\nline three",
                    filename = "a.kt",
                    lineWrap = false,
                    fontSize = 13,
                    scrollTop = 0,
                    revealLine = null,
                    onChange = {},
                    onSave = {},
                    onRevealConsumed = {},
                    onFontSize = {},
                    onEnsureInit = {},
                )
            }
        }
        waitForIdle()

        val reason = onNodeWithTag("editor_native_fallback_reason").getUnclippedBoundsInRoot()
        val field = onNodeWithTag("editor_native_input").getUnclippedBoundsInRoot()

        assertTrue(
            reason.bottom <= field.top,
            "the reason line must end before the editor starts, " +
                "got reason=${reason.top}..${reason.bottom} field=${field.top}..${field.bottom}",
        )
    }

    @Test
    fun theEditorStillFillsTheRemainingHeight() = runComposeUiTest {
        setContent {
            Box(Modifier.size(600.dp, 400.dp)) {
                EditorSurface(
                    kcefState = KcefState.Error("no browser here"),
                    content = "x",
                    filename = "a.kt",
                    lineWrap = false,
                    fontSize = 13,
                    scrollTop = 0,
                    revealLine = null,
                    onChange = {},
                    onSave = {},
                    onRevealConsumed = {},
                    onFontSize = {},
                    onEnsureInit = {},
                )
            }
        }
        waitForIdle()

        onNodeWithTag("editor_native_fallback").assertIsDisplayed()
        // Giving the reason its own row must not collapse the field to nothing.
        val field = onNodeWithTag("editor_native_input").getUnclippedBoundsInRoot()
        val fieldHeight = field.bottom.value - field.top.value
        assertTrue(
            fieldHeight > 200f,
            "the editor should keep most of the 400dp surface, got ${fieldHeight}dp",
        )
    }
}
