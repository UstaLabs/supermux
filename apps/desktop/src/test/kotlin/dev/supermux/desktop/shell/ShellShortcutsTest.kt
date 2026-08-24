// Was a verbatim port of the Android ShellShortcutsTest. The four pane chords (Ctrl/Cmd+L/E/T/D)
// went with the old shell's four fixed panes — a workspace creates, splits and closes views on its
// own layout tree instead — so the cases that asserted them are gone and are replaced by cases
// asserting those letters are now UNBOUND (rather than silently mapping to something approximate).
package dev.supermux.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellShortcutsTest {
    @Test fun globalShortcutsResolve() {
        assertEquals(ShellShortcut.ToggleSidebar, mapShellShortcut('B'))
        assertEquals(ShellShortcut.NewSession, mapShellShortcut('N'))
        assertEquals(ShellShortcut.MoveToNewWindow, mapShellShortcut('N', shift = true))
        assertEquals(ShellShortcut.ToggleSidebar, mapShellShortcut('B', shift = true))
    }

    @Test fun theOldPaneChordsAreUnbound() {
        // L/E/T/D used to toggle chat/editor/terminal/display. Leaving them mapped to anything
        // would be a guess; leaving them unbound lets a focused pane keep them.
        assertNull(mapShellShortcut('L'))
        assertNull(mapShellShortcut('E'))
        assertNull(mapShellShortcut('T'))
        assertNull(mapShellShortcut('D'))
    }

    @Test fun mappingIsCaseInsensitive() {
        assertEquals(ShellShortcut.ToggleSidebar, mapShellShortcut('b'))
        assertEquals(ShellShortcut.NewSession, mapShellShortcut('n'))
    }

    @Test fun unboundKeysAreNull() {
        assertNull(mapShellShortcut('X'))
        assertNull(mapShellShortcut('1'))
    }

    @Test fun applyToggleSidebarFlipsCollapsed() {
        val ui = ShellUiState()
        assertFalse(ui.sidebarCollapsed)
        applyShellShortcut(ShellShortcut.ToggleSidebar, ui, onNewSession = {})
        assertTrue(ui.sidebarCollapsed)
        applyShellShortcut(ShellShortcut.ToggleSidebar, ui, onNewSession = {})
        assertFalse(ui.sidebarCollapsed)
    }

    @Test fun applyNewSessionInvokesCallback() {
        var called = false
        applyShellShortcut(ShellShortcut.NewSession, ShellUiState(), onNewSession = { called = true })
        assertTrue(called)
    }

    @Test fun applyMoveToNewWindowInvokesCallback() {
        var called = false
        applyShellShortcut(
            ShellShortcut.MoveToNewWindow,
            ShellUiState(),
            onNewSession = {},
            onMoveToNewWindow = { called = true },
        )
        assertTrue(called)
    }
}
