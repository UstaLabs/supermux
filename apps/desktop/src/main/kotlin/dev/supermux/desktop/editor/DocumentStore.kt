// The open-document layer, split out of EditorState.kt (which still delegates to it, so every call
// site is unchanged). Documents are keyed by path and owned HERE, exactly once per path — the tab
// ORDER and the active selection stay in [EditorState], so a later phase can give two panes their
// own tab strips over the SAME [Document] instances instead of two copies of the file text.
//
// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt — keep in
// sync until a shared UI module exists. This mirrors Android 1:1 (openFile/openFileAtLine/closeTab/
// updateContent/saveActive/changedPaths/markChanged/isStale/reload, renamed here to open/openAtLine/
// close/update/save/reload) EXCEPT for three DELIBERATE M3-T4 divergences hardened for the
// over-the-network fsRead (Android does its fsRead in-process, so these races don't bite there —
// desktop's do once the read is a broker round-trip; each is backport-worthy and flagged in the
// task report):
//   (A) [open] has an in-flight guard (`if (loadingPath == path) return`) so two taps on the same
//       not-yet-loaded file can't launch two loads → two duplicate tabs. The success/failure
//       branches also drop their result when the path was closed mid-load (see [cancelledPaths]).
//   (B) [openAtLine] guards its pending-reveal poll with a monotonic [revealNonce] (the iOS
//       EditorState.swift:121-129 pattern) so a superseded reveal never fires on a document that
//       arrived from a LATER open, and logs when the poll gives up (superseded or the 1s timeout).
//       Android instead polls unconditionally — desktop deliberately diverges toward the iOS-fixed
//       semantics here (backport candidate).
//   (C) [close] cancels an in-flight load for the closed path so its late fsRead result can't
//       resurrect the document the user just closed.
// Plus one shape that disagrees with the Swift test-reference file (Android is the implementation
// reference per the M3 Task 3 brief; flagged in the task report):
//   - `reload(path, fsRead)` takes its own `fsRead` parameter rather than reusing the
//     constructor-injected one (iOS's `reload(path)` has no such parameter) — every Android call
//     site happens to pass the same closure the state was built with, so the extra parameter is
//     redundant there too; preserved here for exact parity, not because it's good API shape.
package dev.supermux.desktop.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One open file's text + per-view scroll/reveal state. Owned by [DocumentStore], never duplicated
 *  per tab: the tab strip holds references to these, so N tabs over one path share one buffer. */
class Document(path: String, content: String) {
    val path = path
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var scrollTop by mutableStateOf(0)
    var revealLine by mutableStateOf<Pair<Int, Int?>?>(null)
}

class DocumentStore(
    private val fsRead: suspend (String) -> Result<String>,
    private val fsWrite: suspend (String, String) -> Boolean,
    private val scope: CoroutineScope,
) {
    /** Open documents by path. A snapshot map so a composable reading [get]/[isDirty] is
     *  invalidated when a document appears or is closed, exactly as the old `tabs` list was. */
    private val docs = mutableStateMapOf<String, Document>()

    var loadingPath by mutableStateOf<String?>(null)
    var loadError by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** Workdir-relative paths the broker reported changed on disk (fs_changed) → reload banner. */
    var changedPaths by mutableStateOf(setOf<String>())

    /** Paths whose in-flight load was cancelled by [close] — the load result is dropped, never
     *  re-added, so a close during a slow (networked) fsRead can't resurrect the closed tab (M3-T4). */
    private val cancelledPaths = mutableSetOf<String>()

    /** Monotonic guard for [openAtLine]'s pending-reveal poll (iOS revealNonce parity). A poll
     *  only applies its reveal while it is still the newest request; a superseded poll logs + drops. */
    private var revealNonce = 0

    /**
     * Host hook fired whenever [open] resolves a document (already-open fast path or a completed
     * read). The tab ORDER and the active selection live in [EditorState], so the host appends the
     * document to its tab list when absent and activates it: unconditionally when [current] (this
     * open still owns the loading gate, or the document was already open), otherwise only as the
     * "nothing is selected" fallback. See EditorState.init for the gating rationale.
     */
    var onOpened: (doc: Document, current: Boolean) -> Unit = { _, _ -> }

    fun get(path: String): Document? = docs[path]

    fun isDirty(path: String): Boolean {
        val doc = docs[path] ?: return false
        return doc.content != doc.savedContent
    }

    fun open(path: String) {
        docs[path]?.let {
            onOpened(it, true)
            loadError = null
            return
        }
        // In-flight guard (M3-T4 divergence A): a second open of the SAME still-loading path is a
        // no-op, so two quick taps can't launch two networked fsRead loads → two duplicate tabs.
        if (loadingPath == path) return
        cancelledPaths.remove(path) // a fresh open supersedes a prior close-cancel of this path
        loadingPath = path
        loadError = null
        scope.launch {
            fsRead(path)
                .onSuccess { content ->
                    // Dropped if the document was closed mid-load (divergence C) — never resurrect it.
                    if (cancelledPaths.remove(path)) {
                        if (loadingPath == path) loadingPath = null
                        return@onSuccess
                    }
                    // The document is always added (a valid file the user opened), but activation +
                    // spinner-clear defer to whichever open is CURRENT: `loadingPath` is single-slot,
                    // so two overlapping cross-path loads both complete — gating on `loadingPath ==
                    // path` keeps the LAST-opened file active (not the last-to-return over the
                    // network) and stops an earlier load from wiping a newer one's loading indicator.
                    val doc = docs.getOrPut(path) { Document(path, content) }
                    val current = loadingPath == path
                    onOpened(doc, current)
                    if (current) loadingPath = null
                }
                .onFailure { err ->
                    if (cancelledPaths.remove(path)) {
                        if (loadingPath == path) loadingPath = null
                        return@onFailure
                    }
                    // Only surface the error (and clear the spinner) if this is still the current
                    // open — a superseded load's failure must not stomp the newer load in progress.
                    if (loadingPath == path) {
                        loadError = err.message ?: "Could not open file"
                        loadingPath = null
                    }
                }
        }
    }

    /**
     * Open [path] and, once present, request a scroll to [line] (1-indexed). The reveal is guarded by
     * a monotonic [revealNonce] (iOS EditorState.swift:121-129 parity): a poll only applies its reveal
     * while it remains the newest request, so a stale reveal from an earlier call can't land on a
     * document that a later navigation produced. Logs when a poll gives up (superseded or the 1s
     * timeout) — the log lines keep the old `[EditorState] openFileAtLine` wording because they are
     * grepped in captured desktop logs; the method rename is internal.
     */
    fun openAtLine(path: String, line: Int?, endLine: Int?) {
        open(path)
        if (line == null) return
        val myNonce = ++revealNonce
        // open may add the document synchronously (cache hit / a non-suspending fsRead) or after the
        // read completes. Set on the document when it exists, else poll briefly for it to arrive.
        val doc = docs[path]
        if (doc != null) {
            if (myNonce == revealNonce) doc.revealLine = line to endLine
            return
        }
        scope.launch {
            repeat(50) {
                if (myNonce != revealNonce) {
                    println("[EditorState] openFileAtLine('$path') reveal superseded — dropping stale reveal")
                    return@launch
                }
                val d = docs[path]
                if (d != null) {
                    if (myNonce == revealNonce) d.revealLine = line to endLine
                    return@launch
                }
                delay(20)
            }
            println("[EditorState] openFileAtLine('$path') gave up after 1s — tab never arrived")
        }
    }

    /**
     * Drop [path]'s document. Cancels an in-flight load for it (divergence C) so its late result is
     * dropped and the just-closed tab can't reappear. The cancel is marked BEFORE the document
     * lookup/removal, because on a close during a cold open there is no document yet, only a
     * [loadingPath].
     */
    fun close(path: String) {
        if (loadingPath == path) {
            cancelledPaths.add(path)
            loadingPath = null
        }
        docs.remove(path)
    }

    fun update(path: String, content: String) {
        docs[path]?.content = content
    }

    fun save(doc: Document) {
        if (saving) return
        saving = true
        scope.launch {
            if (fsWrite(doc.path, doc.content)) {
                doc.savedContent = doc.content
            }
            saving = false
        }
    }

    // ── Live file-watch reload (ports EditorState.swift:79-84, 130-144) ─────────

    /** Record disk-change notifications (workdir-relative paths, leading slash optional). */
    fun markChanged(paths: List<String>) {
        changedPaths = changedPaths + paths.map(::normPath)
    }

    fun isStale(path: String): Boolean = normPath(path) in changedPaths

    private fun normPath(p: String): String = p.removePrefix("/")

    /**
     * Re-read a document from disk and clear its stale flag (parity EditorState.swift:130-144).
     *
     * Same networked-read discipline as [open] (M3-T4): the completion only clears [loadingPath]
     * when it still owns the gate — an unconditional clear would stomp a concurrent `open(B)`'s gate
     * and leave B's document added-but-never-activated. And a [close] during the reload marks the
     * path cancelled; the completion consumes that marker and DROPS the result (the document is
     * gone), so the stale entry can't leak into [cancelledPaths] forever.
     */
    suspend fun reload(path: String, fsRead: suspend (String) -> Result<String>) {
        val doc = docs[path] ?: return
        loadingPath = path
        val result = fsRead(path)
        // close-during-reload: consume the cancel marker and drop the result — the document was
        // removed, so applying content/clearing the stale flag would act on a ghost.
        if (cancelledPaths.remove(path)) {
            if (loadingPath == path) loadingPath = null
            return
        }
        result
            .onSuccess { content ->
                doc.content = content
                doc.savedContent = content
                changedPaths = changedPaths - normPath(path)
            }
            .onFailure { err -> loadError = err.message ?: "Could not reload file" }
        if (loadingPath == path) loadingPath = null
    }
}
