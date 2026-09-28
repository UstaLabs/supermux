package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A 10 MB document, the cursor at its end, the only match just before it: finding the next match
 * wraps around and scans the whole document. Budgets on the best of 5 (a shared Mac).
 */
class SearchPerfTest {
    private val line = "    val someIdentifier = computeSomething(argument, 42) // comment\n"
    private val text = buildString {
        while (length < 10_000_000) append(line)
        append("the NEEDLE is here\n")
    }
    private val doc = Rope.of(text)
    private val at = text.indexOf("NEEDLE")

    private fun best(block: () -> Unit): Double = (0 until 5).minOf {
        val t0 = System.nanoTime(); block(); (System.nanoTime() - t0) / 1e6
    }

    private fun wraps(q: SearchQuery): Double {
        val end = doc.length
        assertEquals(at, q.nextMatch(doc, end, end)!!.from, q.toString())
        return best { q.nextMatch(doc, end, end) }
    }

    @Test fun literalNextMatchWrapsTenMegabytesUnder50ms() {
        repeat(3) { wraps(SearchQuery("needle")) } // warm up the JIT
        val ignoreCase = wraps(SearchQuery("needle"))
        val exact = wraps(SearchQuery("NEEDLE", caseSensitive = true))
        val word = wraps(SearchQuery("needle", wholeWord = true))
        println("SEARCH-PERF 10 MB literal wrap: ignore case ${"%.1f".format(ignoreCase)} ms, match case ${"%.1f".format(exact)} ms, whole word ${"%.1f".format(word)} ms")
        assertTrue(ignoreCase < 50, "ignore case: $ignoreCase ms")
        assertTrue(exact < 50, "match case: $exact ms")
        assertTrue(word < 50, "whole word: $word ms")
    }

    @Test fun regexNextMatchWrapsTenMegabytes() {
        repeat(3) { wraps(SearchQuery("NEED+LE", regexp = true)) }
        val regex = wraps(SearchQuery("NEED+LE", regexp = true))
        val multi = wraps(SearchQuery("NEED+LE\\s", regexp = true))
        println("SEARCH-PERF 10 MB regex wrap: per line ${"%.1f".format(regex)} ms, multi-line ${"%.1f".format(multi)} ms")
        assertTrue(regex < 250, "regex: $regex ms")
        assertTrue(multi < 250, "multi-line regex: $multi ms")
    }

    @Test fun countingTenMegabytesStopsAtTheCap() {
        val c = SearchQuery("val").count(doc)
        assertEquals(MatchCount(10_000, true), c)
        val ms = best { SearchQuery("val").count(doc) }
        val none = best { SearchQuery("absent").count(doc) }
        println("SEARCH-PERF 10 MB count: capped ${"%.1f".format(ms)} ms, no match ${"%.1f".format(none)} ms")
        assertTrue(none < 50, "a full count: $none ms")
    }
}
