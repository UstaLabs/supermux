package dev.supermux.ui.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TreeViewStateTest {
    @Test fun toggleAndPruneDescendants() {
        val v = TreeViewState("/w")
        v.toggle("/w/src"); v.toggle("/w/src/ui"); v.toggle("/w/docs")
        assertEquals(setOf("/w/src", "/w/src/ui", "/w/docs"), v.expanded)
        v.prune("/w/src")
        assertEquals(setOf("/w/docs"), v.expanded)
        v.toggle("/w/docs")
        assertTrue(v.expanded.isEmpty())
    }

    @Test fun revealExpandsAncestorsAndSelects() {
        val v = TreeViewState("/w")
        v.reveal("/w/src/ui/A.kt")
        assertEquals(setOf("/w/src", "/w/src/ui"), v.expanded)
        assertEquals("/w/src/ui/A.kt", v.selected)
    }

    @Test fun holderKeepsStatePerViewAndResetsOnWorkdirChange() {
        val h = TreeViewStates()
        val a = h.forView("v1", workdir = "/w")
        a.toggle("/w/src")
        assertSame(a, h.forView("v1", workdir = "/w"))
        val b = h.forView("v1", workdir = "/other")
        assertNotSame(a, b)
        assertEquals("/other", b.rootPath)
        assertTrue(b.expanded.isEmpty())
        h.forget("v1")
        assertNotSame(b, h.forView("v1", workdir = "/other"))
    }

    @Test fun revealOutsideRootDoesNothing() {
        val v = TreeViewState("/w")
        v.reveal("/other/A.kt")
        kotlin.test.assertNull(v.selected)
        assertTrue(v.expanded.isEmpty())
    }
}
