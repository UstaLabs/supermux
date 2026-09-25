package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

private class Boom : RuntimeException("boom")

/** Failures in the caller's callbacks surface as themselves and leak nothing native. */
class RobustnessTest {
    private val doc = SAMPLE.repeat(40)

    /** The document, 4 units at a time, until index [failAt]. */
    private fun throwingSource(failAt: Int) = TextSource { i ->
        if (i >= failAt) throw Boom()
        if (i >= doc.length) "" else doc.substring(i, minOf(doc.length, i + 4))
    }

    @Test
    fun aSourceThrowingMidParseSurfacesAndTheTreeIsFreed() = SyntaxParser("json").use { p ->
        val before = Ses.debugLiveTrees()
        assertFailsWith<Boom> { p.parse(throwingSource(failAt = 100)) }
        assertEquals(before, Ses.debugLiveTrees(), "the partial tree leaked")
        p.parse(SAMPLE).use { assertFalse(it.hasError) } // the parser still works
        assertEquals(before, Ses.debugLiveTrees())
    }

    @Test
    fun aSourceThrowingMidQuerySurfacesAndNothingLeaks() = SyntaxParser("json").use { p ->
        val before = Ses.debugLiveTrees()
        p.parse(doc).use { t ->
            // #eq? reads every key's text through the source, which fails on the 3rd key or so.
            SyntaxQuery("json", """((pair key: (string (string_content) @k)) (#eq? @k "zz"))""").use { q ->
                assertFailsWith<Boom> { q.captures(t, 0, doc.length, throwingSource(failAt = 60)) }
                assertEquals(0, q.captures(t, 0, doc.length, ChunkedSource(doc)).ints.size) // still usable
            }
        }
        assertEquals(before, Ses.debugLiveTrees())
    }

    @Test
    fun aTimedOutParseFailsAndTheParserThenParsesCorrectly() {
        val big = buildString {
            append('[')
            repeat(20_000) { if (it > 0) append(",\n"); append("{\"k$it\": [1, 2.5, \"ağ 😀\", null]}") }
            append(']')
        }
        SyntaxParser("json").use { p ->
            p.setTimeoutMicros(1)
            assertEquals(SyntaxStatus.TIMEOUT, assertFailsWith<SyntaxException> { p.parse(big) }.status)
            assertEquals(SyntaxStatus.TIMEOUT, assertFailsWith<SyntaxException> { p.parse(ChunkedSource(big)) }.status)
            p.setTimeoutMicros(0)
            p.parse(big).use { t ->
                assertFalse(t.hasError)
                SyntaxParser("json").use { fresh -> fresh.parse(big).use { assertEquals(it.sexp(), t.sexp()) } }
            }
        }
    }

    @Test
    fun copiesAreCounted() = SyntaxParser("json").use { p ->
        val before = Ses.debugLiveTrees()
        p.parse(SAMPLE).use { t -> t.copy().use { assertEquals(before + 2, Ses.debugLiveTrees()) } }
        assertEquals(before, Ses.debugLiveTrees())
    }
}
