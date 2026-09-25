package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The M0 golden contract again, through the platform-free [SyntaxBackend] interface. */
class NativeBackendTest {
    private val backend: SyntaxBackend = NativeBackend()

    private fun spans(q: QueryHandle, t: TreeHandle, text: String): List<String> {
        val c = q.captures(t, 0, text.length, ChunkedSource(text, 3))
        return List(c.size) { Span(c.start(it), c.end(it), q.captureNames[c.capture(it)]) }
            .sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
            .map { it.toString() }
    }

    @Test
    fun goldenSpansThroughTheBackend() {
        assertTrue("json" in backend.languages && "kotlin" in backend.languages)
        backend.ensureLanguage("json")
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
            p.setIncludedRanges(intArrayOf(17, 27), ChunkedSource(doc))
            p.parse(ChunkedSource(doc), null).use { t ->
                assertFalse(t.hasError)
                backend.newQuery("javascript", "\"let\" @k").use { q ->
                    assertEquals(listOf("17-20 k"), spans(q, t, doc))
                }
            }
        }
    }
}
