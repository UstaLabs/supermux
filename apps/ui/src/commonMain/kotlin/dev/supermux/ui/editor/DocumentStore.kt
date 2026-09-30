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
package dev.supermux.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One open file's text + per-view scroll/reveal state. Owned by [DocumentStore], never duplicated
 *  per tab: the tab strip holds references to these, so N tabs over one path share one buffer.
 *
 *  With the native editor (M5) the text IS the [native] view's rope: [content] is derived from it
 *  (read it for a save or the preview, not per keystroke) and [isDirty] compares ropes. Without
 *  one (tests) it is a plain String as before. [content] never holds a
 *  `\r\n`: the store normalizes on load and restores the ending ([crlf]) on save (spec §8). */
class Document(path: String, content: String, crlf: Boolean = false) {
    val path = path
    private var plain by mutableStateOf(content)
    var content: String
        get() = native?.text() ?: plain
        set(value) {
            val n = native
            if (n != null) n.replaceText(value) else plain = value
        }
    var savedContent by mutableStateOf(content)
    /** Saved back with `\r\n` line endings (the file had them). */
    var crlf by mutableStateOf(crlf)
    var scrollTop by mutableStateOf(0)
    var revealLine by mutableStateOf<Pair<Int, Int?>?>(null)

    /** The native editor's views of this document, once a pane asked ([DocumentStore.nativeFor]). */
    var native: NativeDocument? by mutableStateOf(null)
        internal set

    val isDirty: Boolean get() = native?.isDirty ?: (plain != savedContent)

    /** The native views go (the store's owner left): the text they held stays as a plain String. */
    internal fun dropNative() {
        val n = native ?: return
        plain = n.text()
        native = null
        n.dispose()
    }
}

class DocumentStore(
    private val fsRead: suspend (String) -> Result<String>,
    private val fsWrite: suspend (String, String) -> Boolean,
    private val scope: CoroutineScope,
) : WatchedDocuments {
    /** Open documents by path. A snapshot map so a composable reading [get]/[isDirty] is
     *  invalidated when a document appears or is closed, exactly as the old `tabs` list was. */
    private val docs = mutableStateMapOf<String, Document>()

    var loadingPath by mutableStateOf<String?>(null)
    var loadError by mutableStateOf<String?>(null)
    /** [loadError] is the host refusing a binary file (415): the pane offers to open it elsewhere. */
    var loadErrorBinary by mutableStateOf(false)
    /** Paths whose save is in flight: one write per document at a time, other documents unaffected. */
    private var savingPaths by mutableStateOf(setOf<String>())

    /** Some document is being saved (the header spinner). Setting it false forgets every in-flight guard. */
    var saving: Boolean
        get() = savingPaths.isNotEmpty()
        set(value) { if (!value) savingPaths = emptySet() }

    fun isSaving(path: String): Boolean = path in savingPaths

    /** Every open document's path. */
    val paths: Set<String> get() = docs.keys.toSet()

    /** Paths some pane has shown ([retainViewed]): only those can have lost their last pane. */
    private val everViewed = mutableSetOf<String>()

    /**
     * The panes that show documents now are the ones for [viewed] (a workspace's file views): close
     * every document whose LAST pane went (its view, syntax worker and LSP didOpen go with it). A
     * document with unsaved edits stays open, so reopening its tab finds them; a document no pane
     * ever showed yet (an open still resolving its view) is left alone. Returns what was closed.
     */
    fun retainViewed(viewed: Set<String>): List<String> {
        everViewed += viewed
        val gone = docs.keys.filter { it in everViewed && it !in viewed && docs[it]?.isDirty != true }
        for (p in gone) { close(p); everViewed -= p }
        return gone
    }

    /** Workdir-relative paths changed on disk behind an open document → reload banner. Fed by
     *  [dev.supermux.ui.files.FileStaleWatcher]'s folder subscriptions. */
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

    /** Paths of the open documents (a snapshot read: a composable reading it follows opens/closes). */
    override val openPaths: Set<String> get() = docs.keys.toSet()

    private val writeObservers = mutableListOf<WatchedDocuments.WriteObserver>()

    override fun observeWrites(observer: WatchedDocuments.WriteObserver): () -> Unit {
        writeObservers += observer
        return { writeObservers -= observer }
    }

    fun isDirty(path: String): Boolean = docs[path]?.isDirty == true

    // ── The native editor (M5) ──────────────────────────────────────────────────────────────

    /**
     * Set by the store's owner to give documents native views: panes then call [nativeFor]. Null
     * (a test) keeps every document a plain String.
     */
    var native: NativeEditorEnv? = null

    /**
     * [doc]'s native editor, made on first ask (a pane showing it) and kept until the document
     * closes: panes only borrow its views. Null without a [native] environment.
     */
    fun nativeFor(doc: Document): NativeDocument? {
        doc.native?.let { return it }
        val env = native ?: return null
        if (docs[doc.path] !== doc) return null // closed meanwhile
        return NativeDocument(doc, env, onSave = { save(doc) }).also { doc.native = it }
    }

    /** New editor settings for every native view of this store (and the ones made later). */
    fun applySettings(settings: dev.supermux.editor.plugins.view.EditorSettings) {
        val env = native ?: return
        if (env.settings == settings) return
        env.settings = settings
        for (d in docs.values) d.native?.applySettings(settings)
    }

    /** The owner goes: every native view stops (didClose, syntax workers freed); the texts stay. */
    fun disposeNative() {
        for (d in docs.values) d.dropNative()
        hub?.close()
        hub = null
    }

    private var hub: LspHub? = null

    /** The LSP clients of this store's native views (made on first use), or null without a [native] environment. */
    fun lspHub(): LspHub? {
        hub?.let { return it }
        val env = native ?: return null
        return LspHub(this, env.scope, env.lspParseOnWorker).also { hub = it }
    }

    /** The scope the store was made with: its owner's (the native views' plugins run on it). */
    internal val ownerScope: CoroutineScope get() = scope

    /** The store's own reader and writer (an LSP edit to a file nobody has open). */
    internal suspend fun readFile(path: String): Result<String> = fsRead(path)
    internal suspend fun writeFile(path: String, text: String): Boolean = fsWrite(path, text)

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
                    val doc = docs.getOrPut(path) { LineEndings.load(content).let { Document(path, it.text, it.crlf) } }
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
                        loadErrorBinary = (err as? dev.supermux.net.FsException)?.status == 415
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
        docs.remove(path)?.dropNative()
    }

    fun update(path: String, content: String) {
        docs[path]?.content = content
    }

    fun save(doc: Document) {
        if (doc.path in savingPaths) return
        // Snapshot the text NOW (before the launch): an edit typed while the write is in flight stays dirty.
        val snapshot = snapshotOf(doc)
        savingPaths = savingPaths + doc.path
        writeObservers.toList().forEach { it.writeStarted(doc.path) }
        scope.launch { write(doc, snapshot) }
    }

    /** [save], waiting for the write: true when the file was written (false also when a save of it is already running). */
    suspend fun saveNow(doc: Document): Boolean {
        if (doc.path in savingPaths) return false
        val snapshot = snapshotOf(doc)
        savingPaths = savingPaths + doc.path
        writeObservers.toList().forEach { it.writeStarted(doc.path) }
        return write(doc, snapshot)
    }

    /** What is written is what is marked saved: the text at the moment of the save. */
    private fun snapshotOf(doc: Document): Pair<String, dev.supermux.editor.core.Rope?> {
        val rope = doc.native?.primary?.state?.doc
        return (rope?.toString() ?: doc.content) to rope
    }

    private suspend fun write(doc: Document, snapshot: Pair<String, dev.supermux.editor.core.Rope?>): Boolean {
        val (text, rope) = snapshot
        var ok = false
        try {
            ok = fsWrite(doc.path, LineEndings.save(text, doc.crlf))
            if (ok) {
                doc.savedContent = text
                if (rope != null) doc.native?.markSaved(rope)
            }
            return ok
        } finally {
            savingPaths = savingPaths - doc.path
            // The stale-banner tracker: our own write must not read as "changed on disk".
            writeObservers.toList().forEach { it.writeFinished(doc.path, ok) }
        }
    }

    // ── Live file-watch reload (ports EditorState.swift:79-84, 130-144) ─────────

    /** Record disk-change notifications (workdir-relative paths, leading slash optional). */
    override fun markChanged(paths: List<String>) {
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
            .onSuccess { raw ->
                val loaded = LineEndings.load(raw)
                doc.crlf = loaded.crlf
                val native = doc.native
                if (native != null) native.replaceFromDisk(loaded.text) else doc.content = loaded.text
                doc.savedContent = loaded.text
                changedPaths = changedPaths - normPath(path)
            }
            .onFailure { err -> loadError = err.message ?: "Could not reload file" }
        if (loadingPath == path) loadingPath = null
    }
}
