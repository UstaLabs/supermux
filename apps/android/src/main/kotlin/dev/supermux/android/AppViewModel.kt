package dev.supermux.android

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.AddCommentBody
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.GitOpResult
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.BrokerClient
import dev.supermux.net.CodexResetResult
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishReadiness
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.net.PathValidation
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RemoteRepo
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.net.RepoInfo
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.SpawnRequest
import dev.supermux.net.UpdateCommentBody
import dev.supermux.net.TerminalClient
import dev.supermux.net.UpdateStatus
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.VncClient
import dev.supermux.android.session.LauncherDraft
import dev.supermux.android.session.LauncherPrefs
import dev.supermux.android.settings.AddCustomLspArgs
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SendArgs
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** App-scoped DataStore backing per-session composer drafts (process-death-durable; mirrors
 *  iOS UserDefaults "cmux:draft:<id>"). One store for the whole app, keyed per session. */
private val Context.draftDataStore by preferencesDataStore(name = "chat_drafts")

/** App-scoped DataStore backing the New Session launcher's persisted state — separate from
 *  chat_drafts (a different concept/lifecycle: pre-session, not per-session). */
private val Context.launcherDataStore by preferencesDataStore(name = "launcher_state")

class AppViewModel(
    application: Application,
    private val baseUrl: String,
    private val token: String,
) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext

    companion object {
        /** Factory so the VM can be Activity-scoped via viewModel(factory = …) and survive config changes. */
        fun factory(application: Application, baseUrl: String, token: String) = viewModelFactory {
            initializer { AppViewModel(application, baseUrl, token) }
        }
    }

    private val http = HttpClient(CIO) { install(WebSockets) }
    private val client = BrokerClient(baseUrl, token, http)
    private val api = BrokerApi(baseUrl, token, http)
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _messages = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val messages: StateFlow<Map<String, List<LogEntry>>> = _messages
    private val _activity = MutableStateFlow<Map<String, List<ActivityEvent>>>(emptyMap())
    val activity: StateFlow<Map<String, List<ActivityEvent>>> = _activity
    private val _agentState = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> = _agentState
    private val _pendingSend = MutableStateFlow<Set<String>>(emptySet())
    val pendingSend: StateFlow<Set<String>> = _pendingSend
    private val _commands = MutableStateFlow<Map<String, List<SlashCommand>>>(emptyMap())
    val commands: StateFlow<Map<String, List<SlashCommand>>> = _commands
    /** Per-session resolution state of the slash-command set (true = fully resolved). */
    private val _commandsResolved = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val commandsResolved: StateFlow<Map<String, Boolean>> = _commandsResolved

    // ── ServerFrame reducer state (Phase 2 §C: read-side only; UI is Phase 3) ──

    /** Last agent error per session (errorType/errorMessage); cleared when the agent
     *  transitions to a non-error phase. Chat header surfaces this (parity with web). */
    private val _agentErrors = MutableStateFlow<Map<String, ServerFrame.AgentError>>(emptyMap())
    val agentErrors: StateFlow<Map<String, ServerFrame.AgentError>> = _agentErrors

    /** Last/in-flight finish job per session. Seeded from each [SessionInfo.finish_job] on
     *  the snapshot and updated by the `finish_job` frame. Drives the §B.5 finish sheet. */
    private val _finishJobs = MutableStateFlow<Map<String, FinishJobDto>>(emptyMap())
    val finishJobs: StateFlow<Map<String, FinishJobDto>> = _finishJobs

    /** Drop a session's finish job so the Finish sheet returns to the readiness menu on
     *  Dismiss/Done. Client-side only (mirrors web `finishJob.clear(id)` / iOS
     *  `SessionChrome.clearJob()`); there is no broker "clear" endpoint and this never
     *  cancels a running job — the sheet only offers clear on a terminal/failed outcome. */
    fun clearFinishJob(id: String) { _finishJobs.update { it - id } }

    /** Filesystem-change pulses (session + changed paths). The editor file tree / diff tab
     *  re-fetch on each pulse. A SharedFlow (events, not retained state). */
    private val _fsChanges = MutableSharedFlow<ServerFrame.FsChanged>(extraBufferCapacity = 64)
    val fsChanges: SharedFlow<ServerFrame.FsChanged> = _fsChanges

    /** Live display streams, kept in sync via `display_added`/`display_removed` frames
     *  (seeded on demand by [listDisplays]). */
    private val _displays = MutableStateFlow<List<DisplayStream>>(emptyList())
    val displays: StateFlow<List<DisplayStream>> = _displays

    /** Language-server status keyed by "session|path"; updated by lsp_status/ready/error/exit.
     *  The editor surfaces ready/missing/installing per file. */
    private val _lspStatus = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = _lspStatus

    /** Raw inbound LSP JSON-RPC messages forwarded to the editor's LSP bridge (Phase 3).
     *  Phase 2 just exposes the flow; no decoding here. */
    private val _lspRpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 256)
    val lspRpc: SharedFlow<ServerFrame.LspRpcIn> = _lspRpc

    /** Live install log lines per LSP serverId. Drives the §B.2 install screen's live log. */
    private val _lspInstallLog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val lspInstallLog: StateFlow<Map<String, List<String>>> = _lspInstallLog

    /** Terminal install result per LSP serverId (ok/error); the install screen shows the
     *  result and clears the progress log. */
    private val _lspInstallDone = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(emptyMap())
    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> = _lspInstallDone

    init {
        viewModelScope.launch {
            client.frames.collect { f ->
                when (f) {
                    is ServerFrame.Snapshot -> {
                        _sessions.value = f.sessions
                        _messages.value = f.logs
                        _activity.value = f.activity
                        _agentState.value = f.agentState
                        _commands.value = f.commands
                        _commandsResolved.value = f.commandsResolved
                        // Seed finish jobs from each session's snapshot record.
                        _finishJobs.value = f.sessions
                            .mapNotNull { s -> s.finish_job?.let { s.id to it } }
                            .toMap()
                    }
                    is ServerFrame.SessionAdded -> {
                        // The broker re-broadcasts session_added for the SAME session (an early add
                        // right after spawn, then the authoritative post-register add carrying
                        // repo_root / session_branch). Dedup by id and backfill — keep existing
                        // values where the incoming frame omits them — instead of appending a
                        // duplicate row. (web parity: src/web-app/src/stores/sessions.ts add();
                        // iOS parity: BrokerSession.reduce().)
                        val incoming = f.session
                        _sessions.value = if (_sessions.value.none { it.id == incoming.id }) {
                            _sessions.value + incoming
                        } else {
                            _sessions.value.map { s ->
                                if (s.id != incoming.id) s
                                else incoming.copy(
                                    status = incoming.status ?: s.status,
                                    mute = incoming.mute ?: s.mute,
                                    connected = incoming.connected ?: s.connected,
                                    model = incoming.model ?: s.model,
                                    repo_root = incoming.repo_root ?: s.repo_root,
                                    role = incoming.role ?: s.role,
                                    session_branch = incoming.session_branch ?: s.session_branch,
                                    git = incoming.git ?: s.git,
                                    finish_job = incoming.finish_job ?: s.finish_job,
                                )
                            }
                        }
                        incoming.finish_job?.let { job -> _finishJobs.update { it + (incoming.id to job) } }
                    }
                    is ServerFrame.SessionRemoved -> _sessions.value = _sessions.value.filterNot { it.id == f.id }
                    is ServerFrame.MessageAppend -> {
                        // Optimistic-echo dedup (iOS BrokerSession parity): when the real inbound
                        // message lands, drop the matching local-… placeholder we appended on send.
                        _messages.value = _messages.value.toMutableMap().apply {
                            val prev = this[f.session] ?: emptyList()
                            val pruned = if (f.entry.direction.startsWith("in")) {
                                prev.filterNot { it.id.startsWith("local-") && it.text == f.entry.text }
                            } else prev
                            this[f.session] = pruned + f.entry
                        }
                    }
                    is ServerFrame.ActivityAppend -> {
                        _activity.value = _activity.value.toMutableMap().apply {
                            this[f.session] = (this[f.session] ?: emptyList()) + f.event
                        }
                    }
                    is ServerFrame.AgentState -> {
                        _agentState.value = _agentState.value.toMutableMap().apply {
                            this[f.session] = AgentStatus(
                                phase = f.phase, state = f.state, working = f.working,
                                detail = f.detail, tool = f.tool, since = f.since, workingSince = f.workingSince,
                            )
                        }
                        _pendingSend.update { it - f.session }   // first real state clears the client-local "Sending…"
                        // Clear a prior agent error once the agent is no longer dead.
                        if (f.state != "dead" && _agentErrors.value.containsKey(f.session)) {
                            _agentErrors.update { it - f.session }
                        }
                    }
                    is ServerFrame.CommandsChanged -> {
                        _commands.value = _commands.value.toMutableMap().apply {
                            this[f.session] = f.commands
                        }
                        _commandsResolved.update { it + (f.session to f.resolved) }
                    }
                    is ServerFrame.AgentError -> _agentErrors.update { it + (f.session to f) }
                    is ServerFrame.FinishJobFrame -> {
                        val job = f.job
                        if (job != null) {
                            _finishJobs.update { it + (f.session to job) }
                            // Keep list rows in sync with the latest finish job.
                            _sessions.value = _sessions.value.map { s ->
                                if (s.id == f.session) s.copy(finish_job = job) else s
                            }
                        }
                    }
                    is ServerFrame.FsChanged -> _fsChanges.tryEmit(f)
                    is ServerFrame.DisplayAdded ->
                        _displays.update { list -> list.filterNot { it.id == f.display.id } + f.display }
                    is ServerFrame.DisplayRemoved ->
                        _displays.update { list -> list.filterNot { it.id == f.id } }
                    is ServerFrame.LspStatus ->
                        _lspStatus.update { it + ("${f.session}|${f.path}" to f) }
                    is ServerFrame.LspReady -> markLspState(f.session, f.serverId, "ready")
                    is ServerFrame.LspError -> markLspState(f.session, f.serverId, "error", f.error)
                    is ServerFrame.LspRpcIn -> _lspRpc.tryEmit(f)
                    is ServerFrame.LspExit -> markLspState(f.session, f.serverId, "exited")
                    is ServerFrame.LspInstallProgress ->
                        _lspInstallLog.update {
                            it + (f.serverId to ((it[f.serverId] ?: emptyList()) + f.line))
                        }
                    is ServerFrame.LspInstallDone -> _lspInstallDone.update { it + (f.serverId to f) }
                    is ServerFrame.SessionGit ->
                        _sessions.value = _sessions.value.map { s ->
                            if (s.id == f.session) s.copy(git = f.git) else s
                        }
                    else -> {}
                }
            }
        }
        viewModelScope.launch { client.run() }
    }

    /** Patch the `state` (and optionally `error`) of every [ServerFrame.LspStatus] entry
     *  matching [session] + [serverId]; used by the lsp_ready/lsp_error/lsp_exit frames,
     *  which only carry session+serverId while [_lspStatus] is keyed by "session|path". */
    private fun markLspState(session: String?, serverId: String?, state: String, error: String? = null) {
        if (serverId == null) return
        _lspStatus.update { map ->
            map.mapValues { (_, status) ->
                if (status.session == session && status.serverId == serverId) {
                    status.copy(state = state, error = error ?: status.error)
                } else {
                    status
                }
            }
        }
    }

    /** Soft-stop the running agent (POST /sessions/<id>/interrupt). Parity with the iOS
     *  transcript Stop capsule + the `/stop` slash control. */
    fun interrupt(id: String) { viewModelScope.launch { runCatching { api.interrupt(id) } } }

    /** ISO-8601 (UTC) timestamp so an optimistic entry sorts LAST under mergeTimeline's
     *  lexicographic `ts` ordering (the broker emits ISO-8601 too). */
    private fun nowIso(): String = java.time.Instant.now().toString()

    /** Append an optimistic outbound bubble so the user's message shows instantly, before the
     *  broker echoes it back as an inbound message (iOS BrokerSession.send parity). Deduped in
     *  the MessageAppend reducer. Only echoes when there is text (attachments-only stay quiet). */
    private fun appendOptimistic(sessionId: String, text: String) {
        if (text.isEmpty()) return
        val optimistic = LogEntry(
            id = "local-${(_messages.value[sessionId]?.size ?: 0)}-${text.hashCode()}",
            ts = nowIso(),
            direction = "inbound",
            text = text,
        )
        _messages.update { it + (sessionId to ((it[sessionId] ?: emptyList()) + optimistic)) }
    }

    fun send(sessionId: String, text: String) {
        if (text.isBlank()) return
        appendOptimistic(sessionId, text.trim())
        viewModelScope.launch {
            client.send(ClientFrame.Send(sessionId, args = SendArgs(text)))
            _pendingSend.update { it + sessionId }   // optimistic "Sending…" until the next agent_state
        }
    }

    // ── Per-session composer draft persistence (DataStore) ─────────────────────────
    // Survives session-switch AND process death (iOS UserDefaults "cmux:draft:<id>" parity).
    private fun draftKey(sessionId: String) = stringPreferencesKey("draft:$sessionId")

    suspend fun loadDraft(sessionId: String): String =
        runCatching { appContext.draftDataStore.data.first()[draftKey(sessionId)] }.getOrNull() ?: ""

    fun saveDraft(sessionId: String, text: String) {
        viewModelScope.launch {
            runCatching { appContext.draftDataStore.edit { it[draftKey(sessionId)] = text } }
        }
    }

    // ── New Session launcher state persistence (DataStore) ─────────────────────────
    // Two lifecycles: prefs persist forever; draft persists until a session is created.
    private val launcherJson = Json { ignoreUnknownKeys = true }
    private val launcherPrefsKey = stringPreferencesKey("launcher_prefs")
    private val launcherDraftKey = stringPreferencesKey("launcher_draft")

    suspend fun loadLauncherPrefs(): LauncherPrefs =
        runCatching {
            appContext.launcherDataStore.data.first()[launcherPrefsKey]
                ?.let { launcherJson.decodeFromString<LauncherPrefs>(it) }
        }.getOrNull() ?: LauncherPrefs()

    fun saveLauncherPrefs(prefs: LauncherPrefs) {
        viewModelScope.launch {
            runCatching {
                appContext.launcherDataStore.edit { it[launcherPrefsKey] = launcherJson.encodeToString(prefs) }
            }
        }
    }

    suspend fun loadLauncherDraft(): LauncherDraft =
        runCatching {
            appContext.launcherDataStore.data.first()[launcherDraftKey]
                ?.let { launcherJson.decodeFromString<LauncherDraft>(it) }
        }.getOrNull() ?: LauncherDraft()

    fun saveLauncherDraft(draft: LauncherDraft) {
        viewModelScope.launch {
            runCatching {
                appContext.launcherDataStore.edit { it[launcherDraftKey] = launcherJson.encodeToString(draft) }
            }
        }
    }

    fun connectTerminal(sessionId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId)

    /** Raw agent-PTY terminal for the Native tab (kind="agent"); shell tab uses the scratch kind. */
    fun connectAgentTerminal(sessionId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId, kind = "agent")

    /** GET /displays. Also seeds [_displays] (the doc-comment's "seeded on demand"); the
     *  StateFlow then stays live via `display_added`/`display_removed` frames. */
    suspend fun listDisplays(): List<DisplayStream> {
        val list = runCatching { api.listDisplays() }.getOrNull() ?: return _displays.value
        _displays.value = list
        return list
    }
    fun connectScrcpy(streamId: String): ScrcpyClient =
        ScrcpyClient(baseUrl, token, http, streamId)
    fun connectVnc(streamId: String): VncClient =
        VncClient(baseUrl, token, http, streamId)

    /** POST /displays → the started stream (the display_added frame also folds it into [displays]). */
    suspend fun startDisplay(
        sessionName: String,
        provider: String? = null,
        device: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): DisplayStream? =
        runCatching { api.startDisplay(sessionName, provider, device, width, height) }.getOrNull()

    suspend fun stopDisplay(id: String) {
        runCatching { api.stopDisplay(id) }
    }

    suspend fun upload(
        sessionId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        kind: String? = null,
    ): String? = runCatching { api.upload(sessionId, bytes, name, mime, kind).file_id }.getOrNull()

    // ── Voice dictation ──────────────────────────────────────────────────────────

    // sessionId is OPTIONAL — null (e.g. the pre-spawn launcher) posts to the id-less /transcribe;
    // the session only enriches cleanup context server-side (see BrokerApi.transcribePath).

    /** Whisper path: multipart audio → cleaned text. Returns null on failure (caller keeps draft). */
    suspend fun transcribeAudio(sessionId: String?, bytes: ByteArray, filename: String): String? =
        runCatching { api.transcribeAudio(sessionId, bytes, filename).text }.getOrNull()

    /** On-device-STT path: JSON draft → cleaned text. Returns null on failure. */
    suspend fun transcribeDraft(sessionId: String?, draft: String): String? =
        runCatching { api.transcribeDraft(sessionId, draft).text }.getOrNull()

    fun sendWith(sessionId: String, text: String, attachments: List<String>) {
        appendOptimistic(sessionId, text.trim())
        viewModelScope.launch {
            runCatching {
                client.send(ClientFrame.Send(sessionId, args = SendArgs(text, attachments.ifEmpty { null })))
                _pendingSend.update { it + sessionId }   // optimistic "Sending…" until the next agent_state
            }
        }
    }

    fun rename(id: String, name: String) { viewModelScope.launch { runCatching { api.rename(id, name) } } }
    fun setMute(id: String, muted: Boolean) { viewModelScope.launch { runCatching { api.setMute(id, muted) } } }
    fun kill(id: String, onDone: () -> Unit = {}) { viewModelScope.launch { runCatching { api.kill(id) }; onDone() } }

    suspend fun fetchModels(id: String): ModelsResponse? = runCatching { api.models(id) }.getOrNull()
    suspend fun fetchReasoning(id: String): ReasoningResponse? = runCatching { api.reasoningLevels(id) }.getOrNull()
    fun switchModel(id: String, model: String) { viewModelScope.launch { runCatching { api.switchModel(id, model) } } }
    fun switchReasoning(id: String, level: String) { viewModelScope.launch { runCatching { api.switchReasoning(id, level) } } }

    // ── Finish flow ──────────────────────────────────────────────────────────────
    // The chat Finish sheet drives the whole job lifecycle off the `finishJobs` StateFlow;
    // `finish` only kicks off the async job (the outcome arrives on the WS `finish_job`
    // frame). `api.finish` throws CancellationException on a non-2xx (SKIE-safe decode),
    // so runCatching{…}.isSuccess is the kickoff signal surfaced back via [onKickoff].

    /** Kick off a finish job. `prTitle`/`prBody`/`draft`/`prRequiresGreen` are not surfaced by
     *  the sheet, so they keep the broker defaults. [onKickoff] reports whether the POST was
     *  accepted so the sheet can show a kickoff-failure message when no job ever appears. */
    fun finish(
        id: String,
        action: String? = null,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        onKickoff: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = runCatching { api.finish(id, action, skipVerify, commitFirst, commitMessage) }.isSuccess
            onKickoff(ok)
        }
    }

    /** Preflight snapshot for the finish menu (branch sync / diff / conflict / dirty). */
    suspend fun finishReadiness(id: String): FinishReadiness? =
        runCatching { api.finishReadiness(id) }.getOrNull()

    /** Suggest a `.mux/verify.sh` for the no_verify recovery path. */
    suspend fun verifySuggest(id: String): VerifySuggestResult? =
        runCatching { api.verifySuggest(id) }.getOrNull()

    /** Save an edited verify script; the sheet auto-runs merge when `ok`. */
    suspend fun verifySave(id: String, content: String): VerifySaveResult? =
        runCatching { api.verifySave(id, content) }.getOrNull()

    /** Post a message to the agent (the finish sheet's "Let the agent fix it"). */
    fun sendMessage(id: String, text: String) { viewModelScope.launch { runCatching { api.sendMessage(id, text) } } }

    // Git ops for the workspace ⋮ menu — run the broker op and hand the result back so the caller
    // can surface it (parity with iOS SessionChrome.fetch/push/pull/publish).
    fun gitFetch(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { api.gitFetch(id) }.getOrNull()) } }
    fun gitPush(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { api.gitPush(id) }.getOrNull()) } }
    fun gitPull(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { api.gitPull(id) }.getOrNull()) } }
    fun gitPublish(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { api.gitPublish(id) }.getOrNull()) } }

    data class PendingFirstMessage(val text: String, val attachments: List<String> = emptyList())

    private var pendingFirst: Pair<String, PendingFirstMessage>? = null

    fun setPendingFirst(sessionId: String, message: PendingFirstMessage) {
        pendingFirst = sessionId to message
    }

    fun consumePendingFirst(sessionId: String): PendingFirstMessage? {
        val entry = pendingFirst ?: return null
        if (entry.first != sessionId) return null
        pendingFirst = null
        return entry.second
    }

    fun spawn(workdir: String, name: String?, agent: String, model: String? = null) {
        viewModelScope.launch { runCatching { api.spawn(SpawnRequest(workdir = workdir, name = name?.ifBlank { null }, agent = agent, model = model?.ifBlank { null })) } }
    }

    /** Create a session then queue the first message for [ChatScreen] to send on open.
     *  [worktree]/[baseBranch] are only honored when the workdir is an eligible git repo
     *  (the broker ignores them otherwise); baseBranch null → cut from the repo's current branch. */
    suspend fun createSessionWithFirstMessage(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        attachments: List<String> = emptyList(),
        worktree: Boolean = false,
        baseBranch: String? = null,
    ): String {
        val validation = validatePath(workdir) ?: throw IllegalArgumentException("Could not validate path")
        val resolvedPath = validation.path
        if (!validation.ok || resolvedPath.isNullOrBlank()) {
            throw IllegalArgumentException(validation.error ?: "Invalid working directory")
        }
        val resp = api.spawn(
            SpawnRequest(
                workdir = resolvedPath,
                agent = agent,
                model = model?.ifBlank { null },
                worktree = if (worktree) true else null,
                baseBranch = baseBranch?.ifBlank { null },
            ),
        )
        val sessionId = resp.id.ifBlank {
            _sessions.value.firstOrNull { it.name == resp.name }?.id
                ?: throw IllegalStateException("Session created but id not available yet")
        }
        setPendingFirst(sessionId, PendingFirstMessage(text, attachments))
        return sessionId
    }

    // ── Settings / Usage / Devices / Archived ────────────────────────────────

    suspend fun config(): AppConfigDto? = runCatching { api.getConfig() }.getOrNull()
    fun saveName(n: String) { viewModelScope.launch { runCatching { api.putConfig(n) } } }

    /** GET /models?agent= — models for the cleanup-engine + launcher pickers (no session). */
    suspend fun launcherModels(agent: String): List<ModelInfo> =
        runCatching { api.listModels(agent).models }.getOrNull() ?: emptyList()

    /** GET /repos/info?path= — git status for the launcher's worktree picker (null on failure). */
    suspend fun launcherRepoInfo(workdir: String): RepoInfo? =
        runCatching { api.getRepoInfo(workdir) }.getOrNull()

    /**
     * PUT /settings/config { voiceCleanupEngine?, voiceCleanupModel? }.
     * A null arg leaves that field unchanged; an empty-string model ("") resets the
     * model to the engine's default (the broker treats "" as the reset sentinel).
     */
    fun saveVoiceCleanup(engine: String?, model: String?) {
        viewModelScope.launch { runCatching { api.saveConfig(voiceCleanupEngine = engine, voiceCleanupModel = model) } }
    }

    /** Voice-cleanup glossary (shared across devices). */
    suspend fun fetchGlossary(): List<String> = runCatching { api.fetchGlossary() }.getOrNull() ?: emptyList()
    suspend fun updateGlossary(terms: List<String>): List<String>? =
        runCatching { api.updateGlossary(terms) }.getOrNull()
    suspend fun usage(): String? = runCatching { api.usageRaw() }.getOrNull()
    suspend fun redeemCodexReset(): CodexResetResult? =
        runCatching { api.redeemCodexReset() }.getOrNull()
    suspend fun curatorSettings(): CuratorSettingsResponse? = runCatching { api.getCuratorSettings() }.getOrNull()
    suspend fun saveCurator(enabled: Boolean, hour: Int, minute: Int): CuratorSettingsResponse? =
        runCatching { api.saveCuratorSettings(enabled, hour, minute) }.getOrNull()
    suspend fun runCuratorNow() { runCatching { api.runCuratorNow() } }
    suspend fun devices(): List<DeviceDto> = runCatching { api.devices() }.getOrNull() ?: emptyList()
    fun revoke(n: String) { viewModelScope.launch { runCatching { api.revokeDevice(n) } } }
    suspend fun archived(): List<ArchivedDto> = runCatching { api.archived() }.getOrNull() ?: emptyList()
    fun resume(id: String) { viewModelScope.launch { runCatching { api.resume(id) } } }

    // ── Assistant ──────────────────────────────────────────────────────────────

    /** (paName, soul.md) loaded concurrently; null if the config fetch fails. */
    suspend fun assistantLoad(): Pair<String, String>? = coroutineScope {
        val cfg = async { runCatching { api.getConfig() }.getOrNull() }
        val soul = async { runCatching { api.getSoul() }.getOrNull() ?: "" }
        cfg.await()?.let { it.paName to soul.await() }
    }

    /** Save paName via saveConfig (NOT the legacy putConfig), then soul; bool = putSoul success. */
    suspend fun assistantSave(paName: String, soul: String): Boolean {
        runCatching { api.saveConfig(paName = paName) }
        return runCatching { api.putSoul(soul) }.getOrDefault(false)
    }

    // ── Agents ─────────────────────────────────────────────────────────────────

    suspend fun agentStatuses(): List<AgentInstallStatus> =
        runCatching { api.agentStatuses() }.getOrNull() ?: emptyList()
    suspend fun agentStartLogin(kind: String): AgentLoginState? =
        runCatching { api.startAgentLogin(kind) }.getOrNull()
    suspend fun agentPollLogin(kind: String): AgentLoginState? =
        runCatching { api.agentLoginState(kind) }.getOrNull()
    fun agentSendCode(kind: String, code: String) {
        viewModelScope.launch { runCatching { api.sendAgentLoginCode(kind, code) } }
    }
    fun agentCancelLogin(kind: String) {
        viewModelScope.launch { runCatching { api.cancelAgentLogin(kind) } }
    }
    /** Routes the secret to the right saveConfig field by agent kind. */
    fun agentSaveSecret(kind: String, value: String) {
        viewModelScope.launch {
            runCatching {
                when (kind) {
                    "claude" -> api.saveConfig(claudeOauthToken = value)
                    "codex" -> api.saveConfig(codexApiKey = value)
                    "cursor" -> api.saveConfig(cursorApiKey = value)
                }
            }
        }
    }
    suspend fun openCodeProviders(): List<OpenCodeProvider> =
        runCatching { api.openCodeProviders() }.getOrNull() ?: emptyList()
    fun openCodeSetKey(providerId: String, key: String) {
        viewModelScope.launch { runCatching { api.setOpenCodeKey(providerId, key) } }
    }
    suspend fun openCodeStartOAuth(providerId: String, method: Int): OpenCodeOAuthStart? =
        runCatching { api.startOpenCodeOAuth(providerId, method) }.getOrNull()
    fun openCodeFinishOAuth(providerId: String, method: Int, code: String) {
        viewModelScope.launch { runCatching { api.finishOpenCodeOAuth(providerId, method, code) } }
    }

    // ── Editor / LSP ───────────────────────────────────────────────────────────
    // lspInstallLog / lspInstallDone StateFlows already exist (Phase 2, above) — the
    // Editor LSP section collects them directly for the live install log.

    suspend fun lspLoad(): List<LspServer> =
        runCatching { api.getEditorSettings().lsp.servers }.getOrNull() ?: emptyList()
    suspend fun lspToggle(id: String, enabled: Boolean): List<LspServer>? =
        runCatching { api.setLspEnabled(id, enabled).lsp.servers }.getOrNull()
    suspend fun lspInstall(id: String): LspInstallResult? =
        runCatching { api.installEditorLsp(id) }.getOrNull()
    suspend fun lspAddCustom(a: AddCustomLspArgs): LspMutationResult? =
        runCatching {
            api.addCustomEditorLsp(a.id, a.label, a.command, a.extensions, a.args, a.languageId, a.installCmd)
        }.getOrNull()
    suspend fun lspRemoveCustom(id: String): LspMutationResult? =
        runCatching { api.removeCustomEditorLsp(id) }.getOrNull()

    // ── Git hosting (forges) ─────────────────────────────────────────────────────

    suspend fun forgesLoad(): ForgeConnectionsResponse? = runCatching { api.listForges() }.getOrNull()
    suspend fun forgeAdd(kind: String, token: String, host: String?, transport: String): Boolean =
        runCatching { api.addForge(kind, token, host, transport); true }.getOrDefault(false)
    suspend fun forgeImport(kind: String, transport: String): Boolean =
        runCatching { api.importForge(kind, transport); true }.getOrDefault(false)
    fun forgeRemove(id: String) { viewModelScope.launch { runCatching { api.removeForge(id) } } }

    // ── System ─────────────────────────────────────────────────────────────────

    suspend fun updateStatus(): UpdateStatus? = runCatching { api.updateStatus() }.getOrNull()
    fun restartBroker() { viewModelScope.launch { runCatching { api.restartBroker() } } }

    suspend fun fileBytes(fileId: String): ByteArray? = api.fileBytes(fileId)
    suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        runCatching { api.archivedLogs(sessionId) }.getOrNull() ?: emptyList()

    /**
     * Lazily fetch a session's transcript when we don't already have it. The WS `Snapshot`
     * (sent on connect) seeds [messages] for every session live at connect time, and
     * `MessageAppend` keeps them current — but a session resumed from archive arrives via a
     * `SessionAdded` frame, which carries NO history, so its transcript stays empty until the
     * next reconnect/snapshot (e.g. an app restart). Calling this on chat-open closes that gap.
     * Web/iOS parity: web ChatView.loadMessages / iOS BrokerSession.ensureMessagesLoaded fetch
     * GET /sessions/:id/messages (the same endpoint [archivedLogs] hits) when the store has
     * nothing for the session. No-op when the snapshot already populated it.
     */
    fun ensureMessagesLoaded(sessionId: String) {
        if (_messages.value[sessionId]?.isNotEmpty() == true) return
        viewModelScope.launch {
            val fetched = archivedLogs(sessionId)
            // Re-check after the await: a live MessageAppend / optimistic send / fresh snapshot
            // may have populated the buffer while the fetch was in flight — don't clobber it.
            if (fetched.isNotEmpty() && _messages.value[sessionId]?.isNotEmpty() != true) {
                _messages.update { it + (sessionId to fetched) }
            }
        }
    }

    // ── Editor filesystem ──────────────────────────────────────────────────────

    suspend fun fsList(sessionId: String, path: String): List<FsEntry> =
        runCatching { api.fsList(sessionId, path) }.getOrNull() ?: emptyList()
    suspend fun fsRead(sessionId: String, path: String): Result<String> =
        runCatching { api.fsRead(sessionId, path) }
    suspend fun fsWrite(sessionId: String, path: String, content: String): Boolean =
        runCatching { api.fsWrite(sessionId, path, content) }.getOrDefault(false)
    suspend fun fsSearch(sessionId: String, q: String): List<FsSearchResult> =
        runCatching { api.fsSearch(sessionId, q) }.getOrNull() ?: emptyList()

    // ── Editor diff + inline code-review ───────────────────────────────────────
    // HTTP wrappers (parity with iOS BrokerSession review*); the DiffView pane drives
    // these via the lambdas threaded through ChatScreen → EditorPanel.

    /** GET /sessions/<id>/fs/diff → repos + existing review comments (null on failure). */
    suspend fun fsDiff(sessionId: String): FsDiffResult? =
        runCatching { api.fsDiff(sessionId) }.getOrNull()

    /** POST a new inline review comment → the created comment (null on failure). */
    suspend fun reviewAddComment(sessionId: String, body: AddCommentBody): ReviewComment? =
        runCatching { api.reviewAddComment(sessionId, body) }.getOrNull()

    /** PATCH a comment to status="resolved" (iOS reviewResolve parity). */
    suspend fun reviewResolve(sessionId: String, commentId: String): Boolean =
        runCatching { api.reviewUpdateComment(sessionId, commentId, UpdateCommentBody(status = "resolved")) }
            .getOrDefault(false)

    /** POST /review/submit → delivers open comments to the agent (null on failure). */
    suspend fun reviewSubmit(sessionId: String): ReviewSubmitResult? =
        runCatching { api.reviewSubmit(sessionId) }.getOrNull()

    // ── Editor lifecycle + LSP control-plane senders ───────────────────────────
    // editor_open/close start/stop the broker fs-watcher (so fs_changed fires) and the
    // lsp_* frames drive code-intelligence. Inbound frames are already folded into
    // lspStatus / lspRpc / fsChanges flows above; these are the outbound half.

    fun editorOpen(sessionId: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.EditorOpen(sessionId)) } }
    }
    fun editorClose(sessionId: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.EditorClose(sessionId)) } }
    }
    fun lspStatusQuery(sessionId: String, path: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.LspStatusQuery(sessionId, path)) } }
    }
    fun lspOpen(sessionId: String, serverId: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.LspOpen(sessionId, serverId)) } }
    }
    fun lspRpcOut(sessionId: String, serverId: String, message: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.LspRpcOut(sessionId, serverId, message)) } }
    }
    fun lspClose(sessionId: String, serverId: String) {
        viewModelScope.launch { runCatching { client.send(ClientFrame.LspClose(sessionId, serverId)) } }
    }

    suspend fun listProjects(): List<String> = runCatching { api.listProjects() }.getOrNull() ?: emptyList()
    suspend fun validatePath(path: String): PathValidation? = runCatching { api.validatePath(path) }.getOrNull()

    // ── Git hosting / forges (project-picker omnibox; mirrors iOS BrokerSession) ────
    /** Configured GitHub/GitLab connections (empty on any failure). */
    suspend fun listForges(): List<ForgeConnection> =
        runCatching { api.listForges().connections }.getOrNull() ?: emptyList()

    /** Debounced repo search across all connections (empty on any failure). */
    suspend fun searchForge(query: String): List<RemoteRepo> =
        runCatching { api.searchForge(query).repos }.getOrNull() ?: emptyList()

    /** Clone a remote repo → local checkout path, or null on failure. */
    suspend fun cloneForge(connectionId: String, owner: String, name: String): String? =
        runCatching { api.cloneForge(connectionId, owner, name).localPath }.getOrNull()?.ifBlank { null }

    /** `git init` a fresh local repo → its path, or null on failure. */
    suspend fun createLocalRepo(name: String): String? =
        runCatching { api.createLocalRepo(name).localPath }.getOrNull()?.ifBlank { null }

    /** Create a remote repo on the forge then clone it → local path, or null on failure. */
    suspend fun createForge(connectionId: String, name: String): String? =
        runCatching { api.createForge(connectionId, name).localPath }.getOrNull()?.ifBlank { null }

    // ── Proxies ──────────────────────────────────────────────────────────────

    suspend fun proxies(): List<ProxyDto> = runCatching { api.proxies() }.getOrNull() ?: emptyList()
    fun createProxy(sessionName: String, port: Int, domain: String?) {
        viewModelScope.launch { runCatching { api.createProxy(sessionName, port, domain) } }
    }
    fun setProxyPublic(domain: String, isPublic: Boolean) {
        viewModelScope.launch { runCatching { api.setProxyPublic(domain, isPublic) } }
    }
    fun removeProxy(domain: String) {
        viewModelScope.launch { runCatching { api.removeProxy(domain) } }
    }

    override fun onCleared() { http.close() }
}
