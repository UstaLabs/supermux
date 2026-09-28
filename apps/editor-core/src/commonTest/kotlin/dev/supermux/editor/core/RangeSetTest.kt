package dev.supermux.editor.core

import kotlin.random.Random
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

    @Test fun pointDecorationsInsideADeletionAreDropped() {
        val line = Decoration.LineStyle(setOf("l"))
        val set = RangeSet.of(listOf(Ranged(0, 0, line), Ranged(4, 4, line), Ranged(8, 8, line)))
        assertEquals(listOf(Ranged(0, 0, line)), set.map(ChangeSet.of(12, ChangeSpec(3, 11))).ranges)
    }

    @Test fun widgetsAtADeletionBoundaryKeepTheirSideMapping() {
        val before = Decoration.InlineWidget(WidgetKey("w", "a"), side = -1)
        val after = Decoration.InlineWidget(WidgetKey("w", "b"), side = 1)
        val set = RangeSet.of(listOf(Ranged(3, 3, after), Ranged(11, 11, before)))
        assertEquals(listOf(Ranged(3, 3, after), Ranged(3, 3, before)), set.map(ChangeSet.of(12, ChangeSpec(3, 11))).ranges)
    }

    @Test fun betweenFindsOverlapsIncludingEdges() {
        val set = RangeSet.of(listOf(Ranged(0, 2, mark), Ranged(4, 6, mark), Ranged(9, 9, mark)))
        assertEquals(2, set.between(2, 4).size)
        assertEquals(1, set.between(9, 20).size)
    }

    @Test fun betweenMatchesAFullScan() {
        val rnd = Random(3)
        repeat(200) {
            val ranges = List(rnd.nextInt(0, 40)) {
                val from = rnd.nextInt(0, 100)
                Ranged(from, from + if (rnd.nextInt(5) == 0) rnd.nextInt(0, 60) else rnd.nextInt(0, 4), mark)
            }
            val set = RangeSet.of(ranges)
            assertEquals(ranges.size, set.size)
            assertEquals(ranges.isEmpty(), set.isEmpty)
            assertEquals(set.ranges, set.toList())
            repeat(20) {
                val from = rnd.nextInt(-5, 110); val to = from + rnd.nextInt(0, 20)
                assertEquals(set.ranges.filter { it.to >= from && it.from <= to }, set.between(from, to), "between($from, $to)")
            }
        }
    }

    @Test fun lastStartingAtOrBeforeIsABinarySearch() {
        val set = RangeSet.of(listOf(Ranged(0, 2, "a"), Ranged(5, 9, "b"), Ranged(9, 12, "c")))
        assertEquals(null, RangeSet.of(listOf(Ranged(3, 4, "z"))).lastStartingAtOrBefore(2))
        assertEquals("a", set.lastStartingAtOrBefore(1)?.value)
        assertEquals("a", set.lastStartingAtOrBefore(4)?.value)
        assertEquals("b", set.lastStartingAtOrBefore(8)?.value)
        assertEquals("c", set.lastStartingAtOrBefore(9)?.value)
        assertEquals("c", set.lastStartingAtOrBefore(100)?.value)
    }
}
