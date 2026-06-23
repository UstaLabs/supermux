package dev.supermux.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.net.FsDiffResult
import dev.supermux.net.RepoDiff
import dev.supermux.net.ReviewComment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class EditorTab(path: String, content: String) {
    val path = path
    var content by mutableStateOf(content)
    var savedContent by mutableStateOf(content)
    var scrollTop by mutableStateOf(0)
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

    /** Markdown preview toggle (Eye/Pencil). Single flag like iOS — gated by activeIsMarkdown. */
    var previewMode by mutableStateOf(false)

    // Diff / inline code-review state — per-session, survives panel switches like the tree.
    var showDiff by mutableStateOf(false)
    var diffRepos by mutableStateOf<List<RepoDiff>>(emptyList())
    var diffComments by mutableStateOf<List<ReviewComment>>(emptyList())
    var diffLoading by mutableStateOf(false)

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

    // ── Diff / inline code-review (ports EditorState.swift:61-77) ───────────────

    /** Fetch the diff; only flip [showDiff] on a non-null result so a failed fetch never
     *  opens an empty pane (parity EditorState.swift:67). */
    suspend fun loadDiff(fsDiff: suspend () -> FsDiffResult?) {
        diffLoading = true
        val res = fsDiff()
        diffLoading = false
        if (res == null) return
        diffRepos = res.repos
        diffComments = res.comments
        showDiff = true
    }

    /** Re-fetch the diff in place (after add/resolve/submit) — does not toggle [showDiff]. */
    suspend fun reloadDiff(fsDiff: suspend () -> FsDiffResult?) {
        val res = fsDiff() ?: return
        diffRepos = res.repos
        diffComments = res.comments
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
