package dev.supermux.android.workspace

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceLayoutTest {
    @Test fun sidebarWidthClampsToRange() {
        val l = SidebarState()
        l.setSidebarWidth(50.dp); assertEquals(SidebarState.SIDEBAR_MIN, l.sidebarWidth)
        l.setSidebarWidth(999.dp); assertEquals(SidebarState.SIDEBAR_MAX, l.sidebarWidth)
        l.setSidebarWidth(300.dp); assertEquals(300.dp, l.sidebarWidth)
    }

    @Test fun sidebarCollapsedToggles() {
        val l = SidebarState()
        l.sidebarCollapsed = true
        assertTrue(l.sidebarCollapsed)
        l.setSidebarWidth(400.dp)
        assertEquals(400.dp, l.sidebarWidth)
    }
}
