package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [ChangeSet.map]: operational transform of one change set over a concurrent one (both made to the
 * same document), CM6's `ChangeSet.map` / `ChangeDesc.mapDesc`.
 */
class ChangeSetMapTest {
    private val doc = "0123456789"

    @Test fun anEditAfterAnInsertMovesRight() {
        val a = ChangeSet.of(10, ChangeSpec(6, 7, "X"))
        val b = ChangeSet.of(10, ChangeSpec(2, 2, "bb"))
        val mapped = a.map(b)
        assertEquals("01bb2345X789", mapped.apply(b.apply(doc)))
        assertEquals(12, mapped.lengthBefore)
    }

    @Test fun insertionsAtTheSamePositionAreOrderedByBefore() {
        val a = ChangeSet.of(10, ChangeSpec(3, 3, "A"))
        val b = ChangeSet.of(10, ChangeSpec(3, 3, "B"))
        // Default: the other change came first, so a's text goes after it.
        assertEquals("012BA3456789", a.map(b).apply(b.apply(doc)))
        assertEquals("012AB3456789", a.map(b, before = true).apply(b.apply(doc)))
    }

    @Test fun textBothDeletedIsDeletedOnce() {
        val a = ChangeSet.of(10, ChangeSpec(2, 6))
        val b = ChangeSet.of(10, ChangeSpec(4, 8))
        assertEquals("0189", a.map(b).apply(b.apply(doc)))
        assertEquals("0189", b.map(a).apply(a.apply(doc)))
    }

    @Test fun anInsertionInsideADeletedRangeSurvives() {
        val a = ChangeSet.of(10, ChangeSpec(5, 5, "keep"))
        val b = ChangeSet.of(10, ChangeSpec(2, 8))
        assertEquals("01keep89", a.map(b).apply(b.apply(doc)))
        // And the deletion, mapped over the insertion, keeps the inserted text.
        assertEquals("01keep89", b.map(a).apply(a.apply(doc)))
    }

    @Test fun mappingOverAnEmptySetChangesNothing() {
        val a = ChangeSet.of(10, ChangeSpec(1, 4, "q"))
        assertEquals(a, a.map(ChangeSet.empty(10)))
        assertEquals(ChangeSet.empty(12), ChangeSet.empty(10).map(ChangeSet.of(10, ChangeSpec(0, 0, "zz"))))
    }

    @Test fun differentStartDocumentsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ChangeSet.empty(3).map(ChangeSet.empty(4)) }
    }

    /** The OT convergence law: a then b-over-a is b then a-over-b (CM6's property test). */
    @Test fun convergence() = repeatRandom { rnd, text ->
        val a = randomChangeSet(rnd, text.length)
        val b = randomChangeSet(rnd, text.length)
        val ab = a.compose(b.map(a))
        val ba = b.compose(a.map(b, before = true))
        assertEquals(ab.apply(text), ba.apply(text), "a=$a b=$b on '$text'")
        // Both mapped sets start where they must.
        assertEquals(a.lengthAfter, b.map(a).lengthBefore)
        assertEquals(b.lengthAfter, a.map(b, before = true).lengthBefore)
    }

    /** A position mapped through a then b-over-a lands where b then a-over-b puts it, away from any change. */
    @Test fun positionsConvergeOutsideChanges() = repeatRandom { rnd, text ->
        val a = randomChangeSet(rnd, text.length)
        val b = randomChangeSet(rnd, text.length)
        val ab = a.compose(b.map(a))
        val ba = b.compose(a.map(b, before = true))
        val touched = (a.iterChanges() + b.iterChanges()).flatMap { (it.fromA..it.toA).toList() }.toSet()
        for (p in 0..text.length) if (p !in touched) assertEquals(ab.mapPos(p), ba.mapPos(p), "pos $p, a=$a b=$b")
    }

    /** Undo after a concurrent edit (history's case): invert(a) mapped over b undoes a in b's document. */
    @Test fun anInvertedChangeMappedOverALaterOneStillUndoesIt() {
        // a typed "XY" at 5; then a remote insert "rr" at 1 arrived.
        val start = "0123456789"
        val a = ChangeSet.of(10, ChangeSpec(5, 5, "XY"))
        val afterA = a.apply(start)
        val remote = ChangeSet.of(12, ChangeSpec(1, 1, "rr"))
        val undo = a.invert(Rope.of(start)).map(remote)
        assertEquals("0rr123456789", undo.apply(remote.apply(afterA)))
    }

    private fun repeatRandom(block: (Random, String) -> Unit) {
        val rnd = Random(4242)
        repeat(2000) {
            val doc = buildString { repeat(rnd.nextInt(0, 30)) { append("ab\nc"[rnd.nextInt(4)]) } }
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
            pos = to + if (rnd.nextBoolean()) 0 else 1
            if (to == from && ins.isEmpty()) pos++
        }
        return ChangeSet.of(length, specs)
    }
}
