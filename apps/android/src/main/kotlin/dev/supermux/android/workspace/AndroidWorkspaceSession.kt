package dev.supermux.android.workspace

import android.util.Log
import androidx.compose.runtime.Composable
import dev.supermux.android.AppViewModel
import dev.supermux.net.AddViewBody
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.ui.workspace.rememberWorkspaceSession
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.toDto
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * Phone (narrow) never PATCHes [layout] — tabs follow broker membership. A layout write
 * from [WorkspaceFileOpener] would violate D2. Wide still PATCHes through [onPatch].
 */
fun androidLayoutPatch(
    wide: Boolean,
    onPatch: suspend (LayoutNode) -> Unit,
    onSkip: () -> Unit = {},
): suspend (LayoutNode) -> Unit = { tree ->
    if (wide) onPatch(tree) else onSkip()
}

@Composable
fun rememberWorkspaceSession(
    workspace: WorkspaceDto,
    vm: AppViewModel,
    wide: Boolean,
    overlayScope: CoroutineScope,
    newId: () -> String = { UUID.randomUUID().toString() },
): WorkspaceSession = rememberWorkspaceSession(
    workspace = workspace,
    overlayScope = overlayScope,
    patchLayout = androidLayoutPatch(
        wide = wide,
        onPatch = { tree -> vm.fleet.patchWorkspaceLayout(workspace.id, tree.toDto()) },
        onSkip = { Log.w("WorkspaceSession", "skipping layout PATCH on phone workspace=${workspace.id}") },
    ),
    fsRead = { p -> vm.fleet.workspaceFsRead(workspace.id, p) },
    fsWrite = { p, content -> vm.fleet.workspaceFsWrite(workspace.id, p, content) },
    postView = { id, state, groupId ->
        val created = vm.fleet.addView(
            workspace.id,
            AddViewBody(kind = "editor", state = state, id = id, groupId = groupId),
        )?.id
        if (created != null && !wide) {
            vm.fleet.setActiveView(workspace.id, created)
        }
        created
    },
    newId = newId,
)
