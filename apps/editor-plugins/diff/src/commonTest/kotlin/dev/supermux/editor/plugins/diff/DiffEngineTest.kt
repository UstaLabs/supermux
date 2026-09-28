package dev.supermux.editor.plugins.diff

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.LineMapping
import dev.supermux.editor.core.Rope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks that hold for every correct diff of [a] against [b]. */
internal fun assertValidDiff(a: List<String>, b: List<String>, hunks: List<DiffHunk>, what: String = "") {
    // Sorted, not overlapping, not touching (between two hunks at least one pair of equal lines).
    for (i in hunks.indices) {
        val h = hunks[i]
        assertTrue(h.aFrom <= h.aTo && h.bFrom <= h.bTo && (h.aFrom < h.aTo || h.bFrom < h.bTo), "$what: empty or bad hunk $h")
        if (i > 0) {
            val p = hunks[i - 1]
            assertTrue(h.aFrom > p.aTo && h.bFrom > p.bTo, "$what: hunks $p and $h touch or overlap")
            assertEquals(h.aFrom - p.aTo, h.bFrom - p.bTo, "$what: the equal run between $p and $h differs in length")
        }
    }
    // Applying the hunks to A gives B, and every line outside them pairs with an equal line.
    val out = ArrayList<String>()
    var ai = 0
    var bi = 0
    for (h in hunks) {
        while (ai < h.aFrom) { assertEquals(a[ai], b[bi], "$what: paired lines $ai/$bi"); out += a[ai]; ai++; bi++ }
        assertEquals(bi, h.bFrom, "$what: $h starts where its pair does")
        for (j in h.bFrom until h.bTo) out += b[j]
        ai = h.aTo; bi = h.bTo
    }
    while (ai < a.size) { assertEquals(a[ai], b.getOrNull(bi), "$what: tail $ai/$bi"); out += a[ai]; ai++; bi++ }
    assertEquals(b.size, bi, "$what: B fully covered")
    assertEquals(b, out, "$what: A with the hunks applied is B")
}

/** [chars] applied to [a] gives [b]. */
internal fun applyChars(a: String, b: String, chars: List<CharChange>): String {
    val sb = StringBuilder()
    var at = 0
    for (c in chars) {
        assertTrue(c.aFrom >= at && c.aFrom <= c.aTo && c.aTo <= a.length && c.bFrom <= c.bTo && c.bTo <= b.length, "bad char change $c")
        sb.append(a, at, c.aFrom)
        sb.append(b, c.bFrom, c.bTo)
        at = c.aTo
    }
    sb.append(a, at, a.length)
    return sb.toString()
}

internal fun randomLines(rnd: Random, n: Int, vocabulary: Int = 1_000_000): MutableList<String> =
    MutableList(n) { "    val v${rnd.nextInt(vocabulary)} = f(${rnd.nextInt(100)})" }

/** [a] with [changes] random line edits (replace, insert, delete), each 1–3 lines. */
internal fun mutate(rnd: Random, a: List<String>, changes: Int, vocabulary: Int = 1_000_000): List<String> {
    val b = a.toMutableList()
    repeat(changes) {
        val at = rnd.nextInt(0, b.size + 1)
        val n = rnd.nextInt(1, 4)
        when (rnd.nextInt(3)) {
            0 -> for (i in at until minOf(b.size, at + n)) b[i] = b[i].replace("f(", "g(") + " // changed"
            1 -> b.addAll(at, List(n) { "    inserted${rnd.nextInt(vocabulary)}()" })
            else -> repeat(minOf(n, b.size - at)) { b.removeAt(at) }
        }
    }
    return b
}

class DiffEngineTest {
    private fun lines(s: String) = s.split('\n')

    @Test fun equalTextsHaveNoHunks() {
        assertEquals(emptyList(), LineDiff.diff(lines("a\nb\nc"), lines("a\nb\nc")).hunks)
        assertEquals(emptyList(), LineDiff.diff(listOf(""), listOf("")).hunks)
    }

    @Test fun insertDeleteAndChangeAreSeparateHunks() {
        val a = lines("one\ntwo\nthree\nfour\nfive\nsix")
        val b = lines("one\nTWO\nthree\nfour\ninserted\nfive")
        val h = LineDiff.diff(a, b).hunks
        assertEquals(listOf(DiffHunk(1, 2, 1, 2), DiffHunk(4, 4, 4, 5), DiffHunk(5, 6, 6, 6)), h.map { it.copy(chars = null) })
        assertValidDiff(a, b, h)
        assertTrue(h[0].isChange && h[1].isInsert && h[2].isDelete)
    }

    @Test fun theLineMappingIsEditorCoresType() {
        val r = LineDiff.diff(lines("a\nb\nc"), lines("a\nx\ny\nc"))
        assertEquals(LineMapping(listOf(LineMapping.Hunk(1, 2, 1, 3))), r.lineMapping)
    }

    @Test fun randomDiffsApplyToTheWorkingCopy() {
        val rnd = Random(7)
        repeat(300) { case ->
            val vocab = if (case % 3 == 0) 6 else 1_000_000 // few distinct lines: no unique anchors
            val a = List(rnd.nextInt(0, 60)) { "l${rnd.nextInt(vocab)}" }
            val b = mutate(rnd, a, rnd.nextInt(0, 8), vocab)
            val r = LineDiff.diff(a.ifEmpty { listOf("") }, b.ifEmpty { listOf("") })
            assertValidDiff(a.ifEmpty { listOf("") }, b.ifEmpty { listOf("") }, r.hunks, "case $case")
        }
    }

    @Test fun smallDiffsAreMinimal() {
        // Myers on a small region: the shortest edit script (2 deletions + 1 insertion here).
        val a = lines("a\nb\nc\na\nb\nb\na")
        val b = lines("c\nb\na\nb\na\nc")
        val h = LineDiff.diff(a, b).hunks
        assertValidDiff(a, b, h)
        assertEquals(5, h.sumOf { (it.aTo - it.aFrom) + (it.bTo - it.bFrom) }, "$h")
    }

    @Test fun uniqueLinesAnchorTheDiff() {
        // Patience: the unique `fun` lines pair even though the braces around them repeat.
        val a = lines("fun a() {\n}\n\nfun b() {\n}\n\nfun c() {\n}")
        val b = lines("fun a() {\n}\n\nfun c() {\n}\n\nfun b() {\n    x()\n}")
        assertValidDiff(a, b, LineDiff.diff(a, b).hunks)
    }

    // ------------------------------------------------------------------ pathological --

    @Test fun allLinesIdenticalIsFastAndValid() {
        val a = List(20_000) { "}" }
        val b = a.toMutableList().also { it.add(10_000, "x"); it.removeAt(3) }
        val r = LineDiff.diff(a, b)
        assertValidDiff(a, b, r.hunks)
        assertTrue(r.hunks.size <= 4, "${r.hunks}")
    }

    @Test fun allLinesDifferentIsOneHunk() {
        val a = List(10_000) { "a$it" }
        val b = List(10_000) { "b$it" }
        val r = LineDiff.diff(a, b)
        assertEquals(listOf(DiffHunk(0, 10_000, 0, 10_000)), r.hunks.map { it.copy(chars = null) })
    }

    @Test fun aPathologicalRegionDegradesToACoarseHunkInsteadOfHanging() {
        // Two random texts over a two-line alphabet: no unique line anywhere, a huge edit distance.
        val rnd = Random(1)
        val a = List(20_000) { if (rnd.nextBoolean()) "x" else "y" }
        val b = List(20_000) { if (rnd.nextBoolean()) "x" else "y" }
        val r = LineDiff.diff(a, b, DiffOptions(maxCost = 200_000))
        assertValidDiff(a, b, r.hunks)
        assertTrue(r.coarse, "the cost cap was hit")
    }

    @Test fun veryLongLinesAreComparedWholeAndCharDiffIsCapped() {
        val long = "x".repeat(1_000_000)
        val a = listOf("a", long, "b")
        val b = listOf("a", long + "y", "b")
        val r = LineDiff.diff(a, b)
        assertEquals(listOf(DiffHunk(1, 2, 1, 2)), r.hunks.map { it.copy(chars = null) })
        assertNull(r.hunks[0].chars, "a 1 MB line gets no character diff (cost cap)")
    }

    // ------------------------------------------------------------------ characters --

    @Test fun charDiffFindsTheChangedWordInsideALine() {
        val a = "    val count = total + 1"
        val b = "    val counter = total + 2"
        val c = assertNotNull(CharDiff.diff(a, b))
        assertEquals(b, applyChars(a, b, c))
        // "count" -> "counter" refines to "er" inserted; "1" -> "2" replaced.
        assertEquals(listOf("" to "er", "1" to "2"), c.map { a.substring(it.aFrom, it.aTo) to b.substring(it.bFrom, it.bTo) })
    }

    @Test fun charDiffKeepsUnrelatedWordsWhole() {
        val a = "return foo(bar)"
        val b = "return baz(qux)"
        val c = assertNotNull(CharDiff.diff(a, b))
        assertEquals(b, applyChars(a, b, c))
        assertEquals(listOf("foo" to "baz", "bar" to "qux"), c.map { a.substring(it.aFrom, it.aTo) to b.substring(it.bFrom, it.bTo) })
    }

    @Test fun charDiffMergesChangesSplitByOneCharacter() {
        val a = "alpha beta gamma"
        val b = "one two gamma"
        val c = assertNotNull(CharDiff.diff(a, b))
        assertEquals(listOf("alpha beta" to "one two"), c.map { a.substring(it.aFrom, it.aTo) to b.substring(it.bFrom, it.bTo) })
    }

    @Test fun charDiffsAcrossLinesOfAHunk() {
        val a = listOf("x", "fun f(a: Int) {", "    return a", "}", "y")
        val b = listOf("x", "fun f(a: Long) {", "    return a * 2", "}", "y")
        val r = LineDiff.diff(a, b)
        assertEquals(1, r.hunks.size)
        val h = r.hunks[0]
        val at = a.subList(h.aFrom, h.aTo).joinToString("\n")
        val bt = b.subList(h.bFrom, h.bTo).joinToString("\n")
        val chars = assertNotNull(h.chars)
        assertEquals(bt, applyChars(at, bt, chars))
        assertEquals(listOf("Int" to "Long", "" to " * 2"), chars.map { at.substring(it.aFrom, it.aTo) to bt.substring(it.bFrom, it.bTo) })
    }

    @Test fun randomCharDiffsApply() {
        val rnd = Random(11)
        val alphabet = "ab c_d(e)\n"
        repeat(500) {
            val a = CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] }.concatToString()
            val b = CharArray(rnd.nextInt(0, 40)) { alphabet[rnd.nextInt(alphabet.length)] }.concatToString()
            val c = CharDiff.diff(a, b) ?: return@repeat
            assertEquals(b, applyChars(a, b, c), "'$a' -> '$b': $c")
            for (i in 1 until c.size) assertTrue(c[i].aFrom > c[i - 1].aTo && c[i].bFrom > c[i - 1].bTo, "$c")
        }
    }

    @Test fun pureInsertionsAndDeletionsCarryNoCharacterDiff() {
        val r = LineDiff.diff(listOf("a", "c"), listOf("a", "b", "c"))
        assertNull(r.hunks.single().chars)
    }

    // ------------------------------------------------------------------ incremental --

    @Test fun anEditReDiffsOnlyTheRegionItTouches() {
        val rnd = Random(5)
        val a = randomLines(rnd, 2_000)
        val b0 = mutate(rnd, a, 40)
        var doc = Rope.of(b0.joinToString("\n"))
        var hunks = LineDiff.diff(a, b0).hunks
        repeat(300) { step ->
            // Type, delete, paste lines, anywhere (sometimes several changes at once).
            val specs = ArrayList<ChangeSpec>()
            var at = 0
            repeat(rnd.nextInt(1, 3)) {
                if (at >= doc.length) return@repeat
                val from = rnd.nextInt(at, doc.length + 1)
                val to = minOf(doc.length, from + if (rnd.nextBoolean()) 0 else rnd.nextInt(0, 80))
                val insert = when (rnd.nextInt(4)) { 0 -> ""; 1 -> "x"; 2 -> "\n"; else -> "new line ${rnd.nextInt()}\nand another\n" }
                specs += ChangeSpec(from, to, insert)
                at = to + 1
            }
            val cs = ChangeSet.of(doc.length, specs)
            val next = cs.apply(doc)
            val s = Splice.apply(a, hunks, doc, next, cs, DiffOptions())
            assertTrue(s.exact, "a small edit is re-diffed at once")
            val bl = next.toString().split('\n')
            assertValidDiff(a, bl, s.hunks, "step $step")
            hunks = s.hunks
            doc = next
        }
    }

    @Test fun anEditTooBigForTheUiThreadIsACoarseHunkForNow() {
        val rnd = Random(9)
        val a = randomLines(rnd, 5_000)
        val doc = Rope.of(a.joinToString("\n"))
        val paste = randomLines(rnd, 4_000).joinToString("\n") + "\n"
        val cs = ChangeSet.of(doc.length, listOf(ChangeSpec(doc.lineStart(100), doc.lineStart(100), paste)))
        val next = cs.apply(doc)
        val s = Splice.apply(a, emptyList(), doc, next, cs, DiffOptions(), limitLines = 2_000)
        assertTrue(!s.exact, "too big: coarse, the background job refines it")
        assertValidDiff(a, next.toString().split('\n'), s.hunks)
    }

    @Test fun slicedDiffGivesTheSameResultAndPauses() = kotlinx.coroutines.test.runTest {
        val rnd = Random(3)
        val a = randomLines(rnd, 5_000)
        val b = mutate(rnd, a, 500)
        var pauses = 0
        val sliced = LineDiff.diffSliced(a, b, DiffOptions()) { pauses++ }
        assertEquals(LineDiff.diff(a, b).hunks, sliced.hunks)
        assertTrue(pauses > 0, "the job gives the thread back")
    }

    @Test fun baseFromAUnifiedPatch() {
        val base = "one\ntwo\nthree\nfour\nfive\nsix\nseven"
        val working = "one\nTWO\nthree\nfour\nfive and a half\nsix\nseven\neight"
        val patch = """
            diff --git a/f.txt b/f.txt
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             one
            -two
            +TWO
             three
            @@ -4,4 +4,5 @@
             four
            -five
            +five and a half
             six
             seven
            +eight
        """.trimIndent()
        assertEquals(base, UnifiedPatch.base(working, patch))
    }
}
