package dev.supermux.android.workspace

import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.viewTitle
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class PhoneTabModel(
    val viewIds: List<String>,
    val selectedId: String?,
)

/** Flattened phone tabs: document-order view ids, selected = activeViewId or first. */
fun phoneTabModel(layout: LayoutNode?, activeViewId: String?): PhoneTabModel {
    val ids = layout?.let { collectViewIds(it) } ?: emptyList()
    val selected = when {
        activeViewId != null && activeViewId in ids -> activeViewId
        else -> ids.firstOrNull()
    }
    return PhoneTabModel(ids, selected)
}

/** PATCH used by [dev.supermux.android.AppViewModel.setActiveView] — never includes a layout. */
fun activeViewPatchBody(activeViewId: String): PatchWorkspaceBody =
    PatchWorkspaceBody(name = null, layout = null, activeViewId = activeViewId)

/**
 * Whether opening a session should PATCH the workspace's chat view active.
 *
 * Runs once per [selected] change ([lastActivated] is the last selection we
 * fully resolved). Empty workspaces still retry so cold start can activate
 * once the list lands — a later [WorkspaceChanged] for the same selection
 * must not re-activate (that frame is the broker acknowledging a tab switch).
 */
fun chatActivationDecision(
    selected: String?,
    lastActivated: String?,
    ws: WorkspaceDto?,
    chatView: ViewDto?,
): ChatActivationHandle {
    val sid = selected?.takeIf { it.isNotBlank() } ?: return ChatActivationHandle.Skip
    if (sid == lastActivated) return ChatActivationHandle.Skip
    if (ws == null) return ChatActivationHandle.ApplyRetry
    if (chatView == null) return ChatActivationHandle.Skip
    return ChatActivationHandle.ApplyConsume
}

enum class ChatActivationHandle { Skip, ApplyRetry, ApplyConsume }

fun phoneAddKinds(): List<NewViewKind> = listOf(
    NewViewKind.TERMINAL,
    NewViewKind.EDITOR,
    NewViewKind.DIFF,
    NewViewKind.DISPLAY,
)

fun addViewState(kind: NewViewKind, nowMillis: Long = 0L): JsonObject = when (kind) {
    NewViewKind.TERMINAL -> buildJsonObject {
        put("scope", JsonPrimitive("workspace"))
        put("terminalId", JsonPrimitive("t" + nowMillis.toString().takeLast(6)))
    }
    NewViewKind.EDITOR -> buildJsonObject { put("mode", JsonPrimitive("tree")) }
    NewViewKind.DIFF -> buildJsonObject { put("mode", JsonPrimitive("diff")) }
    NewViewKind.DISPLAY -> buildJsonObject { put("displayId", JsonPrimitive("")) }
    NewViewKind.CHAT -> buildJsonObject { }
}

fun closeNeedsConfirm(kind: String): Boolean =
    kind == "chat" || kind == "terminal" || kind == "display"

fun closeNeedsConfirm(view: ViewDto): Boolean = closeNeedsConfirm(view.kind)

/** Spec §9.3 — one question naming what the close stops. */
fun closeConfirmText(view: ViewDto, sessionName: String?): String = when (view.kind) {
    "chat" -> {
        val name = sessionName?.takeIf { it.isNotBlank() } ?: viewTitle(view)
        "Close this chat? This archives the session $name."
    }
    "terminal" -> "Close this terminal? This kills it."
    "display" -> "Close this display? This stops the stream."
    else -> "Close this view?"
}
