package dev.supermux.ui.shell

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
        val sidebar = SidebarState()
        assertEquals(false, sidebar.sidebarCollapsed)
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleSidebar, sidebar, selectedId = null, onNewSession = {})
        assertTrue(sidebar.sidebarCollapsed)
    }

    @Test fun applyNewSessionInvokesCallback() {
        var called = false
        applyWorkspaceShortcut(WorkspaceShortcut.NewSession, SidebarState(), selectedId = null, onNewSession = { called = true })
        assertTrue(called)
    }

    @Test fun applyToggleSidebarTwiceRestores() {
        val sidebar = SidebarState()
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleSidebar, sidebar, selectedId = null, onNewSession = {})
        applyWorkspaceShortcut(WorkspaceShortcut.ToggleSidebar, sidebar, selectedId = null, onNewSession = {})
        assertFalse(sidebar.sidebarCollapsed)
    }
}
