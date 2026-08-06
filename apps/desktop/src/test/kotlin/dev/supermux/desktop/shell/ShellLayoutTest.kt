// Ported verbatim (package rename only) from
// apps/android/src/test/kotlin/dev/supermux/android/shell/ShellLayoutTest.kt.
package dev.supermux.desktop.shell

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ShellLayoutTest {
    @Test fun sidebarWidthClampsToRange() {
        val l = ShellLayout()
        l.setSidebarWidth(50.dp);  assertEquals(ShellLayout.SIDEBAR_MIN, l.sidebarWidth)
        l.setSidebarWidth(999.dp); assertEquals(ShellLayout.SIDEBAR_MAX, l.sidebarWidth)
        l.setSidebarWidth(300.dp); assertEquals(300.dp, l.sidebarWidth)
    }
    @Test fun fractionsClampToTheirRanges() {
        val l = ShellLayout()
        l.setChatFraction(0f);  assertEquals(ShellLayout.CHAT_MIN, l.chatFraction)
        l.setChatFraction(1f);  assertEquals(ShellLayout.CHAT_MAX, l.chatFraction)
        l.setWorkDisplayFraction(0f); assertEquals(ShellLayout.WORKDISP_MIN, l.workDisplayFraction)
        l.setEditorTermFraction(1f);  assertEquals(ShellLayout.EDITORTERM_MAX, l.editorTermFraction)
    }
    @Test fun defaultPanesAreChatOnly() {
        val l = ShellLayout()
        val p = l.panesFor("s1")
        assertTrue(p.chat); assertFalse(p.hasWork)
    }
    @Test fun neverEmptyInvariant_closingLastWorkPaneRestoresChat() {
        val l = ShellLayout()
        l.setPanes("s1", PaneVisibility(chat = false, editor = true))
        assertFalse(l.panesFor("s1").chat)
        l.toggleEditor("s1")
        assertTrue(l.panesFor("s1").chat)
    }
    @Test fun cannotHideChatWhenNoWork() {
        val l = ShellLayout()
        l.toggleChat("s1")
        assertTrue(l.panesFor("s1").chat)
    }
    @Test fun canHideChatWhenWorkPresent() {
        val l = ShellLayout()
        l.toggleEditor("s1")
        l.toggleChat("s1")
        assertFalse(l.panesFor("s1").chat)
        assertTrue(l.panesFor("s1").editor)
    }
    @Test fun paneStateIsPerSession() {
        val l = ShellLayout()
        l.toggleEditor("s1")
        assertTrue(l.panesFor("s1").editor)
        assertFalse(l.panesFor("s2").editor)
    }
    @Test fun pruneDropsDeadSessions() {
        val l = ShellLayout()
        l.toggleEditor("s1"); l.toggleTerminal("s2")
        l.prune(setOf("s1"))
        assertTrue(l.panesFor("s1").editor)
        assertFalse(l.panesFor("s2").hasWork)
    }
    @Test fun snapshotRoundTrips() {
        val l = ShellLayout()
        l.setSidebarWidth(400.dp); l.setChatFraction(0.35f); l.toggleEditor("s1"); l.setNativeView("s1", true)
        val restored = ShellLayout().apply { restore(l.snapshot()) }
        assertEquals(400.dp, restored.sidebarWidth)
        assertEquals(0.35f, restored.chatFraction)
        assertTrue(restored.panesFor("s1").editor)
        assertTrue(restored.nativeView("s1"))
    }
}
