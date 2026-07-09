// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorState.kt — keep in
// sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - previewMode (markdown preview toggle) and the Diff/inline-code-review state + methods
//     (showDiff, diffRepos, diffComments, diffLoading, loadDiff, reloadDiff — and their FsDiffResult/
//     RepoDiff/ReviewComment imports) are OMITTED entirely — TODO(M4): port DiffView + markdown
//     preview once the desktop diff pane lands (plan Task 3 scope trims say "prefer OUT (YAGNI)");
//     both are cleanly separable from tabs/tree/search/reload, so left out rather than kept inert.
//   - Everything else — tabs, tree UI state, search, changedPaths/markChanged/isStale/reload,
//     openFile/openFileAtLine/closeTab/selectTab/updateContent/saveActive — mirrors Android 1:1,
//     INCLUDING two shapes that disagree with the Swift test-reference file (Android is the
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

    /** Workdir-relative paths the broker reported changed on disk (fs_changed) → reload banner. */
    var changedPaths by mutableStateOf(setOf<String>())

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
        loadingPath = path
        loadError = null
        scope.launch {
            fsRead(path)
                .onSuccess { content ->
                    tabs.add(EditorTab(path, content))
                    activeTabPath = path
                    loadingPath = null
                }
                .onFailure { err ->
                    loadError = err.message ?: "Could not open file"
                    loadingPath = null
                }
        }
    }

    /** Open [path] and, once present, request a scroll to [line] (1-indexed). */
    fun openFileAtLine(path: String, line: Int?, endLine: Int?) {
        openFile(path)
        // openFile may add the tab synchronously (cache hit) or after fsRead; set on the tab
        // when it exists, else stash on a fresh open via a one-shot.
        val tab = tabs.find { it.path == path }
        if (line != null) {
            if (tab != null) tab.revealLine = line to endLine
            else scope.launch {
                // tab arrives after fsRead completes; poll the state list briefly.
                repeat(50) {
                    val t = tabs.find { it.path == path }
                    if (t != null) { t.revealLine = line to endLine; return@launch }
                    delay(20)
                }
            }
        }
    }

    fun closeTab(path: String) {
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

    /** Re-read a tab from disk and clear its stale flag (parity EditorState.swift:130-144). */
    suspend fun reload(path: String, fsRead: suspend (String) -> Result<String>) {
        val tab = tabs.find { it.path == path } ?: return
        loadingPath = path
        fsRead(path)
            .onSuccess { content ->
                tab.content = content
                tab.savedContent = content
                changedPaths = changedPaths - normPath(path)
            }
            .onFailure { err -> loadError = err.message ?: "Could not reload file" }
        loadingPath = null
    }
}
