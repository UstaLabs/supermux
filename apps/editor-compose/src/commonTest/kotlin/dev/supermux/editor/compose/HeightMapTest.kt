package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeightMapTest {
    @Test fun estimatesUntilMeasured() {
        val m = HeightMap(4, 10f)
        assertEquals(40f, m.totalHeight)
        assertEquals(20f, m.top(2))
        m.setMeasured(1, 25f)
        assertEquals(55f, m.totalHeight)
        assertEquals(35f, m.top(2))
        assertEquals(25f, m.height(1))
        assertEquals(0, m.lineAt(-5f))
        assertEquals(1, m.lineAt(10f))
        assertEquals(1, m.lineAt(34.9f))
        assertEquals(2, m.lineAt(35f))
        assertEquals(3, m.lineAt(1e9f), "clamped to the last line")
    }

    @Test fun blocksAddHeightAroundALine() {
        val m = HeightMap(3, 10f)
        m.setBlockHeight(1, above = 5f, below = 7f)
        assertEquals(42f, m.totalHeight)
        assertEquals(22f, m.height(1))
        assertEquals(10f, m.top(1))
        assertEquals(5f, m.blockAbove(1))
        assertEquals(32f, m.top(2))
        m.setMeasured(1, 20f) // the TEXT height; the blocks stay
        assertEquals(32f, m.height(1))
    }

    @Test fun anEditReplacesTheTouchedLinesWithEstimates() {
        val before = Rope.of("a\nbb\nccc\nd")
        val m = HeightMap(before.lineCount, 10f)
        for (i in 0 until 4) m.setMeasured(i, 10f + i)
        // Replace "bb\nccc" (lines 1..2) by "X\nY\nZ": three estimated lines where two measured ones were.
        val changes = ChangeSet.of(before.length, ChangeSpec(2, 8, "X\nY\nZ"))
        val after = changes.apply(before)
        m.applyChanges(changes, before, after)
        assertEquals(after.lineCount, m.lineCount)
        assertEquals(10f, m.height(0))
        assertEquals(listOf(10f, 10f, 10f), (1..3).map { m.height(it) })
        assertEquals(13f, m.height(4), "the untouched last line kept its measurement")
    }

    /** The model: a plain array of text heights plus block heights, rebuilt naively on every edit. */
    private class Model(n: Int, val est: Float) {
        val h = MutableList(n) { est }
        val above = MutableList(n) { 0f }
        val below = MutableList(n) { 0f }
        fun total(i: Int) = h[i] + above[i] + below[i]
        fun replace(start: Int, count: Int, newCount: Int) {
            repeat(count) { h.removeAt(start); above.removeAt(start); below.removeAt(start) }
            repeat(newCount) { h.add(start, est); above.add(start, 0f); below.add(start, 0f) }
        }
    }

    @Test fun randomEditsAndMeasurementsMatchAPlainArray() {
        for (seed in 0 until 40) {
            val rnd = Random(seed)
            var doc = Rope.of(List(rnd.nextInt(1, 300)) { "x".repeat(rnd.nextInt(0, 5)) }.joinToString("\n"))
            // A small chunk size so splits, merges and multi-chunk replacements all happen.
            val m = HeightMap(doc.lineCount, 7f, chunkTarget = 8)
            val model = Model(doc.lineCount, 7f)
            val log = ArrayList<String>()
            repeat(200) {
                when (rnd.nextInt(4)) {
                    0 -> {
                        val i = rnd.nextInt(model.h.size)
                        val v = rnd.nextInt(1, 60).toFloat()
                        m.setMeasured(i, v); model.h[i] = v; log += "measure $i=$v"
                    }
                    1 -> {
                        val i = rnd.nextInt(model.h.size)
                        val a = rnd.nextInt(0, 3) * 4f
                        val b = rnd.nextInt(0, 3) * 5f
                        m.setBlockHeight(i, a, b); model.above[i] = a; model.below[i] = b; log += "block $i=$a,$b"
                    }
                    else -> {
                        val from = rnd.nextInt(doc.length + 1)
                        val to = minOf(doc.length, from + rnd.nextInt(0, 40))
                        val insert = List(rnd.nextInt(0, 4)) { "y".repeat(rnd.nextInt(0, 3)) }.joinToString("\n")
                            .let { if (rnd.nextBoolean()) it else "" }
                        val changes = ChangeSet.of(doc.length, ChangeSpec(from, to, insert))
                        if (changes.isEmpty) return@repeat // a no-op edit touches nothing
                        val after = changes.apply(doc)
                        val startLine = doc.lineIndexAt(from)
                        val endLine = doc.lineIndexAt(to)
                        model.replace(startLine, endLine - startLine + 1, after.lineIndexAt(from + insert.length) - after.lineIndexAt(from) + 1)
                        log += "edit lines $startLine..$endLine -> ${after.lineIndexAt(from + insert.length) - after.lineIndexAt(from) + 1} (of ${doc.lineCount})"
                        m.applyChanges(changes, doc, after)
                        doc = after
                    }
                }
                check(m, model, rnd, "seed $seed after ${log.takeLast(6)}")
            }
        }
    }

    @Test fun severalChangesInOneChangeSetAreAppliedBackToFront() {
        val rnd = Random(7)
        for (round in 0 until 50) {
            val doc = Rope.of(List(60) { "line $it" }.joinToString("\n"))
            val m = HeightMap(doc.lineCount, 5f, chunkTarget = 4)
            val model = Model(doc.lineCount, 5f)
            for (i in 0 until doc.lineCount) { val v = 5f + i; m.setMeasured(i, v); model.h[i] = v }
            // Three non-overlapping changes, far enough apart to land on different lines.
            val specs = listOf(30, 200, 400).map { at -> ChangeSpec(at, at + rnd.nextInt(0, 30), "n\n".repeat(rnd.nextInt(0, 3))) }
            val changes = ChangeSet.of(doc.length, specs)
            val after = changes.apply(doc)
            for (s in specs.reversed()) {
                val startLine = doc.lineIndexAt(s.from)
                val endLine = doc.lineIndexAt(s.to)
                model.replace(startLine, endLine - startLine + 1, s.insert.count { it == '\n' } + 1)
            }
            m.applyChanges(changes, doc, after)
            assertEquals(after.lineCount, m.lineCount)
            check(m, model, rnd, "round $round")
        }
    }

    private fun check(m: HeightMap, model: Model, rnd: Random, what: String) {
        assertEquals(model.h.size, m.lineCount, "$what: line count")
        val tops = DoubleArray(model.h.size + 1)
        for (i in model.h.indices) tops[i + 1] = tops[i] + model.total(i)
        val total = tops[model.h.size]
        assertClose(total, m.totalHeight, "$what: total")
        for (i in model.h.indices) {
            assertClose(tops[i], m.top(i), "$what: top($i)")
            assertClose(model.total(i).toDouble(), m.height(i), "$what: height($i)")
        }
        repeat(20) {
            val y = rnd.nextDouble(-10.0, total + 10.0).toFloat()
            var expect = model.h.size - 1
            for (i in model.h.indices) if (y < tops[i + 1]) { expect = i; break }
            if (y < 0) expect = 0
            val got = m.lineAt(y)
            // At an exact boundary float rounding may pick either neighbour; both contain y.
            assertTrue(got == expect || abs(tops[got] - y) < 1e-3 || abs(tops[got + 1] - y) < 1e-3, "$what: lineAt($y) = $got, expected $expect")
        }
    }

    private fun assertClose(expected: Double, actual: Float, what: String) =
        assertTrue(abs(expected - actual) < 1e-2, "$what: expected $expected, got $actual")
}
