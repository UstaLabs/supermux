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
import dev.supermux.net.AgentModelsResponse
import dev.supermux.net.HostRequirements
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
import dev.supermux.ui.chat.toLivePass
import dev.supermux.ui.chat.toVoiceCaps
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.agentSettingsActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * [listProjects] that can tell "no projects" from "couldn't ask": null = the call failed. Set,
     * the launcher uses it instead, and a definite EMPTY answer (with no catalog and no sessions)
     * is a fresh host — it shows the first-project screen. Unset (a preview, a test, an older
     * wiring) the launcher never assumes a fresh host.
     */
    val listProjectsOrNull: (suspend () -> List<String>?)? = null,
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
    /**
     * The target host's cached model catalog (GET /agents/models, refreshed on connect and on
     * `agent_models_changed`). While it is non-null the launcher reads agents, models and
     * reasoning from it with no request; null (an older broker, or not answered yet) falls back
     * to [launcherAgents] / [launcherModels] / [launcherReasoning] per call.
     */
    val agentModels: Flow<AgentModelsResponse?> = flowOf(null),
    /**
     * The target host's requirements (`host_requirements`), LIVE like [agentModels]: while its
     * git is missing the launcher shows the "git not installed" notice. Informational only — a
     * session starts without git, it just gets no worktree.
     */
    val hostRequirements: Flow<HostRequirements?> = flowOf(null),
    /** `POST /system/install-git` on the target host; true when its installer started. */
    val installGit: suspend () -> Boolean = { false },
    val launcherModels: suspend (agent: String) -> List<ModelInfo> = { emptyList() },
    val launcherReasoning: suspend (agent: String, model: String?) -> ReasoningResponse? = { _, _ -> null },
    /** `fetch=true` refreshes origin's remote-tracking refs (once per repo, on picker open). */
    val launcherRepoInfo: suspend (workdir: String, fetch: Boolean) -> RepoInfo? = { _, _ -> null },
    /** Agent slash commands for the composer's "/" menu — Android's `loadCommands`. */
    val launcherCommands: suspend (agent: String, workdir: String) -> List<SlashCommand> =
        { _, _ -> emptyList() },
    /** The host's INSTALLED agent kinds; empty keeps each launcher's own fallback list. */
    val launcherAgents: suspend () -> List<String> = { emptyList() },
    /** The target host's accounts (every agent), live; null until loaded. Drives the picker. */
    val accounts: Flow<List<dev.supermux.net.AccountDto>?> = flowOf(null),
    /** Start following [accounts] (the first call fetches GET /accounts). */
    val ensureAccounts: () -> Unit = {},
    /** Snapshot catalog of permission modes, keyed by agent. */
    val permissionModes: Map<String, List<PermissionModeInfo>> = emptyMap(),
    val listForges: suspend () -> List<ForgeConnection> = { emptyList() },
    /** `null` = the search FAILED (distinguishable from an empty success). */
    val searchForge: suspend (query: String) -> ForgeSearchResponse? = { ForgeSearchResponse() },
    /**
     * Clone / create: the new checkout's path, null on a network failure, and a THROWN
     * [BrokerRefusal] when the broker said no — so the picker can show the broker's own reason.
     */
    val cloneForge: suspend (connectionId: String, owner: String, name: String) -> String? =
        { _, _, _ -> null },
    /** A new folder under the broker's projects root (`git init`'d only where git is installed). */
    val createLocalRepo: suspend (name: String) -> String? = { null },
    val createForge: suspend (connectionId: String, name: String) -> String? = { _, _ -> null },
    /**
     * The target host's agent install/login calls — the very holder Settings ▸ Agents uses. Set, the
     * launcher shows its "Install an agent" panel when no agent is installed; null never gates.
     */
    val agentSetup: AgentSettingsActions? = null,
    /** Dictation cleanup vocabulary; empty = no glossary. */
    val fetchGlossary: suspend () -> List<String> = { emptyList() },
    /** Clean up a typed draft (no session yet → the broker's id-less /transcribe). */
    val transcribeDraft: suspend (draft: String) -> String? = { null },
    val transcribeAudio: suspend (bytes: ByteArray, filename: String, mime: String) -> String? = { _, _, _ -> null },
    /** A live partial dictation pass (`/transcribe?partial=1`); null = no live text. */
    val transcribePartial: (suspend (dev.supermux.ui.chat.LivePassRequest) -> dev.supermux.ui.chat.LivePass?)? = null,
    /** The broker's voice capabilities, for the default dictation mode. */
    val loadVoiceCaps: suspend () -> dev.supermux.ui.chat.VoiceCaps? = { null },
    /** Spoken-edit check: (text, new words) → edited text, or null. */
    val checkEdit: (suspend (String, String) -> String?)? = null,
    /** Mic start: open the broker connection a recording's live passes reuse. */
    val transcribeWarm: (suspend (String) -> Unit)? = null,
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
        account: String?,
    ) -> String = { _, _, _, _, _, _, _, _, _, _, _ -> error("No host connected") },
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

/** GET /projects with failure as null (HostStore's own wrapper says "empty" for both). */
private suspend fun projectsOrNull(app: HostStore?): List<String>? = try {
    app?.api?.listProjects()
} catch (c: CancellationException) {
    currentCoroutineContext().ensureActive()
    null
} catch (_: Throwable) {
    null
}

// Clone / create straight through the host's BrokerApi: HostStore's wrappers collapse a refusal to
// null, which left the picker only "check the connection" to say. [refusalOrNull] keeps the reason.
private suspend fun cloneOn(app: HostStore?, connectionId: String, owner: String, name: String): String? =
    app?.let { refusalOrNull { it.api.cloneForge(connectionId, owner, name).localPath }?.ifBlank { null } }

private suspend fun createLocalOn(app: HostStore?, name: String): String? =
    app?.let { refusalOrNull { it.api.createLocalRepo(name).localPath }?.ifBlank { null } }

private suspend fun createForgeOn(app: HostStore?, connectionId: String, name: String): String? =
    app?.let { refusalOrNull { it.api.createForge(connectionId, name).localPath }?.ifBlank { null } }

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
            listProjectsOrNull = { projectsOrNull(app) },
            projectCatalog = combine(app.projectCatalogKnown, app.projects) { known, list ->
                if (known) list else emptyList()
            },
            addProjectLocation = { id, path -> app.addProjectLocation(id, path) },
            projectImage = { app.projectImageBytes(it) },
            validatePath = { app.validatePath(it) },
            agentModels = app.agentModels,
            hostRequirements = app.hostRequirements,
            installGit = { app.installGit()?.ok == true },
            launcherModels = { app.launcherModels(it) },
            launcherReasoning = { agent, model -> app.launcherReasoning(agent, model) },
            launcherRepoInfo = { workdir, fetch -> app.launcherRepoInfo(workdir, fetch) },
            launcherCommands = { agent, workdir -> app.launcherCommands(agent, workdir) },
            launcherAgents = { app.launcherAgents() },
            accounts = app.accounts,
            ensureAccounts = { app.ensureAccounts() },
            listForges = { app.listForges() },
            searchForge = { app.searchForge(it) },
            cloneForge = { cid, owner, name -> cloneOn(app, cid, owner, name) },
            createLocalRepo = { createLocalOn(app, it) },
            createForge = { cid, name -> createForgeOn(app, cid, name) },
            agentSetup = agentSettingsActions(app),
            fetchGlossary = { app.fetchGlossary().orEmpty() },
            transcribeDraft = { draft -> app.transcribeDraft(null, draft)?.text },
            transcribeAudio = { bytes, name, mime -> app.transcribeAudio(null, bytes, name, mime)?.text },
            transcribePartial = { r ->
                app.transcribePartial(null, r.bytes, r.filename, r.mime, r.fromMs, r.previousText, r.dictationId, r.final)?.toLivePass()
            },
            loadVoiceCaps = { app.appConfig()?.toVoiceCaps() },
            checkEdit = { text, newWords -> app.transcribeEdit(text, newWords) },
            transcribeWarm = { id -> app.transcribeWarm(id) },
            createSessionWithFirstMessage = { wd, agent, model, level, text, staged, wt, base, replace, perm, acct ->
                app.createSessionWithFirstMessageOrThrow(
                    wd, agent, model, level, text, staged, wt, base, replaceDraftId = replace,
                    permissionMode = perm, account = acct,
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
            listProjectsOrNull = { projectsOrNull(fleet.activeApp()) },
            // Same routing as every other launcher call: the ACTIVE host (the launcher's host pill),
            // bound to its LIVE HostStore so a connection rebuild rebinds (see FleetStore).
            projectCatalog = fleet.activeProjectCatalog,
            addProjectLocation = { id, path ->
                fleet.activeApp()?.addProjectLocation(id, path) ?: ProjectLocationResult.Failed
            },
            projectImage = { fleet.activeApp()?.projectImageBytes(it) },
            validatePath = { fleet.validatePath(it) },
            agentModels = fleet.activeAgentModels,
            hostRequirements = fleet.activeHostRequirements,
            installGit = { fleet.installGit()?.ok == true },
            launcherModels = { fleet.launcherModels(it) },
            launcherReasoning = { agent, model -> fleet.launcherReasoning(agent, model) },
            launcherRepoInfo = { workdir, fetch -> fleet.launcherRepoInfo(workdir, fetch) },
            launcherCommands = { agent, workdir -> fleet.launcherCommands(agent, workdir) },
            launcherAgents = { fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } },
            accounts = fleet.activeAccounts,
            ensureAccounts = { fleet.activeApp()?.ensureAccounts() },
            listForges = { fleet.listForges() },
            searchForge = { fleet.searchForge(it) },
            cloneForge = { cid, owner, name -> cloneOn(fleet.activeApp(), cid, owner, name) },
            createLocalRepo = { createLocalOn(fleet.activeApp(), it) },
            createForge = { cid, name -> createForgeOn(fleet.activeApp(), cid, name) },
            agentSetup = agentSettingsActions(fleet),
            fetchGlossary = { fleet.fetchGlossary().orEmpty() },
            transcribeDraft = { draft -> fleet.transcribeDraft(null, draft) },
            transcribeAudio = { bytes, name, mime -> fleet.transcribeAudio(null, bytes, name, mime) },
            transcribePartial = { r ->
                fleet.transcribePartial(null, r.bytes, r.filename, r.mime, r.fromMs, r.previousText, r.dictationId, r.final)?.toLivePass()
            },
            loadVoiceCaps = { fleet.voiceConfig(null)?.toVoiceCaps() },
            checkEdit = { text, newWords -> fleet.transcribeEdit(null, text, newWords) },
            transcribeWarm = { id -> fleet.transcribeWarm(null, id) },
            createSessionWithFirstMessage = { wd, agent, model, level, text, staged, wt, base, replace, perm, acct ->
                fleet.createSessionWithFirstMessageOrThrow(
                    wd, agent, model, level, text, staged, wt, base, replaceDraftId = replace,
                    permissionMode = perm, account = acct,
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
    /** Mark these sessions read on the broker without opening them (batch "Mark as read"). */
    val markRead: (sessionIds: List<String>) -> Unit = {},
    val renameHost: (recordId: String, name: String) -> Unit = { _, _ -> },
    val forgetHost: (recordId: String) -> Unit = {},
    /** Worktree lookup for the archive dialogs (spec 2026-09-22-explicit-worktree-cleanup), asked
     *  of the host that OWNS the session / workspace being archived (never just the active host). */
    val worktreeForSessionWorkdir: suspend (sessionId: String, workdir: String) -> WorktreeForWorkdirDto? = { _, _ -> null },
    val worktreeForWorkspaceWorkdir: suspend (workspaceId: String, workdir: String) -> WorktreeForWorkdirDto? = { _, _ -> null },
    /** A worktree's size, asked of the same owning host after the dialog shows its changes. */
    val worktreeSizeForSession: suspend (sessionId: String, worktreeId: String) -> Long? = { _, _ -> null },
    val worktreeSizeForWorkspace: suspend (workspaceId: String, worktreeId: String) -> Long? = { _, _ -> null },
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
    markRead = markRead,
    renameHost = renameHost,
    forgetHost = forgetHost,
    worktreeForSessionWorkdir = worktreeForSessionWorkdir,
    worktreeForWorkspaceWorkdir = worktreeForWorkspaceWorkdir,
    worktreeSizeForSession = worktreeSizeForSession,
    worktreeSizeForWorkspace = worktreeSizeForWorkspace,
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
            markRead = { app.markReadOnServer(it) },
            renameHost = { id, name -> renameHost(id, name) },
            forgetHost = { id -> forgetHost(id) },
            worktreeForSessionWorkdir = { _, workdir -> app.worktreeForWorkdir(workdir) },
            worktreeForWorkspaceWorkdir = { _, workdir -> app.worktreeForWorkdir(workdir) },
            worktreeSizeForSession = { _, id -> app.worktreeSize(id) },
            worktreeSizeForWorkspace = { _, id -> app.worktreeSize(id) },
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
        markRead = { fleet.markReadOnServer(it) },
        renameHost = { id, name -> fleet.renameHost(id, name) },
        forgetHost = { id -> fleet.forgetHost(id) },
        worktreeForSessionWorkdir = { id, workdir -> fleet.worktreeForSessionWorkdir(id, workdir) },
        worktreeForWorkspaceWorkdir = { id, workdir -> fleet.worktreeForWorkspaceWorkdir(id, workdir) },
        worktreeSizeForSession = { id, wt -> fleet.worktreeSizeForSession(id, wt) },
        worktreeSizeForWorkspace = { id, wt -> fleet.worktreeSizeForWorkspace(id, wt) },
        killAndDeleteWorktree = { id, ids, onDone -> fleet.killAndDeleteWorktree(id, ids, onDone) },
        archiveWorkspaceAndDeleteWorktree = { id, ids, onDone -> fleet.archiveWorkspaceAndDeleteWorktree(id, ids, onDone) },
    )
}
