package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class EditsTest {
    /** (row, UTF-16 column) of [index] in [text], computed the slow and obvious way. */
    private fun point(text: String, index: Int): Pair<Int, Int> {
        val before = text.substring(0, index)
        return before.count { it == '\n' } to (index - (before.lastIndexOf('\n') + 1))
    }

    /** Apply [edits] one by one to a String, checking every coordinate against the String itself. */
    private fun replay(changes: ChangeSet, before: String): String {
        val after = changes.apply(before)
        val edits = textEditsFor(changes, Rope.of(before), Rope.of(after))
        val inserted = changes.iterChanges().map { it.inserted }
        assertEquals(inserted.size, edits.size, "one edit per change")
        var model = before
        edits.forEachIndexed { k, e ->
            assertEquals(point(model, e.start), e.startRow to e.startColumn, "start point of edit $k")
            assertEquals(point(model, e.oldEnd), e.oldEndRow to e.oldEndColumn, "old end point of edit $k")
            assertEquals(e.start + inserted[k].length, e.newEnd, "new end of edit $k")
            model = model.substring(0, e.start) + inserted[k] + model.substring(e.oldEnd)
            assertEquals(point(model, e.newEnd), e.newEndRow to e.newEndColumn, "new end point of edit $k")
        }
        assertEquals(after, model)
        return model
    }

    private val alphabet = listOf("a", "b", "x", " ", "\n", "\n", "ğ", "😀", "{", "}", "\t")

    private fun randomText(r: Random, max: Int) = buildString { repeat(r.nextInt(max)) { append(alphabet[r.nextInt(alphabet.size)]) } }

    /** Code-point boundaries of [s]: never split a surrogate pair. */
    private fun boundaries(s: String): List<Int> = (0..s.length).filter { it == 0 || it == s.length || !s[it - 1].isHighSurrogate() }

    @Test fun randomChangeSetsReplayExactly() {
        val r = Random(20260926)
        repeat(500) { case ->
            val doc = randomText(r, 60)
            val cuts = boundaries(doc).shuffled(r).take(r.nextInt(1, 9)).sorted()
            // pair up cut points into non-overlapping [from, to) ranges, some of them pure inserts
            val specs = cuts.chunked(2).map { p ->
                val from = p[0]
                val to = if (p.size == 2 && r.nextBoolean()) p[1] else from
                ChangeSpec(from, to, randomText(r, 8))
            }
            val cs = ChangeSet.of(doc.length, specs)
            try { replay(cs, doc) } catch (e: AssertionError) { throw AssertionError("case $case: doc=${doc.escape()} changes=$cs: ${e.message}", e) }
        }
    }

    private fun String.escape() = replace("\n", "\\n")

    @Test fun multiChangeSet() {
        val doc = "fun a() {\n    b()\n}\n"
        replay(ChangeSet.of(doc.length, ChangeSpec(0, 3, "private fun"), ChangeSpec(14, 15, "cc"), ChangeSpec(20, 20, "// end\n")), doc)
    }

    @Test fun multiLineInsert() {
        val doc = "line one\nline two\n"
        val e = replay(ChangeSet.of(doc.length, ChangeSpec(9, 9, "new a\nnew b\nnew c")), doc)
        assertEquals("line one\nnew a\nnew b\nnew cline two\n", e)
        val edit = textEditsFor(ChangeSet.of(doc.length, ChangeSpec(9, 9, "new a\nnew b\nnew c")), Rope.of(doc), Rope.of(e)).single()
        assertEquals(TextEdit(9, 9, 26, 1, 0, 1, 0, 3, 5), edit)
    }

    @Test fun emojiColumnsAreUtf16() {
        val doc = "😀😀x\n😀y"
        val cs = ChangeSet.of(doc.length, ChangeSpec(4, 5, "ağ"), ChangeSpec(8, 9, "zz"))
        replay(cs, doc)
        val edits = textEditsFor(cs, Rope.of(doc), Rope.of(cs.apply(doc)))
        assertEquals(TextEdit(4, 5, 6, 0, 4, 0, 5, 0, 6), edits[0])
        // the second change starts after the first one's result: index 9 of the intermediate document
        assertEquals(TextEdit(9, 10, 11, 1, 2, 1, 3, 1, 4), edits[1])
    }

    @Test fun deletionAcrossLines() {
        val doc = "aaa\nbbb\nccc\nddd"
        val cs = ChangeSet.of(doc.length, ChangeSpec(2, 10, ""))
        replay(cs, doc)
        assertEquals(TextEdit(2, 10, 2, 0, 2, 2, 2, 0, 2), textEditsFor(cs, Rope.of(doc), Rope.of(cs.apply(doc))).single())
    }
}
