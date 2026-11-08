package dev.supermux.ui.shell

import dev.supermux.editor.core.KeyChord
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.KeyOs
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ⌘W (Ctrl+W, and Ctrl+Shift+W past a terminal off Apple) closes the tab on show in the pane last worked in (#91). */
class CloseTabCommandTest {
    private val two = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a", "b"), "b"), LayoutNode.Group("g2", listOf("c", "d"), "c")))

    @Test fun closeTabIsModW() {
        assertEquals(listOf(KeyChord.parse("Mod-w", apple = true)), DefaultKeymap.chordsFor(AppCommands.CloseTab, KeyOs.Apple))
        assertEquals(
            setOf(KeyChord.parse("Mod-w", apple = false), KeyChord.parse("Ctrl-Shift-w", apple = false)),
            DefaultKeymap.chordsFor(AppCommands.CloseTab, KeyOs.Other).toSet(),
        )
    }

    @Test fun itClosesTheTabOnShowInThePaneLastWorkedIn() {
        assertEquals("c", closeTabTarget(two, focusedViewId = "d"))
    }

    @Test fun withNoPaneWorkedInItIsTheFirstPanes() {
        assertEquals("b", closeTabTarget(two, focusedViewId = null))
        assertEquals("b", closeTabTarget(two, focusedViewId = "gone"))
    }

    @Test fun anEmptyWorkspaceHasNothingToClose() {
        assertNull(closeTabTarget(LayoutNode.Group("g", emptyList(), null), focusedViewId = null))
    }
}
