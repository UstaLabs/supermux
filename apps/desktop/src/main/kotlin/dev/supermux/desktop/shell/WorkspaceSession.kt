package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import dev.supermux.state.HostStore
import dev.supermux.net.AddViewBody
import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.proto.WorkspaceDto
import dev.supermux.ui.workspace.WorkspaceSession
import dev.supermux.ui.workspace.rememberWorkspaceSession
import dev.supermux.workspace.toDto
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

@Composable
internal fun rememberWorkspaceSession(
    workspace: WorkspaceDto,
    wsApp: HostStore,
    overlayScope: CoroutineScope,
): WorkspaceSession = rememberWorkspaceSession(
    workspace = workspace,
    overlayScope = overlayScope,
    patchLayout = { tree ->
        wsApp.api.patchWorkspace(workspace.id, PatchWorkspaceBody(layout = tree.toDto()))
    },
    fsRead = { p -> wsApp.workspaceFsRead(workspace.id, p) },
    fsWrite = { p, content -> wsApp.workspaceFsWrite(workspace.id, p, content) },
    postView = { id, state, groupId ->
        runCatching {
            wsApp.api.addView(
                workspace.id,
                AddViewBody(kind = "editor", state = state, id = id, groupId = groupId),
            )
        }.onFailure { println("[AppShell] open file view failed: $it") }
            .getOrNull()?.id
    },
    newId = { UUID.randomUUID().toString() },
)
