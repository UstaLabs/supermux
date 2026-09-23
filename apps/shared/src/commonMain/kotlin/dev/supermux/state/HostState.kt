package dev.supermux.state

import dev.supermux.net.DisplayStream
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.WorkspaceDto

/** Everything the broker's frames drive. Immutable; [reduceHostFrame] returns a new copy (or the same instance). */
data class HostState(
    val sessions: List<SessionInfo> = emptyList(),
    val workspaces: List<WorkspaceDto> = emptyList(),
    val archivedWorkspaces: List<WorkspaceDto> = emptyList(),
    val messages: Map<String, List<LogEntry>> = emptyMap(),
    val activity: Map<String, List<ActivityEvent>> = emptyMap(),
    val agentState: Map<String, AgentStatus> = emptyMap(),
    val agentErrors: Map<String, ServerFrame.AgentError> = emptyMap(),
    val bgTasks: Map<String, List<ServerFrame.BgTask>> = emptyMap(),
    val commands: Map<String, List<SlashCommand>> = emptyMap(),
    val commandsResolved: Map<String, Boolean> = emptyMap(),
    val lastRead: Map<String, String> = emptyMap(),
    val finishJobs: Map<String, FinishJobDto> = emptyMap(),
    val lspStatus: Map<String, ServerFrame.LspStatus> = emptyMap(),
    val lspInstallLog: Map<String, List<String>> = emptyMap(),
    val lspInstallDone: Map<String, ServerFrame.LspInstallDone> = emptyMap(),
    val displays: List<DisplayStream> = emptyList(),
    /** The broker's persistent project catalog. */
    val projects: List<ProjectDto> = emptyList(),
    /**
     * True once a snapshot or `projects_changed` frame carried a catalog — tells an old broker
     * (path grouping only) apart from a new broker with no projects yet.
     */
    val projectCatalogKnown: Boolean = false,
    /** Open permission / question prompts keyed by session id. */
    val requests: Map<String, List<PromptRequest>> = emptyMap(),
    /** Last `{type:"error", reason}` from the broker (permission mode, request respond, …). */
    val lastError: String? = null,
    /** Catalog of permission modes per agent (from the snapshot). */
    val permissionModes: Map<String, List<dev.supermux.proto.PermissionModeInfo>> = emptyMap(),
)

/** Outcome of adding a location to a persistent project. */
sealed interface ProjectLocationResult {
    data class Added(val project: ProjectDto) : ProjectLocationResult
    /** The path already belongs to [projectId] (HTTP 409). */
    data class Conflict(val projectId: String) : ProjectLocationResult
    data object Failed : ProjectLocationResult
}
