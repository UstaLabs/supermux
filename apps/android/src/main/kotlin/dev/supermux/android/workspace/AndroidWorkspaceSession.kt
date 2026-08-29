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
    isWorkspaceWidth: Boolean,
    onPatch: suspend (LayoutNode) -> Unit,
    onSkip: () -> Unit = {},
): suspend (LayoutNode) -> Unit = { tree ->
    if (isWorkspaceWidth) onPatch(tree) else onSkip()
}

@Composable
fun rememberWorkspaceSession(
    workspace: WorkspaceDto,
    vm: AppViewModel,
    isWorkspaceWidth: Boolean,
    overlayScope: CoroutineScope,
    newId: () -> String = { UUID.randomUUID().toString() },
): WorkspaceSession = rememberWorkspaceSession(
    workspace = workspace,
    overlayScope = overlayScope,
    patchLayout = androidLayoutPatch(
        isWorkspaceWidth = isWorkspaceWidth,
        onPatch = { tree -> vm.patchWorkspaceLayout(workspace.id, tree.toDto()) },
        onSkip = { Log.d("WorkspaceSession", "skipping layout PATCH on phone") },
    ),
    fsRead = { p -> vm.workspaceFsRead(workspace.id, p) },
    fsWrite = { p, content -> vm.workspaceFsWrite(workspace.id, p, content) },
    postView = { id, state, groupId ->
        val created = vm.addView(
            workspace.id,
            AddViewBody(kind = "editor", state = state, id = id, groupId = groupId),
        )?.id
        if (created != null && !isWorkspaceWidth) {
            vm.setActiveView(workspace.id, created)
        }
        created
    },
    newId = newId,
)
