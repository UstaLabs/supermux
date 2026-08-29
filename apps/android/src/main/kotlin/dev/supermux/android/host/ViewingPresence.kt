package dev.supermux.android.host

import dev.supermux.android.workspace.phoneTabModel
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds

/**
 * Atomic viewing snapshot for one workspace. Carries its own [workspaceId] so a
 * workspace switch cannot pair the new selection with the previous tree.
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
 * Phone: only the active tab's chat (if that tab is a chat).
 * Tablet: every chat view in the rendered tree (`collectViewIds` ∩ chat views).
 */
fun visibleChatIdsForAndroid(
    tablet: Boolean,
    layout: LayoutNode?,
    views: List<ViewDto>,
    activeViewId: String?,
): List<String> {
    val byId = views.associateBy { it.id }
    if (tablet) {
        val ids = layout?.let { collectViewIds(it) } ?: views.map { it.id }
        return ids.mapNotNull { id -> byId[id]?.chatSessionId() }.distinct()
    }
    val selected = phoneTabModel(layout, activeViewId).selectedId
    return listOfNotNull(selected?.let { byId[it]?.chatSessionId() })
}

fun visibleChatIdsForAndroid(tablet: Boolean, workspace: WorkspaceDto, layout: LayoutNode?): List<String> =
    visibleChatIdsForAndroid(tablet, layout, workspace.views, workspace.activeViewId)

/**
 * The Viewing frames to send for the currently visible chat views (spec §11).
 * Identical bytes to desktop: one visible chat is the bare form; N ids go in
 * one frame's `sessions`; nothing visible is the null-session not-visible frame.
 */
fun viewingFramesFor(visibleChatSessionIds: List<String>): List<ClientFrame.Viewing> = when {
    visibleChatSessionIds.isEmpty() -> listOf(ClientFrame.Viewing(null, false))
    else -> listOf(
        ClientFrame.Viewing(
            session = visibleChatSessionIds.first(),
            visible = true,
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

data class PushTapResolution(
    val sessionId: String,
    val workspaceId: String?,
    val activeViewId: String?,
) {
    val sessionOnly: Boolean get() = workspaceId == null
}

fun resolvePushTap(sessionId: String, workspaces: List<WorkspaceDto>): PushTapResolution {
    val ws = workspaceForSession(workspaces, sessionId)
    val view = ws?.views?.firstOrNull { it.chatSessionId() == sessionId }
    return PushTapResolution(
        sessionId = sessionId,
        workspaceId = ws?.id,
        activeViewId = view?.id,
    )
}

fun notificationCancelSessionIds(visibleChatSessionIds: List<String>): List<String> =
    visibleChatSessionIds
