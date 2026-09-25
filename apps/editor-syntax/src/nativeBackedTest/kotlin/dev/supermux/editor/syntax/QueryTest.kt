package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Every capture as "start-end name pN" (pattern index), sorted. */
private fun captures(query: String, text: String = SAMPLE): List<String> = SesHighlighter("json").use { h ->
    h.parse(text)
    SyntaxQuery("json", query).use { q ->
        val a = q.captures(h.tree!!, 0, text.length, ChunkedSource(text, 3))
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
        assertEquals(mapOf("priority" to "105", "injection.language" to "json"), q.patternSettings(0))
        assertEquals(mapOf("injection.combined" to null), q.patternSettings(1))
        assertEquals(mapOf("is?:local" to null, "is-not?:global" to "x", "capture.key" to "v"), q.patternSettings(2))
        assertEquals(emptyMap<String, String?>(), q.patternSettings(3))
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
                assertEquals(0, q.captures(t, 0, src.length, ChunkedSource(src)).size)
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
