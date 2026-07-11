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
import dev.supermux.net.ChunkSource
import dev.supermux.net.GitOpResult
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.BrokerClient
import dev.supermux.net.CodexResetResult
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishReadiness
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsRefsResult
import dev.supermux.net.FsSearchResult
import dev.supermux.net.HostIdentity
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
import dev.supermux.android.host.HostConnections
import dev.supermux.android.host.HostStores
import dev.supermux.android.host.HostView
import dev.supermux.android.session.LauncherDraft
import dev.supermux.android.session.LauncherPrefs
import dev.supermux.android.session.StagedUpload
import dev.supermux.android.settings.AddCustomLspArgs
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.PairingPayload
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
 *  chat_drafts (a different concept/lifecycle: pre-session, not per-session). Also holds the
 *  merged-list host-filter selection (a UI pref, same lifecycle as launcher prefs). */
private val Context.launcherDataStore by preferencesDataStore(name = "launcher_state")

/** Outcome of an add-host attempt (spec §3.4 / §5). */
sealed interface AddHostResult {
    data class Added(val host: PairedHost) : AddHostResult
    /** Typed-URL path reached a real supermux host, but it is already set up and needs a claim
     *  minted from its own UI (paste/scan that link instead). */
    data class NeedsClaim(val identity: HostIdentity) : AddHostResult
    data class Error(val message: String) : AddHostResult
}

/**
 * Multi-host app view-model (spec §5). Holds N per-host connections via [HostConnections] and folds
 * every host's frames into unified, sessionId-keyed state — session ids are globally unique across
 * hosts, so the reducer maps merge without collision. Per-session commands route to the OWNING host
 * (via [sessionHost]); host-global operations (settings, agents, projects, spawn) route to the
 * ACTIVE host ([activeHost], defaulting to the first paired host, switched by opening a chat or the
 * launcher's host picker). Existing single-host users are transparently migrated to `PairedHost[0]`.
 */
class AppViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext: Context = application.applicationContext

    companion object {
        /** Factory so the VM can be Activity-scoped via viewModel(factory = …) and survive config changes. */
        fun factory(application: Application) = viewModelFactory {
            initializer { AppViewModel(application) }
        }
    }

    private val http = HttpClient(CIO) { install(WebSockets) }

    // The multi-host store + connection registry. Migration (idempotent) guarantees a PairedHost[0]
    // for existing single-host users before we read the list. Every frame is tagged with its host's
    // recordId and folded by [reduce]; connect/disconnect drive [onConnState].
    private val store: PairedHostStore = HostStores.store(appContext)
    private val hostConns = HostConnections(viewModelScope, http, onFrame = ::reduce, onConnState = ::onConnState)

    // Per-host session buckets are the source of truth for the merged list: a host's Snapshot
    // replaces only ITS bucket (so another host's sessions survive), and a bucket is retained while
    // its host is offline (spec §5 "greyed group with last-seen"). [_sessions]/[_sessionHost] are
    // derived from these buckets in store order.
    private val sessionsByHost = LinkedHashMap<String, List<SessionInfo>>()
    private val onlineHosts = HashMap<String, Boolean>()

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    /** sessionId → owning host recordId (drives per-row badges + per-session routing). */
    private val _sessionHost = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionHost: StateFlow<Map<String, String>> = _sessionHost
    /** The paired fleet as the list renders it (identity + reachability + badge slot). */
    private val _hostViews = MutableStateFlow<List<HostView>>(emptyList())
    val hostViews: StateFlow<List<HostView>> = _hostViews
    /** recordId of the host that host-global ops target (settings/agents/spawn). */
    private val _activeHost = MutableStateFlow<String?>(null)
    val activeHost: StateFlow<String?> = _activeHost

    // Viewing presence — tells the OWNING broker which chat is foreground (parity with iOS/web) so it
    // suppresses a push for a chat you're already looking at.
    private var viewingSession: String? = null
    private var viewingVisible: Boolean = false
    private var lastSentViewing: Pair<String?, Boolean>? = null
    private var viewingHeartbeat: Job? = null
    private val _messages = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val messages: StateFlow<Map<String, List<LogEntry>>> = _messages
    private val _activity = MutableStateFlow<Map<String, List<ActivityEvent>>>(emptyMap())
    val activity: StateFlow<Map<String, List<ActivityEvent>>> = _activity
    private val _agentState = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> = _agentState
    private val _bgTasks = MutableStateFlow<Map<String, List<ServerFrame.BgTask>>>(emptyMap())
    val bgTasks: StateFlow<Map<String, List<ServerFrame.BgTask>>> = _bgTasks
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
        // Idempotent: seeds PairedHost[0] from the legacy single-host (token, baseUrl) if the store
        // is empty (e.g. the session right after onboarding persisted the legacy secure store).
        HostStores.migrateFromLegacyIfNeeded(appContext)
        _activeHost.value = store.list().firstOrNull()?.recordId
        rebuildHostViews()
        // Opens one control WS + api per paired host; frames flow into reduce(), tagged by recordId.
        hostConns.sync(store.list())
    }

    // ── Multi-host reducer + derived state ─────────────────────────────────────────

    /** Fold a frame from host [recordId] into the unified, sessionId-keyed state. Runs on the
     *  viewModelScope (Main-confined), so the plain mutable maps below are race-free. */
    private fun reduce(recordId: String, f: ServerFrame) {
        when (f) {
            is ServerFrame.Snapshot -> {
                val prev = sessionsByHost[recordId].orEmpty().map { it.id }.toSet()
                sessionsByHost[recordId] = f.sessions
                onlineHosts[recordId] = true
                store.updateSeen(recordId, System.currentTimeMillis())
                rebuildSessions(); rebuildHostViews()
                // Replace only THIS host's slice of each session-keyed map (drop stale ids, add fresh).
                _messages.value = _messages.value - prev + f.logs
                _activity.value = _activity.value - prev + f.activity
                _bgTasks.value = _bgTasks.value - prev + f.bgTasks
                _agentState.value = _agentState.value - prev + f.agentState
                _commands.value = _commands.value - prev + f.commands
                _commandsResolved.value = _commandsResolved.value - prev + f.commandsResolved
                _finishJobs.value = (_finishJobs.value - prev) +
                    f.sessions.mapNotNull { s -> s.finish_job?.let { s.id to it } }.toMap()
                // A (re)connect always begins with a snapshot; re-assert viewing presence so the
                // owning broker's per-device tracker is current after a reconnect.
                lastSentViewing = null
                sendViewingIfChanged()
            }
            is ServerFrame.SessionAdded -> {
                // Dedup + backfill within this host's bucket (web/iOS parity): the broker
                // re-broadcasts session_added for the same session (early add then the authoritative
                // post-register one carrying repo_root/session_branch).
                val incoming = f.session
                val bucket = sessionsByHost[recordId].orEmpty()
                sessionsByHost[recordId] = if (bucket.none { it.id == incoming.id }) {
                    bucket + incoming
                } else {
                    bucket.map { s ->
                        if (s.id != incoming.id) s
                        else incoming.copy(
                            status = incoming.status ?: s.status,
                            mute = incoming.mute ?: s.mute,
                            connected = incoming.connected ?: s.connected,
                            model = incoming.model ?: s.model,
                            reasoningLevel = incoming.reasoningLevel ?: s.reasoningLevel,
                            repo_root = incoming.repo_root ?: s.repo_root,
                            role = incoming.role ?: s.role,
                            session_branch = incoming.session_branch ?: s.session_branch,
                            git = incoming.git ?: s.git,
                            finish_job = incoming.finish_job ?: s.finish_job,
                        )
                    }
                }
                rebuildSessions()
                incoming.finish_job?.let { job -> _finishJobs.update { it + (incoming.id to job) } }
            }
            is ServerFrame.SessionRemoved -> {
                sessionsByHost[recordId] = sessionsByHost[recordId].orEmpty().filterNot { it.id == f.id }
                rebuildSessions()
                _bgTasks.update { it - f.id }
            }
            is ServerFrame.SessionState ->
                patchSession(f.session) {
                    it.copy(
                        mute = f.mute ?: it.mute,
                        connected = f.connected ?: it.connected,
                        model = f.model ?: it.model,
                        reasoningLevel = f.reasoningLevel ?: it.reasoningLevel,
                    )
                }
            is ServerFrame.MessageAppend -> {
                // Optimistic-echo dedup (iOS BrokerSession parity): when the real inbound message
                // lands, drop the matching local-… placeholder we appended on send.
                _messages.value = _messages.value.toMutableMap().apply {
                    val prev = this[f.session] ?: emptyList()
                    val pruned = if (f.entry.direction.startsWith("in")) {
                        prev.filterNot { it.id.startsWith("local-") && it.text == f.entry.text }
                    } else prev
                    this[f.session] = pruned + f.entry
                }
            }
            is ServerFrame.ActivityAppend ->
                _activity.value = _activity.value.toMutableMap().apply {
                    this[f.session] = (this[f.session] ?: emptyList()) + f.event
                }
            is ServerFrame.BgTasks -> _bgTasks.update { it + (f.session to f.tasks) }
            is ServerFrame.AgentState -> {
                _agentState.value = _agentState.value.toMutableMap().apply {
                    this[f.session] = AgentStatus(
                        phase = f.phase, state = f.state, working = f.working,
                        detail = f.detail, tool = f.tool, since = f.since, workingSince = f.workingSince,
                        waiting = f.waiting, bgOpen = f.bgOpen,
                    )
                }
                _pendingSend.update { it - f.session }   // first real state clears the client-local "Sending…"
                if (f.state != "dead" && _agentErrors.value.containsKey(f.session)) {
                    _agentErrors.update { it - f.session }
                }
            }
            is ServerFrame.CommandsChanged -> {
                _commands.value = _commands.value.toMutableMap().apply { this[f.session] = f.commands }
                _commandsResolved.update { it + (f.session to f.resolved) }
            }
            is ServerFrame.AgentError -> _agentErrors.update { it + (f.session to f) }
            is ServerFrame.FinishJobFrame -> {
                val job = f.job
                if (job != null) {
                    _finishJobs.update { it + (f.session to job) }
                    patchSession(f.session) { it.copy(finish_job = job) }
                }
            }
            is ServerFrame.FsChanged -> _fsChanges.tryEmit(f)
            is ServerFrame.DisplayAdded ->
                _displays.update { list -> list.filterNot { it.id == f.display.id } + f.display }
            is ServerFrame.DisplayRemoved ->
                _displays.update { list -> list.filterNot { it.id == f.id } }
            is ServerFrame.LspStatus -> _lspStatus.update { it + ("${f.session}|${f.path}" to f) }
            is ServerFrame.LspReady -> markLspState(f.session, f.serverId, "ready")
            is ServerFrame.LspError -> markLspState(f.session, f.serverId, "error", f.error)
            is ServerFrame.LspRpcIn -> _lspRpc.tryEmit(f)
            is ServerFrame.LspExit -> markLspState(f.session, f.serverId, "exited")
            is ServerFrame.LspInstallProgress ->
                _lspInstallLog.update { it + (f.serverId to ((it[f.serverId] ?: emptyList()) + f.line)) }
            is ServerFrame.LspInstallDone -> _lspInstallDone.update { it + (f.serverId to f) }
            is ServerFrame.SessionGit -> patchSession(f.session) { it.copy(git = f.git) }
            else -> {}
        }
    }

    /** Socket connect/disconnect for a host — drives the offline/greyed group (spec §5). The
     *  session bucket is deliberately retained on disconnect so its last snapshot stays visible. */
    private fun onConnState(recordId: String, online: Boolean) {
        onlineHosts[recordId] = online
        rebuildHostViews()
    }

    /** Rederive [_sessions] + [_sessionHost] from the per-host buckets, in store host order. */
    private fun rebuildSessions() {
        val order = store.list().map { it.recordId }
        val ids = LinkedHashSet(order).apply { addAll(sessionsByHost.keys) }
        val flat = ArrayList<SessionInfo>()
        val owner = HashMap<String, String>()
        for (rid in ids) {
            sessionsByHost[rid]?.forEach { s ->
                if (owner[s.id] == null) { flat += s; owner[s.id] = rid }
            }
        }
        _sessions.value = flat
        _sessionHost.value = owner
    }

    /** Rebuild [_hostViews] from the store + live online map. */
    private fun rebuildHostViews() {
        _hostViews.value = store.list().map { h ->
            HostView(
                recordId = h.recordId,
                hostId = h.hostId,
                displayName = h.displayName,
                online = onlineHosts[h.recordId] == true,
                lastSeenAt = h.lastSeenAt,
            )
        }
    }

    /** Patch one session in its owning host's bucket, then rederive the merged list. No-op if the
     *  session's owner/row is unknown (e.g. a state frame racing ahead of its session_added). */
    private fun patchSession(id: String, f: (SessionInfo) -> SessionInfo) {
        val rid = _sessionHost.value[id] ?: sessionsByHost.entries.firstOrNull { e -> e.value.any { it.id == id } }?.key ?: return
        val bucket = sessionsByHost[rid] ?: return
        val idx = bucket.indexOfFirst { it.id == id }
        if (idx < 0) return
        sessionsByHost[rid] = bucket.toMutableList().also { it[idx] = f(it[idx]) }
        rebuildSessions()
    }

    // ── Per-session / active-host routing ──────────────────────────────────────────

    private fun ownerOf(sessionId: String): String? = _sessionHost.value[sessionId]
    private fun apiFor(sessionId: String): BrokerApi? = hostConns.api(ownerOf(sessionId))
    private fun clientFor(sessionId: String): BrokerClient? = hostConns.client(ownerOf(sessionId))
    private fun connFor(sessionId: String): HostConnections.Conn? =
        hostConns.conn(ownerOf(sessionId)) ?: activeConn()
    private fun activeConn(): HostConnections.Conn? =
        hostConns.conn(_activeHost.value) ?: hostConns.all().firstOrNull()
    private fun activeApi(): BrokerApi? = activeConn()?.api
    private fun activeClient(): BrokerClient? = activeConn()?.client

    /** Route host-global operations (settings/agents/spawn/launcher pickers) to a chosen host —
     *  the launcher's host picker and opening a chat both call this. */
    fun setActiveHost(recordId: String) { _activeHost.value = recordId }

    // ── Add host (spec §3.4 / §5) ──────────────────────────────────────────────────

    /**
     * Claim a host from a scanned/pasted [PairingPayload]: POST /pair/claim, ABORT if the returned
     * `host.hostId` differs from the payload's (identity-mismatch guard), then persist via the store
     * and open its connection. The merged list picks up its sessions on the next snapshot.
     */
    suspend fun addHost(payload: PairingPayload, deviceName: String): AddHostResult {
        val url = payload.relayUrl ?: payload.directUrl
            ?: return AddHostResult.Error("That pairing link has no host URL.")
        val api = BrokerApi(url, "", http)
        val res = runCatching { api.pairClaim(payload.claimSecret, deviceName) }.getOrNull()
            ?: return AddHostResult.Error("The host rejected the pairing — the claim is expired or already used.")
        if (res.deviceToken.isBlank()) return AddHostResult.Error("The host didn't return a device token.")
        res.host?.hostId?.takeIf { it.isNotBlank() }?.let { returned ->
            if (returned != payload.hostId) {
                return AddHostResult.Error("Host identity mismatch (scanned ${payload.hostId}, got $returned) — aborting.")
            }
        }
        val host = store.add(
            displayName = payload.name.ifBlank { res.host?.name?.ifBlank { null } ?: "New host" },
            token = res.deviceToken,
            relayUrl = payload.relayUrl,
            directUrl = payload.directUrl,
            hostId = payload.hostId,
            platform = res.host?.platform,
            version = res.host?.version,
        )
        onHostsChanged()
        return AddHostResult.Added(host)
    }

    /**
     * Typed-URL add-host (Tailscale/VPN/reverse-proxy — spec §5/D10): GET /host to confirm it is a
     * supermux broker, then try a secret-less claim. On a fresh/unclaimed broker that mints a device
     * (trust-on-first-connect) and we persist it; an already-set-up host returns [NeedsClaim] so the
     * user mints a claim from the host's own UI and scans/pastes that instead.
     */
    suspend fun addHostByUrl(rawUrl: String, deviceName: String): AddHostResult {
        val url = normalizeHostUrl(rawUrl) ?: return AddHostResult.Error("Enter a valid http(s) or ws(s) URL.")
        val api = BrokerApi(url, "", http)
        val identity = runCatching { api.getHost() }.getOrNull()
            ?: return AddHostResult.Error("That doesn't look like a supermux host (no /host response).")
        val res = runCatching { api.pairClaim("", deviceName) }.getOrNull()
        if (res == null || res.deviceToken.isBlank()) return AddHostResult.NeedsClaim(identity)
        res.host?.hostId?.takeIf { it.isNotBlank() }?.let { returned ->
            if (identity.hostId.isNotBlank() && returned != identity.hostId) {
                return AddHostResult.Error("Host identity mismatch — aborting.")
            }
        }
        val host = store.add(
            displayName = identity.name.ifBlank { "New host" },
            token = res.deviceToken,
            directUrl = url,
            hostId = identity.hostId.ifBlank { null },
            platform = res.host?.platform ?: identity.platform,
            version = res.host?.version ?: identity.version,
        )
        onHostsChanged()
        return AddHostResult.Added(host)
    }

    /** Forget a host: drop its record/token (best-effort local revoke happens in the persistence),
     *  close its socket, and prune its cached sessions from the merged list. */
    fun forgetHost(recordId: String) {
        store.remove(recordId)
        sessionsByHost.remove(recordId)
        onlineHosts.remove(recordId)
        if (_activeHost.value == recordId) _activeHost.value = store.list().firstOrNull()?.recordId
        onHostsChanged()
        rebuildSessions()
    }

    private fun onHostsChanged() {
        hostConns.sync(store.list())
        if (_activeHost.value == null) _activeHost.value = store.list().firstOrNull()?.recordId
        rebuildHostViews()
    }

    private fun normalizeHostUrl(raw: String): String? {
        val t = raw.trim().trimEnd('/')
        if (t.isBlank()) return null
        return when {
            t.startsWith("http://") || t.startsWith("https://") ||
                t.startsWith("ws://") || t.startsWith("wss://") -> t
            t.contains("://") -> null
            else -> "https://$t"
        }
    }

    // ── Host-filter persistence (merged-list chip selection) ────────────────────────
    private val hostFilterKey = stringPreferencesKey("host_filter")

    /** Persisted host-filter recordId, or null for "All". */
    suspend fun loadHostFilter(): String? =
        runCatching { appContext.launcherDataStore.data.first()[hostFilterKey] }.getOrNull()
            ?.takeIf { it.isNotBlank() }

    fun saveHostFilter(recordId: String?) {
        viewModelScope.launch {
            runCatching { appContext.launcherDataStore.edit { it[hostFilterKey] = recordId ?: "" } }
        }
    }

    // ── Viewing presence (mirrors iOS BrokerSession / web useViewing) ──────────────

    /** Report the foreground chat (`null` = the session list) + whether the app is visible.
     *  Also makes that chat's host the active host so host-global ops target it. */
    fun updateViewing(session: String?, visible: Boolean) {
        viewingSession = session
        viewingVisible = visible
        if (session != null) ownerOf(session)?.let { _activeHost.value = it }
        sendViewingIfChanged()
        ensureViewingHeartbeat()
    }

    private fun sendViewingIfChanged() {
        val next = viewingSession to viewingVisible
        if (lastSentViewing == next) return
        lastSentViewing = next
        val target = viewingSession?.let { clientFor(it) } ?: activeClient()
        viewModelScope.launch { runCatching { target?.send(ClientFrame.Viewing(viewingSession, viewingVisible)) } }
    }

    private fun ensureViewingHeartbeat() {
        if (viewingHeartbeat?.isActive == true) return
        viewingHeartbeat = viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                if (viewingVisible) {
                    val target = viewingSession?.let { clientFor(it) } ?: activeClient()
                    runCatching { target?.send(ClientFrame.Viewing(viewingSession, true)) }
                }
            }
        }
    }

    /** Patch the `state` (and optionally `error`) of every [ServerFrame.LspStatus] entry
     *  matching [session] + [serverId]. */
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

    /** Soft-stop the running agent (POST /sessions/<id>/interrupt). */
    fun interrupt(id: String) { viewModelScope.launch { runCatching { apiFor(id)?.interrupt(id) } } }

    /** ISO-8601 (UTC) timestamp so an optimistic entry sorts LAST under mergeTimeline's
     *  lexicographic `ts` ordering (the broker emits ISO-8601 too). */
    private fun nowIso(): String = java.time.Instant.now().toString()

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
            clientFor(sessionId)?.send(ClientFrame.Send(sessionId, args = SendArgs(text)))
            _pendingSend.update { it + sessionId }
        }
    }

    // ── Per-session composer draft persistence (DataStore) ─────────────────────────
    private fun draftKey(sessionId: String) = stringPreferencesKey("draft:$sessionId")

    suspend fun loadDraft(sessionId: String): String =
        runCatching { appContext.draftDataStore.data.first()[draftKey(sessionId)] }.getOrNull() ?: ""

    fun saveDraft(sessionId: String, text: String) {
        viewModelScope.launch {
            runCatching { appContext.draftDataStore.edit { it[draftKey(sessionId)] = text } }
        }
    }

    // ── New Session launcher state persistence (DataStore) ─────────────────────────
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

    fun connectTerminal(sessionId: String): TerminalClient {
        val c = connFor(sessionId)
        return TerminalClient(c?.baseUrl ?: "", c?.token ?: "", http, sessionId)
    }

    /** Raw agent-PTY terminal for the Native tab (kind="agent"); shell tab uses the scratch kind. */
    fun connectAgentTerminal(sessionId: String): TerminalClient {
        val c = connFor(sessionId)
        return TerminalClient(c?.baseUrl ?: "", c?.token ?: "", http, sessionId, kind = "agent")
    }

    /** GET /displays on the active host. Also seeds [_displays]. */
    suspend fun listDisplays(): List<DisplayStream> {
        val list = runCatching { activeApi()?.listDisplays() }.getOrNull() ?: return _displays.value
        _displays.value = list
        return list
    }
    fun connectScrcpy(streamId: String): ScrcpyClient {
        val c = activeConn()
        return ScrcpyClient(c?.baseUrl ?: "", c?.token ?: "", http, streamId)
    }
    fun connectVnc(streamId: String): VncClient {
        val c = activeConn()
        return VncClient(c?.baseUrl ?: "", c?.token ?: "", http, streamId)
    }

    suspend fun startDisplay(
        sessionName: String,
        provider: String? = null,
        device: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): DisplayStream? =
        runCatching { activeApi()?.startDisplay(sessionName, provider, device, width, height) }.getOrNull()

    suspend fun stopDisplay(id: String) { runCatching { activeApi()?.stopDisplay(id) } }

    suspend fun upload(
        sessionId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        kind: String? = null,
    ): String? = runCatching { apiFor(sessionId)?.upload(sessionId, bytes, name, mime, kind)?.file_id }.getOrNull()

    suspend fun uploadResumable(
        sessionId: String,
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String? = null,
        onProgress: (Long, Long) -> Unit,
    ): String? = runCatching {
        apiFor(sessionId)?.uploadResumable(sessionId, source, name, mime, kind, onProgress)?.file_id
    }.getOrNull()

    // ── Voice dictation ──────────────────────────────────────────────────────────

    /** Whisper path: multipart audio → cleaned text. sessionId null (launcher) → active host. */
    suspend fun transcribeAudio(sessionId: String?, bytes: ByteArray, filename: String): String? =
        runCatching { (sessionId?.let { apiFor(it) } ?: activeApi())?.transcribeAudio(sessionId, bytes, filename)?.text }.getOrNull()

    /** On-device-STT path: JSON draft → cleaned text. sessionId null (launcher) → active host. */
    suspend fun transcribeDraft(sessionId: String?, draft: String): String? =
        runCatching { (sessionId?.let { apiFor(it) } ?: activeApi())?.transcribeDraft(sessionId, draft)?.text }.getOrNull()

    fun sendWith(sessionId: String, text: String, attachments: List<String>) {
        appendOptimistic(sessionId, text.trim())
        viewModelScope.launch {
            runCatching {
                clientFor(sessionId)?.send(ClientFrame.Send(sessionId, args = SendArgs(text, attachments.ifEmpty { null })))
                _pendingSend.update { it + sessionId }
            }
        }
    }

    fun rename(id: String, name: String) { viewModelScope.launch { runCatching { apiFor(id)?.rename(id, name) } } }
    fun setMute(id: String, muted: Boolean) { viewModelScope.launch { runCatching { apiFor(id)?.setMute(id, muted) } } }
    fun kill(id: String, onDone: () -> Unit = {}) { viewModelScope.launch { runCatching { apiFor(id)?.kill(id) }; onDone() } }

    suspend fun fetchModels(id: String): ModelsResponse? = runCatching { apiFor(id)?.models(id) }.getOrNull()
    suspend fun fetchReasoning(id: String): ReasoningResponse? = runCatching { apiFor(id)?.reasoningLevels(id) }.getOrNull()
    // Optimistic local update (web parity): the pill flips immediately; the broker's session_state
    // broadcast confirms it (or rolls it back on a failed live switch).
    fun switchModel(id: String, model: String) {
        viewModelScope.launch {
            runCatching { apiFor(id)?.switchModel(id, model) }.onSuccess {
                patchSession(id) { it.copy(model = model) }
            }
        }
    }
    fun switchReasoning(id: String, level: String) {
        viewModelScope.launch {
            runCatching { apiFor(id)?.switchReasoning(id, level) }.onSuccess {
                patchSession(id) { it.copy(reasoningLevel = level) }
            }
        }
    }

    // ── Finish flow ──────────────────────────────────────────────────────────────
    fun finish(
        id: String,
        action: String? = null,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        onKickoff: (Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            val ok = runCatching { apiFor(id)?.finish(id, action, skipVerify, commitFirst, commitMessage) }.isSuccess
            onKickoff(ok)
        }
    }

    suspend fun finishReadiness(id: String): FinishReadiness? =
        runCatching { apiFor(id)?.finishReadiness(id) }.getOrNull()
    suspend fun verifySuggest(id: String): VerifySuggestResult? =
        runCatching { apiFor(id)?.verifySuggest(id) }.getOrNull()
    suspend fun verifySave(id: String, content: String): VerifySaveResult? =
        runCatching { apiFor(id)?.verifySave(id, content) }.getOrNull()
    fun sendMessage(id: String, text: String) { viewModelScope.launch { runCatching { apiFor(id)?.sendMessage(id, text) } } }

    fun gitFetch(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { apiFor(id)?.gitFetch(id) }.getOrNull()) } }
    fun gitPush(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { apiFor(id)?.gitPush(id) }.getOrNull()) } }
    fun gitPull(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { apiFor(id)?.gitPull(id) }.getOrNull()) } }
    fun gitPublish(id: String, onResult: (GitOpResult?) -> Unit) { viewModelScope.launch { onResult(runCatching { apiFor(id)?.gitPublish(id) }.getOrNull()) } }

    data class PendingFirstMessage(val text: String, val attachments: List<String> = emptyList())

    private var pendingFirst: Pair<String, PendingFirstMessage>? = null

    fun setPendingFirst(sessionId: String, message: PendingFirstMessage) { pendingFirst = sessionId to message }

    fun consumePendingFirst(sessionId: String): PendingFirstMessage? {
        val entry = pendingFirst ?: return null
        if (entry.first != sessionId) return null
        pendingFirst = null
        return entry.second
    }

    fun spawn(workdir: String, name: String?, agent: String, model: String? = null) {
        viewModelScope.launch {
            runCatching {
                activeApi()?.spawn(SpawnRequest(workdir = workdir, name = name?.ifBlank { null }, agent = agent, model = model?.ifBlank { null }))
            }
        }
    }

    /** Create a session on the ACTIVE host (the launcher's host picker sets it) then queue the first
     *  message for [dev.supermux.android.chat.ChatScreen] to send on open. */
    suspend fun createSessionWithFirstMessage(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        staged: List<StagedUpload> = emptyList(),
        worktree: Boolean = false,
        baseBranch: String? = null,
        reasoningLevel: String? = null,
    ): String {
        val api = activeApi() ?: throw IllegalStateException("No host connected")
        val validation = runCatching { api.validatePath(workdir) }.getOrNull()
            ?: throw IllegalArgumentException("Could not validate path")
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
                reasoningLevel = reasoningLevel?.ifBlank { null },
            ),
        )
        val sessionId = resp.id.ifBlank {
            _sessions.value.firstOrNull { it.name == resp.name }?.id
                ?: throw IllegalStateException("Session created but id not available yet")
        }
        // Uploads bind to the SAME host we spawned on (not the owner index, which the WS frame may
        // not have populated yet). A file that fails to upload is skipped — the first message still
        // sends with whatever succeeded.
        val attachmentIds = staged.mapNotNull { s ->
            runCatching { api.uploadResumable(sessionId, s.source, s.name, s.mime, s.kind) { _, _ -> }.file_id }.getOrNull()
        }
        setPendingFirst(sessionId, PendingFirstMessage(text, attachmentIds))
        return sessionId
    }

    // ── Settings / Usage / Devices / Archived (active host) ────────────────────────

    suspend fun config(): AppConfigDto? = runCatching { activeApi()?.getConfig() }.getOrNull()
    fun saveName(n: String) { viewModelScope.launch { runCatching { activeApi()?.putConfig(n) } } }

    suspend fun launcherModels(agent: String): List<ModelInfo> =
        runCatching { activeApi()?.listModels(agent)?.models }.getOrNull() ?: emptyList()
    suspend fun launcherReasoning(agent: String, model: String?): ReasoningResponse? =
        runCatching { activeApi()?.getReasoningLevels(agent, model) }.getOrNull()
    suspend fun launcherRepoInfo(workdir: String): RepoInfo? =
        runCatching { activeApi()?.getRepoInfo(workdir) }.getOrNull()
    suspend fun launcherCommands(agent: String, workdir: String): List<SlashCommand> =
        if (workdir.isBlank()) emptyList()
        else runCatching { activeApi()?.previewCommands(agent, workdir)?.commands }.getOrNull() ?: emptyList()

    fun saveVoiceCleanup(engine: String?, model: String?) {
        viewModelScope.launch { runCatching { activeApi()?.saveConfig(voiceCleanupEngine = engine, voiceCleanupModel = model) } }
    }

    suspend fun fetchGlossary(): List<String> = runCatching { activeApi()?.fetchGlossary() }.getOrNull() ?: emptyList()
    suspend fun updateGlossary(terms: List<String>): List<String>? =
        runCatching { activeApi()?.updateGlossary(terms) }.getOrNull()
    suspend fun usage(): String? = runCatching { activeApi()?.usageRaw() }.getOrNull()
    suspend fun redeemCodexReset(): CodexResetResult? = runCatching { activeApi()?.redeemCodexReset() }.getOrNull()
    suspend fun curatorSettings(): CuratorSettingsResponse? = runCatching { activeApi()?.getCuratorSettings() }.getOrNull()
    suspend fun saveCurator(enabled: Boolean, hour: Int, minute: Int): CuratorSettingsResponse? =
        runCatching { activeApi()?.saveCuratorSettings(enabled, hour, minute) }.getOrNull()
    suspend fun runCuratorNow() { runCatching { activeApi()?.runCuratorNow() } }
    suspend fun devices(): List<DeviceDto> = runCatching { activeApi()?.devices() }.getOrNull() ?: emptyList()
    suspend fun addDevice(name: String): AddDeviceResponse? = runCatching { activeApi()?.addDevice(name) }.getOrNull()
    fun revoke(n: String) { viewModelScope.launch { runCatching { activeApi()?.revokeDevice(n) } } }
    suspend fun archived(): List<ArchivedDto> = runCatching { activeApi()?.archived() }.getOrNull() ?: emptyList()
    fun resume(id: String) { viewModelScope.launch { runCatching { activeApi()?.resume(id) } } }

    // ── Assistant ──────────────────────────────────────────────────────────────

    suspend fun assistantLoad(): Pair<String, String>? = coroutineScope {
        val api = activeApi() ?: return@coroutineScope null
        val cfg = async { runCatching { api.getConfig() }.getOrNull() }
        val soul = async { runCatching { api.getSoul() }.getOrNull() ?: "" }
        cfg.await()?.let { it.paName to soul.await() }
    }

    suspend fun assistantSave(paName: String, soul: String): Boolean {
        val api = activeApi() ?: return false
        runCatching { api.saveConfig(paName = paName) }
        return runCatching { api.putSoul(soul) }.getOrDefault(false)
    }

    // ── Agents ─────────────────────────────────────────────────────────────────

    suspend fun agentStatuses(): List<AgentInstallStatus> =
        runCatching { activeApi()?.agentStatuses() }.getOrNull() ?: emptyList()
    suspend fun agentStartLogin(kind: String): AgentLoginState? =
        runCatching { activeApi()?.startAgentLogin(kind) }.getOrNull()
    suspend fun agentPollLogin(kind: String): AgentLoginState? =
        runCatching { activeApi()?.agentLoginState(kind) }.getOrNull()
    fun agentSendCode(kind: String, code: String) {
        viewModelScope.launch { runCatching { activeApi()?.sendAgentLoginCode(kind, code) } }
    }
    fun agentCancelLogin(kind: String) {
        viewModelScope.launch { runCatching { activeApi()?.cancelAgentLogin(kind) } }
    }
    fun agentSaveSecret(kind: String, value: String) {
        viewModelScope.launch {
            runCatching {
                when (kind) {
                    "claude" -> activeApi()?.saveConfig(claudeOauthToken = value)
                    "codex" -> activeApi()?.saveConfig(codexApiKey = value)
                    "cursor" -> activeApi()?.saveConfig(cursorApiKey = value)
                }
            }
        }
    }
    suspend fun openCodeProviders(): List<OpenCodeProvider> =
        runCatching { activeApi()?.openCodeProviders() }.getOrNull() ?: emptyList()
    fun openCodeSetKey(providerId: String, key: String) {
        viewModelScope.launch { runCatching { activeApi()?.setOpenCodeKey(providerId, key) } }
    }
    suspend fun openCodeStartOAuth(providerId: String, method: Int): OpenCodeOAuthStart? =
        runCatching { activeApi()?.startOpenCodeOAuth(providerId, method) }.getOrNull()
    fun openCodeFinishOAuth(providerId: String, method: Int, code: String) {
        viewModelScope.launch { runCatching { activeApi()?.finishOpenCodeOAuth(providerId, method, code) } }
    }

    // ── Editor / LSP ───────────────────────────────────────────────────────────

    suspend fun lspLoad(): List<LspServer> =
        runCatching { activeApi()?.getEditorSettings()?.lsp?.servers }.getOrNull() ?: emptyList()
    suspend fun lspToggle(id: String, enabled: Boolean): List<LspServer>? =
        runCatching { activeApi()?.setLspEnabled(id, enabled)?.lsp?.servers }.getOrNull()
    suspend fun lspInstall(id: String): LspInstallResult? =
        runCatching { activeApi()?.installEditorLsp(id) }.getOrNull()
    suspend fun lspAddCustom(a: AddCustomLspArgs): LspMutationResult? =
        runCatching {
            activeApi()?.addCustomEditorLsp(a.id, a.label, a.command, a.extensions, a.args, a.languageId, a.installCmd)
        }.getOrNull()
    suspend fun lspRemoveCustom(id: String): LspMutationResult? =
        runCatching { activeApi()?.removeCustomEditorLsp(id) }.getOrNull()

    // ── Git hosting (forges) ─────────────────────────────────────────────────────

    suspend fun forgesLoad(): ForgeConnectionsResponse? = runCatching { activeApi()?.listForges() }.getOrNull()
    suspend fun forgeAdd(kind: String, token: String, host: String?, transport: String): Boolean =
        runCatching { activeApi()?.addForge(kind, token, host, transport); true }.getOrDefault(false)
    suspend fun forgeImport(kind: String, transport: String): Boolean =
        runCatching { activeApi()?.importForge(kind, transport); true }.getOrDefault(false)
    fun forgeRemove(id: String) { viewModelScope.launch { runCatching { activeApi()?.removeForge(id) } } }

    // ── System ─────────────────────────────────────────────────────────────────

    suspend fun updateStatus(): UpdateStatus? = runCatching { activeApi()?.updateStatus() }.getOrNull()
    fun restartBroker() { viewModelScope.launch { runCatching { activeApi()?.restartBroker() } } }

    suspend fun fileBytes(fileId: String): ByteArray? = activeApi()?.fileBytes(fileId)
    suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        runCatching { (apiFor(sessionId) ?: activeApi())?.archivedLogs(sessionId) }.getOrNull() ?: emptyList()

    fun ensureMessagesLoaded(sessionId: String) {
        if (_messages.value[sessionId]?.isNotEmpty() == true) return
        viewModelScope.launch {
            val fetched = archivedLogs(sessionId)
            if (fetched.isNotEmpty() && _messages.value[sessionId]?.isNotEmpty() != true) {
                _messages.update { it + (sessionId to fetched) }
            }
        }
    }

    // ── Editor filesystem ──────────────────────────────────────────────────────

    suspend fun fsList(sessionId: String, path: String): List<FsEntry> =
        runCatching { apiFor(sessionId)?.fsList(sessionId, path) }.getOrNull() ?: emptyList()
    suspend fun fsRead(sessionId: String, path: String): Result<String> =
        runCatching { apiFor(sessionId)?.fsRead(sessionId, path) ?: error("host offline") }
    suspend fun fsWrite(sessionId: String, path: String, content: String): Boolean =
        runCatching { apiFor(sessionId)?.fsWrite(sessionId, path, content) ?: false }.getOrDefault(false)
    suspend fun fsSearch(sessionId: String, q: String): List<FsSearchResult> =
        runCatching { apiFor(sessionId)?.fsSearch(sessionId, q) }.getOrNull() ?: emptyList()

    // ── Editor diff + inline code-review ───────────────────────────────────────

    suspend fun fsDiff(sessionId: String, base: String? = null): FsDiffResult? =
        runCatching { apiFor(sessionId)?.fsDiff(sessionId, base) }.getOrNull()
    suspend fun fsRefs(sessionId: String): FsRefsResult? =
        runCatching { apiFor(sessionId)?.fsRefs(sessionId) }.getOrNull()
    suspend fun reviewAddComment(sessionId: String, body: AddCommentBody): ReviewComment? =
        runCatching { apiFor(sessionId)?.reviewAddComment(sessionId, body) }.getOrNull()
    suspend fun reviewResolve(sessionId: String, commentId: String): Boolean =
        runCatching { apiFor(sessionId)?.reviewUpdateComment(sessionId, commentId, UpdateCommentBody(status = "resolved")) ?: false }
            .getOrDefault(false)
    suspend fun reviewSubmit(sessionId: String): ReviewSubmitResult? =
        runCatching { apiFor(sessionId)?.reviewSubmit(sessionId) }.getOrNull()

    // ── Editor lifecycle + LSP control-plane senders ───────────────────────────

    fun editorOpen(sessionId: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.EditorOpen(sessionId)) } }
    }
    fun editorClose(sessionId: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.EditorClose(sessionId)) } }
    }
    fun lspStatusQuery(sessionId: String, path: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.LspStatusQuery(sessionId, path)) } }
    }
    fun lspOpen(sessionId: String, serverId: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.LspOpen(sessionId, serverId)) } }
    }
    fun lspRpcOut(sessionId: String, serverId: String, message: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.LspRpcOut(sessionId, serverId, message)) } }
    }
    fun lspClose(sessionId: String, serverId: String) {
        viewModelScope.launch { runCatching { clientFor(sessionId)?.send(ClientFrame.LspClose(sessionId, serverId)) } }
    }

    suspend fun listProjects(): List<String> = runCatching { activeApi()?.listProjects() }.getOrNull() ?: emptyList()
    suspend fun validatePath(path: String): PathValidation? = runCatching { activeApi()?.validatePath(path) }.getOrNull()

    // ── Git hosting / forges (project-picker omnibox) ────
    suspend fun listForges(): List<ForgeConnection> =
        runCatching { activeApi()?.listForges()?.connections }.getOrNull() ?: emptyList()
    suspend fun searchForge(query: String): List<RemoteRepo> =
        runCatching { activeApi()?.searchForge(query)?.repos }.getOrNull() ?: emptyList()
    suspend fun cloneForge(connectionId: String, owner: String, name: String): String? =
        runCatching { activeApi()?.cloneForge(connectionId, owner, name)?.localPath }.getOrNull()?.ifBlank { null }
    suspend fun createLocalRepo(name: String): String? =
        runCatching { activeApi()?.createLocalRepo(name)?.localPath }.getOrNull()?.ifBlank { null }
    suspend fun createForge(connectionId: String, name: String): String? =
        runCatching { activeApi()?.createForge(connectionId, name)?.localPath }.getOrNull()?.ifBlank { null }

    // ── Proxies (active host) ──────────────────────────────────────────────────

    suspend fun proxies(): List<ProxyDto> = runCatching { activeApi()?.proxies() }.getOrNull() ?: emptyList()
    fun createProxy(sessionName: String, port: Int, domain: String?) {
        viewModelScope.launch { runCatching { activeApi()?.createProxy(sessionName, port, domain) } }
    }
    fun setProxyPublic(domain: String, isPublic: Boolean) {
        viewModelScope.launch { runCatching { activeApi()?.setProxyPublic(domain, isPublic) } }
    }
    fun removeProxy(domain: String) {
        viewModelScope.launch { runCatching { activeApi()?.removeProxy(domain) } }
    }

    override fun onCleared() {
        hostConns.closeAll()
        http.close()
    }
}
