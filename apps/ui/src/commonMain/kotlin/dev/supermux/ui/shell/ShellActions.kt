// Cluster G1: the seams the SHELL (the view host and, from G7/G8, everything above it) reaches the
// broker through.
//
// Same contract as the settings (E2–E6) and session (F1) holders: an `@Immutable` bundle of
// function references plus a `remember…Actions` builder per store — [HostStore] for a single paired
// host (desktop's wiring) and [FleetStore] for the fleet's per-session routing (Android's). The
// shapes are DESKTOP's, because desktop's `shell/ViewHost.kt` is the base G7 moves: typed results
// and suspend mutations, so a refusal reaches the pane instead of being swallowed.
//
// Members are keyed by SESSION id or WORKSPACE id, never by a `SessionInfo` — the fleet builder
// cannot resolve a `SessionInfo` a caller happens to hold to the right host, and every desktop call
// site already had the id. The two `Flow`s are here rather than collected separately because the
// editor panes take them as parameters: they are stable references, so the bundle stays immutable.
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.supermux.net.AddCommentBody
import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishReadiness
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsRefsResult
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.net.TerminalClient
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.Walkthrough
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.platform.LocalPlatform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Every broker call the view host makes, in one holder.
 *
 * Defaults are the empty/failure answers, so a preview or a test can host a pane without a broker:
 * no files, no diff, no review, and terminals that are simply unavailable.
 */
@Immutable
class ShellActions(
    // ── chat + session controls (the header overflow and the composer's mute row) ──────────────
    val sendMessage: (sessionId: String, text: String) -> Unit = { _, _ -> },
    val rename: (sessionId: String, name: String) -> Unit = { _, _ -> },
    val kill: (sessionId: String) -> Unit = {},
    val setMute: (sessionId: String, muted: Boolean) -> Unit = { _, _ -> },
    /** Continue-in-a-new-conversation. Returns the new session id, null when the broker refused. */
    val continueConversation: suspend (
        source: SessionInfo,
        message: String,
        agent: String?,
        model: String?,
        reasoningLevel: String?,
    ) -> String? = { _, _, _, _, _ -> null },
    /** The host's INSTALLED agent kinds, for the continue dialog's agent picker. */
    val launcherAgents: suspend () -> List<String> = { emptyList() },
    val launcherModels: suspend (agent: String) -> List<ModelInfo> = { emptyList() },
    val launcherReasoning: suspend (agent: String, model: String?) -> ReasoningResponse? = { _, _ -> null },

    // ── finish flow ───────────────────────────────────────────────────────────────────────────
    val finishReadiness: suspend (sessionId: String) -> FinishReadiness? = { null },
    /**
     * Fire-and-forget finish kickoff on the STORE's scope (never the pane's): switching views
     * mid-kickoff must not cancel the POST. [onKickoff] reports only whether it was ACCEPTED.
     */
    val kickoffFinish: (
        sessionId: String,
        action: String,
        skipVerify: Boolean?,
        commitFirst: Boolean?,
        commitMessage: String?,
        onKickoff: (Boolean) -> Unit,
    ) -> Unit = { _, _, _, _, _, done -> done(false) },
    val clearFinishJob: (sessionId: String) -> Unit = {},
    val verifySuggest: suspend (sessionId: String) -> VerifySuggestResult? = { null },
    val verifySave: suspend (sessionId: String, content: String) -> VerifySaveResult? = { _, _ -> null },

    // ── terminals ─────────────────────────────────────────────────────────────────────────────
    /** The agent's own PTY (the chat view's Chat⇄Native pill). Null = no host owns that session. */
    val connectAgentTerminal: (sessionId: String) -> TerminalClient? = { null },
    /** A session-scoped scratch shell. */
    val connectTerminal: (sessionId: String, terminalId: String) -> TerminalClient? = { _, _ -> null },
    /** A workspace-scoped shell in the workspace's work directory (`?workspace=<id>`). */
    val connectWorkspaceTerminal: (workspaceId: String, terminalId: String) -> TerminalClient? =
        { _, _ -> null },

    // ── workspace file system (`/workspaces/:id/fs*`) ──────────────────────────────────────────
    val workspaceFsListResult: suspend (workspaceId: String, path: String) -> Result<List<FsEntry>> =
        { _, _ -> Result.success(emptyList()) },
    val workspaceFsRead: suspend (workspaceId: String, path: String) -> Result<String> =
        { _, _ -> Result.failure(IllegalStateException("No host connected")) },
    val workspaceFsWrite: suspend (workspaceId: String, path: String, content: String) -> Boolean =
        { _, _, _ -> false },
    val workspaceFsSearch: suspend (workspaceId: String, query: String) -> List<FsSearchResult> =
        { _, _ -> emptyList() },
    val workspaceFsDiff: suspend (workspaceId: String, base: String?) -> FsDiffResult? = { _, _ -> null },
    val workspaceFsRefs: suspend (workspaceId: String) -> FsRefsResult? = { null },

    // ── LSP (still session-keyed in this phase) ────────────────────────────────────────────────
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    val lspRpc: Flow<ServerFrame.LspRpcIn> = emptyFlow(),
    val lspStatusQuery: (sessionId: String, path: String) -> Unit = { _, _ -> },
    val lspOpen: (sessionId: String, serverId: String) -> Unit = { _, _ -> },
    val lspRpcOut: (sessionId: String, serverId: String, message: String) -> Unit = { _, _, _ -> },

    // ── review + walkthrough ──────────────────────────────────────────────────────────────────
    val reviewComments: suspend (sessionId: String) -> List<ReviewComment> = { emptyList() },
    val reviewAddComment: suspend (sessionId: String, body: AddCommentBody) -> ReviewComment? =
        { _, _ -> null },
    val reviewResolve: suspend (sessionId: String, commentId: String) -> Boolean = { _, _ -> false },
    val reviewSubmit: suspend (sessionId: String) -> ReviewSubmitResult? = { null },
    val getWalkthrough: suspend (sessionId: String) -> Walkthrough? = { null },
    /**
     * The session's walkthrough holder, or null where the host installed no `WalkthroughSeam`
     * (`Caps.walkthrough` false — reading the holder there THROWS, so the builders check the cap
     * once and every call site just sees null).
     */
    val walkthroughState: (sessionId: String) -> WalkthroughState? = { null },

    // ── displays ──────────────────────────────────────────────────────────────────────────────
    val listDisplays: suspend () -> List<DisplayStream> = { emptyList() },
)

/** [ShellActions] against ONE paired host — desktop's wiring. */
@Composable
fun rememberShellActions(
    app: HostStore,
    /**
     * The store that owns a given session, for the panes that follow a session ACROSS hosts (the
     * diff pane's walkthrough). Defaults to [app], which is what a single-host shell wants.
     */
    appForSession: (String) -> HostStore = { app },
): ShellActions {
    val walkthroughCap = LocalPlatform.current.caps.walkthrough
    return remember(app, appForSession, walkthroughCap) {
        // A session-keyed suspend call needs the SessionInfo the store's review/LSP API takes;
        // resolving it from the store's live list is what every desktop call site did inline.
        fun session(id: String): SessionInfo? = app.sessions.value.firstOrNull { it.id == id }
        ShellActions(
            sendMessage = { id, text -> app.sendMessage(id, text) },
            rename = { id, name -> app.rename(id, name) },
            kill = { id -> app.kill(id) },
            setMute = { id, muted -> app.setMute(id, muted) },
            continueConversation = { source, message, agent, model, level ->
                app.continueConversation(source, message, agent, model, level)
            },
            launcherAgents = { app.launcherAgents() },
            launcherModels = { app.launcherModels(it) },
            launcherReasoning = { agent, model -> app.launcherReasoning(agent, model) },
            finishReadiness = { app.finishReadiness(it) },
            kickoffFinish = { id, action, skipVerify, commitFirst, commitMessage, onKickoff ->
                app.kickoffFinish(id, action, skipVerify, commitFirst, commitMessage, onKickoff)
            },
            clearFinishJob = { app.clearFinishJob(it) },
            verifySuggest = { app.verifySuggest(it) },
            verifySave = { id, content -> app.verifySave(id, content) },
            connectAgentTerminal = { app.connectAgentTerminal(it) },
            connectTerminal = { id, terminalId -> app.connectTerminal(id, terminalId) },
            connectWorkspaceTerminal = { wsId, terminalId -> app.connectWorkspaceTerminal(wsId, terminalId) },
            workspaceFsListResult = { wsId, path -> app.workspaceFsListResult(wsId, path) },
            workspaceFsRead = { wsId, path -> app.workspaceFsRead(wsId, path) },
            workspaceFsWrite = { wsId, path, content -> app.workspaceFsWrite(wsId, path, content) },
            workspaceFsSearch = { wsId, q -> app.workspaceFsSearch(wsId, q) },
            workspaceFsDiff = { wsId, base -> app.workspaceFsDiff(wsId, base) },
            workspaceFsRefs = { wsId -> app.workspaceFsRefs(wsId) },
            lspStatus = app.lspStatus,
            lspRpc = app.lspRpc,
            lspStatusQuery = { id, path -> session(id)?.let { app.lspStatusQuery(it, path) } },
            lspOpen = { id, serverId -> session(id)?.let { app.lspOpen(it, serverId) } },
            lspRpcOut = { id, serverId, message -> session(id)?.let { app.lspRpcOut(it, serverId, message) } },
            reviewComments = { id -> session(id)?.let { app.reviewComments(it) }.orEmpty() },
            reviewAddComment = { id, body -> session(id)?.let { app.reviewAddComment(it, body) } },
            reviewResolve = { id, commentId -> session(id)?.let { app.reviewResolve(it, commentId) } == true },
            reviewSubmit = { id -> session(id)?.let { app.reviewSubmit(it) } },
            getWalkthrough = { id ->
                val owner = appForSession(id)
                owner.sessions.value.firstOrNull { it.id == id }?.let { owner.getWalkthrough(it) }
            },
            walkthroughState = { id ->
                if (walkthroughCap) appForSession(id).walkthroughState<WalkthroughState>(id) else null
            },
            listDisplays = { app.listDisplays() },
        )
    }
}

/** [ShellActions] against the fleet — Android's wiring; every id routes to its owning host. */
@Composable
fun rememberShellActions(fleet: FleetStore): ShellActions {
    val walkthroughCap = LocalPlatform.current.caps.walkthrough
    return remember(fleet, walkthroughCap) {
        ShellActions(
            sendMessage = { id, text -> fleet.sendMessage(id, text) },
            rename = { id, name -> fleet.rename(id, name) },
            kill = { id -> fleet.kill(id) },
            setMute = { id, muted -> fleet.setMute(id, muted) },
            continueConversation = { source, message, agent, model, level ->
                fleet.continueConversation(source, message, agent, model, level)
            },
            launcherAgents = { fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } },
            launcherModels = { fleet.launcherModels(it) },
            launcherReasoning = { agent, model -> fleet.launcherReasoning(agent, model) },
            finishReadiness = { fleet.finishReadiness(it) },
            kickoffFinish = { id, action, skipVerify, commitFirst, commitMessage, onKickoff ->
                fleet.finish(id, action, skipVerify, commitFirst, commitMessage, onKickoff = onKickoff)
            },
            clearFinishJob = { fleet.clearFinishJob(it) },
            verifySuggest = { fleet.verifySuggest(it) },
            verifySave = { id, content -> fleet.verifySave(id, content) },
            connectAgentTerminal = { fleet.connectAgentTerminal(it) },
            connectTerminal = { id, terminalId -> fleet.connectTerminal(id, terminalId) },
            connectWorkspaceTerminal = { wsId, terminalId -> fleet.connectWorkspaceTerminal(wsId, terminalId) },
            workspaceFsListResult = { wsId, path -> fleet.workspaceFsListResult(wsId, path) },
            workspaceFsRead = { wsId, path -> fleet.workspaceFsRead(wsId, path) },
            workspaceFsWrite = { wsId, path, content -> fleet.workspaceFsWrite(wsId, path, content) },
            workspaceFsSearch = { wsId, q -> fleet.workspaceFsSearch(wsId, q) },
            workspaceFsDiff = { wsId, base -> fleet.workspaceFsDiff(wsId, base) },
            workspaceFsRefs = { wsId -> fleet.workspaceFsRefs(wsId) },
            lspStatus = fleet.lspStatus,
            lspRpc = fleet.lspRpc,
            lspStatusQuery = { id, path -> fleet.lspStatusQuery(id, path) },
            lspOpen = { id, serverId -> fleet.lspOpen(id, serverId) },
            lspRpcOut = { id, serverId, message -> fleet.lspRpcOut(id, serverId, message) },
            reviewComments = { id -> fleet.reviewComments(id) },
            reviewAddComment = { id, body -> fleet.reviewAddComment(id, body) },
            reviewResolve = { id, commentId -> fleet.reviewResolve(id, commentId) },
            reviewSubmit = { id -> fleet.reviewSubmit(id) },
            getWalkthrough = { id -> fleet.getWalkthrough(id) },
            walkthroughState = { id ->
                if (walkthroughCap) fleet.walkthroughState<WalkthroughState>(id) else null
            },
            listDisplays = { fleet.listDisplays() },
        )
    }
}
