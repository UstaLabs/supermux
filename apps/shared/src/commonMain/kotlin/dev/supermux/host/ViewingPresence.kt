package dev.supermux.host

import dev.supermux.proto.ClientFrame

/**
 * Atomic viewing snapshot for one workspace. Carries its own [workspaceId] so a
 * workspace switch cannot pair the new selection with the previous tree.
 *
 * An overlay over a workspace (settings, new-session, etc.) yields
 * [ClientFrame.Viewing] `(null, false)` — the same as desktop — rather than
 * reporting the underlying selected chat as still visible. Deliberate: the
 * chats are not on screen while the overlay is up (spec §11).
 */
data class WorkspaceViewingSnapshot(
    val workspaceId: String,
    val visibleChatSessionIds: List<String>,
    val appForeground: Boolean,
)

data class ViewingSurface(
    val homeRoute: Boolean,
    val overlayOpen: Boolean,
    val workspaceResolved: Boolean,
    val appForeground: Boolean,
)

fun viewingSurfaceVisible(surface: ViewingSurface): Boolean =
    surface.homeRoute &&
        !surface.overlayOpen &&
        surface.workspaceResolved &&
        surface.appForeground

fun visibleWorkspaceChatIds(
    surfaceVisible: Boolean,
    selectedWorkspaceId: String?,
    snapshot: WorkspaceViewingSnapshot?,
): List<String> {
    if (
        !surfaceVisible ||
        selectedWorkspaceId == null ||
        snapshot == null ||
        snapshot.workspaceId != selectedWorkspaceId ||
        !snapshot.appForeground
    ) {
        return emptyList()
    }
    return snapshot.visibleChatSessionIds
}

/**
 * The Viewing frames to send for the currently visible chat views (spec §11).
 *
 * Before workspaces there was exactly one open chat. A workspace can show two at
 * once, so the whole visible set goes out in ONE frame via [ClientFrame.Viewing.sessions]
 * — never a workspace id, and never a chat sitting in an inactive tab.
 *
 * One frame, not one per chat: bare `Viewing(s, true)` means "viewing exactly s"
 * and REPLACES the broker's set, because every other client switches chats that
 * way. Sending two such frames would leave only the last one. `session` is still
 * filled with the first id so an older broker that ignores `sessions` degrades to
 * correct single-chat behaviour instead of nothing.
 *
 * With nothing visible, send the null-session frame the list view sends, so the
 * broker clears this device's state instead of keeping a stale one.
 *
 * One visible chat is the bare form; N ids go in one frame's `sessions`;
 * nothing visible is the null-session not-visible frame.
 */
fun viewingFramesFor(visibleChatSessionIds: List<String>): List<ClientFrame.Viewing> = when {
    visibleChatSessionIds.isEmpty() -> listOf(ClientFrame.Viewing(null, false))
    else -> listOf(
        ClientFrame.Viewing(
            session = visibleChatSessionIds.first(),
            visible = true,
            // Only when there really are several. One visible chat — the case
            // every client and every existing test already covers — puts the
            // exact same bytes on the wire as before workspaces existed.
            sessions = visibleChatSessionIds.takeIf { it.size > 1 },
        ),
    )
}

fun framesForSnapshot(snapshot: WorkspaceViewingSnapshot?): List<ClientFrame.Viewing> {
    if (snapshot == null || !snapshot.appForeground) {
        return listOf(ClientFrame.Viewing(null, false))
    }
    val ids = snapshot.visibleChatSessionIds
    if (ids.isEmpty()) return listOf(ClientFrame.Viewing(null, true))
    return viewingFramesFor(ids)
}

/**
 * Session id whose host should receive `Viewing(null, false)` when switching
 * workspaces. Null when there is no previous snapshot or the workspace did not
 * change — the next send can target the new snapshot's first id / active client.
 */
fun previousHostClearSessionId(
    previous: WorkspaceViewingSnapshot?,
    next: WorkspaceViewingSnapshot?,
): String? {
    if (previous == null || next == null) return null
    if (previous.workspaceId == next.workspaceId) return null
    return previous.visibleChatSessionIds.firstOrNull()
}
