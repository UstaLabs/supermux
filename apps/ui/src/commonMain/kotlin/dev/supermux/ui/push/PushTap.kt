// Where a tapped notification lands, as pure functions over the workspace list.
//
// In `:ui` and not in a host module because BOTH hosts that have push need exactly this: Android's
// `MainActivity` resolves an intent extra, and iOS's `MainViewController` resolves the session id
// the APNs tap handler pushed into `IosAppState`. The decision — which workspace owns the chat,
// which view to activate, whether an extra has already been handled, which notifications to
// withdraw — is the same on both, and the alternative was a second copy in `apps/ios` that would
// drift the first time the workspace rules changed. Nothing here touches a platform API.
package dev.supermux.ui.push

import dev.supermux.host.workspaceForSession
import dev.supermux.proto.WorkspaceDto
import dev.supermux.proto.chatSessionId

/** Where a tapped push notification should land: a chat inside a workspace, or the bare session. */
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
    return PushTapResolution(sessionId = sessionId, workspaceId = ws?.id, activeViewId = view?.id)
}

/** Visible chats plus [selectedSessionId], de-duplicated, for notification cancel. */
fun notificationCancelSessionIds(
    visibleChatSessionIds: List<String>,
    selectedSessionId: String? = null,
): List<String> = (visibleChatSessionIds + listOfNotNull(selectedSessionId)).distinct()

/**
 * Whether a push-tap extra should run. [handledSessionId] is the last extra we fully resolved
 * (workspaces were ready). Empty workspaces still apply so the chat opens on cold start, but do
 * not consume — one retry when the list lands.
 */
fun pushTapHandleDecision(
    extraSessionId: String?,
    handledSessionId: String?,
    workspacesReady: Boolean,
): PushTapHandle {
    val sid = extraSessionId?.takeIf { it.isNotBlank() } ?: return PushTapHandle.Skip
    if (sid == handledSessionId) return PushTapHandle.Skip
    return if (workspacesReady) PushTapHandle.ApplyConsume else PushTapHandle.ApplyRetry
}

enum class PushTapHandle { Skip, ApplyRetry, ApplyConsume }
