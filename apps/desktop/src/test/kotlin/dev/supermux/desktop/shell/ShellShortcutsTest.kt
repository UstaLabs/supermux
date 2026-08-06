// Ported verbatim (package rename only) from
// apps/android/src/test/kotlin/dev/supermux/android/shell/ShellShortcutsTest.kt.
package dev.supermux.desktop.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellShortcutsTest {
    @Test fun globalShortcutsResolveWithoutSelection() {
        assertEquals(ShellShortcut.ToggleSidebar, mapShellShortcut('B', hasSelection = false))
        assertEquals(ShellShortcut.NewSession, mapShellShortcut('N', hasSelection = false))
    }

    @Test fun paneShortcutsRequireSelection() {
        assertNull(mapShellShortcut('L', hasSelection = false))
        assertNull(mapShellShortcut('E', hasSelection = false))
        assertNull(mapShellShortcut('T', hasSelection = false))
        assertNull(mapShellShortcut('D', hasSelection = false))
        assertEquals(ShellShortcut.ToggleChat, mapShellShortcut('L', hasSelection = true))
        assertEquals(ShellShortcut.ToggleEditor, mapShellShortcut('E', hasSelection = true))
        assertEquals(ShellShortcut.ToggleTerminal, mapShellShortcut('T', hasSelection = true))
        assertEquals(ShellShortcut.ToggleDisplay, mapShellShortcut('D', hasSelection = true))
    }

    @Test fun mappingIsCaseInsensitive() {
        assertEquals(ShellShortcut.ToggleSidebar, mapShellShortcut('b', hasSelection = true))
        assertEquals(ShellShortcut.ToggleEditor, mapShellShortcut('e', hasSelection = true))
    }

    @Test fun unboundKeysAreNull() {
        assertNull(mapShellShortcut('X', hasSelection = true))
        assertNull(mapShellShortcut('1', hasSelection = true))
    }

    @Test fun applyToggleSidebarFlipsCollapsed() {
        val layout = ShellLayout()
        assertEquals(false, layout.sidebarCollapsed)
        applyShellShortcut(ShellShortcut.ToggleSidebar, layout, selectedId = null, onNewSession = {})
        assertTrue(layout.sidebarCollapsed)
    }

    @Test fun applyNewSessionInvokesCallback() {
        var called = false
        applyShellShortcut(ShellShortcut.NewSession, ShellLayout(), selectedId = null) { called = true }
        assertTrue(called)
    }

    @Test fun applyPaneToggleUsesSelectedId() {
        val layout = ShellLayout()
        applyShellShortcut(ShellShortcut.ToggleEditor, layout, selectedId = "s1", onNewSession = {})
        assertTrue(layout.panesFor("s1").editor)
        assertFalse(layout.panesFor("s2").editor)
    }

    @Test fun applyPaneToggleNoOpsWithoutSelection() {
        val layout = ShellLayout()
        // Should not throw and should not touch any session's panes.
        applyShellShortcut(ShellShortcut.ToggleEditor, layout, selectedId = null, onNewSession = {})
        assertFalse(layout.panesFor("s1").editor)
    }
}
