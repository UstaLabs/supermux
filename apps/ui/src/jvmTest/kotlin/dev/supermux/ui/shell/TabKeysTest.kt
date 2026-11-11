package dev.supermux.ui.shell

import dev.supermux.editor.core.KeyChord
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.KeyOs
import dev.supermux.workspace.LayoutNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #122 (Q2): Chrome's tab keys move between the tabs of the pane last worked in. */
class TabKeysTest {
    private val two = LayoutNode.Split(
        "row", listOf(0.5, 0.5),
        listOf(LayoutNode.Group("g1", listOf("a", "b", "c"), "b"), LayoutNode.Group("g2", listOf("d", "e"), "d")),
    )

    @Test fun nextAndPreviousStepThroughThePaneAndWrap() {
        assertEquals("g1" to "c", tabStep(two, focusedViewId = "b", step = +1))
        assertEquals("g1" to "a", tabStep(two, focusedViewId = "b", step = -1))
        assertEquals("g2" to "e", tabStep(two, focusedViewId = "d", step = +1))
        assertEquals("g2" to "e", tabStep(two, focusedViewId = "d", step = -1))
        assertEquals("g1" to "a", tabStep(LayoutNode.Group("g1", listOf("a", "b", "c"), "c"), focusedViewId = "c", step = +1))
    }

    @Test fun withNoPaneWorkedInItIsTheFirstPane() {
        assertEquals("g1" to "c", tabStep(two, focusedViewId = null, step = +1))
    }

    @Test fun aNumberIsThatTabAndNineIsTheLast() {
        assertEquals("g1" to "a", tabNumbered(two, focusedViewId = "c", n = 1))
        assertEquals("g1" to "c", tabNumbered(two, focusedViewId = "c", n = 3))
        assertEquals("g2" to "e", tabNumbered(two, focusedViewId = "d", n = 9))
        assertNull(tabNumbered(two, focusedViewId = "d", n = 3))
    }

    @Test fun aSingleTabHasNowhereToStep() {
        assertNull(tabStep(LayoutNode.Group("g", listOf("a"), "a"), focusedViewId = "a", step = +1))
    }

    @Test fun theKeysAreChromes() {
        fun keys(os: KeyOs, cmd: dev.supermux.ui.keys.AppCommand) = DefaultKeymap.chordsFor(cmd, os).toSet()
        fun p(spec: String, apple: Boolean) = KeyChord.parse(spec, apple)
        assertEquals(setOf(p("Ctrl-Tab", false), p("Ctrl-PageDown", false)), keys(KeyOs.Other, AppCommands.TabNext))
        assertEquals(setOf(p("Ctrl-Shift-Tab", false), p("Ctrl-PageUp", false)), keys(KeyOs.Other, AppCommands.TabPrevious))
        assertEquals(setOf(p("Mod-Shift-]", true)), keys(KeyOs.Apple, AppCommands.TabNext))
        assertEquals(setOf(p("Mod-Shift-[", true)), keys(KeyOs.Apple, AppCommands.TabPrevious))
        for (n in 1..8) assertEquals(setOf(p("Mod-$n", true)), keys(KeyOs.Apple, AppCommands.tabNumber(n)))
        assertEquals(setOf(p("Mod-9", false)), keys(KeyOs.Other, AppCommands.tabNumber(9)))
    }
}
