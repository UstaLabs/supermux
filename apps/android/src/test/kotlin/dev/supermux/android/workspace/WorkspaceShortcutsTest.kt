package dev.supermux.android.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceShortcutsTest {
    @Test fun globalShortcutsResolveWithoutSelection() {
        assertEquals(WorkspaceShortcut.ToggleSidebar, mapWorkspaceShortcut('B', hasSelection = false))
        assertEquals(WorkspaceShortcut.NewSession, mapWorkspaceShortcut('N', hasSelection = false))
    }

    @Test fun paneShortcutsRequireSelection() {
        assertNull(mapWorkspaceShortcut('L', hasSelection = false))
        assertNull(mapWorkspaceShortcut('E', hasSelection = false))
        assertNull(mapWorkspaceShortcut('T', hasSelection = false))
        assertNull(mapWorkspaceShortcut('D', hasSelection = false))
        assertEquals(WorkspaceShortcut.ToggleChat, mapWorkspaceShortcut('L', hasSelection = true))
        assertEquals(WorkspaceShortcut.ToggleEditor, mapWorkspaceShortcut('E', hasSelection = true))
        assertEquals(WorkspaceShortcut.ToggleTerminal, mapWorkspaceShortcut('T', hasSelection = true))
        assertEquals(WorkspaceShortcut.ToggleDisplay, mapWorkspaceShortcut('D', hasSelection = true))
    }

    @Test fun mappingIsCaseInsensitive() {
        assertEquals(WorkspaceShortcut.ToggleSidebar, mapWorkspaceShortcut('b', hasSelection = true))
        assertEquals(WorkspaceShortcut.ToggleEditor, mapWorkspaceShortcut('e', hasSelection = true))
    }

    @Test fun unboundKeysAreNull() {
        assertNull(mapWorkspaceShortcut('X', hasSelection = true))
        assertNull(mapWorkspaceShortcut('1', hasSelection = true))
    }

    @Test fun applyToggleSidebarFlipsCollapsed() {
        val layout = WorkspaceLayout()
        assertEquals(false, layout.sidebarCollapsed)
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleSidebar, layout, selectedId = null, onNewSession = {})
        assertTrue(layout.sidebarCollapsed)
    }

    @Test fun applyNewSessionInvokesCallback() {
        var called = false
        applyWorkspaceShortcut(WorkspaceShortcut.NewSession, WorkspaceLayout(), selectedId = null) { called = true }
        assertTrue(called)
    }

    @Test fun applyPaneToggleUsesSelectedId() {
        val layout = WorkspaceLayout()
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleEditor, layout, selectedId = "s1", onNewSession = {})
        assertTrue(layout.panesFor("s1").editor)
        assertFalse(layout.panesFor("s2").editor)
    }

    @Test fun applyPaneToggleNoOpsWithoutSelection() {
        val layout = WorkspaceLayout()
        // Should not throw and should not touch any session's panes.
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleEditor, layout, selectedId = null, onNewSession = {})
        assertFalse(layout.panesFor("s1").editor)
    }
}
