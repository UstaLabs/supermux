// What ONE Files pane is looking at. Folder CONTENTS live in the host's FileSystemService and are
// shared; this is only the view: where the tree starts, which folders are open, what's selected,
// and the scroll position. Held per view id in [TreeViewStates], not in `remember {}`, so dragging,
// splitting or re-tabbing the pane keeps it.
package dev.supermux.ui.files

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

@Stable
class TreeViewState(
    rootPath: String,
    /** What must outlive the pane's composition; a pane that keeps it (#89) passes its own. */
    val edits: TreeViewEdits = TreeViewEdits(),
) {
    /** The workspace workdir this state was created for; [rootPath] may move above it. */
    val workdir: String = rootPath
    // Where the tree starts, what is open and selected, and the recents live in [edits] (#89).
    var rootPath: String
        get() = edits.rootPath ?: workdir
        set(value) {
            edits.rootPath = value
        }
    var expanded by edits::expanded
        private set
    var selected by edits::selected
    var query by edits::query
    /**
     * The row-action dialog this pane has open (New file / Rename / Delete), or null. Here rather
     * than inside the tree so the pane header's ⋮ menu can open New file… / New folder… too.
     * Opening or closing one starts its name (and its delete step) afresh.
     */
    var dialog: FileTreeDialog?
        get() = edits.dialog
        set(value) {
            edits.dialog = value
            edits.typed = null
            edits.permanent = false
            edits.op = null
        }
    /**
     * The in-place New file / New folder / Rename field this pane has open, or null — the
     * hardware-keyboard counterpart of [dialog] (see [startAction]).
     */
    var inlineEdit: InlineEdit?
        get() = edits.inlineEdit
        set(value) {
            edits.inlineEdit = value
            edits.typed = null
            edits.op = null
        }
    /** Built where [edits] left the scroll; the tree writes its scrolling back (see `FileTreeView`). */
    val list = LazyListState(edits.scrollIndex, edits.scrollOffset)

    /** The name being typed in the open [dialog] or [inlineEdit]: [initial] until something is. */
    fun typedName(initial: () -> TextFieldValue): TypedName =
        edits.typed ?: TypedName(initial()).also { edits.typed = it }

    /** The op the open [dialog] or [inlineEdit] runs (see [FileOp]). */
    fun fileOp(): FileOp = edits.op ?: FileOp().also { edits.op = it }

    /**
     * Open [action]: in place when [inline] (a hardware keyboard is available — see
     * `LocalHardwareKeyboard`) and it is a New entry or Rename, as its dialog otherwise. Delete
     * always confirms in a dialog. A new entry's folder (and the folders above it) are expanded
     * first so its temporary row has somewhere to go.
     */
    fun startAction(action: FileTreeDialog, inline: Boolean) {
        when {
            inline && action is FileTreeDialog.NewEntry -> {
                dialog = null
                val parent = action.parent
                if (parent != rootPath && isWithin(rootPath, parent)) {
                    expanded = expanded + ancestorsWithin(rootPath, parent).filter { it != rootPath } + parent
                }
                inlineEdit = InlineEdit.Create(parent, action.folder)
            }
            inline && action is FileTreeDialog.Rename -> {
                dialog = null
                inlineEdit = InlineEdit.Rename(action.path)
            }
            else -> {
                inlineEdit = null
                dialog = action
            }
        }
    }

    // The last [RecentMax] files opened from this pane (workdir-relative, newest first): what the
    // "Go to file…" list shows before anything is typed.
    var recent by edits::recent
        private set

    /** Record that [relativePath] was opened from this pane (moves it to the front). */
    fun noteOpened(relativePath: String) {
        recent = (listOf(relativePath) + recent.filterNot { it == relativePath }).take(RecentMax)
    }

    fun isExpanded(path: String) = path in expanded
    fun expand(path: String) { expanded = expanded + path }
    fun toggle(path: String) {
        expanded = if (path in expanded) expanded.filterNot { it == path || isWithin(path, it) }.toSet() else expanded + path
    }
    /** Drop [path] and everything under it (folder gone, or collapsed). */
    fun prune(path: String) { expanded = expanded.filterNot { it == path || isWithin(path, it) }.toSet() }
    fun collapseAll() { expanded = emptySet() }

    /** Expand every ancestor of [path] under [rootPath] and select it. */
    fun reveal(path: String) {
        if (!isWithin(rootPath, path)) return // nothing to show outside the tree's root
        val anc = ancestorsWithin(rootPath, path).filter { it != rootPath }
        if (anc.isNotEmpty()) expanded = expanded + anc
        selected = path
    }
}

const val RecentMax = 10

/**
 * The part of a [TreeViewState] that must outlive its pane's composition: a rotation or a width
 * change rebuilt the Files pane with its open dialog or in-place field, the name typed in it and
 * the "Go to file…" query gone (#89), and with them where the tree started, its open folders, the
 * selection, the recent files and the scroll. Plain values only: the scroll is two numbers, and
 * each pane builds its own [TreeViewState.list] from them.
 */
class TreeViewEdits {
    /** Where the tree starts, once moved off the workdir. */
    var rootPath by mutableStateOf<String?>(null)
    var expanded by mutableStateOf<Set<String>>(emptySet())
    var selected by mutableStateOf<String?>(null)
    var recent by mutableStateOf<List<String>>(emptyList())
    var scrollIndex = 0
    var scrollOffset = 0
    /**
     * The active file this pane last revealed. A rebuilt pane does not reveal it again over the
     * selection and scroll it kept; a newly active file is revealed as before.
     */
    var revealed: String? = null
    var query by mutableStateOf("")
    /** "Go to file…"'s last hits, shown again at once by a rebuilt pane (then searched afresh). */
    var results by mutableStateOf<List<GoToEntry>?>(null)
    var dialog by mutableStateOf<FileTreeDialog?>(null)
    var inlineEdit by mutableStateOf<InlineEdit?>(null)
    /** The name typed in [dialog] or [inlineEdit] (see [TreeViewState.typedName]). */
    var typed: TypedName? = null
    /** The delete dialog's second step: the trash is on another filesystem. */
    var permanent by mutableStateOf(false)
    /** The op of the open [dialog] or [inlineEdit] (see [TreeViewState.fileOp]). */
    var op: FileOp? = null
}

/** A name being typed in a Files dialog or in-place field. */
class TypedName(initial: TextFieldValue) {
    var value by mutableStateOf(initial)
}

/**
 * A create, rename or delete that a Files dialog or in-place field started (#89). It runs on the
 * fleet's scope, so a rotation or a width change does not cut it off half done, and its [result]
 * waits here for the dialog or field on screen, which finishes it with its own callbacks: the
 * selection, the folders shown, the tabs open on a renamed or deleted path.
 */
class FileOp {
    var busy by mutableStateOf(false)
    /** The broker's refusal, shown in the dialog or under the field. */
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<FileOpResult?>(null)
}

/** A finished [FileOp]: the [name] it ran for, whether an in-place field takes the tree's focus back, and its failure if any. */
class FileOpResult(val name: String, val refocus: Boolean, val failure: Throwable?)

/** Per-host, in-memory holder: one [TreeViewState] per Files view id. */
class TreeViewStates {
    private val byView = HashMap<String, TreeViewState>()

    /** [edits] is what a new state keeps beyond the pane's composition (see [TreeViewEdits]). */
    fun forView(viewId: String, workdir: String, edits: TreeViewEdits = TreeViewEdits()): TreeViewState {
        val cur = byView[viewId]
        if (cur != null && cur.workdir == workdir) return cur
        return TreeViewState(workdir, edits).also { byView[viewId] = it }
    }

    fun forget(viewId: String) { byView.remove(viewId) }

    /** Drop the states of views that no longer exist (a closed Files pane). */
    fun retainOnly(viewIds: Set<String>) { byView.keys.retainAll(viewIds) }
}
