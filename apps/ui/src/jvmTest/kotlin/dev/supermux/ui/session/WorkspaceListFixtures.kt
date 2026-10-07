package dev.supermux.ui.session

import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun workspaceChatView(id: String, sessionId: String, workspaceId: String = "w") = ViewDto(
    id = id,
    workspaceId = workspaceId,
    kind = "chat",
    state = JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))),
)

internal fun workspaceTermView(id: String, workspaceId: String = "w") = ViewDto(
    id = id,
    workspaceId = workspaceId,
    kind = "terminal",
)

internal fun workspaceDto(
    id: String = "w1",
    name: String = id,
    workdir: String = "/home/u/projects/app",
    repoRoot: String? = null,
    views: List<ViewDto> = emptyList(),
    activeViewId: String? = null,
    primarySessionId: String? = null,
    status: String = "active",
    archivedAt: String? = null,
    sortOrder: Int = 0,
) = WorkspaceDto(
    id = id,
    name = name,
    status = status,
    workdir = workdir,
    repoRoot = repoRoot,
    activeViewId = activeViewId,
    primarySessionId = primarySessionId,
    archivedAt = archivedAt,
    sortOrder = sortOrder,
    views = views,
)
