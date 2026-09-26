package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An editor host: transactions apply on one serial "UI" dispatcher, the worker's updates are
 * posted there too, and the host calls [SyntaxWorker.onState] after every transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Host(
    text: String,
    language: String?,
    backend: SyntaxBackend,
    limits: SyntaxLimits = SyntaxLimits(),
) {
    val ui = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob())
    var state: EditorState = EditorState.create(text, extensions = Syntax.extension(language))
        private set
    val worker = SyntaxWorker(backend, LanguageRegistry.default, scope, { spec -> scope.launch(ui) { applyNow(spec) } }, limits)
    fun state(text: String, language: String?) = EditorState.create(text, extensions = Syntax.extension(language))

    /** Apply [spec] on the calling thread (the UI dispatcher's, or the web's only one: [dispatchNow]). */
    internal fun applyNow(spec: TransactionSpec) {
        state = state.update(spec).state
        worker.onState(state)
    }

    suspend fun dispatch(spec: TransactionSpec) = withContext(ui) { applyNow(spec) }

    /** The host swaps in another state (another file opened in this editor): a new field instance, version 0 again. */
    suspend fun replace(next: EditorState) = withContext(ui) { state = next; worker.onState(next) }
    suspend fun viewport(r: IntRange) = dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(r))))

    /** Until the worker is idle and its last update has been applied. */
    suspend fun settle() {
        repeat(3) {
            withContext(ui) {}
            worker.idle()
            withContext(ui) {}
        }
    }

    val spans: RangeSet<Decoration> get() = state.field(Syntax.field).spans

    fun close() {
        worker.close()
        scope.cancel()
    }
}

class SyntaxWorkerTest {
    private val backend = testBackend()

    private fun fresh(lang: String, text: String): RangeSet<Decoration> = RangeSet.of(highlight(backend, lang, text))

    @Test
    fun typingMapsSpansImmediatelyThenReplacesThem() = runSuspendTest {
        val text = HighlightSamples.KOTLIN
        val host = Host(text, "kotlin", backend)
        try {
            host.viewport(0 until text.length)
            host.settle()
            assertTrue(host.spans.size > 50, "${host.spans.size} spans")
            assertEquals(fresh("kotlin", text), host.spans)
            assertTrue(host.state.facet(decorationsFacet).any { it === host.spans }, "the spans feed the decorations facet")

            // type into the middle of the file
            val at = text.indexOf("val shapes")
            val before = host.spans
            val insert = "val y = \"two\"\n    "
            val spec = TransactionSpec(listOf(ChangeSpec(at, at, insert)))
            val mapped = withContext(host.ui) {
                val tr = host.state.update(spec)
                before.map(tr.changes)
            }
            host.dispatch(spec)
            // right after the transaction: the old spans, shifted (no gap)
            assertEquals(mapped, host.spans)
            // once the worker ran: exactly a fresh highlight of the new text
            host.settle()
            val next = text.replaceRange(at, at, insert)
            assertEquals(host.state.doc.toString(), next)
            assertEquals(fresh("kotlin", next), host.spans)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    @Test
    fun staleUpdateIsMappedThroughLaterEdits() {
        val text = HighlightSamples.KOTLIN
        var st = EditorState.create(text, extensions = Syntax.extension("kotlin"))
        val spans0 = fresh("kotlin", text)
        val update = SyntaxSpansUpdate(0, 0, text.length, spans0, IntArray(0))
        val t1 = st.update(ChangeSpec(0, 0, "// header\n"))
        st = t1.state
        val p = st.doc.toString().indexOf("fun main")
        val t2 = st.update(ChangeSpec(p, p, "private "))
        st = t2.state
        st = st.update(TransactionSpec(effects = listOf(Syntax.spans.of(update)))).state
        val spans = st.field(Syntax.field).spans
        assertEquals(spans0.map(t1.changes).map(t2.changes), spans)
        // the spans land in the right places: `fun` of main, now after "// header\n" and "private "
        val fn = st.doc.toString().indexOf("fun main")
        val at = spans.between(fn, fn + 3).single { it.from == fn }
        assertEquals(Decoration.Mark(setOf("tok-keyword")), at.value)
        assertEquals(fn + 3, at.to)

        // an update older than the change log (Syntax.LOG_SIZE edits) is dropped
        var old = EditorState.create(text, extensions = Syntax.extension("kotlin"))
        repeat(Syntax.LOG_SIZE + 1) { old = old.update(ChangeSpec(0, 0, " ")).state }
        old = old.update(TransactionSpec(effects = listOf(Syntax.spans.of(update)))).state
        assertTrue(old.field(Syntax.field).spans.isEmpty)
    }

    @Test
    fun viewportChangeRequestsNewSpans() = runSuspendTest {
        val text = HighlightSamples.KOTLIN.repeat(200)
        val host = Host(text, "kotlin", backend)
        try {
            host.viewport(0 until 400)
            host.settle()
            assertTrue(host.spans.size > 10)
            // viewport + one screen below: nothing past 800
            assertTrue(host.spans.all { it.to <= 800 }, "${host.spans.last()}")
            val end = text.length
            host.viewport(end - 400 until end)
            host.settle()
            assertTrue(host.spans.any { it.from >= end - 400 }, "no spans at the new viewport")
            assertEquals(fresh("kotlin", text).between(end - 400, end).filter { it.from >= end - 400 },
                host.spans.between(end - 400, end).filter { it.from >= end - 400 })
        } finally {
            host.close()
        }
    }

    @Test
    fun hugeDocumentIsPlainText() = runSuspendTest {
        val huge = Host("a".repeat(5 * 1024 * 1024 + 1), "kotlin", backend)
        val longLine = Host("fun main() {}\n" + "x".repeat(20_001) + "\n", "kotlin", backend)
        try {
            for (h in listOf(huge, longLine)) {
                h.viewport(0 until 1000)
                h.settle()
                assertTrue(Syntax.isOff(h.state))
                assertTrue(h.spans.isEmpty)
            }
        } finally {
            huge.close()
            longLine.close()
        }
    }

    @Test
    fun timeoutTurnsSyntaxOff() = runSuspendTest {
        val host = Host(HighlightSamples.kotlinLines(3000), "kotlin", backend, SyntaxLimits(parseSliceMicros = 100, parseBudgetMicros = 1))
        try {
            host.viewport(0 until 1000)
            host.settle()
            assertTrue(Syntax.isOff(host.state))
            assertTrue(host.spans.isEmpty)
            // and it stays off while typing
            host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "// x\n"))))
            host.settle()
            assertTrue(Syntax.isOff(host.state) && host.spans.isEmpty)
        } finally {
            host.close()
        }
    }

    @Test
    fun closeFreesEveryTree() = runSuspendTest {
        val before = Ses.debugLiveTrees()
        val host = Host(HighlightSamples.MARKDOWN + HighlightSamples.KOTLIN, "markdown", backend)
        host.viewport(0 until 2000)
        host.settle()
        assertTrue(Ses.debugLiveTrees() > before, "the worker holds trees (host + injections)")
        host.worker.close()
        host.worker.join()
        assertEquals(before, Ses.debugLiveTrees())
        host.scope.cancel()
    }

    @Test
    fun plainTextParsesNothing() = runSuspendTest {
        val host = Host("fun main() {}\n", null, backend)
        try {
            host.viewport(0 until 100)
            host.settle()
            assertTrue(host.spans.isEmpty)
            assertFalse(Syntax.isOff(host.state))
        } finally {
            host.close()
        }
    }

    /** 300 random edits while the worker races; once idle, the spans equal a fresh highlight. */
    @Test
    fun randomEditSoak() = runSuspendTest {
        val r = Random(20260926)
        val pieces = listOf("val x = 1\n", "fun f(a: Int) = a\n", "\"str\"", "// c\n", "{", "}", " ", "\n", "ağ😀", "class K", "(", ")")
        val host = Host(HighlightSamples.KOTLIN, "kotlin", backend)
        try {
            host.viewport(0 until 100_000)
            repeat(300) {
                val doc = host.state.doc.toString()
                fun boundary(p: Int) = if (p in 1 until doc.length && doc[p - 1].isHighSurrogate()) p - 1 else p
                val from = boundary(r.nextInt(doc.length + 1))
                val spec = if (r.nextInt(3) == 0 && doc.length > 20) {
                    ChangeSpec(from, boundary(minOf(doc.length, from + r.nextInt(1, 12))), "")
                } else {
                    ChangeSpec(from, from, pieces[r.nextInt(pieces.size)])
                }
                host.dispatch(TransactionSpec(listOf(spec)))
                if (r.nextInt(10) == 0) host.worker.idle() // sometimes let it catch up
            }
            host.dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(0 until host.state.doc.length))))
            host.settle()
            val final = host.state.doc.toString()
            assertTrue(host.spans.size > 50, "${host.spans.size} spans")
            assertEquals(fresh("kotlin", final), host.spans)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    /** The same with Markdown: fences (injections) opened, closed and renamed while the worker races. */
    @Test
    fun randomEditSoakWithInjections() = runSuspendTest {
        val r = Random(4242)
        val pieces = listOf("```kotlin\n", "```\n", "~~~python\n", "fun f() = 1\n", "def g(): pass\n", "# H\n", "*x*", "`", "\n", " ", "<b>", "ağ")
        val host = Host(HighlightSamples.MARKDOWN, "markdown", backend)
        try {
            host.viewport(0 until 100_000)
            repeat(200) {
                val doc = host.state.doc.toString()
                val from = r.nextInt(doc.length + 1).let { if (it in 1 until doc.length && doc[it - 1].isHighSurrogate()) it - 1 else it }
                val spec = if (r.nextInt(3) == 0 && doc.length > 20) {
                    var to = minOf(doc.length, from + r.nextInt(1, 8))
                    if (to in 1 until doc.length && doc[to - 1].isHighSurrogate()) to--
                    ChangeSpec(from, to, "")
                } else {
                    ChangeSpec(from, from, pieces[r.nextInt(pieces.size)])
                }
                host.dispatch(TransactionSpec(listOf(spec)))
                if (r.nextInt(8) == 0) host.worker.idle()
            }
            host.dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(0 until host.state.doc.length))))
            host.settle()
            val final = host.state.doc.toString()
            assertEquals(fresh("markdown", final), host.spans)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    /** A replaced state (version 0 again, other text) must never get the old document's spans. */
    @Test
    fun aReplacedStateIsParsedAgain() = runSuspendTest {
        val host = Host(HighlightSamples.KOTLIN, "kotlin", backend)
        try {
            host.viewport(0 until 100_000)
            host.settle()
            val other = "fun other() {\n    val z = \"zz\"\n}\n"
            val next = host.state(other, "kotlin")
            assertEquals(0L, Syntax.snapshot(next)!!.version)
            host.replace(next)
            host.settle()
            assertTrue(host.spans.isEmpty || host.spans.last().to <= other.length)
            // no viewport yet in the new state: the default one covers this small file
            assertEquals(fresh("kotlin", other), host.spans)
            // and a viewport change on it paints the new text too
            host.viewport(0 until other.length)
            host.settle()
            assertEquals(fresh("kotlin", other), host.spans)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    /** An update computed for another state (another epoch) is ignored by this one. */
    @Test
    fun anUpdateForAnotherEpochIsIgnored() {
        val a = EditorState.create("fun a() {}\n", extensions = Syntax.extension("kotlin"))
        val b = EditorState.create("fun a() {}\n", extensions = Syntax.extension("kotlin"))
        val epochA = Syntax.snapshot(a)!!.epoch
        assertTrue(epochA != Syntax.snapshot(b)!!.epoch)
        val u = SyntaxSpansUpdate(0, 0, 11, fresh("kotlin", "fun a() {}\n"), IntArray(0), epoch = epochA)
        assertTrue(b.update(TransactionSpec(effects = listOf(Syntax.spans.of(u)))).state.field(Syntax.field).spans.isEmpty)
        assertFalse(a.update(TransactionSpec(effects = listOf(Syntax.spans.of(u)))).state.field(Syntax.field).spans.isEmpty)
    }

    /** An update older than one already applied is ignored (dispatches arriving out of order). */
    @Test
    fun anOlderUpdateIsIgnored() {
        var st = EditorState.create("val a = 1\n", extensions = Syntax.extension("kotlin"))
        val u0 = SyntaxSpansUpdate(0, 0, 10, fresh("kotlin", "val a = 1\n"), IntArray(0))
        st = st.update(ChangeSpec(0, 0, "val b = 2\n")).state
        val t1 = st.doc.toString()
        val u1 = SyntaxSpansUpdate(1, 0, t1.length, fresh("kotlin", t1), IntArray(0))
        st = st.update(TransactionSpec(effects = listOf(Syntax.spans.of(u1)))).state
        val after1 = st.field(Syntax.field).spans
        st = st.update(TransactionSpec(effects = listOf(Syntax.spans.of(u0)))).state
        assertEquals(after1, st.field(Syntax.field).spans)
    }

    /** A backend that has [missing] never, and [late] only after ensureLanguage. */
    private class PartialBackend(val base: NativeBackend, val missing: Set<String>, val late: Set<String>) : SyntaxBackend by base {
        val loaded = HashSet<String>()
        override fun isReady(language: String) = language !in missing && (language !in late || language in loaded) && base.isReady(language)
        override suspend fun ensureLanguage(language: String) {
            if (language in missing) throw SyntaxException("no tables for $language: resource editor-syntax/tables/$language.sesz is missing", SyntaxStatus.NO_TABLES)
            base.ensureLanguage(language)
            loaded += language
        }
    }

    @Test
    fun aHostLanguageWithoutTablesIsPlainText() = runSuspendTest {
        val host = Host("def f(): pass\n", "python", PartialBackend(backend, setOf("python"), emptySet()))
        try {
            host.viewport(0 until 100)
            host.settle()
            assertTrue(Syntax.isOff(host.state))
            assertTrue(host.spans.isEmpty)
            assertEquals(SyntaxStatus.NO_TABLES, (host.worker.lastError as SyntaxException).status)
            // it does not retry on every keystroke: still off, and no new attempt
            host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "# x\n"))))
            host.settle()
            assertTrue(Syntax.isOff(host.state))
        } finally {
            host.close()
        }
    }

    @Test
    fun anInjectionWithoutTablesIsSkippedAndTheRestHighlighted() = runSuspendTest {
        val text = "# Title\n\n```python\ndef f(): pass\n```\n\n```kotlin\nfun g() = 1\n```\n"
        val host = Host(text, "markdown", PartialBackend(backend, setOf("python"), emptySet()))
        try {
            host.viewport(0 until 1000)
            host.settle()
            assertFalse(Syntax.isOff(host.state))
            val kw = host.spans.firstOrNull { it.from == text.indexOf("fun") }
            assertEquals(setOf("tok-keyword"), (kw?.value as dev.supermux.editor.core.Decoration.Mark?)?.classes, "kotlin is still coloured")
            assertTrue(host.spans.none { it.from == text.indexOf("def") && (it.value as dev.supermux.editor.core.Decoration.Mark).classes == setOf("tok-keyword") })
            assertEquals(SyntaxStatus.NO_TABLES, (host.worker.lastError as SyntaxException).status)
        } finally {
            host.close()
        }
    }

    @Test
    fun anInjectionLoadedLaterIsParsedWhenReady() = runSuspendTest {
        val text = "```python\ndef f(): pass\n```\n"
        val host = Host(text, "markdown", PartialBackend(backend, emptySet(), setOf("python")))
        try {
            host.viewport(0 until 1000)
            host.settle()
            val kw = host.spans.firstOrNull { it.from == text.indexOf("def") }
            assertEquals(setOf("tok-keyword"), (kw?.value as dev.supermux.editor.core.Decoration.Mark?)?.classes, "python arrived later")
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    /** A parse far longer than one slice still finishes (it resumes slice after slice). */
    @Test
    fun aSlowFullParseStillFinishes() = runSuspendTest {
        val text = HighlightSamples.kotlinLines(2000)
        val host = Host(text, "kotlin", backend, SyntaxLimits(parseSliceMicros = 200))
        try {
            host.viewport(0 until text.length)
            host.settle()
            assertFalse(Syntax.isOff(host.state))
            assertEquals(fresh("kotlin", text), host.spans)
        } finally {
            host.close()
        }
    }

    /**
     * A newer TEXT arriving early in a (non-first) sliced parse cancels it, and the newer one is
     * parsed instead: here a whole-document replacement, which reparses everything, then typing.
     */
    @Test
    fun aNewerTextRestartsAnEarlySlicedReparse() = runSuspendTest {
        val text = HighlightSamples.kotlinLines(2000)
        val host = Host(text, "kotlin", backend, SyntaxLimits(parseSliceMicros = 200))
        try {
            host.viewport(0 until 1_000_000)
            host.settle()
            var typed = false
            host.worker.onSlice = {
                if (!typed) {
                    typed = true
                    // applied (and posted to the worker) before the next slice checks: not a race
                    dispatchNow(host, TransactionSpec(listOf(ChangeSpec(0, 0, "// typed\n"))))
                }
            }
            val other = text.replace("Shape", "Form").replace("area", "size")
            host.dispatch(TransactionSpec(listOf(ChangeSpec(0, host.state.doc.length, other))))
            host.settle()
            assertTrue(typed)
            assertTrue(host.worker.restarts >= 1, "restarts ${host.worker.restarts}")
            assertEquals(fresh("kotlin", host.state.doc.toString()), host.spans)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }

    /** A first parse is never abandoned (it has nothing to resume from): it finishes, then the edit applies. */
    @Test
    fun aFirstParseIsNeverCancelled() = runSuspendTest {
        val text = HighlightSamples.kotlinLines(2000)
        val host = Host(text, "kotlin", backend, SyntaxLimits(parseSliceMicros = 200))
        try {
            var typed = false
            host.worker.onSlice = {
                if (!typed) {
                    typed = true
                    host.scope.launch(host.ui) { host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "// typed\n")))) }
                }
            }
            host.viewport(0 until 1_000_000)
            host.settle()
            assertTrue(typed)
            assertEquals(0, host.worker.restarts)
            assertEquals(fresh("kotlin", host.state.doc.toString()), host.spans)
        } finally {
            host.close()
        }
    }

    @Test
    fun idleReturnsOnceTheWorkerIsClosed() = runSuspendTest {
        val host = Host("val a = 1\n", "kotlin", backend)
        host.worker.close()
        host.worker.join()
        host.worker.onState(host.state)
        kotlinx.coroutines.withTimeout(5_000) { host.worker.idle() }
        host.scope.cancel()
    }

    /** A big paste does not stay in the change log for 64 more edits. */
    @Test
    fun theChangeLogKeepsAtMostAMegabyteOfInsertedText() {
        var st = EditorState.create("", extensions = Syntax.extension("kotlin"))
        st = st.update(ChangeSpec(0, 0, "x".repeat(Syntax.LOG_MAX_INSERTED))).state
        st = st.update(ChangeSpec(0, 0, "y")).state
        val log = Syntax.snapshot(st)!!.log
        assertEquals(listOf(2L), log.map { it.version })
    }

    /** A Kotlin file whose first full parse takes more than [minMs] here (sized by timing 10k lines). */
    private fun slowKotlin(minMs: Double = 450.0): String {
        val probe = HighlightSamples.kotlinLines(10_000)
        val t = Highlighter(backend, "kotlin").use { h -> ms { h.parse(RopeText(dev.supermux.editor.core.Rope.of(probe)), probe.length, null).close() } }
        val lines = (10_000 * kotlin.math.ceil(minMs / t)).toInt().coerceIn(10_000, 60_000)
        return HighlightSamples.kotlinLines(lines)
    }

    /** Spans in the viewport window of [host] vs a fresh parse of its text over the same window. */
    private fun assertWindowMatchesFresh(host: Host, from: Int, to: Int) {
        val text = host.state.doc.toString()
        val want = Highlighter(backend, "kotlin").use { h ->
            val r = RopeText(dev.supermux.editor.core.Rope.of(text))
            // wider than the window: spans crossing its edges must not come out clipped
            h.parse(r, text.length, null, r).use { d -> RangeSet.of(h.spans(d, maxOf(0, from - 2000), minOf(text.length, to + 2000), r)) }
        }
        assertEquals(want.between(from, to).filter { it.from >= from && it.to <= to }, host.spans.between(from, to).filter { it.from >= from && it.to <= to })
    }

    /** The surface scrolls every 15 ms during a long first parse: viewport-only snapshots never cancel it. */
    @Test
    fun scrollingDuringALongFirstParseStillGetsSpans() = runSuspendTest {
        val text = slowKotlin()
        val host = Host(text, "kotlin", backend)
        try {
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            var k = 0
            while (host.spans.isEmpty && t0.elapsedNow().inWholeSeconds < 60) {
                val at = (k++ * 997) % (text.length - 2000)
                host.viewport(at until at + 2000)
                kotlinx.coroutines.delay(15)
            }
            println("LIVELOCK scroll lines=${host.state.doc.lineCount} firstSpansAfter=${t0.elapsedNow().inWholeMilliseconds}ms scrolls=$k restarts=${host.worker.restarts}")
            assertFalse(host.spans.isEmpty, "no spans after ${t0.elapsedNow()} of scrolling (${host.worker.restarts} restarts)")
            assertEquals(0, host.worker.restarts, "a viewport-only snapshot cancelled the parse")
            host.settle()
            val vp = Syntax.snapshot(host.state)!!.viewport
            assertWindowMatchesFresh(host, vp.first, vp.last + 1)
        } finally {
            host.close()
        }
    }

    /** Typing every 120 ms during a long first parse: it finishes, then the edits apply incrementally. */
    @Test
    fun typingDuringALongFirstParseStillGetsSpans() = runSuspendTest {
        val text = slowKotlin()
        val host = Host(text, "kotlin", backend)
        try {
            host.viewport(0 until 3000)
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            var k = 0
            while ((host.spans.isEmpty || k < 5) && t0.elapsedNow().inWholeSeconds < 60) {
                host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "// typed $k\n"))))
                k++
                kotlinx.coroutines.delay(120)
            }
            println("LIVELOCK type lines=${host.state.doc.lineCount} firstSpansAfter=${t0.elapsedNow().inWholeMilliseconds}ms keystrokes=$k restarts=${host.worker.restarts}")
            assertFalse(host.spans.isEmpty, "no spans after ${t0.elapsedNow()} of typing (${host.worker.restarts} restarts)")
            host.settle()
            assertWindowMatchesFresh(host, 0, 3000)
            assertNull(host.worker.lastError)
        } finally {
            host.close()
        }
    }
}
