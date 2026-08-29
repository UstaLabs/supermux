package dev.supermux.android.workspace

import androidx.compose.runtime.Composable
import dev.supermux.android.AppViewModel
import dev.supermux.net.AddViewBody
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.ui.workspace.rememberWorkspaceSession
import dev.supermux.workspace.toDto
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

@Composable
fun rememberWorkspaceSession(
    workspace: WorkspaceDto,
    vm: AppViewModel,
    recordId: String,
    overlayScope: CoroutineScope,
): WorkspaceSession = rememberWorkspaceSession(
    workspace = workspace,
    overlayScope = overlayScope,
    patchLayout = { tree ->
        vm.patchWorkspaceLayout(workspace.id, tree.toDto())
    },
    fsRead = { p -> vm.workspaceFsRead(workspace.id, p) },
    fsWrite = { p, content -> vm.workspaceFsWrite(workspace.id, p, content) },
    postView = { id, state, groupId ->
        vm.addView(
            workspace.id,
            AddViewBody(kind = "editor", state = state, id = id, groupId = groupId),
        )?.id
    },
    newId = { UUID.randomUUID().toString() },
)
