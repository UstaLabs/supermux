package dev.supermux.ui.workspace

import dev.supermux.proto.stateString
import dev.supermux.ui.files.TreeViewStates
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.stateString
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.ui.files.affectedOpenPaths
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.WorkspaceFileOpener
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.isFileView
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/**
 * Per-workspace UI objects that must be shared across windows of the same
 * workspace: layout sync, the document store, provisional views, preview
 * toggles. Remembered on [WorkspaceDto.id] so switching workspaces still
 * resets them.
 */
class WorkspaceSession(
    val workspaceId: String,
    val provisionalViews: SnapshotStateMap<String, ViewDto>,
    val layoutSync: WorkspaceLayoutState,
    val documents: DocumentStore,
    val previewModes: SnapshotStateMap<String, Boolean>,
    val viewsById: Map<String, ViewDto>,
    val fileOpener: WorkspaceFileOpener,
    /** Fresh client-minted view ids (cluster G8: the phone/tablet add-view paths need one too). */
    val newId: () -> String = { "" },
    /** Per-view Files-tree state (open folders, root, selection, scroll), outliving the panes. */
    val treeStates: TreeViewStates = TreeViewStates(),
) {
    /**
     * The `file` view the user last worked in (pressed its tab or pane, or it just became active in
     * its group) — what the Files tree reveals. See `dev.supermux.ui.files.activeFilePath`.
     */
    var focusedFileViewId by mutableStateOf<String?>(null)

    /** The view whose pane the user last pressed, of any kind — where Ctrl/Cmd+T opens a terminal. */
    var focusedViewId: String? = null
}

// ── A rename / delete in the Files tree vs the open file tabs ────────────────────────────────
//
// A file tab keeps its document keyed by PATH, and Save writes to that path. Left alone, a tab over
// a file the tree just deleted would silently recreate it on the next Save, and a tab over a
// renamed file would write a second copy at the old name. So after every successful rename/delete
// the host walks its open `file` views (see [planMovedFileViews]) and, per document:
//
//  - CLEAN (nothing unsaved): nothing can be lost, so the tab follows the file. Deleted → the tab
//    closes (the same broker close as its × button). Renamed / inside a renamed folder → the new
//    path opens in the tab's OWN group and the old tab closes, so it looks like the tab was renamed.
//  - DIRTY (unsaved edits): we never throw edits away and never guess a destination. The tab stays
//    on the OLD path and is marked stale (DocumentStore.markChanged), which shows the existing
//    "changed on disk" banner: the user sees the file is gone/moved and a Save — which would
//    recreate the old path — is an explicit choice (they can also copy the text, or close the tab).

/** What to do with one open `file` view after a tree entry moved. */
sealed interface MovedFileStep {
    val viewId: String

    /** Clean, and its file is gone: close the tab. */
    data class Close(override val viewId: String, val path: String) : MovedFileStep

    /** Clean, and its file now lives at [newPath]: open that in [groupId] (the tab's own group), then close this tab. */
    data class Reopen(override val viewId: String, val oldPath: String, val newPath: String, val groupId: String?) : MovedFileStep

    /** Unsaved edits: keep the tab on its old path and flag it stale (the "changed on disk" banner). */
    data class MarkStale(override val viewId: String, val path: String) : MovedFileStep
}

/**
 * The steps for the open `file` views in [views] after the tree entry [oldAbs] moved to [newAbs]
 * (null = deleted). Pure; see the block comment above for the rules. [isDirty] takes a
 * workdir-relative path.
 */
fun planMovedFileViews(
    workdir: String,
    oldAbs: String,
    newAbs: String?,
    views: Map<String, ViewDto>,
    tree: LayoutNode,
    isDirty: (String) -> Boolean,
): List<MovedFileStep> {
    val fileViews = views.values.filter { it.isFileView() && it.stateString("path") != null }
    val moved = affectedOpenPaths(workdir, oldAbs, newAbs, fileViews.map { it.stateString("path")!! })
        .associateBy { it.oldPath }
    return fileViews.mapNotNull { v ->
        val path = v.stateString("path")!!
        val m = moved[path] ?: return@mapNotNull null
        when {
            isDirty(path) -> MovedFileStep.MarkStale(v.id, path)
            m.newPath == null -> MovedFileStep.Close(v.id, path)
            else -> MovedFileStep.Reopen(v.id, path, m.newPath, groupIdOf(tree, v.id))
        }
    }
}

/**
 * Apply [planMovedFileViews] to this workspace. [closeView] is the broker close the tab's × button
 * uses; [onPlaced] gets each view a Reopen placed (a host with windows claims it there).
 */
fun WorkspaceSession.applyEntryMoved(
    workdir: String,
    oldAbs: String,
    newAbs: String?,
    closeView: (viewId: String) -> Unit,
    onPlaced: (viewId: String) -> Unit = {},
) {
    val steps = planMovedFileViews(workdir, oldAbs, newAbs, viewsById, layoutSync.tree, documents::isDirty)
    val stale = ArrayList<String>()
    for (step in steps) {
        when (step) {
            is MovedFileStep.MarkStale -> stale += step.path
            is MovedFileStep.Close -> {
                closeView(step.viewId)
                documents.close(step.path)
            }
            is MovedFileStep.Reopen -> {
                // Open FIRST, so the group never goes empty (and collapses) between the two.
                fileOpener.open(step.newPath, sourceViewId = step.viewId, onPlaced = onPlaced, intoGroupId = step.groupId)
                closeView(step.viewId)
                documents.close(step.oldPath)
            }
        }
    }
    if (stale.isNotEmpty()) documents.markChanged(stale.distinct())
}

/**
 * The broker ALWAYS wins on a collision — its row is the real one, and ours
 * was only ever a stand-in for it.
 */
fun mergeWorkspaceViews(
    provisional: Map<String, ViewDto>,
    server: Map<String, ViewDto>,
): Map<String, ViewDto> =
    if (provisional.isEmpty()) server else provisional + server

@Composable
fun rememberWorkspaceSession(
    workspace: WorkspaceDto,
    overlayScope: CoroutineScope,
    patchLayout: suspend (LayoutNode) -> Unit,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    postView: suspend (id: String, state: JsonObject, groupId: String) -> String?,
    newId: () -> String,
): WorkspaceSession {
    // Local tree for drag responsiveness; the debounced PATCH and the
    // workspace_changed adoption both live in rememberWorkspaceLayout,
    // where the round trip can be tested on its own.
    // Views this client minted and put in the tree before the POST
    // returned (spec §9.0). Without a record here the layout would name
    // an id nothing knows about: the tab would say "view" and the pane
    // would draw nothing until the broker frame landed.
    //
    // Declared BEFORE the layout sync because the sync needs it: a
    // layout naming one of these is a layout the broker will refuse,
    // so the PATCH waits for them (see rememberWorkspaceLayout).
    val provisionalViews = remember(workspace.id) { mutableStateMapOf<String, ViewDto>() }
    val layoutSync = rememberWorkspaceLayout(
        workspaceId = workspace.id,
        serverLayout = workspace.layout,
        unconfirmedViews = provisionalViews.keys.toSet(),
        push = patchLayout,
    )
    val serverViews = remember(workspace) { workspace.views.associateBy { it.id } }
    val viewsById = mergeWorkspaceViews(provisionalViews.toMap(), serverViews)
    // …and the stand-in goes as soon as the real row arrives.
    LaunchedEffect(serverViews) {
        provisionalViews.keys.filter { it in serverViews }.forEach { provisionalViews.remove(it) }
    }

    // ── The workspace's open documents ────────────────────────────
    // ONE store for the whole workspace, not one per pane: two `file`
    // panes on one path must share one buffer, so a split shows the same
    // unsaved text on both sides and dragging a file tab between groups
    // cannot lose an edit (spec §7.2 / §18).
    val documents = remember(workspace.id) {
        DocumentStore(
            fsRead = fsRead,
            fsWrite = fsWrite,
            scope = overlayScope,
        )
    }
    // The native editor's views live as long as the store, not as long as a pane (M5): they go
    // with it, so every language server gets its didClose and every syntax worker is freed.
    androidx.compose.runtime.DisposableEffect(documents) { onDispose { documents.disposeNative() } }
    // A file whose last pane closed leaves the store (unless it has unsaved edits).
    val viewedFiles = viewsById.values
        .filter { it.kind == "editor" && it.stateString("mode") == "file" }
        .mapNotNull { it.stateString("path") }
        .toSet()
    LaunchedEffect(documents, viewedFiles) { documents.retainViewed(viewedFiles) }

    // Opening a file is a layout edit plus a POST that carries the id
    // we already used — see WorkspaceFileOpen.kt. Rebuilt every
    // composition on purpose: it reads the tree and the view map at
    // CALL time, and capturing either in a remember would freeze it.
    // Markdown preview per view id. It used to be local state inside
    // FilePane, driven by a button in that pane's action row; the row is
    // gone and the tab owns the toggle, so the state lives out here.
    val previewModes = remember(workspace.id) { mutableStateMapOf<String, Boolean>() }
    // Files-pane view state per view id — held here, beside the documents, so it outlives panes.
    val treeStates = remember(workspace.id) { TreeViewStates() }
    LaunchedEffect(treeStates, viewsById.keys) { treeStates.retainOnly(viewsById.keys) }
    val fileOpener = WorkspaceFileOpener(
        workspaceId = workspace.id,
        treeOf = { layoutSync.tree },
        // Computed INSIDE the lambda, not captured. `viewsById` is a
        // per-composition value, so handing it over froze the opener's
        // idea of what is open until the next recomposition — and two
        // clicks in one frame then both decided the file was not open
        // yet and each made a view. Read it live.
        viewsOf = { provisionalViews.toMap() + workspace.views.associateBy { it.id } },
        edit = { transform -> layoutSync.edit(transform) },
        provisional = provisionalViews,
        reveal = { p, line, endLine -> documents.openAtLine(p, line, endLine) },
        // Answers with the id the broker actually created, which is not
        // always the one we asked for — see WorkspaceFileOpener.post.
        post = postView,
        scope = overlayScope,
        newId = newId,
    )
    return WorkspaceSession(
        workspaceId = workspace.id,
        provisionalViews = provisionalViews,
        layoutSync = layoutSync,
        documents = documents,
        previewModes = previewModes,
        viewsById = viewsById,
        fileOpener = fileOpener,
        newId = newId,
        treeStates = treeStates,
    )
}
