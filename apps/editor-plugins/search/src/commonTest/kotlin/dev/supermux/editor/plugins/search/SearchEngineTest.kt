package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SearchEngineTest {
    private fun all(q: SearchQuery, text: String): List<Pair<Int, Int>> =
        q.cursor(Rope.of(text)).asSequence().map { it.from to it.to }.toList()

    private fun found(q: SearchQuery, text: String): List<String> =
        q.cursor(Rope.of(text)).asSequence().map { text.substring(it.from, it.to) }.toList()

    // ------------------------------------------------------------------------ literal --

    @Test fun literalIgnoresCaseByDefault() {
        val t = "a foo b Foo FOO fOo"
        assertEquals(listOf(2 to 5, 8 to 11, 12 to 15, 16 to 19), all(SearchQuery("foo"), t))
        assertEquals(listOf(2 to 5), all(SearchQuery("foo", caseSensitive = true), t))
        assertEquals(listOf(8 to 11), all(SearchQuery("Foo", caseSensitive = true), t))
    }

    @Test fun literalMatchesDoNotOverlap() {
        assertEquals(listOf(0 to 2, 2 to 4), all(SearchQuery("aa"), "aaaaa"))
    }

    @Test fun literalUnquotesCm6Escapes() {
        assertEquals(listOf(1 to 4), all(SearchQuery("a\\nb"), "xa\nbx"))
        assertEquals(listOf(1 to 2), all(SearchQuery("\\t"), "x\ty"))
        assertEquals(listOf(1 to 2), all(SearchQuery("\\r"), "x\ry"))
        assertEquals(listOf(1 to 2), all(SearchQuery("\\\\"), "x\\y"))
        // Anything else after a backslash is itself.
        assertEquals(listOf(1 to 3), all(SearchQuery("\\d"), "x\\dy"))
    }

    @Test fun anEmptyQueryIsInvalidAndFindsNothing() {
        val q = SearchQuery("")
        assertFalse(q.valid)
        assertEquals(emptyList(), all(q, "abc"))
        assertNull(q.nextMatch(Rope.of("abc"), 0, 0))
    }

    @Test fun wholeWordUsesTheEditorsWordCharacters() {
        val t = "foo foobar _foo afoo foo. (foo) fooğ ğfoo"
        assertEquals(listOf("foo", "foo", "foo"), found(SearchQuery("foo", wholeWord = true), t))
        assertEquals(listOf(0 to 3, 21 to 24, 27 to 30), all(SearchQuery("foo", wholeWord = true), t))
        // CM6's rule: a query that starts or ends with a non-word character only needs that side free.
        assertEquals(listOf(3 to 5), all(SearchQuery(".(", wholeWord = true), "foo.(x"))
        // An emoji is not a word character.
        assertEquals(listOf(2 to 3), all(SearchQuery("a", wholeWord = true), "😀a😀"))
    }

    @Test fun matchesThatSpanRopeChunksAndScanWindowsAreFound() {
        // Rope leaves are 1024 units, the scan windows [SearchCursors.WINDOW]: a needle across
        // each kind of boundary, several times over.
        val len = SearchCursors.WINDOW * 3 + 5000
        val at = listOf(1020, 2045, SearchCursors.WINDOW - 3, SearchCursors.WINDOW * 2 - 1, SearchCursors.WINDOW * 3 - 5, len - 6)
        val sb = StringBuilder("x".repeat(len))
        for (p in at) sb.setRange(p, p + 6, "NEEDLE")
        val text = sb.toString()
        val want = at.map { it to it + 6 }
        assertEquals(want, all(SearchQuery("needle"), text))
        assertEquals(want, all(SearchQuery("NEEDLE", caseSensitive = true), text))
        assertEquals(want, all(SearchQuery("NEE+DLE", regexp = true), text))
    }

    @Test fun aRangeLimitsTheCursor() {
        val t = "ab ab ab ab"
        assertEquals(listOf(3 to 5, 6 to 8), SearchQuery("ab").cursor(Rope.of(t), 2, 9).asSequence().map { it.from to it.to }.toList())
    }

    // -------------------------------------------------------------------------- regex --

    @Test fun regexFindsWithGroups() {
        val t = "user@host and root@box"
        val ms = SearchQuery("(\\w+)@(\\w+)", regexp = true).cursor(Rope.of(t)).asSequence().toList()
        assertEquals(listOf(0 to 9, 14 to 22), ms.map { it.from to it.to })
        assertEquals(listOf("user@host", "user", "host"), ms[0].groups)
    }

    @Test fun anInvalidRegexIsAnErrorStateAndNeverThrows() {
        for (bad in listOf("a(", "[", "*a", "(?<n>x", "a{2,1}", "\\")) {
            val q = SearchQuery(bad, regexp = true)
            assertFalse(q.valid, bad)
            assertNotNull(q.error, bad)
            val doc = Rope.of("a( [ *a")
            assertEquals(emptyList(), q.cursor(doc).asSequence().toList(), bad)
            assertNull(q.nextMatch(doc, 0, 0), bad)
            assertNull(q.prevMatch(doc, 3, 3), bad)
            assertEquals(MatchCount(0, false), q.count(doc), bad)
        }
        // The same text as a literal is fine.
        assertTrue(SearchQuery("a(").valid)
        assertEquals(listOf(0 to 2), all(SearchQuery("a("), "a( ["))
    }

    @Test fun regexIgnoresCaseUnlessAsked() {
        assertEquals(listOf(0 to 3, 4 to 7), all(SearchQuery("f.o", regexp = true), "foo FOO"))
        assertEquals(listOf(0 to 3), all(SearchQuery("f.o", regexp = true, caseSensitive = true), "foo FOO"))
    }

    @Test fun regexAnchorsAreLineAnchors() {
        val t = "foo\nbar foo\nfoo"
        assertEquals(listOf(0 to 3, 12 to 15), all(SearchQuery("^foo", regexp = true), t))
        assertEquals(listOf(0 to 3, 8 to 11, 12 to 15), all(SearchQuery("foo$", regexp = true), t))
    }

    @Test fun aRegexWithNewlinesSearchesAcrossLines() {
        val t = "a\nb\n\nc   \n  d"
        assertEquals(listOf(0 to 3), all(SearchQuery("a\\nb", regexp = true), t))
        assertEquals(listOf(1 to 2, 3 to 5, 9 to 12), all(SearchQuery("\\n\\s*", regexp = true), t))
        // Per line, `.` never crosses a line break.
        assertEquals(listOf(0 to 1, 2 to 3), all(SearchQuery("a.*|b.*", regexp = true), "a\nb"))
    }

    @Test fun emptyRegexMatchesAdvance() {
        // "^" matches every line start, once each, and the cursor moves on.
        assertEquals(listOf(0 to 0, 2 to 2, 4 to 4), all(SearchQuery("^", regexp = true), "a\nb\nc"))
        assertEquals(listOf(0 to 0, 1 to 1, 2 to 2), all(SearchQuery("x*", regexp = true), "ab"))
    }

    @Test fun wordClassesAreTheEditorsWordCharacters() {
        assertEquals(listOf("ağaç", "İstanbul", "ı_1"), found(SearchQuery("\\w+", regexp = true), "ağaç İstanbul ı_1"))
        assertEquals(listOf(" ", "-"), found(SearchQuery("\\W", regexp = true), "ğ ı-x"))
        assertEquals(
            "[\\p{L}\\p{Nd}_]x[a\\p{L}\\p{Nd}_]\\\\w\\Q\\w\\E[]\\p{L}\\p{Nd}_][^\\p{L}\\p{Nd}\\p{Pc}](?:(?![\\p{L}\\p{Nd}_])[\\s\\S])",
            SearchQuery.unicodeWordClasses("\\wx[a\\w]\\\\w\\Q\\w\\E[]\\w][^\\w]\\W"),
        )
        // A negated class with \w: `_` is a word character on every engine.
        assertEquals(listOf("-", "."), found(SearchQuery("[^\\w\\s]", regexp = true), "a_ğ -."))
    }

    @Test fun regexWholeWord() {
        assertEquals(listOf(0 to 3, 9 to 12), all(SearchQuery("f\\w+", regexp = true, wholeWord = true), "foo xfoo fab"))
    }

    // --------------------------------------------------------------- Unicode, Turkish --

    @Test fun emojiAreSearchedWhole() {
        val t = "a😀b😀"
        assertEquals(listOf(1 to 3, 4 to 6), all(SearchQuery("😀"), t))
        assertEquals(listOf(1 to 3, 4 to 6), all(SearchQuery("😀", regexp = true), t))
        assertEquals(listOf(3 to 4), all(SearchQuery("B"), t))
    }

    /**
     * Without match case, the folding is locale-free (a Kotlin Char's uppercase, then lowercase):
     * Turkish dotted and dotless i are ONE letter then (`i`, `I`, `İ`, `ı` all match each other).
     * Match case tells them apart. Literal and regex agree.
     */
    @Test fun turkishCaseFolding() {
        val t = "istanbul İstanbul ISTANBUL ıstanbul"
        val each = listOf(0 to 8, 9 to 17, 18 to 26, 27 to 35)
        for (q in listOf("istanbul", "İSTANBUL", "Istanbul", "ıstanbul")) {
            assertEquals(each, all(SearchQuery(q), t), q)
            assertEquals(each, all(SearchQuery(q, regexp = true), t), "regex $q")
        }
        assertEquals(listOf(9 to 17), all(SearchQuery("İstanbul", caseSensitive = true), t))
        assertEquals(listOf(27 to 35), all(SearchQuery("ıstanbul", caseSensitive = true), t))
        assertEquals(listOf(27 to 35), all(SearchQuery("ıstanbul", caseSensitive = true, regexp = true), t))
        // Other letters fold as usual: ğ/Ğ, ş/Ş, ç/Ç, ö/Ö, ü/Ü.
        assertEquals(listOf(0 to 5, 6 to 11), all(SearchQuery("ğüşöç"), "ĞÜŞÖÇ ğüşöç"))
    }

    // ----------------------------------------------------------------------- replace --

    @Test fun regexReplaceTemplates() {
        val q = SearchQuery("(\\w+)@(\\w+)", regexp = true, replace = "$2 at $1 [$&] $$ $3 $12 \\n")
        val m = q.cursor(Rope.of("ab@cd")).next()
        // $3 is no group: kept as typed. $12: group 1 then "2" (CM6: the longest group number there is).
        assertEquals("cd at ab [ab@cd] \$ \$3 ab2 \n", q.replacement(m))
    }

    @Test fun anOptionalGroupThatDidNotTakePartIsEmpty() {
        val q = SearchQuery("a(x)?b", regexp = true, replace = "[$1]")
        assertEquals("[]", q.replacement(q.cursor(Rope.of("ab")).next()))
    }

    @Test fun literalReplaceIsUnquotedButNotATemplate() {
        val q = SearchQuery("x", replace = "\$1 \$& \\t")
        assertEquals("\$1 \$& \t", q.replacement(q.cursor(Rope.of("x")).next()))
    }

    // ------------------------------------------------------------- next and previous --

    @Test fun nextAndPreviousWrapAndSkipTheCurrentMatch() {
        val doc = Rope.of("ab ab ab")
        val q = SearchQuery("ab")
        assertEquals(3 to 5, q.nextMatch(doc, 0, 2)!!.let { it.from to it.to })
        assertEquals(6 to 8, q.nextMatch(doc, 3, 5)!!.let { it.from to it.to })
        assertEquals(0 to 2, q.nextMatch(doc, 6, 8)!!.let { it.from to it.to }, "wraps to the start")
        assertEquals(3 to 5, q.prevMatch(doc, 6, 8)!!.let { it.from to it.to })
        assertEquals(6 to 8, q.prevMatch(doc, 0, 2)!!.let { it.from to it.to }, "wraps to the end")
        // A cursor (not a match) finds the next one from where it is.
        assertEquals(3 to 5, q.nextMatch(doc, 3, 3)!!.let { it.from to it.to })
        assertEquals(0 to 2, q.prevMatch(doc, 3, 3)!!.let { it.from to it.to })
        // The only match, selected: there is no other.
        assertNull(SearchQuery("x").nextMatch(Rope.of("a x b"), 2, 3))
        assertNull(SearchQuery("x").prevMatch(Rope.of("a x b"), 2, 3))
    }

    @Test fun previousFindsAcrossManyWindowsBack() {
        val text = "hit" + "y".repeat(SearchCursors.WINDOW * 3) + "end"
        val doc = Rope.of(text)
        assertEquals(0, SearchQuery("hit").prevMatch(doc, text.length, text.length)!!.from)
        assertEquals(0, SearchQuery("h.t", regexp = true).prevMatch(doc, text.length, text.length)!!.from)
        assertEquals(0, SearchQuery("hit").nextMatch(doc, text.length, text.length)!!.from)
    }

    @Test fun previousOfEmptyRegexMatchesKeepsMoving() {
        val doc = Rope.of("a\nb\nc")
        val q = SearchQuery("^", regexp = true)
        assertEquals(2, q.prevMatch(doc, 4, 4)!!.from)
        assertEquals(4, q.nextMatch(doc, 2, 2)!!.from)
    }

    // -------------------------------------------------------------------------- count --

    @Test fun theCountIsCappedAtTenThousand() {
        val many = Rope.of("x ".repeat(10_001))
        val c = SearchQuery("x").count(many)
        assertEquals(MatchCount(10_000, true), c)
        assertEquals("10,000+", c.label)
        assertEquals("10,000", SearchQuery("x").count(Rope.of("x ".repeat(10_000))).label)
        assertEquals("17", MatchCount(17, false).label)
        assertEquals("1,234", MatchCount(1234, false).label)
        assertNull(SearchQuery("x").matchAll(many, 10_000))
        assertEquals(10_000, SearchQuery("x").matchAll(Rope.of("x ".repeat(10_000)), 10_000)!!.size)
    }

    // -------------------------------------------------------------- a shared golden --

    /**
     * The same queries give the same matches on the JVM, iOS (Kotlin/Native's Regex) and in the
     * browser (Kotlin/Wasm's): [GOLDEN] was written from the JVM. A line that differs names itself.
     */
    @Test fun theGoldenHoldsOnThisPlatform() {
        if (GOLDEN == "TBD") { println("GOLDEN:\n" + GOLDEN_CASES.joinToString("\n") { goldenLine(it) }); fail("no golden yet") }
        val diffs = GOLDEN_CASES.zip(GOLDEN.lines()).mapNotNull { (case, want) ->
            val got = goldenLine(case)
            if (got == want) null else "$case\n   want $want\n   got  $got"
        }
        assertEquals(GOLDEN_CASES.size, GOLDEN.lines().size, "golden size")
        assertTrue(diffs.isEmpty(), "differs from the JVM golden:\n" + diffs.joinToString("\n"))
    }
}

private data class GoldenCase(val search: String, val regexp: Boolean = true, val caseSensitive: Boolean = false, val wholeWord: Boolean = false)

private const val GOLDEN_TEXT = "Foo foo_bar fooBar 123 4.5 0x1F\nİstanbul ıi Iİ ağaç ÇİÇEK straße STRASSE\n😀 x😀 é É é\n\ttab  two  spaces\nend."

private val GOLDEN_CASES = listOf(
    GoldenCase("foo"), GoldenCase("foo", caseSensitive = true), GoldenCase("foo", wholeWord = true),
    GoldenCase("\\w+"), GoldenCase("\\W+"), GoldenCase("\\d+"), GoldenCase("\\s+"), GoldenCase("\\b\\w"),
    GoldenCase("[a-z]+"), GoldenCase("[[:alpha:]]+"), GoldenCase("\\p{L}+"), GoldenCase("\\p{Lu}", caseSensitive = true), GoldenCase("[\\w-]+"), GoldenCase("[^\\w\\s]+"),
    GoldenCase("i"), GoldenCase("i", caseSensitive = true), GoldenCase("İ"), GoldenCase("ı"),
    GoldenCase("ç"), GoldenCase("ss"), GoldenCase("ß"), GoldenCase("é"), GoldenCase("é"),
    GoldenCase("."), GoldenCase("^\\w"), GoldenCase("\\w$"), GoldenCase("\\.$"), GoldenCase("(?<=a)ğ"),
    GoldenCase("a(?!ğ)"), GoldenCase("(\\w)\\1"), GoldenCase("x{2,}|o{2}"), GoldenCase("\\t\\w+"),
    GoldenCase("\\n."), GoldenCase("spaces\\nend"), GoldenCase("😀"), GoldenCase("[😀]"),
    GoldenCase("(?i)FOO"), GoldenCase("\\bfoo\\b"), GoldenCase("0x[0-9A-F]+", caseSensitive = true),
    GoldenCase("fOO", regexp = false), GoldenCase("ISTANBUL", regexp = false), GoldenCase("é", regexp = false),
    GoldenCase("\\w+", wholeWord = true), GoldenCase("ağ", wholeWord = true), GoldenCase("ç", regexp = false, wholeWord = true),
)

private fun goldenLine(c: GoldenCase): String {
    val q = SearchQuery(c.search, caseSensitive = c.caseSensitive, regexp = c.regexp, wholeWord = c.wholeWord)
    if (!q.valid) return "invalid"
    return q.cursor(Rope.of(GOLDEN_TEXT)).asSequence().take(60).joinToString(" ") { "${it.from}-${it.to}" }.ifEmpty { "none" }
}

/** Written from the JVM (`goldenLine` for each case, one per line). */
private val GOLDEN = """
0-3 4-7 12-15
4-7 12-15
0-3
0-3 4-11 12-18 19-22 23-24 25-26 27-31 32-40 41-43 44-46 47-51 52-57 58-64 65-72 76-77 80-81 82-83 84-85 88-91 93-96 98-104 105-108
3-4 11-12 18-19 22-23 24-25 26-27 31-32 40-41 43-44 46-47 51-52 57-58 64-65 72-76 77-80 81-82 83-84 85-88 91-93 96-98 104-105 108-109
19-22 23-24 25-26 27-28 29-30
3-4 11-12 18-19 22-23 26-27 31-32 40-41 43-44 46-47 51-52 57-58 64-65 72-73 75-76 79-80 81-82 83-84 86-88 91-93 96-98 104-105
0-1 4-5 12-13 19-20 23-24 25-26 27-28 32-33 41-42 44-45 47-48 52-53 58-59 65-66 76-77 80-81 82-83 84-85 88-89 93-94 98-99 105-106
0-3 4-7 8-11 12-18 28-29 30-31 32-40 41-43 44-46 47-48 49-50 53-54 55-57 58-62 63-64 65-72 76-77 84-85 88-91 93-96 98-104 105-108
9-10 16-17 35-36 39-40 47-48 49-50 61-62 68-69 89-90 99-101
0-3 4-7 8-11 12-18 28-29 30-31 32-40 41-43 44-46 47-51 52-57 58-64 65-72 76-77 80-81 82-83 84-85 88-91 93-96 98-104 105-108
0-1 15-16 30-31 32-33 44-45 45-46 52-53 53-54 54-55 55-56 56-57 65-66 66-67 67-68 68-69 69-70 70-71 71-72 82-83
0-3 4-11 12-18 19-22 23-24 25-26 27-31 32-40 41-43 44-46 47-51 52-57 58-64 65-72 76-77 80-81 82-83 84-85 88-91 93-96 98-104 105-108
24-25 73-75 77-79 85-86 108-109
32-33 41-42 42-43 44-45 45-46 53-54
42-43
32-33 41-42 42-43 44-45 45-46 53-54
32-33 41-42 42-43 44-45 45-46 53-54
50-51 52-53 54-55
69-71
62-63
80-81 82-83
84-86
0-1 1-2 2-3 3-4 4-5 5-6 6-7 7-8 8-9 9-10 10-11 11-12 12-13 13-14 14-15 15-16 16-17 17-18 18-19 19-20 20-21 21-22 22-23 23-24 24-25 25-26 26-27 27-28 28-29 29-30 30-31 32-33 33-34 34-35 35-36 36-37 37-38 38-39 39-40 40-41 41-42 42-43 43-44 44-45 45-46 46-47 47-48 48-49 49-50 50-51 51-52 52-53 53-54 54-55 55-56 56-57 57-58 58-59 59-60 60-61
0-1 32-33 105-106
30-31 71-72 103-104
108-109
48-49
9-10 16-17 35-36 49-50 61-62 68-69 89-90 100-101
1-3 5-7 13-15 41-43 44-46 69-71
1-3 5-7 13-15
87-91
31-33 72-75 86-88 104-106
98-108
73-75 77-79
73-75 77-79
0-3 4-7 12-15
0-3
27-31
0-3 4-7 12-15
32-40
80-81 82-83
0-3 4-11 12-18 19-22 23-24 25-26 27-31 32-40 41-43 44-46 47-51 52-57 58-64 65-72 76-77 80-81 82-83 84-85 88-91 93-96 98-104 105-108
none
none
""".trimIndent()
