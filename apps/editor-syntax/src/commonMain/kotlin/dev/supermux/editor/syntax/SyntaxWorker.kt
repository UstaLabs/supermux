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
import kotlin.concurrent.Volatile

/** When a document is plain text instead: too big, a line too long, or a parse too slow. */
data class SyntaxLimits(
    /** A parse runs in slices this long; between slices a newer snapshot cancels it (and it restarts on that one). */
    val parseSliceMicros: Long = 50_000,
    /** One document version's whole parse (every layer) may take this long; beyond it, syntax is off. */
    val parseBudgetMicros: Long = 10_000_000,
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
 * below, and [dispatch]es a [Syntax.spans] effect from the worker's thread.
 *
 * The host must deliver those dispatches to the state IN ORDER (FIFO, e.g. by posting each to its
 * UI thread's queue); the field also ignores an update older than one it already applied.
 *
 * The worker owns every native object (parsers, trees; the queries are the backend's) and frees
 * them all when its scope is cancelled or [close] is called.
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
    private val job: Job = scope.launch(dispatcher) { loop() }.also { j ->
        // cancelled before it ever ran (so no finally ran): idle() must still return
        j.invokeOnCompletion { done.value = Long.MAX_VALUE }
    }

    /** The last failure of the loop (it carries on with the next snapshot), for diagnostics. */
    @Volatile var lastError: Throwable? = null
        private set

    /** Parses abandoned for a newer snapshot (tests). */
    @Volatile internal var restarts = 0
        private set

    /** Milliseconds per step of the last cycle (diagnostics, benchmarks). */
    val lastCycle: MutableMap<String, Double> = LinkedHashMap()

    private inline fun <T> step(name: String, block: () -> T): T {
        val t = kotlin.time.TimeSource.Monotonic.markNow()
        try { return block() } finally { lastCycle[name] = t.elapsedNow().inWholeNanoseconds / 1e6 }
    }

    /** Called between two parse slices, on the worker's thread (tests). */
    internal var onSlice: (() -> Unit)? = null

    fun onState(state: EditorState) {
        val snapshot = Syntax.snapshot(state) ?: return
        var seq = 0L
        posted.update { seq = it + 1; seq }
        inbox.trySend(seq to snapshot)
    }

    /** Suspends until every state posted so far has been handled, or the worker stopped (tests, benchmarks). */
    suspend fun idle() {
        val target = posted.value
        done.first { it >= target }
    }

    /** Stop and free every native handle (asynchronously, on the worker's thread; [join] waits). */
    fun close() {
        job.cancel()
        inbox.close()
    }

    suspend fun join() = job.join()

    // ------------------------------------------------------------------ worker thread only --

    private var epoch = -1L
    private var language: String? = null
    private var highlighter: Highlighter? = null
    private var parsed: ParsedDocument? = null
    /** The text [parsed]'s trees carry every edit up to (they may still need a reparse: [stale]). */
    private var parsedDoc: Rope? = null
    private var parsedVersion = -1L
    private var stale = false
    private var sent: Triple<Long, Long, IntRange>? = null
    private var off = false
    private var longLine: Pair<Long, Boolean>? = null // (version, answer) for this epoch

    private suspend fun loop() {
        try {
            for ((seq, snapshot) in inbox) {
                try {
                    process(snapshot, seq)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ParseCancelled) {
                    restarts++ // the newer snapshot is already in the inbox
                } catch (e: Throwable) {
                    lastError = e
                    resetParse()
                }
                done.value = seq
            }
        } finally {
            reset() // on the worker's thread, cancelled or not: every native handle goes
            done.value = Long.MAX_VALUE
        }
    }

    private fun resetParse() {
        parsed?.close()
        parsed = null
        parsedDoc = null
        parsedVersion = -1
        stale = false
        sent = null
    }

    private fun reset() {
        resetParse()
        highlighter?.close()
        highlighter = null
        longLine = null
    }

    private suspend fun process(s: SyntaxSnapshot, seq: Long) {
        if (s.epoch != epoch || s.language != language) {
            // another document (a replaced state starts at version 0 again), or another language
            reset()
            epoch = s.epoch
            language = s.language
            off = false
        }
        val lang = language ?: return
        if (s.syntaxOff) { off = true; return }
        if (off) return
        if (sent == Triple(s.epoch, s.version, s.viewport) && parsedDoc === s.doc) return // nothing new
        if (s.doc.length > limits.maxDocumentLength || step("longLines") { hasLongLine(s) }) return turnOff(s)

        val h = highlighter ?: run {
            try {
                backend.ensureLanguage(lang)
            } catch (e: SyntaxException) {
                lastError = e
                return turnOff(s) // the document's own grammar is unavailable: plain text for good
            }
            Highlighter(backend, lang, registry).also {
                it.sliceMicros = limits.parseSliceMicros
                it.budgetMicros = limits.parseBudgetMicros
                it.onSlice = { onSlice?.invoke() }
                highlighter = it
            }
        }
        val text = RopeText(s.doc)
        // Bring the trees up to this version: the logged edits, or (another text at the same version,
        // a gap in the log) a parse from scratch.
        val cur = parsed
        if (cur != null && (s.version != parsedVersion || s.doc !== parsedDoc)) {
            if (s.version > parsedVersion && step("edits") { applyEdits(cur, s) }) {
                parsedDoc = s.doc
                parsedVersion = s.version
                stale = true
            } else {
                resetParse()
            }
        }
        val cancel = { posted.value > seq }
        while (parsed == null || stale) {
            val next = try {
                step("parse") { h.parse(text, s.doc.length, parsed, text, cancel) }
            } catch (e: SyntaxException) {
                if (e.status != SyntaxStatus.TIMEOUT) throw e
                return turnOff(s)
            }
            parsed?.close()
            parsed = next
            parsedDoc = s.doc
            parsedVersion = s.version
            stale = false
            // injected languages met before they were loaded: load them, then parse again (no edits)
            val pending = h.pendingLanguages.toList()
            h.pendingLanguages.clear()
            for (l in pending) {
                try { backend.ensureLanguage(l) } catch (e: SyntaxException) { h.failedLanguages += l; lastError = e }
            }
            if (pending.any { it !in h.failedLanguages }) stale = true
        }
        val doc = parsed!!
        val vs = if (s.viewport.isEmpty()) 0 else s.viewport.first
        val ve = if (s.viewport.isEmpty()) limits.defaultViewportLength else s.viewport.last + 1
        val screen = maxOf(0, ve - vs)
        val start = maxOf(0, minOf(vs, s.doc.length) - screen)
        val end = minOf(s.doc.length, ve + screen)
        val spans = step("spans") { RangeSet.of(h.spans(doc, start, end, text)) }
        val folds = step("folds") { h.folds(doc, start, end, text) }
        sent = Triple(s.epoch, s.version, s.viewport)
        dispatch(TransactionSpec(effects = listOf(Syntax.spans.of(SyntaxSpansUpdate(s.version, start, end, spans, folds, epoch = s.epoch)))))
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

    /** Cached per version: a viewport-only snapshot does not rescan. */
    private fun hasLongLine(s: SyntaxSnapshot): Boolean {
        longLine?.let { (v, answer) -> if (v == s.version) return answer }
        val doc = s.doc
        val answer = if (doc.length <= limits.maxLineLength) false else {
            // Checked fully the first time; after that only the lines the edits since touched.
            val known = longLine
            val later = s.log.filter { known != null && it.version > known.first }
            val lines: List<IntRange> = if (known == null || known.second || later.isEmpty() || later.size.toLong() != s.version - known.first) {
                listOf(0 until doc.lineCount)
            } else {
                val all = later.drop(1).fold(later[0].changes) { acc, v -> acc.compose(v.changes) }
                all.iterChanges().map { doc.lineIndexAt(it.fromB)..doc.lineIndexAt(it.toB) }
            }
            lines.any { r ->
                r.any { i ->
                    val start = doc.lineStart(i)
                    val end = if (i + 1 < doc.lineCount) doc.lineStart(i + 1) - 1 else doc.length
                    end - start > limits.maxLineLength
                }
            }
        }
        longLine = s.version to answer
        return answer
    }

    private fun turnOff(s: SyntaxSnapshot) {
        off = true
        reset()
        dispatch(TransactionSpec(effects = listOf(Syntax.spans.of(
            SyntaxSpansUpdate(s.version, 0, s.doc.length, RangeSet.empty(), IntArray(0), syntaxOff = true, epoch = s.epoch),
        ))))
    }

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    }
}
