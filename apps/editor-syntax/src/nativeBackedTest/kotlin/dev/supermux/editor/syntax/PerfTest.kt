package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

internal fun ms(block: () -> Unit): Double {
    val t = TimeSource.Monotonic.markNow()
    block()
    return t.elapsedNow().inWholeNanoseconds / 1e6
}

internal fun median(xs: List<Double>) = xs.sorted()[xs.size / 2]
internal fun fmt(x: Double): String = (kotlin.math.round(x * 100) / 100).toString()

/** The documents native/README.md's performance table is measured on (10k lines each). */
internal object PerfCases {
    val kotlin by lazy { "kotlin" to HighlightSamples.kotlinLines(10_000) }
    val markdown by lazy { "markdown" to HighlightSamples.repeatTo(HighlightSamples.MARKDOWN_UNIT, 10_000, "# Big\n\n") }
    val vue by lazy {
        "vue" to HighlightSamples.repeatTo(
            HighlightSamples.VUE_SCRIPT_UNIT, 10_000,
            header = "<template>\n  <div :class=\"cls\">{{ msg }}</div>\n</template>\n\n<script lang=\"ts\">\n",
            footer = "</script>\n\n<style scoped>\n.a { margin: 0 }\n</style>\n",
        )
    }
    val php by lazy { "php" to HighlightSamples.repeatTo(HighlightSamples.PHP_UNIT, 10_000, "<html><body>\n<?php\n", "?>\n</body></html>\n") }
    /** Markdown without headings: one flat document (tree-sitter-markdown's incremental worst case). */
    val flatMarkdown by lazy {
        "markdown" to HighlightSamples.repeatTo(HighlightSamples.MARKDOWN_UNIT.replace("## Section\n\n", ""), 10_000)
    }
    val all get() = listOf(kotlin, markdown, flatMarkdown, vue, php)

    class Numbers(val lines: Int, val full: Double, val incremental: Double, val view60: Double, val view180: Double, val cycle: Double) {
        override fun toString() = "lines=$lines full=${fmt(full)}ms keystroke=${fmt(incremental)}ms viewport60=${fmt(view60)}ms " +
            "viewport180=${fmt(view180)}ms keystroke+180=${fmt(incremental + view180)}ms workerCycle=${fmt(cycle)}ms"
    }

    /** Highlighter-level times, then the worker's whole cycle (edit -> spans applied) for one keystroke in the middle. */
    fun measure(backend: NativeBackend, lang: String, text: String): Numbers {
        var rope = Rope.of(text)
        val lines = rope.lineCount
        val full = ArrayList<Double>()
        val incr = ArrayList<Double>()
        val v60 = ArrayList<Double>()
        val v180 = ArrayList<Double>()
        Highlighter(backend, lang).use { h ->
            var doc: ParsedDocument? = null
            repeat(3) {
                doc?.close()
                full += ms { doc = h.parse(RopeText(rope), rope.length, null, RopeText(rope)); h.spans(doc!!, 0, rope.length, RopeText(rope)) }
            }
            repeat(30) { k ->
                val at = rope.lineStart(lines / 2) + 2
                val cs = ChangeSet.of(rope.length, if (k % 2 == 0) ChangeSpec(at, at, "x") else ChangeSpec(at, at + 1, ""))
                val next = cs.apply(rope)
                var parsed: ParsedDocument? = null
                var editMs = 0.0
                var closeMs = 0.0
                incr += ms {
                    editMs = ms { for (e in textEditsFor(cs, rope, next)) doc!!.edit(e) }
                    parsed = h.parse(RopeText(next), next.length, doc, RopeText(next))
                }
                closeMs = ms { doc!!.close() }
                if (k == 29) println("PHASES $lang layers=${parsed!!.layers.size} edit=${fmt(editMs)}ms close=${fmt(closeMs)}ms " +
                    h.phases.entries.joinToString(" ") { "${it.key}=${fmt(it.value / 1e6)}ms" })
                doc = parsed
                rope = next
                v60 += ms { h.spans(doc!!, rope.lineStart(lines / 2 - 30), rope.lineStart(lines / 2 + 30), RopeText(rope)) }
                v180 += ms { h.spans(doc!!, rope.lineStart(lines / 2 - 90), rope.lineStart(lines / 2 + 90), RopeText(rope)) }
            }
            doc?.close()
        }
        val cycle = runBlocking {
            val host = Host(text, lang, backend)
            try {
                val r = Rope.of(text)
                host.viewport(r.lineStart(lines / 2 - 30) until r.lineStart(lines / 2 + 30))
                host.settle()
                val xs = ArrayList<Double>()
                repeat(20) { k ->
                    val at = host.state.doc.lineStart(lines / 2) + 2
                    val t = TimeSource.Monotonic.markNow()
                    host.dispatch(TransactionSpec(listOf(if (k % 2 == 0) ChangeSpec(at, at, "x") else ChangeSpec(at, at + 1, ""))))
                    host.settle()
                    xs += t.elapsedNow().inWholeNanoseconds / 1e6
                }
                println("CYCLE $lang " + host.worker.lastCycle.entries.joinToString(" ") { "${it.key}=${fmt(it.value)}ms" })
                median(xs.drop(5))
            } finally {
                host.close()
            }
        }
        val warm = { xs: List<Double> -> median(xs.drop(xs.size / 3)) }
        return Numbers(lines, median(full.drop(1)), warm(incr), warm(v60), warm(v180), cycle)
    }

    /** The UI thread's share: one keystroke (spans mapped) and one worker update (spans replaced) with 70k spans possible. */
    fun uiCost(backend: NativeBackend): Pair<Double, Double> {
        val text = HighlightSamples.kotlinLines(10_000)
        val all = RangeSet.of(highlight(backend, "kotlin", text))
        check(all.size >= 70_000) { "only ${all.size} spans" }
        val rope = Rope.of(text)
        val mid = rope.lineStart(rope.lineCount / 2)
        val vp = mid until rope.lineStart(rope.lineCount / 2 + 60)
        var st = EditorState.create(text, extensions = Syntax.extension("kotlin"))
        st = st.update(TransactionSpec(effects = listOf(Syntax.setViewport.of(vp)))).state
        // the worst case the field can be handed: every span of the document
        st = st.update(TransactionSpec(effects = listOf(Syntax.spans.of(SyntaxSpansUpdate(0, 0, text.length, all, IntArray(0)))))).state
        val keys = ArrayList<Double>()
        val updates = ArrayList<Double>()
        repeat(40) { k ->
            val tr = st.update(ChangeSpec(mid + 3, mid + 3, "x"))
            keys += ms { st.update(ChangeSpec(mid + 3, mid + 3, "x")) }
            st = tr.state
            val v = Syntax.snapshot(st)!!.version
            val screen = vp.last + 1 - vp.first
            val s0 = vp.first - screen
            val s1 = vp.last + 1 + screen
            val upd = SyntaxSpansUpdate(v, s0, s1, RangeSet.of(all.between(s0, s1).filter { it.from >= s0 && it.to <= s1 }), IntArray(0))
            val spec = TransactionSpec(effects = listOf(Syntax.spans.of(upd)))
            updates += ms { st.update(spec) }
            st = st.update(spec).state
        }
        return median(keys.drop(10)) to median(updates.drop(10))
    }
}

class PerfTest {
    private val backend = testBackend()

    @Test
    fun documents10kLines() {
        for (case in PerfCases.all) {
            val (lang, text) = case
            val n = PerfCases.measure(backend, lang, text)
            println("PERF ${if (case === PerfCases.flatMarkdown) "markdown-flat" else lang} $n")
            assertTrue(n.cycle > 0)
        }
    }

    @Test
    fun uiThreadCost() {
        val (key, upd) = PerfCases.uiCost(backend)
        println("PERF ui keystroke(map)=${fmt(key)}ms update(replace)=${fmt(upd)}ms")
    }
}
