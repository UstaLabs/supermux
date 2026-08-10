// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt — keep in
// sync until a shared UI module exists.
//
// This is now a thin coordinator over three collaborators, each holding one of the jobs this class
// used to do at once (behaviour unchanged; every member below still exists, delegating):
//   - [DocumentStore] — the open documents (the [Document] text buffers) + their load/save/reload
//     lifecycle, including the three M3-T4 networked-fsRead divergences (in-flight guard,
//     close-during-load cancel, reveal nonce) documented in DocumentStore.kt's header.
//   - [ExplorerState] — file-tree + search UI state.
//   - [DiffState] — diff / inline code-review state.
// What stays HERE is what is per-VIEW rather than per-file: the tab ORDER, the active selection and
// the preview toggle. Documents are keyed by path in the store and exist exactly once, so a later
// phase can hand a second pane its own tab list over the SAME [Document] instances rather than a
// second copy of the file text.
//
// Desktop adaptations vs. the Android source:
//   - previewMode (markdown preview toggle) landed in M4g-1 (single flag, not per-tab — Android
//     EditorState.kt:41 parity). The Diff/inline-code-review state + methods (showDiff, diffRepos,
//     diffComments, diffLoading, loadDiff, reloadDiff) landed in M4g-2 — verbatim port of Android
//     EditorState.kt:44-47,138-155, now in DiffState.kt.
//   - Everything else — tabs, tree UI state, search, changedPaths/markChanged/isStale/reload,
//     openFile/openFileAtLine/closeTab/selectTab/updateContent/saveActive — mirrors Android 1:1,
//     EXCEPT for three DELIBERATE M3-T4 divergences hardened for the over-the-network fsRead; they
//     moved with the document layer, see DocumentStore.kt's header for (A) the openFile in-flight
//     guard, (B) the openFileAtLine reveal nonce and (C) the closeTab load-cancel.
//   - INCLUDING two shapes that disagree with the Swift test-reference file (Android is the
//     implementation reference per the M3 Task 3 brief; both are flagged in the task report):
//       (1) `captureActiveScroll(scrollTop)` acts on the currently ACTIVE tab implicitly, unlike
//           iOS's per-path `setScroll(path, top)`.
//       (2) `reload(path, fsRead)` takes its own `fsRead` parameter rather than reusing the
//           constructor-injected one (iOS's `reload(path)` has no such parameter) — every Android
//           call site happens to pass the same closure the state was built with, so the extra
//           parameter is redundant there too; preserved here for exact parity, not because it's
//           good API shape.
package dev.supermux.desktop.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsRefsResult
import dev.supermux.net.RepoDiff
import dev.supermux.net.RepoRefs
import dev.supermux.net.ReviewComment
import kotlinx.coroutines.CoroutineScope

class EditorState(
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    scope: CoroutineScope,
) {
    val documents = DocumentStore(fsRead, fsWrite, scope)
    val explorer = ExplorerState()
    val diff = DiffState()

    /** The tab strip: ORDER + membership for this view. Holds [Document] references owned by
     *  [documents] (not copies), so the strip is a view onto the store — which is what lets a second
     *  pane later keep its own order over the same buffers. Kept a snapshot LIST of documents rather
     *  than of paths because every call site reads elements (`it.path`, `.content`, `.scrollTop`,
     *  `.revealLine`) and a derived list would hand Compose a new identity on every read. */
    val tabs = mutableStateListOf<Document>()
    var activeTabPath by mutableStateOf<String?>(null)

    /** Markdown-preview toggle (M4g-1) — a single flag for the whole panel, not per-tab (Android
     *  EditorState.kt:41 parity). Only takes effect on the active tab when it's a `.md`/`.markdown`
     *  path (see [isMarkdownPath] / the showPreview gate in EditorPanel.kt). */
    var previewMode by mutableStateOf(false)

    init {
        documents.onOpened = { doc, current ->
            // The tab is always added (a valid file the user opened), but activation defers to
            // whichever open is CURRENT: `loadingPath` is single-slot, so two overlapping cross-path
            // loads both complete — gating on it keeps the LAST-opened file active (not the
            // last-to-return over the network).
            if (tabs.none { it.path == doc.path }) tabs.add(doc)
            if (current) {
                activeTabPath = doc.path
            } else if (activeTabPath == null) {
                // The gate moved on (e.g. a newer open was closed mid-load, clearing it) and nothing
                // is selected: a superseded-but-successful load should still show its tab rather
                // than leaving a bare surface under an unselected tab strip.
                activeTabPath = doc.path
            }
        }
    }

    // ── Documents (delegates to [documents]) ───────────────────────────────────────────────────

    var loadingPath: String?
        get() = documents.loadingPath
        set(value) { documents.loadingPath = value }

    var loadError: String?
        get() = documents.loadError
        set(value) { documents.loadError = value }

    var saving: Boolean
        get() = documents.saving
        set(value) { documents.saving = value }

    /** Workdir-relative paths the broker reported changed on disk (fs_changed) → reload banner. */
    var changedPaths: Set<String>
        get() = documents.changedPaths
        set(value) { documents.changedPaths = value }

    val activeTab: Document?
        get() = tabs.find { it.path == activeTabPath }

    fun isDirty(path: String): Boolean = documents.isDirty(path)

    fun captureActiveScroll(scrollTop: Int) {
        activeTab?.scrollTop = scrollTop
    }

    fun openFile(path: String) = documents.open(path)

    fun openFileAtLine(path: String, line: Int?, endLine: Int?) = documents.openAtLine(path, line, endLine)

    fun closeTab(path: String) {
        // Cancel an in-flight load for this path (divergence C) so its late result is dropped and the
        // just-closed tab can't reappear. Done BEFORE the tab lookup: on a close during a cold open
        // there is no tab yet, only a loadingPath.
        documents.close(path)
        val idx = tabs.indexOfFirst { it.path == path }
        if (idx == -1) return
        tabs.removeAt(idx)
        if (activeTabPath == path) {
            activeTabPath = tabs.getOrNull(idx.coerceAtMost(tabs.lastIndex))?.path
        }
        if (activeTabPath == null) loadError = null
    }

    fun selectTab(path: String) {
        activeTabPath = path
        loadError = null
    }

    fun updateContent(path: String, content: String) = documents.update(path, content)

    fun saveActive() {
        val tab = activeTab ?: return
        documents.save(tab)
    }

    /** Record disk-change notifications (workdir-relative paths, leading slash optional). */
    fun markChanged(paths: List<String>) = documents.markChanged(paths)

    fun isStale(path: String): Boolean = documents.isStale(path)

    /** Re-read a tab from disk and clear its stale flag (parity EditorState.swift:130-144). */
    suspend fun reload(path: String, fsRead: suspend (String) -> Result<String>) =
        documents.reload(path, fsRead)

    // ── File tree / search (delegates to [explorer]) ───────────────────────────────────────────

    /** File tree UI state — survives panel / session switches while composed. */
    val treeRoot get() = explorer.treeRoot

    var treeRootLoaded: Boolean
        get() = explorer.treeRootLoaded
        set(value) { explorer.treeRootLoaded = value }

    var expandedPaths: Set<String>
        get() = explorer.expandedPaths
        set(value) { explorer.expandedPaths = value }

    var treeLoadingPaths: Set<String>
        get() = explorer.treeLoadingPaths
        set(value) { explorer.treeLoadingPaths = value }

    var treeVisible: Boolean?
        get() = explorer.treeVisible
        set(value) { explorer.treeVisible = value }

    var searchQuery: String
        get() = explorer.searchQuery
        set(value) { explorer.searchQuery = value }

    /** Per-directory tree-listing errors (path → message) surfaced as an inline row (M3-T4). */
    var treeLoadError: Map<String, String>
        get() = explorer.treeLoadError
        set(value) { explorer.treeLoadError = value }

    // ── Diff / inline code-review (delegates to [diff]) ────────────────────────────────────────

    var showDiff: Boolean
        get() = diff.showDiff
        set(value) { diff.showDiff = value }

    var diffRepos: List<RepoDiff>
        get() = diff.diffRepos
        set(value) { diff.diffRepos = value }

    var diffComments: List<ReviewComment>
        get() = diff.diffComments
        set(value) { diff.diffComments = value }

    var diffLoading: Boolean
        get() = diff.diffLoading
        set(value) { diff.diffLoading = value }

    var diffBase: String
        get() = diff.diffBase
        set(value) { diff.diffBase = value }

    var diffRefs: List<RepoRefs>
        get() = diff.diffRefs
        set(value) { diff.diffRefs = value }

    suspend fun loadDiff(fsDiff: suspend (String) -> FsDiffResult?, fsRefs: suspend () -> FsRefsResult?) =
        diff.loadDiff(fsDiff, fsRefs)

    suspend fun reloadDiff(fsDiff: suspend (String) -> FsDiffResult?) = diff.reloadDiff(fsDiff)

    suspend fun setDiffBase(base: String, fsDiff: suspend (String) -> FsDiffResult?) =
        diff.setDiffBase(base, fsDiff)
}
