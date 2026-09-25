package dev.supermux.editor.syntax

import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** When a document is plain text instead: too big, a line too long, or a parse too slow. */
data class SyntaxLimits(
    /** Per parse of each layer; hitting it turns syntax off for the document. */
    val parseTimeoutMicros: Long = 200_000,
    /** UTF-16 units (5 MiB). */
    val maxDocumentLength: Int = 5 * 1024 * 1024,
    val maxLineLength: Int = 20_000,
    /** Spans are computed for the viewport; this many units when the surface has not said yet. */
    val defaultViewportLength: Int = 16_384,
)

/**
 * The background syntax loop for one document. The host calls [onState] after every transaction
 * (cheap, never blocks); the worker, on its own single-threaded dispatcher, brings its parse up to
 * date with the edits since its last parse, highlights the viewport plus one screen above and
 * below, and [dispatch]es a [Syntax.spans] effect (from the worker's thread: the host hops to its
 * UI thread itself).
 *
 * The worker owns every native object (parsers, trees, queries) and frees them all when its scope
 * is cancelled or [close] is called.
 */
class SyntaxWorker(
    private val backend: SyntaxBackend,
    private val registry: LanguageRegistry,
    scope: CoroutineScope,
    private val dispatch: (TransactionSpec) -> Unit,
    private val limits: SyntaxLimits = SyntaxLimits(),
    dispatcher: CoroutineDispatcher = defaultDispatcher(),
) {
    private val inbox = Channel<Pair<Long, SyntaxSnapshot>>(Channel.CONFLATED)
    private val posted = MutableStateFlow(0L)
    private val done = MutableStateFlow(0L)
    private val job: Job = scope.launch(dispatcher) { loop() }

    /** The last failure of the loop (it carries on with the next snapshot), for diagnostics. */
    var lastError: Throwable? = null
        private set

    fun onState(state: EditorState) {
        val snapshot = Syntax.snapshot(state) ?: return
        var seq = 0L
        posted.update { seq = it + 1; seq }
        inbox.trySend(seq to snapshot)
    }

    /** Suspends until every state posted so far has been handled (tests and benchmarks). */
    suspend fun idle() {
        val target = posted.value
        done.first { it >= target || job.isCompleted }
    }

    /** Stop and free every native handle (asynchronously, on the worker's thread; [join] waits). */
    fun close() {
        job.cancel()
        inbox.close()
    }

    suspend fun join() = job.join()

    // ------------------------------------------------------------------ worker thread only --

    private var language: String? = null
    private var highlighter: Highlighter? = null
    private var parsed: ParsedDocument? = null
    private var parsedDoc: Rope? = null
    private var parsedVersion = -1L
    private var sent: Pair<Long, IntRange>? = null
    private var off = false

    private suspend fun loop() {
        try {
            for ((seq, snapshot) in inbox) {
                try {
                    process(snapshot)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = e
                    reset()
                }
                done.value = seq
            }
        } finally {
            reset() // on the worker's thread, cancelled or not: every native handle goes
        }
    }

    private fun reset() {
        parsed?.close()
        parsed = null
        parsedDoc = null
        parsedVersion = -1
        highlighter?.close()
        highlighter = null
        sent = null
    }

    private fun process(s: SyntaxSnapshot) {
        if (s.language != language) {
            reset()
            language = s.language
            off = false
        }
        val lang = language ?: return
        if (s.syntaxOff) { off = true; return }
        if (off) return
        if (sent == s.version to s.viewport) return // nothing new (e.g. the state our own update made)
        if (s.doc.length > limits.maxDocumentLength || hasLongLine(s)) return turnOff(s)

        val h = highlighter ?: Highlighter(backend, lang, registry).also {
            it.timeoutMicros = limits.parseTimeoutMicros
            highlighter = it
        }
        val text = TextSource { i -> if (i >= s.doc.length) "" else s.doc.chunkAt(i) }
        if (parsed == null || parsedVersion != s.version) {
            val previous = parsed?.takeIf { applyEdits(it, s) }
            if (previous == null) { parsed?.close(); parsed = null }
            val next = try {
                h.parse(text, s.doc.length, previous)
            } catch (e: SyntaxException) {
                if (e.status != SyntaxStatus.TIMEOUT) throw e
                return turnOff(s)
            }
            parsed?.close()
            parsed = next
            parsedDoc = s.doc
            parsedVersion = s.version
        }
        val doc = parsed!!
        val vs = if (s.viewport.isEmpty()) 0 else s.viewport.first
        val ve = if (s.viewport.isEmpty()) limits.defaultViewportLength else s.viewport.last + 1
        val screen = maxOf(0, ve - vs)
        val start = maxOf(0, minOf(vs, s.doc.length) - screen)
        val end = minOf(s.doc.length, ve + screen)
        val spans = RangeSet.of(h.spans(doc, start, end, text))
        val folds = h.folds(doc, start, end, text)
        sent = s.version to s.viewport
        dispatch(TransactionSpec(effects = listOf(Syntax.spans.of(SyntaxSpansUpdate(s.version, start, end, spans, folds)))))
    }

    /**
     * Record every edit since [parsedVersion] in [doc]'s trees: false when the snapshot's log no
     * longer reaches back that far (then the document is parsed from scratch).
     */
    private fun applyEdits(doc: ParsedDocument, s: SyntaxSnapshot): Boolean {
        val from = parsedDoc ?: return false
        val later = s.log.filter { it.version > parsedVersion }
        if (later.size.toLong() != s.version - parsedVersion || later.isEmpty()) return false
        var before = from
        for ((i, entry) in later.withIndex()) {
            val after = if (i == later.size - 1) s.doc else entry.changes.apply(before)
            for (e in textEditsFor(entry.changes, before, after)) doc.edit(e)
            before = after
        }
        return true
    }

    private fun hasLongLine(s: SyntaxSnapshot): Boolean {
        val doc = s.doc
        if (doc.length <= limits.maxLineLength) return false
        // Checked fully the first time; after that only the lines the edits since touched.
        val from = parsedDoc
        val later = s.log.filter { it.version > parsedVersion }
        val lines: List<IntRange> = if (from == null || later.isEmpty() || later.size.toLong() != s.version - parsedVersion) {
            listOf(0 until doc.lineCount)
        } else {
            // every edit since, in the final document's coordinates
            val all = later.drop(1).fold(later[0].changes) { acc, v -> acc.compose(v.changes) }
            all.iterChanges().map { doc.lineIndexAt(it.fromB)..doc.lineIndexAt(it.toB) }
        }
        for (i in lines.flatten()) {
            val start = doc.lineStart(i)
            val end = if (i + 1 < doc.lineCount) doc.lineStart(i + 1) - 1 else doc.length
            if (end - start > limits.maxLineLength) return true
        }
        return false
    }

    private fun turnOff(s: SyntaxSnapshot) {
        off = true
        reset()
        dispatch(TransactionSpec(effects = listOf(Syntax.spans.of(
            SyntaxSpansUpdate(s.version, 0, s.doc.length, RangeSet.empty(), IntArray(0), syntaxOff = true),
        ))))
    }

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
