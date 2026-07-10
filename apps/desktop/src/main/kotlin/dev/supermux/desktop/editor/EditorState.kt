// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt — keep in
// sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - previewMode (markdown preview toggle) landed in M4g-1 (single flag, not per-tab — Android
//     EditorState.kt:41 parity). The Diff/inline-code-review state + methods (showDiff, diffRepos,
//     diffComments, diffLoading, loadDiff, reloadDiff — and their FsDiffResult/RepoDiff/ReviewComment
//     imports) remain OMITTED — TODO(M4g-2): port DiffView once the desktop diff pane lands; it's
//     cleanly separable from tabs/tree/search/reload, so left out rather than kept inert.
//   - Everything else — tabs, tree UI state, search, changedPaths/markChanged/isStale/reload,
//     openFile/openFileAtLine/closeTab/selectTab/updateContent/saveActive — mirrors Android 1:1,
//     EXCEPT for three DELIBERATE M3-T4 divergences hardened for the over-the-network fsRead (Android
//     does its fsRead in-process, so these races don't bite there — desktop's do once the read is a
//     broker round-trip; each is backport-worthy and flagged in the task report):
//       (A) openFile has an in-flight guard (`if (loadingPath == path) return`) so two taps on the
//           same not-yet-loaded file can't launch two loads → two duplicate tabs. The success/failure
//           branches also drop their result when the path was closed mid-load (see [cancelledPaths]).
//       (B) openFileAtLine guards its pending-reveal poll with a monotonic [revealNonce] (the iOS
//           EditorState.swift:121-129 pattern) so a superseded reveal never fires on a tab that
//           arrived from a LATER open, and logs when the poll gives up (superseded or 1s timeout).
//           Android instead polls unconditionally — desktop deliberately diverges toward the
//           iOS-fixed semantics here (backport candidate).
//       (C) closeTab cancels an in-flight load for the closed path so its late fsRead result can't
//           resurrect the tab the user just closed.
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EditorTab(path: String, content: String) {
    val path = path
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var scrollTop by mutableStateOf(0)
    var revealLine by mutableStateOf<Pair<Int, Int?>?>(null)
}

class EditorState(
    private val fsRead: suspend (String) -> Result<String>,
    private val fsWrite: suspend (String, String) -> Boolean,
    private val scope: CoroutineScope,
) {
    val tabs = mutableStateListOf<EditorTab>()
    var activeTabPath by mutableStateOf<String?>(null)
    var loadingPath by mutableStateOf<String?>(null)
    var loadError by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    /** File tree UI state — survives panel / session switches while composed. */
    val treeRoot = mutableStateListOf<TreeNode>()
    var treeRootLoaded by mutableStateOf(false)
    var expandedPaths by mutableStateOf(setOf<String>())
    var treeLoadingPaths by mutableStateOf(setOf<String>())
    var treeVisible by mutableStateOf<Boolean?>(null)
    var searchQuery by mutableStateOf("")

    /** Markdown-preview toggle (M4g-1) — a single flag for the whole panel, not per-tab (Android
     *  EditorState.kt:41 parity). Only takes effect on the active tab when it's a `.md`/`.markdown`
     *  path (see [isMarkdownPath] / the showPreview gate in EditorPanel.kt). */
    var previewMode by mutableStateOf(false)

    /** Workdir-relative paths the broker reported changed on disk (fs_changed) → reload banner. */
    var changedPaths by mutableStateOf(setOf<String>())

    /** Per-directory tree-listing errors (path → message) surfaced as an inline row (M3-T4). */
    var treeLoadError by mutableStateOf<Map<String, String>>(emptyMap())

    /** Paths whose in-flight load was cancelled by [closeTab] — the load result is dropped, never
     *  re-added, so a close during a slow (networked) fsRead can't resurrect the closed tab (M3-T4). */
    private val cancelledPaths = mutableSetOf<String>()

    /** Monotonic guard for [openFileAtLine]'s pending-reveal poll (iOS revealNonce parity). A poll
     *  only applies its reveal while it is still the newest request; a superseded poll logs + drops. */
    private var revealNonce = 0

    val activeTab: EditorTab?
        get() = tabs.find { it.path == activeTabPath }

    fun isDirty(path: String): Boolean {
        val tab = tabs.find { it.path == path } ?: return false
        return tab.content != tab.savedContent
    }

    fun captureActiveScroll(scrollTop: Int) {
        activeTab?.scrollTop = scrollTop
    }

    fun openFile(path: String) {
        tabs.find { it.path == path }?.let {
            activeTabPath = path
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
                    // Dropped if the tab was closed mid-load (divergence C) — never resurrect it.
                    if (cancelledPaths.remove(path)) {
                        if (loadingPath == path) loadingPath = null
                        return@onSuccess
                    }
                    // The tab is always added (a valid file the user opened), but activation +
                    // spinner-clear defer to whichever open is CURRENT: `loadingPath` is single-slot,
                    // so two overlapping cross-path loads both complete — gating on `loadingPath ==
                    // path` keeps the LAST-opened file active (not the last-to-return over the
                    // network) and stops an earlier load from wiping a newer one's loading indicator.
                    if (tabs.none { it.path == path }) tabs.add(EditorTab(path, content))
                    if (loadingPath == path) {
                        activeTabPath = path
                        loadingPath = null
                    } else if (activeTabPath == null) {
                        // The gate moved on (e.g. a newer open was closed mid-load, clearing it) and
                        // nothing is selected: a superseded-but-successful load should still show its
                        // tab rather than leaving a bare surface under an unselected tab strip.
                        activeTabPath = path
                    }
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
     * while it remains the newest request, so a stale reveal from an earlier call can't land on a tab
     * that a later navigation produced. Logs when a poll gives up (superseded or the 1s timeout).
     */
    fun openFileAtLine(path: String, line: Int?, endLine: Int?) {
        openFile(path)
        if (line == null) return
        val myNonce = ++revealNonce
        // openFile may add the tab synchronously (cache hit / a non-suspending fsRead) or after the
        // read completes. Set on the tab when it exists, else poll briefly for it to arrive.
        val tab = tabs.find { it.path == path }
        if (tab != null) {
            if (myNonce == revealNonce) tab.revealLine = line to endLine
            return
        }
        scope.launch {
            repeat(50) {
                if (myNonce != revealNonce) {
                    println("[EditorState] openFileAtLine('$path') reveal superseded — dropping stale reveal")
                    return@launch
                }
                val t = tabs.find { it.path == path }
                if (t != null) {
                    if (myNonce == revealNonce) t.revealLine = line to endLine
                    return@launch
                }
                delay(20)
            }
            println("[EditorState] openFileAtLine('$path') gave up after 1s — tab never arrived")
        }
    }

    fun closeTab(path: String) {
        // Cancel an in-flight load for this path (divergence C) so its late result is dropped and the
        // just-closed tab can't reappear. Done BEFORE the tab lookup: on a close during a cold open
        // there is no tab yet, only a loadingPath.
        if (loadingPath == path) {
            cancelledPaths.add(path)
            loadingPath = null
        }
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

    fun updateContent(path: String, content: String) {
        tabs.find { it.path == path }?.content = content
    }

    fun saveActive() {
        val tab = activeTab ?: return
        if (saving) return
        saving = true
        scope.launch {
            if (fsWrite(tab.path, tab.content)) {
                tab.savedContent = tab.content
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
     * Re-read a tab from disk and clear its stale flag (parity EditorState.swift:130-144).
     *
     * Same networked-read discipline as [openFile] (M3-T4): the completion only clears
     * [loadingPath] when it still owns the gate — an unconditional clear would stomp a concurrent
     * `openFile(B)`'s gate and leave B's tab added-but-never-activated. And a [closeTab] during the
     * reload marks the path cancelled; the completion consumes that marker and DROPS the result
     * (the tab is gone), so the stale entry can't leak into [cancelledPaths] forever.
     */
    suspend fun reload(path: String, fsRead: suspend (String) -> Result<String>) {
        val tab = tabs.find { it.path == path } ?: return
        loadingPath = path
        val result = fsRead(path)
        // closeTab-during-reload: consume the cancel marker and drop the result — the tab was
        // removed, so applying content/clearing the stale flag would act on a ghost.
        if (cancelledPaths.remove(path)) {
            if (loadingPath == path) loadingPath = null
            return
        }
        result
            .onSuccess { content ->
                tab.content = content
                tab.savedContent = content
                changedPaths = changedPaths - normPath(path)
            }
            .onFailure { err -> loadError = err.message ?: "Could not reload file" }
        if (loadingPath == path) loadingPath = null
    }
}
