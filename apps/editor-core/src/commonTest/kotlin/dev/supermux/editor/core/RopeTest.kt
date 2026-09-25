package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RopeTest {
    @Test fun emptyRopeHasOneEmptyLine() {
        val r = Rope.of("")
        assertEquals(0, r.length)
        assertEquals(1, r.lineCount)
        assertEquals(Line(1, 0, 0, ""), r.line(1))
    }

    @Test fun linesAreFoundByNumberAndPosition() {
        val r = Rope.of("ab\ncde\n\nf")
        assertEquals(4, r.lineCount)
        assertEquals(Line(2, 3, 6, "cde"), r.line(2))
        assertEquals(Line(3, 7, 7, ""), r.line(3))
        assertEquals(2, r.lineAt(6).number) // the position of the '\n' belongs to its line
        assertEquals(4, r.lineAt(9).number)
    }

    @Test fun replaceSharesAndDoesNotMutate() {
        val a = Rope.of("hello world")
        val b = a.replace(6, 11, "rope")
        assertEquals("hello world", a.toString())
        assertEquals("hello rope", b.toString())
    }

    @Test fun surrogatePairsCountAsTwoUnits() {
        val r = Rope.of("a😀b")
        assertEquals(4, r.length)
        assertEquals("b", r.slice(3, 4))
    }

    @Test fun chunkAtFeedsAParserTheWholeText() {
        val text = buildString { repeat(5000) { append("line $it\n") } }
        val r = Rope.of(text)
        val rebuilt = StringBuilder()
        var pos = 0
        while (pos < r.length) { val c = r.chunkAt(pos); rebuilt.append(c); pos += c.length }
        assertEquals(text, rebuilt.toString())
        assertEquals("", r.chunkAt(r.length).toString())
    }

    /** The rope must agree with a plain String through thousands of random edits. */
    @Test fun randomEditsMatchAStringModel() {
        val rnd = Random(20260925)
        var model = ""
        var rope = Rope.EMPTY
        val alphabet = "abc \n😀ğ"
        repeat(4000) { step ->
            val from = rnd.nextInt(model.length + 1)
            val to = (from + rnd.nextInt(0, 12)).coerceAtMost(model.length)
            val len = if (rnd.nextInt(20) == 0) rnd.nextInt(3000) else rnd.nextInt(8)
            val insert = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            model = model.replaceRange(from, to, insert)
            rope = rope.replace(from, to, insert)
            if (step % 97 == 0) {
                assertEquals(model, rope.toString(), "text after step $step")
                assertEquals(model.count { it == '\n' } + 1, rope.lineCount, "lineCount after step $step")
                if (model.isNotEmpty()) {
                    val p = rnd.nextInt(model.length + 1)
                    assertEquals(model.substring(0, p).count { it == '\n' }, rope.lineIndexAt(p), "lineIndexAt($p)")
                    val n = rnd.nextInt(rope.lineCount) + 1
                    assertEquals(model.split('\n')[n - 1], rope.line(n).text, "line($n)")
                }
            }
        }
        assertEquals(model, rope.toString())
        // Lazy rebalancing keeps the tree shallow.
        assertTrue(rope.depth <= 8 + 2 * 32, "depth ${rope.depth}")
    }

    @Test fun equalTextWithDifferentTreeShapesIsEqual() {
        val text = buildString { repeat(3000) { append("line $it\n") } }
        val a = Rope.of(text)
        // Built by many small edits, so its chunk boundaries differ from a's.
        var b = Rope.EMPTY
        var i = 0
        while (i < text.length) { val n = minOf(text.length - i, 37 + i % 700); b = b.replace(b.length, b.length, text.substring(i, i + n)); i += n }
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(a.hashCode(), a.hashCode())
        assertNotEquals(a, a.replace(5000, 5001, "#"))
        assertNotEquals(a, Rope.of(text.dropLast(1)))
    }

    @Test fun bigDocumentStaysShallow() {
        val r = Rope.of("x".repeat(4_000_000))
        assertTrue(r.depth <= 13, "depth ${r.depth}")
    }
}
