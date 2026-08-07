package dev.supermux.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The heavyweight children must hide for exactly as long as something modal is
 * open, and not one composition longer.
 *
 * ── Regression: modals are invisible over a terminal ─────────────────────────
 *
 * Ahmet: "most modals etc. stays under when there is terminal view".
 *
 * Compose cannot paint over JediTerm or KCEF, so [ModalPresence] tells them to
 * step aside. The failure modes are asymmetric and both bad: miss a retain and
 * the dialog is invisible again; miss a release and the terminal stays hidden
 * forever with no way for the user to get it back. The count is what these pin
 * down.
 *
 * The visual half of this — that a dialog and a dropdown really do become
 * visible once the SwingPanel steps aside, and that the pane does not reflow —
 * cannot be asserted here: it needs a real heavyweight AWT child and a
 * screenshot. That is `InteropZOrderProbe` under Xvfb.
 */
@OptIn(ExperimentalTestApi::class)
class ModalPresenceTest {

    @Test
    fun aDialogCountsWhileItIsOpenAndReleasesWhenItCloses() = runComposeUiTest {
        lateinit var presence: ModalPresence
        var open by mutableStateOf(false)

        setContent {
            presence = remember { ModalPresence() }
            CompositionLocalProvider(LocalModalPresence provides presence) {
                if (open) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = { TextButton(onClick = {}) { Text("ok") } },
                        title = { Text("hello") },
                    )
                }
            }
        }
        waitForIdle()
        assertFalse(presence.anyOpen, "nothing is open yet")

        open = true
        waitForIdle()
        assertTrue(presence.anyOpen, "an open dialog must hide the heavyweight children")
        assertEquals(1, presence.count)

        open = false
        waitForIdle()
        assertFalse(presence.anyOpen, "closing must give the terminal back")
        assertEquals(0, presence.count)
    }

    @Test
    fun aClosedDropdownDoesNotCount() = runComposeUiTest {
        // Every app bar composes its menus unconditionally and toggles `expanded`.
        // Counting a composed-but-closed menu would pin every terminal hidden.
        lateinit var presence: ModalPresence
        var expanded by mutableStateOf(false)

        setContent {
            presence = remember { ModalPresence() }
            CompositionLocalProvider(LocalModalPresence provides presence) {
                DropdownMenu(expanded = expanded, onDismissRequest = {}) { Text("item") }
            }
        }
        waitForIdle()
        assertEquals(0, presence.count, "a closed menu is not a modal")

        expanded = true
        waitForIdle()
        assertEquals(1, presence.count)

        expanded = false
        waitForIdle()
        assertEquals(0, presence.count)
    }

    @Test
    fun overlappingModalsDoNotUncoverEarly() = runComposeUiTest {
        // A menu inside a dialog. Closing the menu must NOT re-show the terminal
        // while the dialog is still up — which a boolean flag would have done.
        lateinit var presence: ModalPresence
        var dialog by mutableStateOf(true)
        var menu by mutableStateOf(true)

        setContent {
            presence = remember { ModalPresence() }
            CompositionLocalProvider(LocalModalPresence provides presence) {
                if (dialog) {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {
                            DropdownMenu(expanded = menu, onDismissRequest = {}) { Text("item") }
                        },
                    )
                }
            }
        }
        waitForIdle()
        assertEquals(2, presence.count, "dialog + menu")

        menu = false
        waitForIdle()
        assertTrue(presence.anyOpen, "the dialog is still open, so stay hidden")
        assertEquals(1, presence.count)

        dialog = false
        waitForIdle()
        assertFalse(presence.anyOpen)
    }

    @Test
    fun releaseNeverGoesNegative() {
        // A stray release must not bank credit that swallows a later retain and
        // leaves a dialog invisible.
        val presence = ModalPresence()
        presence.release()
        presence.release()
        assertEquals(0, presence.count)
        presence.retain()
        assertTrue(presence.anyOpen, "a retain after stray releases must still hide")
    }
}
