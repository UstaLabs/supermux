// The seams the New-Session launcher and the session list reach the broker through (cluster F1).
//
// Same contract as the settings holders (clusters E2–E6): an `@Immutable` bundle of function
// references plus a `remember…Actions` builder per store — [HostStore] for a single paired host
// (desktop's wiring, where the launcher already targets ONE host and the shell picks which) and
// [FleetStore] for the fleet's active host / per-session routing (Android's wiring).
//
// The shapes are DESKTOP's: typed results and suspend mutations, so a refusal reaches the screen
// instead of being swallowed by a fire-and-forget launch. Where Android's store wrapper was looser
// it was retyped in `:shared` rather than dumbing the holder down (F1 retyped `FleetStore.resume`
// to `suspend … Boolean`). Two shapes are deliberately unified here:
//   - [LauncherActions.searchForge] returns `ForgeSearchResponse?` — a null response is a FAILED
//     search, which Android's `List<RemoteRepo>` could not tell apart from "no matches".
//   - [LauncherActions.createSessionWithFirstMessage] takes desktop's parameter order (…, text,
//     staged, worktree, baseBranch, replaceDraftId) and THROWS the broker's own refusal, which is
//     what both launchers turn into their inline error text.
//
// Navigation callbacks ([LauncherActions.openSession], host selection) cannot come from a store, so
// the builders take them and hold them through `rememberUpdatedState` — the bundle itself is
// remembered on the store alone, so a call site that captures mutable shell state (Android's
// `selected`/`navController`) never gets a stale lambda.
package dev.supermux.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoInfo
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.proto.PermissionModeInfo
import dev.supermux.proto.ProjectDto
import dev.supermux.proto.SlashCommand
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.ProjectLocationResult
import dev.supermux.state.StagedUpload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Every broker call (and the two navigations) the New-Session launcher makes, in one holder.
 *
 * Defaults are the empty/failure answers, so a preview or a test can build a partial launcher
 * without a broker: no projects, no models, no forges, and a submit that refuses.
 */
@Immutable
class LauncherActions(
    /** Recent project directories on the target host. */
    val listProjects: suspend () -> List<String> = { emptyList() },
    /**
     * The target host's persistent project catalog, LIVE: a `StateFlow` projection that follows
     * `projects_changed` broadcasts and the launcher's host pill, so a rename, a new location or a
     * new project shows up without the launcher refetching. Empty when the broker predates the
     * catalog (the launcher then keeps its path picker) — a one-shot `GET /project-catalog` would
     * go stale the moment another client edited a project, which is why this is a flow and not
     * the plan's `suspend () -> List`.
     */
    val projectCatalog: Flow<List<ProjectDto>> = flowOf(emptyList()),
    /** Register [path] as a location of [projectId] on the target host (409 → Conflict). */
    val addProjectLocation: suspend (projectId: String, path: String) -> ProjectLocationResult =
        { _, _ -> ProjectLocationResult.Failed },
    /** A catalog project's image bytes (authenticated), null without one or on failure. */
    val projectImage: suspend (project: ProjectDto) -> ByteArray? = { null },
    /** `null` = transport failure; an INVALID path is a non-null `PathValidation(ok=false)`. */
    val validatePath: suspend (path: String) -> PathValidation? = { null },
    val launcherModels: suspend (agent: String) -> List<ModelInfo> = { emptyList() },
    val launcherReasoning: suspend (agent: String, model: String?) -> ReasoningResponse? = { _, _ -> null },
    /** `fetch=true` refreshes origin's remote-tracking refs (once per repo, on picker open). */
    val launcherRepoInfo: suspend (workdir: String, fetch: Boolean) -> RepoInfo? = { _, _ -> null },
    /** Agent slash commands for the composer's "/" menu — Android's `loadCommands`. */
    val launcherCommands: suspend (agent: String, workdir: String) -> List<SlashCommand> =
        { _, _ -> emptyList() },
    /** The host's INSTALLED agent kinds; empty keeps each launcher's own fallback list. */
    val launcherAgents: suspend () -> List<String> = { emptyList() },
    /** Snapshot catalog of permission modes, keyed by agent. */
    val permissionModes: Map<String, List<PermissionModeInfo>> = emptyMap(),
    val listForges: suspend () -> List<ForgeConnection> = { emptyList() },
    /** `null` = the search FAILED (distinguishable from an empty success). */
    val searchForge: suspend (query: String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
    val cloneForge: suspend (connectionId: String, owner: String, name: String) -> String? =
        { _, _, _ -> null },
    val createLocalRepo: suspend (name: String) -> String? = { null },
    val createForge: suspend (connectionId: String, name: String) -> String? = { _, _ -> null },
    /** Dictation cleanup vocabulary; empty = no glossary. */
    val fetchGlossary: suspend () -> List<String> = { emptyList() },
    /** Clean up a typed draft (no session yet → the broker's id-less /transcribe). */
    val transcribeDraft: suspend (draft: String) -> String? = { null },
    val transcribeAudio: suspend (bytes: ByteArray, filename: String, mime: String) -> String? = { _, _, _ -> null },
    /**
     * Spawn + send the first message. Returns the new session id and THROWS the broker's own
     * message on refusal (bad workdir, spawn 4xx) — the launchers show that text inline.
     */
    val createSessionWithFirstMessage: suspend (
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        staged: List<StagedUpload>,
        worktree: Boolean,
        baseBranch: String?,
        replaceDraftId: String?,
        permissionMode: String?,
    ) -> String = { _, _, _, _, _, _, _, _, _, _ -> error("No host connected") },
    /** Save as a draft session (no agent process). Returns the draft's id, null on failure. */
    val createDraftSession: suspend (
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        replaceDraftId: String?,
    ) -> String? = { _, _, _, _, _, _ -> null },
    /** Retarget every loader above at another paired host (the launcher's host pill). */
    val setActiveHost: (recordId: String) -> Unit = {},
    /** Open the session the launcher just created — navigation, not a broker call. */
    val openSession: (sessionId: String) -> Unit = {},
)

/** [LauncherActions] against ONE paired host — desktop's wiring (the shell picks the host). */
@Composable
fun rememberLauncherActions(
    app: HostStore,
    onSelectHost: (String) -> Unit = {},
    onOpenSession: (String) -> Unit = {},
): LauncherActions {
    val selectHost by rememberUpdatedState(onSelectHost)
    val openSession by rememberUpdatedState(onOpenSession)
    return remember(app) {
        LauncherActions(
            listProjects = { app.listProjects() },
            projectCatalog = combine(app.projectCatalogKnown, app.projects) { known, list ->
                if (known) list else emptyList()
            },
            addProjectLocation = { id, path -> app.addProjectLocation(id, path) },
            projectImage = { app.projectImageBytes(it) },
            validatePath = { app.validatePath(it) },
            launcherModels = { app.launcherModels(it) },
            launcherReasoning = { agent, model -> app.launcherReasoning(agent, model) },
            launcherRepoInfo = { workdir, fetch -> app.launcherRepoInfo(workdir, fetch) },
            launcherCommands = { agent, workdir -> app.launcherCommands(agent, workdir) },
            launcherAgents = { app.launcherAgents() },
            listForges = { app.listForges() },
            searchForge = { app.searchForge(it) },
            cloneForge = { cid, owner, name -> app.cloneForge(cid, owner, name) },
            createLocalRepo = { app.createLocalRepo(it) },
            createForge = { cid, name -> app.createForge(cid, name) },
            fetchGlossary = { app.fetchGlossary().orEmpty() },
            transcribeDraft = { draft -> app.transcribeDraft(null, draft)?.text },
            transcribeAudio = { bytes, name, mime -> app.transcribeAudio(null, bytes, name, mime)?.text },
            createSessionWithFirstMessage = { wd, agent, model, level, text, staged, wt, base, replace, perm ->
                app.createSessionWithFirstMessageOrThrow(
                    wd, agent, model, level, text, staged, wt, base, replaceDraftId = replace,
                    permissionMode = perm,
                )
            },
            createDraftSession = { wd, agent, model, level, text, replace ->
                app.createDraftSession(
                    wd, agent, model, text, reasoningLevel = level, replaceDraftId = replace,
                )
            },
            setActiveHost = { selectHost(it) },
            openSession = { openSession(it) },
        )
    }
}

/** [LauncherActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberLauncherActions(
    fleet: FleetStore,
    onOpenSession: (String) -> Unit = {},
): LauncherActions {
    val openSession by rememberUpdatedState(onOpenSession)
    return remember(fleet) {
        LauncherActions(
            listProjects = { fleet.listProjects() },
            // Same routing as every other launcher call: the ACTIVE host (the launcher's host pill).
            // Same routing as every other launcher call: the ACTIVE host (the launcher's host pill),
            // bound to its LIVE HostStore so a connection rebuild rebinds (see FleetStore).
            projectCatalog = fleet.activeProjectCatalog,
            addProjectLocation = { id, path ->
                fleet.activeApp()?.addProjectLocation(id, path) ?: ProjectLocationResult.Failed
            },
            projectImage = { fleet.activeApp()?.projectImageBytes(it) },
            validatePath = { fleet.validatePath(it) },
            launcherModels = { fleet.launcherModels(it) },
            launcherReasoning = { agent, model -> fleet.launcherReasoning(agent, model) },
            launcherRepoInfo = { workdir, fetch -> fleet.launcherRepoInfo(workdir, fetch) },
            launcherCommands = { agent, workdir -> fleet.launcherCommands(agent, workdir) },
            launcherAgents = { fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } },
            listForges = { fleet.listForges() },
            searchForge = { fleet.searchForge(it) },
            cloneForge = { cid, owner, name -> fleet.cloneForge(cid, owner, name) },
            createLocalRepo = { fleet.createLocalRepo(it) },
            createForge = { cid, name -> fleet.createForge(cid, name) },
            fetchGlossary = { fleet.fetchGlossary().orEmpty() },
            transcribeDraft = { draft -> fleet.transcribeDraft(null, draft) },
            transcribeAudio = { bytes, name, mime -> fleet.transcribeAudio(null, bytes, name, mime) },
            createSessionWithFirstMessage = { wd, agent, model, level, text, staged, wt, base, replace, perm ->
                fleet.createSessionWithFirstMessageOrThrow(
                    wd, agent, model, level, text, staged, wt, base, replaceDraftId = replace,
                    permissionMode = perm,
                )
            },
            createDraftSession = { wd, agent, model, level, text, replace ->
                fleet.createDraftSession(
                    wd, agent, model, text, reasoningLevel = level, replaceDraftId = replace,
                )
            },
            setActiveHost = { fleet.setActiveHost(it) },
            openSession = { openSession(it) },
        )
    }
}

/**
 * Every broker call the session list makes, in one holder.
 *
 * Row ops are keyed by SESSION id and host ops by host RECORD id; the fleet builder routes each to
 * the owning host, the single-host builder sends them all to its own. [renameHost] / [forgetHost]
 * are fleet-level by nature — the [HostStore] builder takes them as callbacks so a single-host
 * shell can still wire its own (or leave them as the no-op it already behaves like).
 */
@Immutable
class SessionListActions(
    val listProjects: suspend () -> List<String> = { emptyList() },
    val validatePath: suspend (path: String) -> PathValidation? = { null },
    val rename: (id: String, name: String) -> Unit = { _, _ -> },
    /** Kill/archive a session. [onDone] fires once the store has acted (a host that shed the
     *  session still has to prune its own keep-alive layer), so it is part of the shape. */
    val kill: (id: String, onDone: () -> Unit) -> Unit = { _, done -> done() },
    val setMute: (id: String, muted: Boolean) -> Unit = { _, _ -> },
    /** Resume from archive; `false` = the broker refused. Suspend since F1 retyped the fleet's. */
    val resume: suspend (id: String) -> Boolean = { false },
    val reorderSessions: (orderedIds: List<String>) -> Unit = {},
    val reorderWorkspaces: (orderedIds: List<String>) -> Unit = {},
    val archiveWorkspace: (workspaceId: String) -> Unit = {},
    val restoreWorkspace: (workspaceId: String) -> Unit = {},
    val renameHost: (recordId: String, name: String) -> Unit = { _, _ -> },
    val forgetHost: (recordId: String) -> Unit = {},
    /** Worktree lookup for the archive dialogs (spec 2026-09-22-explicit-worktree-cleanup), asked
     *  of the host that OWNS the session / workspace being archived (never just the active host). */
    val worktreeForSessionWorkdir: suspend (sessionId: String, workdir: String) -> WorktreeForWorkdirDto? = { _, _ -> null },
    val worktreeForWorkspaceWorkdir: suspend (workspaceId: String, workdir: String) -> WorktreeForWorkdirDto? = { _, _ -> null },
    /** Archive + delete exactly [worktreeIds] (the ids the dialog displayed and the user confirmed).
     *  Fire-and-forget on the store's own scope (the dialog is already gone, and the screen may be
     *  too, by the time a long delete finishes); `onDone` gets the per-worktree results, null when
     *  the archive request itself failed. */
    val killAndDeleteWorktree: (id: String, worktreeIds: List<String>, onDone: (List<WorktreeDeleteResultDto>?) -> Unit) -> Unit =
        { _, _, onDone -> onDone(null) },
    val archiveWorkspaceAndDeleteWorktree: (workspaceId: String, worktreeIds: List<String>, onDone: (List<WorktreeDeleteResultDto>?) -> Unit) -> Unit =
        { _, _, onDone -> onDone(null) },
)

/**
 * The same holder with the two WORKSPACE ops wrapped, so a shell can add its own selection side
 * effects (deselect the chat it just archived, close the archived overlay it just restored)
 * without rebuilding the bundle or teaching the screen about them.
 */
fun SessionListActions.withWorkspaceOps(
    archiveWorkspace: (workspaceId: String) -> Unit = this.archiveWorkspace,
    restoreWorkspace: (workspaceId: String) -> Unit = this.restoreWorkspace,
): SessionListActions = SessionListActions(
    listProjects = listProjects,
    validatePath = validatePath,
    rename = rename,
    kill = kill,
    setMute = setMute,
    resume = resume,
    reorderSessions = reorderSessions,
    reorderWorkspaces = reorderWorkspaces,
    archiveWorkspace = archiveWorkspace,
    restoreWorkspace = restoreWorkspace,
    renameHost = renameHost,
    forgetHost = forgetHost,
    worktreeForSessionWorkdir = worktreeForSessionWorkdir,
    worktreeForWorkspaceWorkdir = worktreeForWorkspaceWorkdir,
    killAndDeleteWorktree = killAndDeleteWorktree,
    archiveWorkspaceAndDeleteWorktree = archiveWorkspaceAndDeleteWorktree,
)

/** [SessionListActions] against ONE paired host — single-host desktop. */
@Composable
fun rememberSessionListActions(
    app: HostStore,
    onRenameHost: (String, String) -> Unit = { _, _ -> },
    onForgetHost: (String) -> Unit = {},
): SessionListActions {
    val renameHost by rememberUpdatedState(onRenameHost)
    val forgetHost by rememberUpdatedState(onForgetHost)
    return remember(app) {
        SessionListActions(
            listProjects = { app.listProjects() },
            validatePath = { app.validatePath(it) },
            rename = { id, name -> app.rename(id, name) },
            kill = { id, done -> app.kill(id, done) },
            setMute = { id, muted -> app.setMute(id, muted) },
            resume = { id -> app.resume(id) },
            reorderSessions = { app.reorderSessions(it) },
            reorderWorkspaces = { app.reorderWorkspaces(it) },
            archiveWorkspace = { app.archiveWorkspace(it) },
            restoreWorkspace = { app.restoreWorkspace(it) },
            renameHost = { id, name -> renameHost(id, name) },
            forgetHost = { id -> forgetHost(id) },
            worktreeForSessionWorkdir = { _, workdir -> app.worktreeForWorkdir(workdir) },
            worktreeForWorkspaceWorkdir = { _, workdir -> app.worktreeForWorkdir(workdir) },
            killAndDeleteWorktree = { id, ids, onDone -> app.killAndDeleteWorktree(id, ids, onDone) },
            archiveWorkspaceAndDeleteWorktree = { id, ids, onDone -> app.archiveWorkspaceAndDeleteWorktree(id, ids, onDone) },
        )
    }
}

/** [SessionListActions] over the whole fleet — each op routed to the OWNING host. */
@Composable
fun rememberSessionListActions(fleet: FleetStore): SessionListActions = remember(fleet) {
    SessionListActions(
        listProjects = { fleet.listProjects() },
        validatePath = { fleet.validatePath(it) },
        rename = { id, name -> fleet.rename(id, name) },
        kill = { id, done -> fleet.kill(id, done) },
        setMute = { id, muted -> fleet.setMute(id, muted) },
        resume = { id -> fleet.resume(id) },
        reorderSessions = { fleet.reorderSessions(it) },
        reorderWorkspaces = { fleet.reorderWorkspaces(it) },
        archiveWorkspace = { fleet.archiveWorkspace(it) },
        restoreWorkspace = { fleet.restoreWorkspace(it) },
        renameHost = { id, name -> fleet.renameHost(id, name) },
        forgetHost = { id -> fleet.forgetHost(id) },
        worktreeForSessionWorkdir = { id, workdir -> fleet.worktreeForSessionWorkdir(id, workdir) },
        worktreeForWorkspaceWorkdir = { id, workdir -> fleet.worktreeForWorkspaceWorkdir(id, workdir) },
        killAndDeleteWorktree = { id, ids, onDone -> fleet.killAndDeleteWorktree(id, ids, onDone) },
        archiveWorkspaceAndDeleteWorktree = { id, ids, onDone -> fleet.archiveWorkspaceAndDeleteWorktree(id, ids, onDone) },
    )
}
