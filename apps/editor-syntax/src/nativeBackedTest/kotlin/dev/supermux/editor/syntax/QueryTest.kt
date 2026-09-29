package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every capture as "start-end name pN" (pattern index), sorted. */
private fun captures(query: String, text: String = SAMPLE): List<String> = SesHighlighter("json").use { h ->
    h.parse(text)
    SyntaxQuery("json", query).use { q ->
        val a = q.captures(h.tree!!, 0, text.length, ChunkedSource(text, 3)).ints
        List(a.size / 4) { "${a[4 * it]}-${a[4 * it + 1]} ${q.captureNames[a[4 * it + 2]]} p${a[4 * it + 3]}" }.sorted()
    }
}

// The keys of SAMPLE: "ağ" is 2-4, "e😀" is 25-28 (the emoji is a surrogate pair).
private const val KEYS = "(pair key: (string (string_content) @k))"

/** Query compilation and text predicates beyond the M0 golden contract. */
class QueryTest {
    @Test
    fun matchFiltersInsideTheCursorAndTheFirstPatternWins() {
        val got = captures("""(pair key: (string (string_content) @k) (#match? @k "^a")) $KEYS""")
        assertEquals(listOf("2-4 k p0", "2-4 k p1", "25-28 k p1"), got)
        // A highlighter keeps, per node, the lowest pattern that survived its predicates.
        val winner = got.groupBy { it.substringBefore(' ') }.mapValues { (_, v) -> v.minOf { it.substringAfterLast(" p").toInt() } }
        assertEquals(mapOf("2-4" to 0, "25-28" to 1), winner)
    }

    @Test
    fun notMatch() =
        assertEquals(listOf("25-28 k p0"), captures("""(pair key: (string (string_content) @k) (#not-match? @k "^a"))"""))

    @Test
    fun nonAsciiRegexes() {
        assertEquals(listOf("2-4 k p0"), captures("""(pair key: (string (string_content) @k) (#match? @k "ğ$"))"""))
        assertEquals(listOf("25-28 k p0"), captures("""(pair key: (string (string_content) @k) (#match? @k "e😀"))"""))
    }

    @Test
    fun patternsAreCounted() = SyntaxQuery(
        "json",
        """
        ((string) @s (#match? @s "x"))
        ((number) @n (#match? @n "x"))
        ((null) @z (#not-match? @z "y"))
        """,
    ).use { q -> assertEquals(3, q.patternCount) }

    @Test
    fun directivesAreReadBack() = SyntaxQuery(
        "json",
        """
        ((string) @s (#set! priority "105") (#set! injection.language "json"))
        ((number) @n (#set! injection.combined))
        ((null) @x (#is? local) (#is-not? global "x") (#set! @x capture.key "v"))
        (true) @t
        """,
    ).use { q ->
        val set = PatternSetting.Kind.SET
        assertEquals(mapOf("priority" to "105", "injection.language" to "json"), q.settingsMap(0))
        assertEquals(mapOf("injection.combined" to null), q.settingsMap(1))
        assertEquals(
            listOf(
                PatternSetting(PatternSetting.Kind.IS, null, "local", null),
                PatternSetting(PatternSetting.Kind.IS_NOT, null, "global", "x"),
                PatternSetting(set, q.captureNames.indexOf("x"), "capture.key", "v"),
            ),
            q.patternSettings(2),
        )
        assertEquals(emptyMap<String, String?>(), q.settingsMap(2)) // the map holds capture-less #set! only
        assertEquals(emptyList(), q.patternSettings(3))
    }

    @Test
    fun setWithCaptureValueIsRefused() {
        val e = assertFailsWith<SyntaxException> { SyntaxQuery("javascript", """((identifier) @c (#set! k @c))""") }
        assertEquals(SyntaxStatus.QUERY, e.status)
    }

    @Test
    fun settingsKeepTheCaptureId() = SyntaxQuery(
        "javascript",
        """((identifier) @a (number) @b (#set! @a k "1") (#set! @b k "2"))""",
    ).use { q ->
        val a = q.captureNames.indexOf("a")
        val b = q.captureNames.indexOf("b")
        assertEquals(
            listOf(PatternSetting(PatternSetting.Kind.SET, a, "k", "1"), PatternSetting(PatternSetting.Kind.SET, b, "k", "2")),
            q.patternSettings(0),
        )
    }

    @Test
    fun matchCallbackRunsOncePerMatch() = SyntaxParser("javascript").use { p ->
        val src = "let x1 = 1; let x2 = 2; let y = 3;"
        p.parse(src).use { t ->
            var calls = 0
            val counting = TextSource { i -> ChunkedSource(src).chunkAt(i) }
            SyntaxQuery(
                "javascript",
                """((variable_declarator name: (identifier) @a value: (number) @b) @c (#match? @a "^x"))""",
            ).use { q ->
                q.regexCalls = { calls++ }
                val c = q.captures(t, 0, src.length, counting)
                assertEquals(6, c.ints.size / 4, "two matches, three captures each")
                assertEquals(3, calls, "one regex call per match (3 declarators), not per capture")
            }
        }
    }

    @Test
    fun matchLimitExceededIsReported() = SyntaxParser("json").use { p ->
        // 1024 patterns x 72 numbers: 73k states hold captures, none ever finds its string. (Many
        // patterns, because tree-sitter compares one pattern's states pairwise at every node.)
        val src = (1..72).joinToString(",", "[", "]") { "1" }
        p.parse(src).use { t ->
            SyntaxQuery("json", "(array (number) @a (string)) ".repeat(1024)).use { q ->
                assertTrue(q.captures(t, 0, src.length, ChunkedSource(src)).exceededMatchLimit)
            }
            SyntaxQuery("json", "(number) @n").use { q ->
                val c = q.captures(t, 0, src.length, ChunkedSource(src))
                assertFalse(c.exceededMatchLimit)
                assertEquals(72, c.ints.size / 4)
            }
        }
    }

    @Test
    fun includedRangesParseOnlyThoseRanges() {
        val doc = "<p>x</p>\n<script>let a = 1;</script>"
        SyntaxParser("html").use { hp ->
            hp.parse(doc).use { ht ->
                val content = SyntaxQuery("html", "(script_element (raw_text) @c)").use { q ->
                    q.captures(ht, 0, doc.length, ChunkedSource(doc)).ints
                }
                assertEquals(listOf(17, 27), content.take(2).toList())
                SyntaxParser("javascript").use { jp ->
                    // start, end, startRow, startColumn, endRow, endColumn: line 1, columns 8..18
                    jp.setIncludedRanges(intArrayOf(content[0], content[1], 1, 8, 1, 18))
                    jp.parse(ChunkedSource(doc)).use { jt ->
                        assertFalse(jt.hasError, jt.sexp())
                        SyntaxQuery("javascript", "(program) @p \"let\" @k").use { q ->
                            val c = q.captures(jt, 0, doc.length, ChunkedSource(doc)).ints
                            assertEquals(listOf(17, 27, 17, 20), listOf(c[0], c[1], c[4], c[5]))
                        }
                    }
                    assertEquals(
                        SyntaxStatus.INVALID_ARGUMENT,
                        assertFailsWith<SyntaxException> { jp.setIncludedRanges(intArrayOf(10, 20, 0, 10, 0, 20, 5, 30, 0, 5, 0, 30)) }.status,
                    )
                    jp.setIncludedRanges(IntArray(0))
                    jp.parse(doc).use { assertTrue(it.hasError, "the whole HTML document parsed as javascript") }
                }
            }
        }
    }


    @Test
    fun malformedMatchIsAQueryError() {
        val e = assertFailsWith<SyntaxException> { SyntaxQuery("json", """((string) @s (#match? @s @s))""") }
        assertEquals(SyntaxStatus.QUERY, e.status)
    }

    @Test
    fun emptyAnyOfValuesDoNotOverflow() = SyntaxParser("javascript").use { p ->
        val src = "a; b;"
        p.parse(src).use { t ->
            SyntaxQuery("javascript", """((identifier) @x (#any-of? @x "" "" "" "" "" "" "" "" "" ""))""").use { q ->
                assertEquals(0, q.captures(t, 0, src.length, ChunkedSource(src)).ints.size)
            }
        }
    }

    @Test
    fun anyOfWithCaptureValuesIsAQueryError() {
        val e = assertFailsWith<SyntaxException> {
            SyntaxQuery(
                "javascript",
                "((identifier) @a (#aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa? @a) (#any-of? @a @a @a @a @a @a @a @a @a @a))",
            )
        }
        assertEquals(SyntaxStatus.QUERY, e.status)
    }
}
