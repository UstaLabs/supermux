package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Incremental parses (edits, reused and copied layers, re-found injections) must give exactly what
 * a fresh parse of the same text gives: the same layers with the same ranges, and the same spans.
 */
class InjectionReplayTest {
    private val backend = testBackend()

    private class Replay(val backend: SyntaxBackend, val lang: String, start: String, val registry: LanguageRegistry = LanguageRegistry.default) {
        val h = Highlighter(backend, lang, registry)
        var rope: Rope = Rope.of(start)
        var doc: ParsedDocument = h.parse(RopeText(rope), rope.length, null, RopeText(rope))

        fun edit(from: Int, to: Int, insert: String) {
            val cs = ChangeSet.of(rope.length, ChangeSpec(from, to, insert))
            val next = cs.apply(rope)
            textEditsFor(cs, rope, next).forEach { doc.edit(it) }
            val nd = h.parse(RopeText(next), next.length, doc, RopeText(next))
            doc.close()
            doc = nd
            rope = next
        }

        /** Layers and spans of the incremental parse, then of a fresh one: they must be equal. */
        fun check(what: String) {
            val text = RopeText(rope)
            Highlighter(backend, lang, registry).use { fh ->
                fh.parse(text, rope.length, null, text).use { fresh ->
                    assertEquals(fresh.layerSignature, doc.layerSignature, "layers after $what")
                    assertEquals(
                        dumpSpans(fh.spans(fresh, 0, rope.length, text), rope.toString()),
                        dumpSpans(h.spans(doc, 0, rope.length, text), rope.toString()),
                        "spans after $what",
                    )
                }
            }
        }

        fun close() { doc.close(); h.close() }
    }

    @Test fun insertingANewlineBeforeAStyleElementKeepsItsLayer() {
        // The review's repro: "\n" between `</script>\n\n` and `<style scoped>`.
        val r = Replay(backend, "vue", HighlightSamples.VUE)
        try {
            val at = HighlightSamples.VUE.indexOf("<style")
            assertEquals("</script>\n\n", HighlightSamples.VUE.substring(at - 11, at))
            assertTrue(r.doc.injections.any { it.startsWith("1:css@") }, r.doc.injections.toString())
            r.edit(at, at, "\n")
            assertTrue(r.doc.injections.any { it.startsWith("1:css@") }, r.doc.injections.toString())
            r.check("the newline")
        } finally {
            r.close()
        }
    }

    private val pieces = mapOf(
        "vue" to listOf("<style>a{b:c}</style>\n", "<script>let x = 1</script>\n", "</", ">", "<", "\n", " ", "{{ y }}", "lang=\"ts\" ", "\"", "x"),
        "html" to listOf("<script>let a = 1;</script>", "<style>p{color:red}</style>", "</", ">", "<", "\n", " ", "<!-- c -->", "\""),
        "php" to listOf("<?php ", "?>", "<script>1</script>", "<b>", "\n", " ", "\$x = 1;", "echo \"a\";", "/*", "*/"),
        "markdown" to listOf("```kotlin\n", "```\n", "~~~python\n", "fun f() = 1\n", "<div>x</div>\n", "\n", " ", "*a*", "`", "# H\n", "- li\n", "[l](u)"),
    )

    private fun soak(lang: String, start: String, seed: Int, steps: Int = 120) {
        val r = Replay(backend, lang, start)
        val rnd = Random(seed)
        try {
            repeat(steps) { k ->
                val text = r.rope.toString()
                fun boundary(p: Int) = if (p in 1 until text.length && text[p - 1].isHighSurrogate()) p - 1 else p
                val from = boundary(rnd.nextInt(text.length + 1))
                if (rnd.nextInt(3) == 0 && text.isNotEmpty()) {
                    val to = boundary(minOf(text.length, from + rnd.nextInt(1, 10)))
                    r.edit(from, to, "")
                    r.check("$lang seed $seed step $k: delete $from..$to")
                } else {
                    val p = pieces.getValue(lang)[rnd.nextInt(pieces.getValue(lang).size)]
                    r.edit(from, from, p)
                    r.check("$lang seed $seed step $k: insert ${p.replace("\n", "\\n")} at $from")
                }
            }
        } finally {
            r.close()
        }
    }

    @Test fun vueReplay() { for (seed in 1..6) soak("vue", HighlightSamples.VUE, seed) }
    @Test fun htmlReplay() { for (seed in 1..6) soak("html", HighlightSamples.HTML, seed) }
    @Test fun phpReplay() { for (seed in 1..6) soak("php", HighlightSamples.PHP, seed) }
    @Test fun markdownReplay() { for (seed in 1..6) soak("markdown", HighlightSamples.MARKDOWN + HighlightSamples.MARKDOWN_UNIT, seed) }

    /** Two layers of the same depth over the same text: they paint in (depth, start, language) order, however they were found. */
    @Test fun overlappingLayersOfOneDepthPaintInAFixedOrder() {
        val twice = LanguageRegistry { l, k ->
            val q = BundledQueries.get(l, k)
            if (l == "markdown" && k == QueryKind.INJECTIONS) {
                q + "\n((fenced_code_block (code_fence_content) @injection.content) (#set! injection.language \"json\") (#set! injection.include-unnamed-children))\n"
            } else q
        }
        val start = "# T\n\n```python\n[1, \"a\", None]\n```\n\ntext\n"
        val r = Replay(backend, "markdown", start, twice)
        try {
            val sig = r.doc.injections
            assertTrue(sig.any { it.startsWith("1:json@") } && sig.any { it.startsWith("1:python@") }, sig.toString())
            // json sorts after python, so it paints last and wins where both colour: `"a"` is a json string
            val at = start.indexOf("\"a\"")
            val span = r.h.spans(r.doc, 0, start.length, RopeText(r.rope)).first { at >= it.from && at < it.to }
            assertEquals(setOf("tok-string"), (span.value as dev.supermux.editor.core.Decoration.Mark).classes)
            r.check("the first parse")
            val rnd = Random(7)
            repeat(60) { k ->
                val text = r.rope.toString()
                val from = rnd.nextInt(text.length + 1)
                r.edit(from, from, listOf("```json\n", "```\n", "1", "\"s\"", "\n", " ")[rnd.nextInt(6)])
                r.check("overlap step $k")
            }
        } finally {
            r.close()
        }
    }
}
