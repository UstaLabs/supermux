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
import kotlinx.coroutines.runBlocking
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
private class Host(
    text: String,
    language: String?,
    backend: SyntaxBackend,
    limits: SyntaxLimits = SyntaxLimits(),
) {
    val ui = Dispatchers.Default.limitedParallelism(1)
    val scope = CoroutineScope(SupervisorJob())
    var state: EditorState = EditorState.create(text, extensions = Syntax.extension(language))
        private set
    val worker = SyntaxWorker(backend, LanguageRegistry.default, scope, { spec -> scope.launch(ui) { apply(spec) } }, limits)

    private fun apply(spec: TransactionSpec) {
        state = state.update(spec).state
        worker.onState(state)
    }

    suspend fun dispatch(spec: TransactionSpec) = withContext(ui) { apply(spec) }
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
    fun typingMapsSpansImmediatelyThenReplacesThem() = runBlocking {
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
    fun viewportChangeRequestsNewSpans() = runBlocking {
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
    fun hugeDocumentIsPlainText() = runBlocking {
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
    fun timeoutTurnsSyntaxOff() = runBlocking {
        val host = Host(HighlightSamples.KOTLIN.repeat(300), "kotlin", backend, SyntaxLimits(parseTimeoutMicros = 1))
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
    fun closeFreesEveryTree() = runBlocking {
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
    fun plainTextParsesNothing() = runBlocking {
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
    fun randomEditSoak() = runBlocking {
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
    fun randomEditSoakWithInjections() = runBlocking {
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
}
