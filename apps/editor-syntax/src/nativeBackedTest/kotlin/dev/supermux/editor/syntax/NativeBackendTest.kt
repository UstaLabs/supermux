package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The M0 golden contract again, through the platform-free [SyntaxBackend] interface. */
class NativeBackendTest {
    private val backend = NativeBackend()

    private fun spans(q: QueryHandle, t: TreeHandle, text: String): List<String> {
        val c = q.captures(t, 0, text.length, ChunkedSource(text, 3))
        return List(c.size) { Span(c.start(it), c.end(it), q.captureNames[c.capture(it)]) }
            .sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
            .map { it.toString() }
    }

    @Test
    fun goldenSpansThroughTheBackend() {
        assertTrue("json" in backend.languages && "kotlin" in backend.languages)
        backend.ensureLanguageNow("json")
        backend.newParser("json").use { p ->
            assertEquals("json", p.language)
            p.parse(ChunkedSource(SAMPLE, 3), null).use { t ->
                assertFalse(t.hasError)
                backend.newQuery("json", JSON_HIGHLIGHTS).use { q ->
                    assertEquals(GOLDEN, spans(q, t, SAMPLE))
                    assertEquals(6, q.patternCount)
                }
            }
        }
    }

    @Test
    fun incrementalReparseThroughTheBackend() {
        val next = SAMPLE.replaceRange(8, 9, "12345")
        backend.newParser("json").use { p ->
            p.parse(ChunkedSource(SAMPLE), null).use { old ->
                old.edit(TextEdit(8, 9, 13, 0, 8, 0, 9, 0, 13))
                p.parse(ChunkedSource(next), old).use { fresh ->
                    assertTrue(old.changedRanges(fresh).size % 2 == 0)
                    fresh.copy().use { copy ->
                        backend.newQuery("json", JSON_HIGHLIGHTS).use { q ->
                            val s = spans(q, copy, next)
                            assertEquals("8-13 number", s[2])
                            assertEquals("37-39 escape", s[8])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun settingsAndIncludedRangesThroughTheBackend() {
        val doc = "<p>x</p>\n<script>let a = 1;</script>"
        backend.newQuery("javascript", """((identifier) @x (#set! injection.language "js"))""").use { q ->
            assertEquals(listOf(PatternSetting(PatternSetting.Kind.SET, null, "injection.language", "js")), q.settings(0))
        }
        backend.newParser("javascript").use { p ->
            p.setIncludedRanges(intArrayOf(17, 27), LineTable(ChunkedSource(doc), doc.length))
            p.parse(ChunkedSource(doc), null).use { t ->
                assertFalse(t.hasError)
                backend.newQuery("javascript", "\"let\" @k").use { q ->
                    assertEquals(listOf("17-20 k"), spans(q, t, doc))
                }
            }
        }
    }
}

class MatchesTest {
    @Test
    fun matchesGroupCapturesPerMatch() {
        val backend = testBackend()
        val src = "let a = 1; let bb = 22;"
        backend.newParser("javascript").use { p ->
            p.parse(ChunkedSource(src), null).use { t ->
                backend.newQuery(
                    "javascript",
                    """(variable_declarator name: (identifier) @n value: (number) @v) ((identifier) @x (#eq? @x "bb"))""",
                ).use { q ->
                    val got = q.matches(t, 0, src.length, ChunkedSource(src)).toList()
                        .map { m -> "p${m.pattern}:" + m.captures.joinToString(",") { "${it.start}-${it.end}@${q.captureNames[it.index]}" } }
                        .sorted()
                    assertEquals(listOf("p0:15-17@n,20-22@v", "p0:4-5@n,8-9@v", "p1:15-17@x"), got)
                }
                backend.newQuery("javascript", "(variable_declarator) @d").use { q ->
                    val d = q.matches(t, 0, 10, ChunkedSource(src), childrenOf = 0).toList().single().captures.single()
                    assertEquals(listOf(4, 9), listOf(d.start, d.end))
                    assertEquals(listOf("4-5 true", "6-7 false", "8-9 true"), List(d.childCount) { "${d.childStart(it)}-${d.childEnd(it)} ${d.childIsNamed(it)}" })
                }
            }
        }
    }
}
