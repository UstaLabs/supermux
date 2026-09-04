package dev.supermux.android.host

import dev.supermux.android.workspace.phoneTabModel
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds

/**
 * The Android-layout half of viewing presence: which chats are actually ON SCREEN given the
 * phone tab model / tablet layout tree. Everything platform-neutral (the snapshot type, the
 * surface-visible rule, the frame shapes) lives in shared `dev.supermux.host.ViewingPresence`.
 *
 * Phone: only the active tab's chat (if that tab is a chat).
 * Tablet: every **on-screen** chat (`collectActiveViewIds` ∩ chat views).
 * A chat sitting in a background tab is not on screen (spec §11).
 */
fun visibleChatIdsForAndroid(
    tablet: Boolean,
    layout: LayoutNode?,
    views: List<ViewDto>,
    activeViewId: String?,
): List<String> {
    val byId = views.associateBy { it.id }
    if (tablet) {
        val ids = layout?.let { collectActiveViewIds(it) } ?: views.map { it.id }
        return ids.mapNotNull { id -> byId[id]?.chatSessionId() }.distinct()
    }
    val selected = phoneTabModel(layout, activeViewId).selectedId
    return listOfNotNull(selected?.let { byId[it]?.chatSessionId() })
}

fun visibleChatIdsForAndroid(tablet: Boolean, workspace: WorkspaceDto, layout: LayoutNode?): List<String> =
    visibleChatIdsForAndroid(tablet, layout, workspace.views, workspace.activeViewId)
