package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RangeSetTest {
    private val mark = Decoration.Mark(setOf("m"))
    private val inclusive = Decoration.Mark(setOf("m"), inclusiveStart = true, inclusiveEnd = true)

    @Test fun exclusiveMarksDoNotGrowAtTheirEdges() {
        val set = RangeSet.of(listOf(Ranged(2, 5, mark)))
        assertEquals(listOf(Ranged(3, 6, mark)), set.map(ChangeSet.of(8, ChangeSpec(2, 2, "x"))).ranges)
        assertEquals(listOf(Ranged(2, 5, mark)), set.map(ChangeSet.of(8, ChangeSpec(5, 5, "x"))).ranges)
    }

    @Test fun inclusiveMarksGrow() {
        val set = RangeSet.of(listOf(Ranged(2, 5, inclusive)))
        assertEquals(listOf(Ranged(2, 6, inclusive)), set.map(ChangeSet.of(8, ChangeSpec(2, 2, "x"))).ranges)
        assertEquals(listOf(Ranged(2, 6, inclusive)), set.map(ChangeSet.of(8, ChangeSpec(5, 5, "x"))).ranges)
    }

    @Test fun deletedRangesDisappearAndPartialOnesShrink() {
        val set = RangeSet.of(listOf(Ranged(2, 5, mark), Ranged(6, 9, mark)))
        val mapped = set.map(ChangeSet.of(10, ChangeSpec(1, 7)))
        assertEquals(listOf(Ranged(1, 3, mark)), mapped.ranges)
    }

    @Test fun pointDecorationsSurviveAndRespectSide() {
        val before = Decoration.InlineWidget(WidgetKey("w", "a"), side = -1)
        val after = Decoration.InlineWidget(WidgetKey("w", "b"), side = 1)
        val set = RangeSet.of(listOf(Ranged(3, 3, before), Ranged(3, 3, after)))
        val mapped = set.map(ChangeSet.of(5, ChangeSpec(3, 3, "xy"))).ranges
        assertEquals(listOf(Ranged(3, 3, before), Ranged(5, 5, after)), mapped)
    }

    @Test fun betweenFindsOverlapsIncludingEdges() {
        val set = RangeSet.of(listOf(Ranged(0, 2, mark), Ranged(4, 6, mark), Ranged(9, 9, mark)))
        assertEquals(2, set.between(2, 4).size)
        assertEquals(1, set.between(9, 20).size)
    }
}
