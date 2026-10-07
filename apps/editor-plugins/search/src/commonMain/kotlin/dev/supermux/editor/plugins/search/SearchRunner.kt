package dev.supermux.editor.plugins.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * The panel's search work, off the keystroke: what the find field commits, the finds of its buttons
 * and keys, and the match count. On a document of at most [Search.ASYNC_LIMIT] units a find is done
 * at once; on a bigger one it runs in [scope] in slices of at most [sliceMs] of work, giving the
 * thread back between them ([searching] meanwhile), the selection jumping when the match is found; a
 * newer find cancels an older one. The count ([info]; null while counting: "…") is sliced the same
 * way, restarted when the query changes, and after a document edit or a selection move only once
 * they have settled for [RECOUNT_MS] (at every size).
 *
 * Main-thread cost per keystroke in the find field, 10 MB, no match anywhere: `SearchMainThreadTest`
 * (JVM, wasm): every slice stays under 16 ms.
 */
/** Give the UI thread back for a moment (the web: a real event-loop turn, see the wasm actual). */
internal expect suspend fun giveBackThread()

internal class SearchRunner(
    private val editor: CommandTarget,
    private val scope: CoroutineScope,
    private val sliceMs: Long = 3,
) {
    /** "3 of 17", or null while the count runs on a big document. */
    var info: MatchInfo? by mutableStateOf(null)
        private set

    /** A sliced find is running (the panel says "searching…"). */
    var searching: Boolean by mutableStateOf(false)
        private set

    /** What the panel last committed from its fields: the fields sync from the query only when it differs (a change from outside). */
    var committedSearch: String = Search.query(editor.state).search
    var committedReplace: String = Search.query(editor.state).replace

    private var findJob: Job? = null
    private var countJob: Job? = null
    private var counted: Triple<Rope, SearchQuery, EditorSelection>? = null
    private var detach: (() -> Unit)? = null
    private var mark = TimeSource.Monotonic.markNow()

    /** Hear the editor (the count follows it, a key's find on a big document comes here). */
    fun attach() {
        val v = editor as? EditorView
        val removeTr = v?.addListener { tr -> onTransaction(tr) }
        val removeReplace = v?.addReplaceListener { recount(debounce = false) }
        editor.dispatch(TransactionSpec(effects = listOf(Search.runnerAttached.of(true))))
        detach = { removeTr?.invoke(); removeReplace?.invoke() }
        recount(debounce = false)
    }

    fun close() {
        detach?.invoke(); detach = null
        findJob?.cancel(); countJob?.cancel()
        editor.dispatch(TransactionSpec(effects = listOf(Search.runnerAttached.of(false))))
    }

    private fun onTransaction(tr: Transaction) {
        for (e in tr.effects) e.valueIf(Search.requestSearch)?.let { find(it) }
        // A sliced find still running: an edit re-runs it ONCE from the new state (its positions
        // are stale); the user moving the caret cancels it (their move wins).
        val running = inFlight
        if (running != null && findJob?.isActive == true && !tr.isUserEvent("select.search")) {
            when {
                tr.docChanged && !running.rerun -> { val m = tr.state.selection.main; run(running.dir, m.from, m.to, rerun = true) }
                tr.docChanged -> findJob?.cancel()
                tr.selectionSet -> findJob?.cancel()
            }
        }
        val before = Search.query(tr.startState)
        val now = Search.query(tr.state)
        when {
            before != now -> recount(debounce = false)
            tr.docChanged -> recount(debounce = true)
            tr.startState.selection != tr.state.selection -> recount(debounce = tr.state.doc.length > Search.ASYNC_LIMIT)
        }
    }

    // --------------------------------------------------------------------------- the query --

    /** The find field's text as the query, nothing searched ([commitFind] searches). */
    fun setSearch(text: String) {
        committedSearch = text
        val q = Search.query(editor.state)
        if (q.search != text) editor.dispatch(TransactionSpec(effects = listOf(Search.setQueryEffect.of(q.copy(search = text)))))
    }

    fun setReplace(text: String) {
        committedReplace = text
        val q = Search.query(editor.state)
        if (q.replace != text) editor.dispatch(TransactionSpec(effects = listOf(Search.setQueryEffect.of(q.copy(replace = text)))))
    }

    /**
     * The find field's text is the query: set it, then select the first match at or after the main
     * selection's start (VS Code's incremental search; CM6 only marks), scrolled into view.
     */
    fun commitFind(text: String) {
        val old = Search.query(editor.state)
        setSearch(text)
        if (text == old.search) return
        val main = editor.state.selection.main
        run(dir = 0, main.from, main.to)
    }

    /** The next (+1) or previous (-1) match from the main selection (the buttons, Enter, the keys). */
    fun find(dir: Int) {
        val q = Search.query(editor.state)
        if (!q.valid) { Search.openSearchPanel.run(editor); return }
        val main = editor.state.selection.main
        run(dir, main.from, main.to)
    }

    /** The sliced find in flight: its direction, and whether it is already the re-run after an edit. */
    private class InFlight(val dir: Int, val rerun: Boolean)
    private var inFlight: InFlight? = null

    private fun run(dir: Int, from: Int, to: Int, rerun: Boolean = false) {
        findJob?.cancel()
        inFlight = null
        searching = false
        val st = editor.state
        val q = Search.query(st)
        if (!q.valid) return
        val doc = st.doc
        if (doc.length <= Search.ASYNC_LIMIT) {
            select(when (dir) {
                0 -> q.nextImpl(doc, from, from, exclude = false) {}
                1 -> q.nextMatch(doc, from, to)
                else -> q.prevMatch(doc, from, to)
            })
            return
        }
        searching = true
        val selection = st.selection
        inFlight = InFlight(dir, rerun)
        val job = scope.launch {
            mark = TimeSource.Monotonic.markNow()
            val m = when (dir) {
                0 -> q.nextImpl(doc, from, from, exclude = false, SearchCursors.SLICED_WINDOW) { pause() }
                1 -> q.nextMatchSliced(doc, from, to) { pause() }
                else -> q.prevMatchSliced(doc, from, to) { pause() }
            }
            // Only for the document, query AND selection it was asked for: a caret the user moved
            // meanwhile is never overridden (an edit re-ran it; a newer find cancelled it).
            val now = editor.state
            if (now.doc === doc && Search.query(now) == q && now.selection == selection) select(m)
        }
        findJob = job
        job.invokeOnCompletion { if (findJob === job) { searching = false; inFlight = null } }
    }

    private fun select(m: SearchMatch?) {
        if (m == null) return
        val sel = editor.state.selection
        if (sel.ranges.size == 1 && sel.main.from == m.from && sel.main.to == m.to) return
        editor.dispatch(TransactionSpec(selection = EditorSelection.single(m.from, m.to), scrollIntoView = true, userEvent = "select.search"))
    }

    /** Give the thread back once [sliceMs] of work has been done since the last time. */
    private suspend fun pause() {
        if (mark.elapsedNow().inWholeMilliseconds >= sliceMs) {
            giveBackThread()
            mark = TimeSource.Monotonic.markNow()
        }
    }

    // --------------------------------------------------------------------------- the count --

    private fun recount(debounce: Boolean) {
        countJob?.cancel()
        val st = editor.state
        val q = Search.query(st)
        val key = Triple(st.doc, q, st.selection)
        if (key == counted && info != null) return
        val main = st.selection.main
        if (!debounce && st.doc.length <= Search.ASYNC_LIMIT) {
            info = q.info(st.doc, main.from, main.to)
            counted = key
            return
        }
        countJob = scope.launch {
            if (debounce) delay(RECOUNT_MS)
            val now = editor.state
            val nq = Search.query(now)
            val nm = now.selection.main
            if (now.doc.length <= Search.ASYNC_LIMIT) {
                info = nq.info(now.doc, nm.from, nm.to)
            } else {
                info = null
                mark = TimeSource.Monotonic.markNow()
                info = nq.infoSliced(now.doc, nm.from, nm.to) { pause() }
            }
            counted = Triple(now.doc, nq, now.selection)
        }
    }

    /** True when no find and no count is running (tests). */
    val idle: Boolean get() = findJob?.isActive != true && countJob?.isActive != true

    companion object {
        /** How long the document and the selection must stay still before the count starts again. */
        const val RECOUNT_MS = 120L
    }
}
