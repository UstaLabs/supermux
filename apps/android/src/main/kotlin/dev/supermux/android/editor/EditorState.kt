package dev.supermux.android.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
}
