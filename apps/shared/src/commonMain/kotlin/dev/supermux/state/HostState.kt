package dev.supermux.state

import dev.supermux.net.DisplayStream
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LogEntry
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
)
