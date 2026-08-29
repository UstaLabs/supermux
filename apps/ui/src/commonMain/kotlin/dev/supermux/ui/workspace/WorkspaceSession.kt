package dev.supermux.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.editor.DocumentStore
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.WorkspaceFileOpener
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
)

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

    // Opening a file is a layout edit plus a POST that carries the id
    // we already used — see WorkspaceFileOpen.kt. Rebuilt every
    // composition on purpose: it reads the tree and the view map at
    // CALL time, and capturing either in a remember would freeze it.
    // Markdown preview per view id. It used to be local state inside
    // FilePane, driven by a button in that pane's action row; the row is
    // gone and the tab owns the toggle, so the state lives out here.
    val previewModes = remember(workspace.id) { mutableStateMapOf<String, Boolean>() }
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
    )
}
