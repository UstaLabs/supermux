package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// tree-sitter-json 0.24.8 queries/highlights.scm, verbatim.
const val JSON_HIGHLIGHTS = """
(pair
  key: (_) @string.special.key)

(string) @string

(number) @number

[
  (null)
  (true)
  (false)
] @constant.builtin

(escape_sequence) @escape

(comment) @comment
"""

// Raw string: the \n below is a backslash + n in the JSON (an escape_sequence), not a newline.
const val SAMPLE = """{"ağ": [1, true, null], "e😀": "x\n"}"""

private val GOLDEN = listOf(
    "1-5 string", "1-5 string.special.key",
    "8-9 number",
    "11-15 constant.builtin",
    "17-21 constant.builtin",
    "24-29 string", "24-29 string.special.key",
    "31-36 string",
    "33-35 escape",
)

/** M0's golden contract, verbatim, on the ses_* binding (JVM, iOS simulator, Android). */
class GoldenHighlightTest {
    @Test
    fun highlightsAreUtf16AndIdenticalOnEveryBackend() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        assertEquals(GOLDEN, h.highlights(JSON_HIGHLIGHTS).map { it.toString() })
    }

    @Test
    fun incrementalEditShiftsLaterSpans() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        // Replace `1` (8..9) with `12345`: +4 units. Everything after index 9 moves by 4.
        val next = h.edit(8, 9, "12345")
        assertEquals(SAMPLE.replaceRange(8, 9, "12345"), next)
        val spans = h.highlights(JSON_HIGHLIGHTS).map { it.toString() }
        assertEquals("8-13 number", spans[2])
        assertEquals("35-40 string", spans[7])
        assertEquals("37-39 escape", spans[8])
    }
}

/** Beyond M0: the pull reader, the Rope, predicates, changed ranges and table loading. */
class SesBindingTest {
    @Test
    fun threeUnitChunksSplitTheSurrogatePairAndChangeNothing() = SesHighlighter("json", chunk = 3).use { h ->
        h.parse(SAMPLE)
        assertEquals(GOLDEN, h.highlights(JSON_HIGHLIGHTS).map { it.toString() })
        h.edit(8, 9, "12345")
        assertEquals("37-39 escape", h.highlights(JSON_HIGHLIGHTS).map { it.toString() }[8])
    }

    @Test
    fun ropeChunkAtIsTheTextSource() {
        val doc = buildString {
            append('[')
            for (i in 0 until 4000) { if (i > 0) append(",\n"); append("{\"k$i ağ\": \"😀$i\", \"n\": $i}") }
            append(']')
        }
        val rope = Rope.of(doc)
        SyntaxParser("json").use { p ->
            val fromRope = p.parse(TextSource { rope.chunkAt(it) })
            val fromString = p.parse(doc)
            try {
                assertEquals(fromString.sexp(), fromRope.sexp())
                assertFalse(fromRope.hasError)
                SyntaxQuery("json", JSON_HIGHLIGHTS).use { q ->
                    // a window in the middle of the document, like visible lines + overscan
                    val from = doc.length / 2
                    val a = q.captures(fromRope, from, from + 500, TextSource { rope.chunkAt(it) })
                    val b = q.captures(fromString, from, from + 500, ChunkedSource(doc))
                    assertTrue(a.isNotEmpty())
                    assertEquals(b.toList(), a.toList())
                    for (i in a.indices step 4) {
                        val text = doc.substring(a[i], a[i + 1])
                        if (q.captureNames[a[i + 2]] == "number") assertTrue(text.all { it.isDigit() }, text)
                        if (q.captureNames[a[i + 2]] == "string") assertTrue(text.startsWith('"') && text.endsWith('"'), text)
                    }
                }
            } finally { fromRope.close(); fromString.close() }
        }
    }

    @Test
    fun textPredicatesCompareUtf16() = SesHighlighter("json", chunk = 3).use { h ->
        h.parse(SAMPLE)
        val q = """
            ((pair key: (string (string_content) @emoji)) (#eq? @emoji "e😀"))
            ((pair key: (string (string_content) @other)) (#not-eq? @other "e😀"))
            ((string (string_content) @listed) (#any-of? @listed "ağ" "zz"))
        """
        assertEquals(listOf("2-4 listed", "2-4 other", "25-28 emoji"), h.highlights(q).map { it.toString() })
    }

    @Test
    fun changedRangesAreUtf16() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        h.edit(24, 29, "\"k\"") // replace the key "e😀" (24..29) with "k"
        val r = h.lastChangedRanges
        assertTrue(r.isEmpty() || (r[0] >= 0 && r.last() <= h.source.length), r.toList().toString())
        h.edit(8, 9, "[2]") // number -> array: a structural change must be reported
        val r2 = h.lastChangedRanges
        assertTrue(r2.isNotEmpty() && r2[0] <= 8 && r2[1] >= 11, r2.toList().toString())
    }

    @Test
    fun codeOnlyGrammarNeedsTables() {
        // haskell, not the prototype's python: python is in the core set, whose tables are bundled.
        assertTrue("haskell" in SyntaxLanguages.names())
        assertFalse(SyntaxLanguages.hasTables("haskell"))
        val e = assertFailsWith<SyntaxException> { SyntaxParser("haskell") }
        assertEquals(SyntaxStatus.NO_TABLES, e.status)
        val bad = assertFailsWith<SyntaxException> { SyntaxLanguages.provideTables("haskell", ByteArray(40)) }
        assertEquals(SyntaxStatus.BAD_TABLES, bad.status)
        assertEquals(SyntaxStatus.UNKNOWN_LANGUAGE, assertFailsWith<SyntaxException> { SyntaxParser("cobol") }.status)
    }

    @Test
    fun fsharpTablesInflateOnFirstUse() {
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        SyntaxLanguages.load("fsharp")
        val loadMs = t0.elapsedNow().inWholeMicroseconds / 1000.0
        SyntaxParser("fsharp").use { p ->
            val t1 = kotlin.time.TimeSource.Monotonic.markNow()
            p.parse(FSHARP_SAMPLE).use { t ->
                val parseMs = t1.elapsedNow().inWholeMicroseconds / 1000.0
                println("SES fsharp first-use load=${loadMs}ms parse=${parseMs}ms units=${FSHARP_SAMPLE.length}")
                assertFalse(t.hasError, t.sexp().take(400))
                assertTrue(t.sexp().startsWith("(file (named_module"), t.sexp().take(200))
            }
        }
    }
}

const val FSHARP_SAMPLE = """module Supermux.Sample

open System

type Shape =
    | Circle of radius: float
    | Rect of width: float * height: float

let area shape =
    match shape with
    | Circle r -> Math.PI * r * r
    | Rect (w, h) -> w * h

type Account(owner: string, initial: decimal) =
    let mutable balance = initial
    member _.Owner = owner
    member this.Deposit(amount: decimal) =
        balance <- balance + amount
        this

let greeting = "ağ 😀"

[<EntryPoint>]
let main argv =
    for s in [ Circle 1.0; Rect(2.0, 3.0) ] do
        printfn "%A -> %.2f %s" s (area s) greeting
    0
"""
