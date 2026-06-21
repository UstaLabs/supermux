package dev.supermux.proto

import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishResult
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    val id: String = "",
    val name: String,
    val workdir: String,
    val agent: String,
    val status: String? = null,
    val mute: Boolean? = null,
    val connected: Boolean? = null,
    val model: String? = null,
    val repo_root: String? = null,
    val role: String? = null,
    /** Worktree session's pinned branch (present only for worktree-backed sessions). */
    val session_branch: String? = null,
    /** Last/in-flight finish job for this session (mirrors the broker session record). */
    val finish_job: FinishJobDto? = null,
)

/** A finish job's outcome/state machine, broadcast on the `finish_job` WS frame and
 *  carried on the session snapshot. Mirrors the broker's `FinishJob`
 *  (src/core/worktree/finish-job.ts): the terminal result lives in [outcome]. */
@Serializable
data class FinishJobDto(
    val sessionId: String = "",
    val action: String = "merge",     // merge | pr | keep | discard
    val status: String = "running",   // running | done | failed
    val stage: String? = null,
    val outcome: FinishResult? = null,
    val startedAt: Double = 0.0,       // epoch millis (Date.now())
    val endedAt: Double? = null,
)

@Serializable
data class LogEntry(
    val id: String,
    val ts: String,
    val direction: String,
    val text: String? = null,
    val op: String? = null,
    val channel: String? = null,
    val chat_id: String? = null,
    val message_id: String? = null,
    val attachments: List<Attachment>? = null,
)

@Serializable
data class Attachment(
    val file_id: String,
    val kind: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val name: String? = null,
)

@Serializable
data class ActivityEvent(
    val ts: String,
    val kind: String,
    val title: String? = null,
    val seq: Int? = null,
    val tool: String? = null,
    val detail: String? = null,
    val phase: String? = null,
    val callId: String? = null,
)

@Serializable
data class AgentStatus(val phase: String, val since: Long? = null)

@Serializable
data class SendArgs(val text: String = "", val attachments: List<String>? = null)

@Serializable
data class ControlAction(val kind: String, val muted: Boolean? = null)

@Serializable
data class SlashCommand(
    val id: String,
    val family: String,
    val name: String,
    val sigil: String = "/",
    val description: String? = null,
    val insertText: String? = null,
    val action: ControlAction? = null,
)

@Serializable
sealed interface ServerFrame {
    @Serializable @SerialName("snapshot")
    data class Snapshot(
        val sessions: List<SessionInfo> = emptyList(),
        val logs: Map<String, List<LogEntry>> = emptyMap(),
        val activity: Map<String, List<ActivityEvent>> = emptyMap(),
        val agentState: Map<String, AgentStatus> = emptyMap(),
        val commands: Map<String, List<SlashCommand>> = emptyMap(),
        val commandsResolved: Map<String, Boolean> = emptyMap(),
    ) : ServerFrame

    @Serializable @SerialName("session_added")
    data class SessionAdded(val session: SessionInfo) : ServerFrame

    @Serializable @SerialName("session_removed")
    data class SessionRemoved(val id: String) : ServerFrame

    @Serializable @SerialName("agent_state")
    data class AgentState(
        val session: String,
        val phase: String,
        val tool: String? = null,
        val since: Long? = null,
        val workingSince: Long? = null,
    ) : ServerFrame

    @Serializable @SerialName("agent_error")
    data class AgentError(
        val session: String,
        val errorType: String? = null,
        val errorMessage: String? = null,
    ) : ServerFrame

    @Serializable @SerialName("message_append")
    data class MessageAppend(val session: String, val entry: LogEntry) : ServerFrame

    @Serializable @SerialName("activity_append")
    data class ActivityAppend(val session: String, val event: ActivityEvent) : ServerFrame

    @Serializable @SerialName("commands_changed")
    data class CommandsChanged(
        val session: String,
        val commands: List<SlashCommand> = emptyList(),
        val resolved: Boolean = false,
    ) : ServerFrame

    @Serializable @SerialName("fs_changed")
    data class FsChanged(val session: String, val paths: List<String> = emptyList()) : ServerFrame

    // Finish job lifecycle: the broker broadcasts `{type:"finish_job",session,job}`
    // on every job state change (running → done|failed) — src/main.ts:onUpdate.
    @Serializable @SerialName("finish_job")
    data class FinishJobFrame(val session: String = "", val job: FinishJobDto? = null) : ServerFrame

    // Display stream lifecycle: the broker broadcasts these on the control WS
    // (src/main.ts: `{type:"display_added",display}` / `{type:"display_removed",id}`).
    @Serializable @SerialName("display_added")
    data class DisplayAdded(val display: DisplayStream) : ServerFrame

    @Serializable @SerialName("display_removed")
    data class DisplayRemoved(val id: String) : ServerFrame

    @Serializable @SerialName("lsp_status")
    data class LspStatus(
        val session: String? = null,
        val path: String? = null,
        val supported: Boolean = false,
        val serverId: String? = null,
        val label: String? = null,
        val languageId: String? = null,
        val state: String? = null,
        val installLabel: String? = null,
        val requires: String? = null,
        val error: String? = null,
    ) : ServerFrame

    @Serializable @SerialName("lsp_ready")
    data class LspReady(val session: String, val serverId: String) : ServerFrame

    @Serializable @SerialName("lsp_error")
    data class LspError(
        val session: String? = null,
        val serverId: String? = null,
        val error: String? = null,
    ) : ServerFrame

    @Serializable @SerialName("lsp_rpc")
    data class LspRpcIn(val session: String, val serverId: String, val message: String) : ServerFrame

    @Serializable @SerialName("lsp_exit")
    data class LspExit(val session: String, val serverId: String) : ServerFrame

    @Serializable @SerialName("lsp_install_progress")
    data class LspInstallProgress(val serverId: String, val line: String = "") : ServerFrame

    @Serializable @SerialName("lsp_install_done")
    data class LspInstallDone(
        val serverId: String,
        val ok: Boolean = false,
        val error: String? = null,
    ) : ServerFrame
}

@Serializable
sealed interface ClientFrame {
    @Serializable @SerialName("inbound")
    data class Inbound(val session: String, val text: String) : ClientFrame

    @Serializable @SerialName("presence")
    data class Presence(val present: Boolean, val session: String? = null) : ClientFrame

    @Serializable @SerialName("send")
    data class Send(
        val session: String,
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val op: String = "reply",
        val args: SendArgs,
    ) : ClientFrame

    @Serializable @SerialName("editor_open")
    data class EditorOpen(val session: String) : ClientFrame

    @Serializable @SerialName("editor_close")
    data class EditorClose(val session: String) : ClientFrame

    @Serializable @SerialName("lsp_status_query")
    data class LspStatusQuery(val session: String, val path: String) : ClientFrame

    @Serializable @SerialName("lsp_open")
    data class LspOpen(val session: String, val serverId: String) : ClientFrame

    @Serializable @SerialName("lsp_rpc")
    data class LspRpcOut(val session: String, val serverId: String, val message: String) : ClientFrame

    @Serializable @SerialName("lsp_install")
    data class LspInstall(val serverId: String) : ClientFrame

    @Serializable @SerialName("lsp_close")
    data class LspClose(val session: String, val serverId: String) : ClientFrame
}
