package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChangeSetTest {
    @Test fun appliesUnsortedNonOverlappingSpecs() {
        val cs = ChangeSet.of(11, ChangeSpec(6, 11, "rope"), ChangeSpec(0, 0, ">"))
        assertEquals(">hello rope", cs.apply("hello world"))
        assertEquals(11, cs.lengthBefore)
        assertEquals(11, cs.lengthAfter)
    }

    @Test fun overlappingSpecsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ChangeSet.of(10, ChangeSpec(0, 5), ChangeSpec(3, 6)) }
    }

    @Test fun anInsertAndAReplacementAtTheSamePositionWorkInEitherOrder() {
        val a = ChangeSet.of(10, ChangeSpec(5, 8, "b"), ChangeSpec(5, 5, "a"))
        val b = ChangeSet.of(10, ChangeSpec(5, 5, "a"), ChangeSpec(5, 8, "b"))
        assertEquals(a, b)
        assertEquals("01234ab89", a.apply("0123456789"))
    }

    @Test fun normalFormMakesEqualEffectsEqual() {
        val a = ChangeSet.Builder().retain(2).insert("x").delete(3).retain(1).build()
        val b = ChangeSet.Builder().retain(2).delete(3).insert("x").retain(1).build()
        assertEquals(a, b)
        assertEquals("=2 -3 +\"x\" =1", a.toString())
    }

    @Test fun iterChangesReportsBothCoordinates() {
        val cs = ChangeSet.of(10, ChangeSpec(2, 4, "abc"), ChangeSpec(8, 8, "Z"))
        assertEquals(listOf(Change(2, 4, 2, 5, "abc"), Change(8, 8, 9, 10, "Z")), cs.iterChanges())
    }

    @Test fun mapPosFollowsTheDocumentedRules() {
        val ins = ChangeSet.of(10, ChangeSpec(5, 5, "abc"))
        assertEquals(4, ins.mapPos(4))
        assertEquals(5, ins.mapPos(5, -1))  // stays before an insertion at its position
        assertEquals(8, ins.mapPos(5, 1))   // or moves after it
        assertEquals(9, ins.mapPos(6))
        val rep = ChangeSet.of(10, ChangeSpec(2, 6, "xy"))
        assertEquals(2, rep.mapPos(2, 1))   // start of a replaced range stays at its start
        assertEquals(2, rep.mapPos(4, -1))  // inside: start of the replacement…
        assertEquals(4, rep.mapPos(4, 1))   // …or its end
        assertEquals(4, rep.mapPos(6))      // end of the range = end of the replacement
        assertEquals(8, rep.mapPos(10))
    }

    @Test fun invertUndoesExactly() = repeatRandom { rnd, doc ->
        val cs = randomChangeSet(rnd, doc.length)
        val after = cs.apply(Rope.of(doc))
        assertEquals(doc, cs.invert(Rope.of(doc)).apply(after).toString())
    }

    @Test fun composeEqualsApplyingBoth() = repeatRandom { rnd, doc ->
        val a = randomChangeSet(rnd, doc.length)
        val mid = a.apply(doc)
        val b = randomChangeSet(rnd, mid.length)
        assertEquals(b.apply(mid), a.compose(b).apply(doc))
    }

    @Test fun composeWithInverseIsIdentity() = repeatRandom { rnd, doc ->
        val a = randomChangeSet(rnd, doc.length)
        val undone = a.compose(a.invert(Rope.of(doc)))
        assertEquals(doc, undone.apply(doc))
    }

    @Test fun mapPosStaysInBoundsAndMonotonic() = repeatRandom { rnd, doc ->
        val cs = randomChangeSet(rnd, doc.length)
        var last = -1
        for (p in 0..doc.length) {
            val m = cs.mapPos(p, 1)
            assertTrue(m in 0..cs.lengthAfter, "mapPos($p)=$m")
            assertTrue(m >= last, "not monotonic at $p")
            last = m
        }
    }

    @Test fun mapPosMatchesAChangeByChangeModel() = repeatRandom { rnd, doc ->
        val cs = randomChangeSet(rnd, doc.length)
        for (assoc in intArrayOf(-1, 1)) for (p in 0..doc.length) {
            assertEquals(modelMapPos(cs, p, assoc), cs.mapPos(p, assoc), "mapPos($p, $assoc) for $cs")
        }
    }

    /** mapPos's documented rules, applied change by change over [ChangeSet.iterChanges]. */
    private fun modelMapPos(cs: ChangeSet, pos: Int, assoc: Int): Int {
        var delta = 0
        for (c in cs.iterChanges()) {
            if (pos < c.fromA) break
            val insLen = c.toB - c.fromB
            if (c.fromA == c.toA) {
                if (pos == c.fromA) return if (assoc < 0) pos + delta else pos + delta + insLen
            } else {
                if (pos == c.fromA) return pos + delta
                if (pos < c.toA) return if (assoc < 0) c.fromB else c.toB
            }
            delta += insLen - (c.toA - c.fromA)
        }
        return pos + delta
    }

    private fun repeatRandom(block: (Random, String) -> Unit) {
        val rnd = Random(42)
        repeat(500) {
            val doc = buildString { repeat(rnd.nextInt(0, 40)) { append("ab\nc"[rnd.nextInt(4)]) } }
            block(rnd, doc)
        }
    }

    private fun randomChangeSet(rnd: Random, length: Int): ChangeSet {
        val specs = ArrayList<ChangeSpec>()
        var pos = 0
        while (pos <= length && rnd.nextInt(4) != 0) {
            val from = pos + rnd.nextInt(0, (length - pos) + 1).coerceAtMost(5)
            if (from > length) break
            val to = (from + rnd.nextInt(0, 4)).coerceAtMost(length)
            val ins = "XYZ".take(rnd.nextInt(0, 4))
            specs += ChangeSpec(from, to, ins)
            pos = to + 1
        }
        return ChangeSet.of(length, specs)
    }
}
