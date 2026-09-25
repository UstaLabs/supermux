package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
    @Test fun rangesAreSortedAndOverlapsMerged() {
        val s = EditorSelection.create(listOf(SelectionRange(10, 12), SelectionRange(0, 3), SelectionRange(2, 5)), mainIndex = 0)
        assertEquals("0-5,*10-12", s.toString())
    }

    @Test fun touchingNonEmptyRangesStayApartButACursorJoins() {
        assertEquals(2, EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(3, 6))).ranges.size)
        assertEquals(1, EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(3))).ranges.size)
        assertEquals(1, EditorSelection.create(listOf(SelectionRange(4), SelectionRange(4))).ranges.size)
    }

    @Test fun selectionFollowsEdits() {
        val s = EditorSelection.create(listOf(SelectionRange(2), SelectionRange(8, 10)))
        val cs = ChangeSet.of(12, ChangeSpec(0, 0, "abc"))
        assertEquals("*5-5,11-13", s.map(cs).toString())
    }
}
