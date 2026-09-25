package dev.supermux.editor.syntax

import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.Ranged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `start-end tok-class` per span, then the text it covers (for review by eye). */
internal fun dumpSpans(spans: List<Ranged<Decoration>>, text: String): String = spans.joinToString("\n", postfix = "\n") { r ->
    val cls = (r.value as Decoration.Mark).classes.single()
    "${r.from}-${r.to} $cls\t" + text.substring(r.from, r.to).replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
}

internal fun highlight(backend: SyntaxBackend, lang: String, text: String, maxDepth: Int = 3): List<Ranged<Decoration>> =
    Highlighter(backend, lang, maxDepth = maxDepth).use { h ->
        h.parse(ChunkedSource(text), text.length, null).use { doc -> h.spans(doc, 0, text.length, ChunkedSource(text)) }
    }

class HighlighterTest {
    private val backend = testBackend()

    private fun classAt(spans: List<Ranged<Decoration>>, pos: Int): String? =
        spans.firstOrNull { pos >= it.from && pos < it.to }?.let { (it.value as Decoration.Mark).classes.single() }

    @Test fun goldenSpans() {
        for ((name, sample) in HighlightSamples.ALL) {
            val (lang, text) = sample
            val spans = highlight(backend, lang, text)
            // sorted, non-overlapping, inside the document, non-exclusive marks
            spans.zipWithNext().forEach { (a, b) -> assertTrue(a.to <= b.from, "$name: $a overlaps $b") }
            spans.forEach { r ->
                assertTrue(r.from < r.to && r.to <= text.length, "$name: $r")
                val m = r.value as Decoration.Mark
                assertFalse(m.inclusiveStart || m.inclusiveEnd, "$name: $m")
                assertTrue(m.classes.single() in TokenClasses.ALL, "$name: $m")
            }
            assertGolden("$name.txt", dumpSpans(spans, text))
        }
    }

    @Test fun injectedSpansWinOverHost() {
        val text = HighlightSamples.MARKDOWN
        val fn = text.indexOf("fun main")
        val hostOnly = highlight(backend, "markdown", text, maxDepth = 0)
        val injected = highlight(backend, "markdown", text)
        assertEquals("tok-keyword", classAt(injected, fn))
        assertTrue(classAt(hostOnly, fn) != "tok-keyword", "the host alone does not know Kotlin: ${classAt(hostOnly, fn)}")
        // the host's own spans outside the fence stay
        assertEquals(classAt(hostOnly, 0), classAt(injected, 0))
    }

    @Test fun markdownFenceUsesTheFenceLanguage() {
        val text = "Text\n\n```python\ndef f(): pass\n```\n\n~~~kotlin\nval x = 1\n~~~\n"
        Highlighter(backend, "markdown").use { h ->
            h.parse(ChunkedSource(text), text.length, null).use { doc ->
                assertTrue("1:python" in doc.injections && "1:kotlin" in doc.injections, doc.injections.toString())
                val spans = h.spans(doc, 0, text.length, ChunkedSource(text))
                assertEquals("tok-keyword", classAt(spans, text.indexOf("def")))
                assertEquals("tok-keyword", classAt(spans, text.indexOf("val")))
            }
        }
    }

    @Test fun unknownFenceLanguageStaysPlain() {
        val text = "```foobar\nfun main() {}\n```\n"
        Highlighter(backend, "markdown").use { h ->
            h.parse(ChunkedSource(text), text.length, null).use { doc ->
                assertTrue(doc.injections.none { it.endsWith("foobar") || it.endsWith("kotlin") }, doc.injections.toString())
                assertTrue(classAt(h.spans(doc, 0, text.length, ChunkedSource(text)), text.indexOf("fun")) != "tok-keyword")
            }
        }
    }

    @Test fun depthIsCapped() {
        fun nest(levels: Int): String {
            // each level a Markdown fence (5, 4, 3 backticks, then tildes) around the next
            var inner = "~~~~kotlin\nfun x() {}\n~~~~\n"
            val fences = listOf("```", "````", "`````", "``````")
            for (i in 0 until levels) inner = "${fences[i]}markdown\n$inner${fences[i]}\n"
            return inner
        }
        // 2 Markdown levels inside the host: Kotlin is at depth 3, still highlighted
        val three = nest(2)
        assertEquals("tok-keyword", classAt(highlight(backend, "markdown", three), three.indexOf("fun")))
        // 3 levels: Kotlin would be depth 4, past the cap
        val four = nest(3)
        assertTrue(classAt(highlight(backend, "markdown", four), four.indexOf("fun")) != "tok-keyword")
    }

    @Test fun kotlinClassFoldsOncePerBlock() {
        val text = "class A {\n    fun a() {\n        x()\n    }\n\n    fun b() {\n        y()\n    }\n}\n"
        Highlighter(backend, "kotlin").use { h ->
            h.parse(ChunkedSource(text), text.length, null).use { doc ->
                val f = h.folds(doc, 0, text.length, ChunkedSource(text)).toList().chunked(2)
                val blocks = f.map { (s, e) -> text.substring(s, e) }
                assertEquals(3, f.size, "folds: $blocks")
                assertTrue(blocks.any { it.startsWith("{") && it.contains("fun a") && it.contains("fun b") }, "$blocks")
                assertTrue(blocks.count { it.startsWith("{") && it.contains("()") && !it.contains("fun") } == 2, "$blocks")
            }
        }
    }

    @Test fun incrementalReparseMatchesAFreshParse() {
        val text = HighlightSamples.VUE
        val at = text.indexOf("\"hi\"")
        val next = text.replaceRange(at, at + 4, "'hello there'")
        Highlighter(backend, "vue").use { h ->
            val first = h.parse(ChunkedSource(text), text.length, null)
            val rope = dev.supermux.editor.core.Rope.of(text)
            val cs = dev.supermux.editor.core.ChangeSet.of(text.length, dev.supermux.editor.core.ChangeSpec(at, at + 4, "'hello there'"))
            textEditsFor(cs, rope, cs.apply(rope)).forEach { first.edit(it) }
            val second = h.parse(ChunkedSource(next), next.length, first)
            first.close()
            second.use { doc ->
                assertEquals(dumpSpans(highlight(backend, "vue", next), next), dumpSpans(h.spans(doc, 0, next.length, ChunkedSource(next)), next))
            }
        }
    }

    /** Edit [start] step by step, reparsing incrementally; after each step the spans equal a fresh highlight. */
    private fun replay(lang: String, start: String, steps: List<(String) -> Triple<Int, Int, String>>) {
        Highlighter(backend, lang).use { h ->
            var text = start
            var doc = h.parse(ChunkedSource(text), text.length, null)
            try {
                for ((k, step) in steps.withIndex()) {
                    val (from, to, insert) = step(text)
                    val cs = dev.supermux.editor.core.ChangeSet.of(text.length, dev.supermux.editor.core.ChangeSpec(from, to, insert))
                    val next = cs.apply(text)
                    textEditsFor(cs, dev.supermux.editor.core.Rope.of(text), dev.supermux.editor.core.Rope.of(next)).forEach { doc.edit(it) }
                    val nd = h.parse(ChunkedSource(next), next.length, doc)
                    doc.close()
                    doc = nd
                    text = next
                    assertEquals(
                        dumpSpans(highlight(backend, lang, text), text),
                        dumpSpans(h.spans(doc, 0, text.length, ChunkedSource(text)), text),
                        "$lang step $k: ${text.replace("\n", "\\n")}",
                    )
                }
            } finally {
                doc.close()
            }
        }
    }

    private fun replace(what: String, with: String, nth: Int = 0): (String) -> Triple<Int, Int, String> = { t ->
        var at = t.indexOf(what)
        repeat(nth) { at = t.indexOf(what, at + 1) }
        check(at >= 0) { "no $what in $t" }
        Triple(at, at + what.length, with)
    }

    @Test fun incrementalInjectionsMatchAFreshParse() {
        replay(
            "markdown", HighlightSamples.MARKDOWN,
            listOf(
                replace("```kotlin", "```python"), // the fence's language changes
                { t -> Triple(t.length, t.length, "\n```js\nlet a = 1\n```\n") }, // a new fence
                replace("```\n", ""), // the first fence's end goes: the rest of the file is its content
                replace("fun main", "fun mai"),
                { t -> val at = t.indexOf("println"); Triple(at, at, "```\n") }, // a closing fence typed back
            ),
        )
        replay(
            "html", HighlightSamples.HTML,
            listOf(replace("<script>", "<scripx>"), replace("<scripx>", "<script>"), replace("color: red", "color: blue; margin: 0")),
        )
        replay("vue", HighlightSamples.VUE, listOf(replace("lang=\"ts\"", "lang=\"js\""), replace("as string", ""), replace("{{ msg }}", "{{ msg + 1 }}")))
    }
}
