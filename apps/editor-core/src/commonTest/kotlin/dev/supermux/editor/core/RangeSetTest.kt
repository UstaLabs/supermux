package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // ------------------------------------------------------------------ M4d task 0 --

    @Test fun anEmptyExclusiveMarkAtAnInsertionIsDroppedNotACrash() {
        val set = RangeSet.of(listOf(Ranged(3, 3, mark), Ranged(1, 4, mark)))
        val mapped = set.map(ChangeSet.of(8, ChangeSpec(3, 3, "xy")))
        assertEquals(listOf(Ranged(1, 6, mark)), mapped.ranges)
        // Elsewhere it just moves.
        assertEquals(listOf(Ranged(5, 5, mark)), RangeSet.of(listOf(Ranged(3, 3, mark))).map(ChangeSet.of(8, ChangeSpec(0, 0, "ab"))).ranges)
    }

    /** One range mapped on its own, by the documented rules (null: it goes). */
    private fun <T> mapOne(r: Ranged<T>, cs: ChangeSet): Ranged<T>? {
        val v = r.value
        if (r.from == r.to && deletedAround(cs, r.from)) return null
        val (sa, ea) = when {
            v is Decoration.Mark -> (if (v.inclusiveStart) -1 else 1) to (if (v.inclusiveEnd) 1 else -1)
            r.from == r.to -> ((v as? Decoration.InlineWidget)?.side ?: -1).let { it to it }
            else -> 1 to -1
        }
        val from = cs.mapPos(r.from, sa)
        val to = cs.mapPos(r.to, ea)
        if (from > to || r.from < r.to && from == to) return null
        return Ranged(from, to, v)
    }

    /** Strictly inside a deleted range, from the change list (not the implementation's walk). */
    private fun deletedAround(cs: ChangeSet, pos: Int) = cs.iterChanges().any { it.toA > it.fromA && pos > it.fromA && pos < it.toA }

    @Test fun mappingRandomRangesThroughRandomEditsMatchesAModelAndStaysSorted() {
        val rnd = Random(41)
        val kinds: List<(Random) -> Decoration> = listOf(
            { r -> Decoration.Mark(setOf("m"), inclusiveStart = r.nextBoolean(), inclusiveEnd = r.nextBoolean()) },
            { _ -> Decoration.LineStyle(setOf("l")) },
            { r -> Decoration.InlineWidget(WidgetKey("w", "x"), side = if (r.nextBoolean()) 1 else -1) },
            { r -> Decoration.BlockWidget(WidgetKey("b", "x"), above = r.nextBoolean()) },
            { _ -> Decoration.Replace() },
        )
        repeat(400) { case ->
            var len = rnd.nextInt(0, 80)
            var set = RangeSet.of(List(rnd.nextInt(0, 30)) {
                val d = kinds[rnd.nextInt(kinds.size)](rnd)
                val from = rnd.nextInt(0, len + 1)
                val point = d !is Decoration.Mark && d !is Decoration.Replace || rnd.nextInt(3) == 0
                Ranged(from, if (point) from else rnd.nextInt(from, len + 1), d)
            })
            repeat(6) { step ->
                val specs = ArrayList<ChangeSpec>()
                var at = 0
                while (at <= len && specs.size < 4 && rnd.nextInt(3) > 0) {
                    val from = rnd.nextInt(at, len + 1)
                    val to = if (rnd.nextBoolean()) from else rnd.nextInt(from, minOf(len, from + 10) + 1)
                    specs += ChangeSpec(from, to, if (rnd.nextBoolean()) "" else "abc".take(rnd.nextInt(1, 4)))
                    at = to + 1
                }
                val cs = ChangeSet.of(len, specs)
                val model = set.ranges.mapNotNull { mapOne(it, cs) }
                val mapped = set.map(cs)
                assertEquals(RangeSet.of(model).ranges, mapped.ranges, "case $case step $step: $set through $cs")
                for (i in 1 until mapped.size) {
                    val a = mapped.ranges[i - 1]; val b = mapped.ranges[i]
                    assertTrue(a.from < b.from || a.from == b.from && a.to <= b.to, "sorted at $i: $mapped")
                }
                len = cs.lengthAfter
                repeat(8) {
                    val from = rnd.nextInt(-2, len + 3); val to = from + rnd.nextInt(0, 12)
                    assertEquals(mapped.ranges.filter { it.to >= from && it.from <= to }, mapped.between(from, to), "between($from, $to) of $mapped")
                }
                set = mapped
            }
        }
    }
}
