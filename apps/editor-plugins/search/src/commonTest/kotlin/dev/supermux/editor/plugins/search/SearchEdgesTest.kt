package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The review's regex window edges, anchors that cannot be windowed, and the empty last line. */
class SearchEdgesTest {
    private fun all(q: SearchQuery, text: String, from: Int = 0, to: Int = text.length) =
        q.cursor(Rope.of(text), from, to).asSequence().map { it.from to it.to }.toList()

    @Test fun inputAnchorsAreRefusedWithAClearError() {
        for (p in listOf("foo\\z", "\\Afoo", "foo\\Z", "\\Gfoo", "(?:x|\\A)")) {
            val q = SearchQuery(p, regexp = true)
            assertFalse(q.valid, p)
            assertTrue(q.error!!.contains("^") && q.error!!.contains("$"), "$p: ${q.error}")
        }
        // Escaped, quoted or inside a class, they are no anchor.
        for (p in listOf("\\\\A", "\\Q\\A\\E", "[\\\\z]")) assertTrue(SearchQuery(p, regexp = true).valid, p)
    }

    @Test fun everyWayOfWritingALineBreakSearchesAcrossLines() {
        for (p in listOf("\\x0a", "\\x0A", "\\x{a}", "\\u000a", "\\012", "\\R", "\\v", "\\cJ", "(?s).", "(?is)a.", "[\\s\\S]", "\\n", "\\p{Cc}", "\\P{L}", "\\X")) {
            assertTrue(SearchQuery(p, regexp = true).multiline, p)
        }
        for (p in listOf("foo", "\\w+", "\\d", "[a-z]", "\\p{L}+", "(?i)x", "\\S")) assertFalse(SearchQuery(p, regexp = true).multiline, p)
    }

    @Test fun aHexNewlineIsFoundAcrossEveryWindowEdge() {
        val text = "ab\n".repeat(30_000)
        assertEquals(29_999, SearchQuery("b\\x0Aa", regexp = true, caseSensitive = true).count(Rope.of(text), limit = 1_000_000).count)
        assertEquals(29_999, SearchQuery("b\\na", regexp = true).count(Rope.of(text), limit = 1_000_000).count)
    }

    @Test fun aLineAnchorNeverMatchesAtAWindowCutInsideALongLine() {
        val cut = SearchCursors.MULTILINE_WINDOW
        val text = "x".repeat(cut - 1) + "bz" + "x".repeat(300_000) + "\nbq\n"
        val bq = text.indexOf("bq")
        assertEquals(listOf(bq to bq + 2), all(SearchQuery("^b[^\\n]", regexp = true), text))
        assertEquals(listOf(bq to bq + 2), all(SearchQuery("^b.", regexp = true), text))
        // $ at a cut is no line end either.
        assertEquals(listOf(text.indexOf("\nbq") - 1 to text.indexOf("\nbq")), all(SearchQuery("x$", regexp = true), text))
        // A look-behind at a window's start sees the text before it.
        val t2 = "y".repeat(SearchCursors.WINDOW * 3) + "\n"
        assertEquals(SearchCursors.WINDOW * 3 - 1, SearchQuery("(?<=y)y", regexp = true).count(Rope.of(t2), limit = 1_000_000).count)
    }

    @Test fun caretOnTheEmptyLastLineMatchesLikeCm6() {
        // "Prefix every line": the empty last line after a final newline has a start too.
        assertEquals(listOf(0 to 0, 2 to 2, 4 to 4), all(SearchQuery("^", regexp = true), "a\nb\n"))
        assertEquals(listOf(0 to 0), all(SearchQuery("^", regexp = true), ""))
        assertEquals(listOf(1 to 1, 3 to 3, 4 to 4), all(SearchQuery("$", regexp = true), "a\nb\n"))
    }

    @Test fun theWrapAroundScanStopsAtTheCursor() {
        // No match: the scan from the cursor to the end, then from the start only up to the cursor.
        val doc = Rope.of("x".repeat(100_000))
        val q = SearchQuery("absent")
        val scanned = SearchStats.measure { q.nextMatch(doc, 50_000, 50_000) }
        assertTrue(scanned <= 120_000, "scanned $scanned units")
        val r = SearchQuery("ab+", regexp = true)
        val scannedR = SearchStats.measure { r.nextMatch(doc, 50_000, 50_000) }
        assertTrue(scannedR <= 120_000, "regex scanned $scannedR units")
    }

    @Test fun aViewportRegexScanOnAHugeLineReadsOnlyAroundIt() {
        val doc = Rope.of("ab".repeat(2_000_000))
        val q = SearchQuery("b(?=a)", regexp = true)
        val scanned = SearchStats.measure { q.cursor(doc, 1_000_000, 1_010_000).asSequence().count() }
        assertTrue(scanned < 100_000, "read $scanned units for a 10K range")
        assertEquals(5_000, q.cursor(doc, 1_000_000, 1_010_000).asSequence().count())
    }

    @Test fun matchFromIncludesAnEmptyMatchAtThePosition() {
        val q = SearchQuery("^", regexp = true)
        val doc = Rope.of("a\nb")
        assertEquals(2, q.matchFrom(doc, 2)!!.from)
        assertEquals(0, q.matchFrom(doc, 3)!!.from, "wraps")
        assertNotNull(SearchQuery("a").matchFrom(doc, 0))
    }

    @Test fun moreWaysOfMatchingALineBreakAreFoundAcrossLines() {
        val text = "ab\n".repeat(30_000)
        for (p in listOf("b\\Ha", "b[x[^a]]a", "b\\p{ASCII}a", "b\\p{InBasicLatin}a", "b\\p{IsCc}a", "b\\p{IsCommon}a", "b\\p{gc=Cc}a", "b\\p{IsAssigned}a", "b\\p{Print}a")) {
            val q = SearchQuery(p, regexp = true, caseSensitive = true)
            assertTrue(q.multiline, "$p is not seen as multi-line")
            // \p{Print} holds no line break on the JVM but does elsewhere; an engine may not know a
            // property at all (an error state, then nothing to count).
            if (!q.valid || p == "b\\p{Print}a") continue
            assertEquals(29_999, q.count(Rope.of(text), limit = 1_000_000).count, p)
        }
        for (p in listOf("\\p{L}+", "\\p{Lu}", "\\p{IsAlphabetic}", "\\pN", "\\p{Nd}")) assertFalse(SearchQuery(p, regexp = true).multiline, p)
    }

    @Test fun aGrownWindowShrinksBackAfterItsMatch() {
        // A match that made the window grow is followed by normal windows (no 2 MB reads per window).
        val text = "<" + "a".repeat(300_000) + ">" + "\nb".repeat(400_000)
        val q = SearchQuery("<[^>]*", regexp = true)
        val c = q.scanner(Rope.of(text))
        assertEquals(0 to 300_001, c.next().let { it.from to it.to })
        val window = SearchStats.largestWindow { while (c.hasNext()) c.next() }
        assertTrue(window <= SearchCursors.MULTILINE_WINDOW + 2 * SearchCursors.PREFIX, "a window of $window units after the long match")
        // Sliced (the panel's runner on a big document): windows never grow past 64K; the match is cut there.
        val s = q.scanner(Rope.of(text), maxWindow = SearchCursors.SLICED_WINDOW)
        val first = s.next()
        assertEquals(0, first.from)
        assertTrue(first.to - first.from <= SearchCursors.SLICED_WINDOW, "a sliced match of ${first.to - first.from}")
    }

    @Test fun anyCharacterIdiomsBecomeADotAndMeanTheSame() {
        assertEquals("a.*b", SearchQuery.anyCharIdioms("a[\\s\\S]*b"))
        assertEquals(null, SearchQuery.anyCharIdioms("\\[\\s\\S]"), "an escaped bracket is no class")
        assertEquals(null, SearchQuery.anyCharIdioms("[x[\\s\\S]]"), "inside a class: left alone")
        assertEquals(null, SearchQuery.anyCharIdioms("a.[\\s\\S]"), "the pattern's own dot keeps its meaning")
        val q = SearchQuery("a.[\\s\\S]", regexp = true)
        assertEquals(emptyList(), q.cursor(Rope.of("a\nb")).asSequence().toList(), "the dot does not match a line break")
        assertEquals(listOf(0 to 3), q.cursor(Rope.of("ab\n")).asSequence().map { it.from to it.to }.toList())
        val text = "a\nb😀c\r\nd"
        for (p in listOf("[\\s\\S]", "[\\w\\W]", "[\\d\\D]")) {
            assertEquals(listOf(0 to text.length), SearchQuery("$p+", regexp = true).cursor(Rope.of(text)).asSequence().map { it.from to it.to }.toList(), p)
        }
    }
}
