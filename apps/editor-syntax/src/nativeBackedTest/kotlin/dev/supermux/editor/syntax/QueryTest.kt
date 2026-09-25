package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Query compilation and text predicates beyond the M0 golden contract. */
class QueryTest {
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
