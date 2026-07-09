// Ported verbatim (package rename only) from
// apps/android/src/test/kotlin/dev/supermux/android/workspace/WorkspaceLayoutTest.kt.
package dev.supermux.desktop.workspace

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class WorkspaceLayoutTest {
    @Test fun sidebarWidthClampsToRange() {
        val l = WorkspaceLayout()
        l.setSidebarWidth(50.dp);  assertEquals(WorkspaceLayout.SIDEBAR_MIN, l.sidebarWidth)
        l.setSidebarWidth(999.dp); assertEquals(WorkspaceLayout.SIDEBAR_MAX, l.sidebarWidth)
        l.setSidebarWidth(300.dp); assertEquals(300.dp, l.sidebarWidth)
    }
    @Test fun fractionsClampToTheirRanges() {
        val l = WorkspaceLayout()
        l.setChatFraction(0f);  assertEquals(WorkspaceLayout.CHAT_MIN, l.chatFraction)
        l.setChatFraction(1f);  assertEquals(WorkspaceLayout.CHAT_MAX, l.chatFraction)
        l.setWorkDisplayFraction(0f); assertEquals(WorkspaceLayout.WORKDISP_MIN, l.workDisplayFraction)
        l.setEditorTermFraction(1f);  assertEquals(WorkspaceLayout.EDITORTERM_MAX, l.editorTermFraction)
    }
    @Test fun defaultPanesAreChatOnly() {
        val l = WorkspaceLayout()
        val p = l.panesFor("s1")
        assertTrue(p.chat); assertFalse(p.hasWork)
    }
    @Test fun neverEmptyInvariant_closingLastWorkPaneRestoresChat() {
        val l = WorkspaceLayout()
        l.setPanes("s1", PaneVisibility(chat = false, editor = true))
        assertFalse(l.panesFor("s1").chat)
        l.toggleEditor("s1")
        assertTrue(l.panesFor("s1").chat)
    }
    @Test fun cannotHideChatWhenNoWork() {
        val l = WorkspaceLayout()
        l.toggleChat("s1")
        assertTrue(l.panesFor("s1").chat)
    }
    @Test fun canHideChatWhenWorkPresent() {
        val l = WorkspaceLayout()
        l.toggleEditor("s1")
        l.toggleChat("s1")
        assertFalse(l.panesFor("s1").chat)
        assertTrue(l.panesFor("s1").editor)
    }
    @Test fun paneStateIsPerSession() {
        val l = WorkspaceLayout()
        l.toggleEditor("s1")
        assertTrue(l.panesFor("s1").editor)
        assertFalse(l.panesFor("s2").editor)
    }
    @Test fun pruneDropsDeadSessions() {
        val l = WorkspaceLayout()
        l.toggleEditor("s1"); l.toggleTerminal("s2")
        l.prune(setOf("s1"))
        assertTrue(l.panesFor("s1").editor)
        assertFalse(l.panesFor("s2").hasWork)
    }
    @Test fun snapshotRoundTrips() {
        val l = WorkspaceLayout()
        l.setSidebarWidth(400.dp); l.setChatFraction(0.35f); l.toggleEditor("s1"); l.setNativeView("s1", true)
        val restored = WorkspaceLayout().apply { restore(l.snapshot()) }
        assertEquals(400.dp, restored.sidebarWidth)
        assertEquals(0.35f, restored.chatFraction)
        assertTrue(restored.panesFor("s1").editor)
        assertTrue(restored.nativeView("s1"))
    }
}
