package dev.supermux.state

import dev.supermux.state.AgentReplyEvent
import dev.supermux.host.HostSnapshotStore
import dev.supermux.host.HostView
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.PairingPayload
import dev.supermux.host.WorkspaceViewingSnapshot
import dev.supermux.host.framesForSnapshot
import dev.supermux.host.hostViewsFrom
import dev.supermux.host.isLegacyHostDisplayName
import dev.supermux.host.mergeSessions
import dev.supermux.host.previousHostClearSessionId
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.util.TransportPolicy
import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.net.AddCommentBody
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.AddViewBody
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ChunkSource
import dev.supermux.net.CodexResetResult
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
import dev.supermux.net.DraftAttachmentDto
import dev.supermux.net.FinishReadiness
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.ForgeSearchResponse
import dev.supermux.net.FsDiffResult
import dev.supermux.net.FsEntry
import dev.supermux.net.FsRefsResult
import dev.supermux.net.FsSearchResult
import dev.supermux.net.GitOpResult
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.ModelInfo
import dev.supermux.net.ModelsResponse
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.net.PADto
import dev.supermux.net.PairUrl
import dev.supermux.net.PathValidation
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoInfo
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSummary
import dev.supermux.net.UpdateStatus
import dev.supermux.net.UsageResponse
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.VncClient
import dev.supermux.host.workspaceForSession
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LayoutNodeDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.HandoffPrefill
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Multi-host orchestrator for the desktop client (spec §5) — the desktop analogue of Android's
 * multi-host `AppViewModel`, built as a thin layer OVER the existing single-host [HostStore]
 * rather than a rewrite of it. One [HostStore] (its own BrokerApi + control WS + reducer) per
 * paired host in the [store]; their per-host `sessions`/`messages`/`agentState`/`agentReplies` flows
 * are folded into merged, recordId-tagged StateFlows the fleet list renders. Session ids are
 * globally unique across hosts, so the merge is a straight fold (see [mergeSessions]).
 *
 * Routing mirrors Android: per-session operations target the OWNING host's [HostStore] (via
 * [appFor]); host-global operations (spawn/settings) target the ACTIVE host ([activeApp]). Existing
 * single-host desktop users are migrated to `PairedHost[0]` before this is built (see
 * host-store migration from a legacy single-host config).
 *
 * @param appFactory builds one host's [HostStore] `(effectiveUrl, token, onConnectionChange)`;
 *   the production default opens a live connection, tests inject `connectOnInit = false` apps.
 * @param claimOverride / hostProbeOverride injectable network seams for the add-host flow so
 *   add-host logic unit-tests without a live broker; default to a throwaway [BrokerApi] over [http].
 */
class FleetStore(
    val store: PairedHostStore,
    scope: CoroutineScope,
    private val deps: HostStoreDeps,
    private val nowMs: () -> Long = { deps.nowMs() },
    private val http: HttpClient = deps.httpFactory(null),
    walkthroughSeam: WalkthroughSeam<*>? = null,
    private val appFactory: (url: String, token: String, onConnectionChange: (Boolean) -> Unit) -> HostStore =
        { url, token, onConn -> HostStore(url, token, scope, deps, onConnectionChange = onConn, walkthroughSeam = walkthroughSeam) },
    private val claimOverride: (suspend (url: String, secret: String, deviceName: String) -> PairClaimResult?)? = null,
    private val hostProbeOverride: (suspend (url: String) -> HostIdentity?)? = null,
    private val localHostDisplayName: () -> String = { "Host" },
    /** Per-host offline-session cache (spec §5). When present, each host's last-known live
     *  sessions seed the merged list BEFORE any socket opens, so a host that is offline at
     *  launch renders its last snapshot instead of an empty group; the first live Snapshot
     *  overwrites the seed, and a forgotten host's cache is dropped. */
    private val snapshots: HostSnapshotStore? = null,
) {
    /** One host's live connection: its [HostStore] plus the flow-collector jobs folding it in. */
    private class HostConn(val app: HostStore, val jobs: MutableList<Job> = mutableListOf())

    // Own child scope (supervised, parented to the caller's) so [close] stops the folds without
    // tearing down the caller's scope, and one failed fold never cancels its siblings.
    private val fleetScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = SynchronizedObject()
    private val settingsJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // Insertion-ordered so the merged list / active-fallback follow the store's host order.
    private val conns = LinkedHashMap<String, HostConn>()
    private val sessionsByHost = LinkedHashMap<String, List<SessionInfo>>()
    private val messagesByHost = HashMap<String, Map<String, List<LogEntry>>>()
    private val agentByHost = HashMap<String, Map<String, AgentStatus>>()
    private val lastReadByHost = HashMap<String, Map<String, String>>()
    private val archivedByHost = HashMap<String, List<ArchivedDto>>()
    private val onlineHosts = HashMap<String, Boolean>()
    private var lastViewingHost: String? = null
    private var viewingSnapshot: WorkspaceViewingSnapshot? = null


    // ── Merged per-host projections (Android AppViewModel parity) ───────────────────
    // Everything the screens read off ONE object. Session/workspace/view ids are globally unique
    // across hosts, so each fan-in is a straight fold in host order.

    /** Live [HostStore]s, republished whenever [conns] changes — the driver for the folds below. */
    private val hostApps = MutableStateFlow<List<HostStore>>(emptyList())

    private fun publishApps() {
        hostApps.value = conns.values.map { it.app }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun <T> eachHost(select: (HostStore) -> Flow<T>): Flow<List<T>> =
        hostApps.flatMapLatest { apps ->
            if (apps.isEmpty()) {
                flowOf(emptyList())
            } else {
                // `combine` reifies its element type, so erase to Any? and cast the row back.
                combine(apps.map { select(it) as Flow<Any?> }) { row -> row.toList() as List<T> }
            }
        }

    private fun <K, V> mergedMap(select: (HostStore) -> StateFlow<Map<K, V>>): StateFlow<Map<K, V>> =
        eachHost(select)
            .map { parts -> LinkedHashMap<K, V>().also { out -> parts.forEach { out.putAll(it) } } as Map<K, V> }
            .stateIn(fleetScope, SharingStarted.Eagerly, emptyMap())

    private fun <T> mergedList(select: (HostStore) -> StateFlow<List<T>>): StateFlow<List<T>> =
        eachHost(select).map { it.flatten() }.stateIn(fleetScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> mergedEvents(select: (HostStore) -> Flow<T>): Flow<T> =
        hostApps.flatMapLatest { apps -> apps.map(select).merge() }

    val workspaces: StateFlow<List<WorkspaceDto>> = mergedList { it.workspaces }
    val archivedWorkspaces: StateFlow<List<WorkspaceDto>> = mergedList { it.archivedWorkspaces }
    val displays: StateFlow<List<DisplayStream>> = mergedList { it.displays }
    val activity: StateFlow<Map<String, List<ActivityEvent>>> = mergedMap { it.activity }
    val agentErrors: StateFlow<Map<String, ServerFrame.AgentError>> = mergedMap { it.agentErrors }
    val bgTasks: StateFlow<Map<String, List<ServerFrame.BgTask>>> = mergedMap { it.bgTasks }
    val commands: StateFlow<Map<String, List<SlashCommand>>> = mergedMap { it.commands }
    val commandsResolved: StateFlow<Map<String, Boolean>> = mergedMap { it.commandsResolved }
    val finishJobs: StateFlow<Map<String, FinishJobDto>> = mergedMap { it.finishJobs }
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = mergedMap { it.lspStatus }
    val lspInstallLog: StateFlow<Map<String, List<String>>> = mergedMap { it.lspInstallLog }
    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> = mergedMap { it.lspInstallDone }
    val pendingSend: StateFlow<Set<String>> =
        eachHost { it.pendingSend }.map { parts -> parts.flatten().toSet() }
            .stateIn(fleetScope, SharingStarted.Eagerly, emptySet())
    val fsChanges: Flow<ServerFrame.FsChanged> = mergedEvents { it.fsChanges }
    val lspRpc: Flow<ServerFrame.LspRpcIn> = mergedEvents { it.lspRpc }

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    /** sessionId → owning host recordId (drives per-row badges + per-session routing). */
    private val _sessionHost = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionHost: StateFlow<Map<String, String>> = _sessionHost.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val messages: StateFlow<Map<String, List<LogEntry>>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> = _agentState.asStateFlow()

    /** Merged sessionId → ISO last_read_at across hosts (ids are globally unique). */
    private val _lastRead = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastRead: StateFlow<Map<String, String>> = _lastRead.asStateFlow()

    private val _archivedSessions = MutableStateFlow<List<ArchivedDto>>(emptyList())
    val archivedSessions: StateFlow<List<ArchivedDto>> = _archivedSessions.asStateFlow()

    val hostFilter: Flow<String?> = deps.settings.string(SettingsKeys.HOST_FILTER).map { it?.takeIf(String::isNotBlank) }

    /** The paired fleet as the list/chips render it (identity + reachability + badge slot). */
    private val _hostViews = MutableStateFlow<List<HostView>>(emptyList())
    val hostViews: StateFlow<List<HostView>> = _hostViews.asStateFlow()

    /** recordId of the host that host-global ops (settings/spawn/launcher) target. */
    private val _activeHost = MutableStateFlow<String?>(null)
    val activeHost: StateFlow<String?> = _activeHost.asStateFlow()

    // Agent replies merged across every host, for AppShell's NotificationController. Same
    // replay-0 + bounded-DROP_OLDEST shape as HostStore.agentReplies.
    private val _agentReplies = MutableSharedFlow<AgentReplyEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val agentReplies: SharedFlow<AgentReplyEvent> = _agentReplies.asSharedFlow()

    init {
        synchronized(lock) {
            _activeHost.value = store.list().firstOrNull()?.recordId
            // Seed from the offline cache BEFORE dialing: an offline host shows its last-known
            // sessions rather than nothing. Prune caches for hosts forgotten while the app was dead.
            snapshots?.let { cache ->
                cache.retainOnly(store.list().map { it.recordId })
                cache.all().forEach { snap ->
                    if (snap.sessions.isNotEmpty()) sessionsByHost[snap.recordId] = snap.sessions
                }
            }
            sync(store.list())
            recomputeAll()
        }
    }

    // ── Connection lifecycle (mirror of Android HostConnections.sync) ────────────────

    /** Reconcile live connections against [hosts]: open one [HostStore] per newly-added host,
     *  close the one for each removed host, rebuild a host whose effective URL or token changed.
     *  Idempotent. Hosts with a blank token or no reachable URL are kept in the store but not dialed. */
    private fun sync(hosts: List<PairedHost>) = synchronized(lock) {
        val wanted = hosts.mapNotNull { h -> effectiveUrl(h)?.let { url -> Triple(h.recordId, url, h.token) } }
            .filter { it.third.isNotBlank() }
        val wantedIds = wanted.map { it.first }.toSet()
        (conns.keys - wantedIds).toList().forEach { close(it) }
        for ((recordId, url, token) in wanted) {
            val existing = conns[recordId]
            if (existing == null) {
                open(recordId, url, token)
            } else if (existing.app.baseUrl != url) {
                close(recordId); open(recordId, url, token)
            }
        }
    }

    private fun open(recordId: String, url: String, token: String) {
        val app = appFactory(url, token) { online -> onConnState(recordId, online) }
        val conn = HostConn(app)
        // Collectors funnel each host's flows into the merged state, tagged by recordId.
        conn.jobs += fleetScope.launch { app.sessions.collect { onHostSessions(recordId, it) } }
        conn.jobs += fleetScope.launch { app.messages.collect { onHostMessages(recordId, it) } }
        conn.jobs += fleetScope.launch { app.agentState.collect { onHostAgent(recordId, it) } }
        conn.jobs += fleetScope.launch { app.lastRead.collect { onHostLastRead(recordId, it) } }
        conn.jobs += fleetScope.launch { app.agentReplies.collect { _agentReplies.tryEmit(it) } }
        conn.jobs += fleetScope.launch { app.archivedSessions.collect { onHostArchived(recordId, it) } }
        conns[recordId] = conn
        publishApps()
    }

    fun close(recordId: String) {
        val c = conns.remove(recordId) ?: return
        c.jobs.forEach { it.cancel() }
        c.app.close()
        onlineHosts[recordId] = false
        // Only when the record is really gone — a URL/token rebuild closes and reopens the same host.
        if (store.list().none { it.recordId == recordId }) snapshots?.remove(recordId)
        publishApps()
    }

    // ── Per-host fold callbacks ──────────────────────────────────────────────────────

    private fun onHostSessions(recordId: String, sessions: List<SessionInfo>) = synchronized(lock) {
        // A freshly opened HostStore emits an empty list before its first Snapshot; that must not
        // wipe a bucket seeded from the offline cache. Once the host is online its list is
        // authoritative, empty included.
        val seedHolds = sessions.isEmpty() &&
            onlineHosts[recordId] != true &&
            sessionsByHost[recordId]?.isNotEmpty() == true
        if (seedHolds) return@synchronized
        sessionsByHost[recordId] = sessions
        recomputeSessions()
    }

    private fun onHostMessages(recordId: String, messages: Map<String, List<LogEntry>>) = synchronized(lock) {
        messagesByHost[recordId] = messages
        recomputeMessages()
    }

    private fun onHostAgent(recordId: String, agent: Map<String, AgentStatus>) = synchronized(lock) {
        agentByHost[recordId] = agent
        recomputeAgent()
    }

    private fun onHostLastRead(recordId: String, reads: Map<String, String>) = synchronized(lock) {
        lastReadByHost[recordId] = reads
        recomputeLastRead()
    }

    /** Socket connect/disconnect for a host — drives the offline/greyed chip (spec §5) and stamps
     *  lastSeen on connect. The session bucket is retained on disconnect so its last snapshot stays
     *  visible. */
    private fun onConnState(recordId: String, online: Boolean) = synchronized(lock) {
        onlineHosts[recordId] = online
        if (online) store.updateSeen(recordId, nowMs())
        rebuildHostViews()
        if (online) backfillHostIdentity(recordId)
    }

    /** Once a host's socket is up, learn its durable hostId from GET /host and backfill the record
     *  (spec §3.1/§5): a migrated `hostId == null` record gets its real id, and if that id already
     *  belongs to another record the two collapse into one (the shared store merges them). Best-effort
     *  and idempotent — skipped once the id is known, so it doesn't refetch on every reconnect. */
    private fun backfillHostIdentity(recordId: String) {
        val current = store.list().firstOrNull { it.recordId == recordId } ?: return
        if (!current.hostId.isNullOrBlank() && !isLegacyHostDisplayName(current.displayName)) return
        val api = conns[recordId]?.app?.api ?: return
        fleetScope.launch {
            val identity = runCatching { api.getHost() }.getOrNull() ?: return@launch
            val hostId = identity.hostId.takeIf { it.isNotBlank() } ?: return@launch
            val displayName = if (isLocalDirectUrl(current.directUrl)) {
                localHostDisplayName()
            } else {
                identity.name
            }
            val merged = synchronized(lock) {
                val before = store.list().map { it.recordId }.toSet()
                store.backfillHostIdentity(recordId, hostId, displayName)
                (before - store.list().map { it.recordId }.toSet()).also { removed ->
                    removed.forEach {
                        sessionsByHost.remove(it); messagesByHost.remove(it)
                        agentByHost.remove(it); lastReadByHost.remove(it); archivedByHost.remove(it); onlineHosts.remove(it)
                    }
                }
            }
            // A duplicate collapsed into this record → reconcile connections (close the removed one).
            if (merged.isNotEmpty()) onHostsChanged()
            synchronized(lock) { recomputeAll() }
        }
    }

    private fun isLocalDirectUrl(url: String?): Boolean {
        val normalized = url?.lowercase() ?: return false
        return normalized.startsWith("http://127.0.0.1:") ||
            normalized.startsWith("http://localhost:")
    }

    private fun recomputeAll() {
        recomputeSessions(); recomputeMessages(); recomputeAgent(); recomputeLastRead(); recomputeArchived(); rebuildHostViews()
    }

    private fun recomputeSessions() {
        val merged = mergeSessions(store.list().map { it.recordId }, sessionsByHost)
        _sessions.value = merged.sessions
        _sessionHost.value = merged.sessionHost
    }

    private fun recomputeMessages() {
        // Ids are globally unique across hosts → a straight union in store order.
        val out = LinkedHashMap<String, List<LogEntry>>()
        store.list().forEach { h -> messagesByHost[h.recordId]?.let { out.putAll(it) } }
        messagesByHost.forEach { (rid, m) -> if (store.list().none { it.recordId == rid }) out.putAll(m) }
        _messages.value = out
    }

    private fun recomputeAgent() {
        val out = LinkedHashMap<String, AgentStatus>()
        store.list().forEach { h -> agentByHost[h.recordId]?.let { out.putAll(it) } }
        agentByHost.forEach { (rid, a) -> if (store.list().none { it.recordId == rid }) out.putAll(a) }
        _agentState.value = out
    }

    private fun recomputeLastRead() {
        val out = LinkedHashMap<String, String>()
        store.list().forEach { h -> lastReadByHost[h.recordId]?.let { out.putAll(it) } }
        lastReadByHost.forEach { (rid, m) -> if (store.list().none { it.recordId == rid }) out.putAll(m) }
        _lastRead.value = out
    }

    private fun onHostArchived(recordId: String, archived: List<ArchivedDto>) = synchronized(lock) {
        archivedByHost[recordId] = archived
        recomputeArchived()
    }

    private fun recomputeArchived() {
        val out = ArrayList<ArchivedDto>()
        store.list().forEach { h -> archivedByHost[h.recordId]?.let { out.addAll(it) } }
        archivedByHost.forEach { (rid, list) -> if (store.list().none { it.recordId == rid }) out.addAll(list) }
        _archivedSessions.value = out
    }

    private fun rebuildHostViews() {
        _hostViews.value = hostViewsFrom(store.list(), onlineHosts)
    }

    // ── Routing ──────────────────────────────────────────────────────────────────────

    /** The [HostStore] owning [sessionId] (per-session routing), or the active host as a
     *  fallback when the owner is unknown (a state frame racing ahead of its session_added). */
    fun appFor(sessionId: String): HostStore? =
        _sessionHost.value[sessionId]?.let { conns[it]?.app } ?: activeApp()

    /** The [HostStore] for a host recordId, or null if it isn't connected/known. */
    fun appForRecord(recordId: String?): HostStore? = recordId?.let { conns[it]?.app }

    /** The active host's app (host-global ops), falling back to the first connected host. */
    fun activeApp(): HostStore? = conns[_activeHost.value]?.app ?: conns.values.firstOrNull()?.app

    /** Route host-global operations to a chosen host — the launcher's host picker + opening a chat. */
    fun setActiveHost(recordId: String) { _activeHost.value = recordId }

    /**
     * Report the foreground chat (`null` = the list) + visibility to the OWNING host, making that
     * host active, and clear the previously-viewed host so its broker stops treating a since-closed
     * chat as foreground (mirrors Android's clientFor(session) viewing routing).
     */
    fun updateViewing(sessionId: String?, visible: Boolean) {
        val owner = sessionId?.let { _sessionHost.value[it] }
        if (owner != null) _activeHost.value = owner
        val prev = lastViewingHost
        if (prev != null && prev != owner) conns[prev]?.app?.updateViewing(null, visible)
        (owner?.let { conns[it]?.app } ?: activeApp())?.updateViewing(sessionId, visible)
        lastViewingHost = owner
    }

    /**
     * Report the workspace viewing snapshot (or null = not looking at a workspace).
     * A workspace switch publishes a non-visible frame to the host that owned
     * the previous snapshot's ids first so that host's tracker cannot linger
     * after switching to a workspace on another host. Visible chat ids are
     * marked read optimistically.
     */
    fun updateViewing(snapshot: WorkspaceViewingSnapshot?) {
        val previous = viewingSnapshot
        val clearOn = previousHostClearSessionId(previous, snapshot)
        if (clearOn != null || (previous != null && snapshot != null && previous.workspaceId != snapshot.workspaceId)) {
            val target = clearOn?.let { appFor(it) } ?: activeApp()
            target?.updateViewing(null, false)
        }
        viewingSnapshot = snapshot
        val ids = snapshot?.takeIf { it.appForeground }?.visibleChatSessionIds.orEmpty()
        ids.firstOrNull()?.let { sid ->
            _sessionHost.value[sid]?.let(::setActiveHost)
        }
        if (snapshot?.appForeground == true) {
            for (id in ids) appFor(id)?.markRead(id)
        }
        val owner = ids.firstOrNull()?.let { _sessionHost.value[it] }
        val dest = owner?.let { conns[it]?.app } ?: activeApp()
        dest?.let { app ->
            for (frame in framesForSnapshot(snapshot)) {
                applyViewingFrame(app, frame)
            }
        }
        lastViewingHost = owner
    }

    private fun applyViewingFrame(app: HostStore, frame: ClientFrame.Viewing) {
        val sessions = frame.sessions
        when {
            sessions != null && sessions.isNotEmpty() -> app.updateViewingSessions(sessions, frame.visible)
            else -> app.updateViewing(frame.session, frame.visible)
        }
    }

    fun saveHostFilter(recordId: String?) {
        fleetScope.launch { deps.settings.putString(SettingsKeys.HOST_FILTER, recordId) }
    }

    fun refreshArchived() {
        conns.values.forEach { it.app.refreshArchived() }
    }

    /** Rename a paired host (the merged-list chip/badge label). */
    fun renameHost(recordId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        store.rename(recordId, trimmed)
        rebuildHostViews()
    }

    /** True when a typed add-host URL is plain HTTP to a non-loopback host. */
    fun urlNeedsInsecureOptIn(rawUrl: String): Boolean {
        val url = normalizeHostUrl(rawUrl) ?: return false
        return !TransportPolicy.isPlainHttpAllowedWithoutOptIn(url)
    }

    // ── Add host (spec §3.4 / §5) ──────────────────────────────────────────────────

    /**
     * Claim a host from a scanned/pasted [PairingPayload]: POST /pair/claim, ABORT if the returned
     * `host.hostId` differs from the payload's (identity-mismatch guard), then persist via the store
     * and open its connection. Mirrors Android AppViewModel.addHost.
     */
    suspend fun addHost(payload: PairingPayload, deviceName: String): AddHostResult {
        val url = payload.relayUrl ?: payload.directUrl
            ?: return AddHostResult.Error("That pairing link has no host URL.")
        val res = claim(url, payload.claimSecret, deviceName)
            ?: return AddHostResult.Error("The host rejected the pairing — the claim is expired or already used.")
        if (res.deviceToken.isBlank()) return AddHostResult.Error("The host didn't return a device token.")
        // Anti-MITM (spec §3.4): require an EXACT, non-empty hostId match — a missing/blank returned
        // id is a failure, not a pass (otherwise a MITM broker omitting `host` would be accepted).
        val returned = res.host?.hostId
        if (returned.isNullOrBlank() || returned != payload.hostId) {
            return AddHostResult.Error(
                "Host identity mismatch (link ${payload.hostId}, got ${returned?.ifBlank { null } ?: "none"}) — aborting.",
            )
        }
        val host = synchronized(lock) {
            store.addOrUpdate(
                displayName = payload.name.ifBlank { res.host?.name?.ifBlank { null } ?: "New host" },
                token = res.deviceToken,
                relayUrl = payload.relayUrl,
                directUrl = payload.directUrl,
                hostId = payload.hostId,
                platform = res.host?.platform,
                version = res.host?.version,
            )
        }
        onHostsChanged()
        return AddHostResult.Added(host)
    }

    /**
     * Typed-URL add-host (Tailscale/VPN/reverse-proxy — spec §5): GET /host to confirm it is a
     * supermux broker, then try a secret-less claim. A fresh/unclaimed broker mints a device
     * (trust-on-first-connect) and we persist it; an already-set-up host returns [AddHostResult.NeedsClaim]
     * so the user pastes a claim minted from the host's own UI. Mirrors Android AppViewModel.addHostByUrl.
     */
    suspend fun addHostByUrl(
        rawUrl: String,
        deviceName: String,
        /** Transport guard (spec §3.5): pass the UI's explicit opt-in when the client offers one, so
         *  nothing is sent to an unencrypted non-loopback host until the user has ticked it. Defaults
         *  to `true` for clients that have no such checkbox. */
        allowInsecure: Boolean = true,
    ): AddHostResult {
        val url = normalizeHostUrl(rawUrl) ?: return AddHostResult.Error("Enter a valid http(s) or ws(s) URL.")
        if (!allowInsecure && !TransportPolicy.isPlainHttpAllowedWithoutOptIn(url)) {
            return AddHostResult.Error(
                "That's an unencrypted (HTTP) address. Tick \u201cConnect over an unencrypted connection\u201d " +
                    "to add it on a trusted/VPN network.",
            )
        }
        val identity = probeHost(url)
            ?: return AddHostResult.Error("That doesn't look like a supermux host (no /host response).")
        val res = claim(url, "", deviceName)
        if (res == null || res.deviceToken.isBlank()) return AddHostResult.NeedsClaim(identity)
        res.host?.hostId?.takeIf { it.isNotBlank() }?.let { returned ->
            if (identity.hostId.isNotBlank() && returned != identity.hostId) {
                return AddHostResult.Error("Host identity mismatch — aborting.")
            }
        }
        val host = synchronized(lock) {
            store.addOrUpdate(
                displayName = identity.name.ifBlank { "New host" },
                token = res.deviceToken,
                directUrl = url,
                hostId = identity.hostId.ifBlank { null },
                platform = res.host?.platform ?: identity.platform,
                version = res.host?.version ?: identity.version,
            )
        }
        onHostsChanged()
        return AddHostResult.Added(host)
    }

    /** Forget a host: drop its record/token, close its socket, and prune its cached sessions from
     *  the merged list. */
    fun forgetHost(recordId: String) {
        synchronized(lock) {
            store.remove(recordId)
            sessionsByHost.remove(recordId)
            messagesByHost.remove(recordId)
            agentByHost.remove(recordId)
            lastReadByHost.remove(recordId)
            archivedByHost.remove(recordId)
            onlineHosts.remove(recordId)
            snapshots?.remove(recordId)
            if (_activeHost.value == recordId) _activeHost.value = store.list().firstOrNull()?.recordId
        }
        onHostsChanged()
        synchronized(lock) { recomputeAll() }
    }

    private fun onHostsChanged() = synchronized(lock) {
        sync(store.list())
        if (_activeHost.value == null) _activeHost.value = store.list().firstOrNull()?.recordId
        rebuildHostViews()
    }

    private suspend fun claim(url: String, secret: String, deviceName: String): PairClaimResult? {
        claimOverride?.let { return it(url, secret, deviceName) }
        return runCatching { BrokerApi(url, "", http).pairClaim(secret, deviceName) }.getOrNull()
    }

    private suspend fun probeHost(url: String): HostIdentity? {
        hostProbeOverride?.let { return it(url) }
        return runCatching { BrokerApi(url, "", http).getHost() }.getOrNull()
    }

    /** Stop every host's connection + fold, and release the throwaway claim/probe HttpClient. */
    fun close() {
        fleetScope.cancel()
        synchronized(lock) { conns.values.toList().forEach { it.app.close() }; conns.clear(); publishApps() }
        http.close()
    }


    // ── Routing forwarders ───────────────────────────────────────────────────────────
    // Screens call every action on the fleet; the fleet picks the owning host. Per-session ops go
    // to appFor(sessionId), workspace ops to appForWorkspace(workspaceId), host-global ops to
    // activeApp(). One line each — the behaviour lives in HostStore.

    /** The [HostStore] owning [workspaceId] (live or archived), else the active host. */
    fun appForWorkspace(workspaceId: String): HostStore? =
        conns.values.firstOrNull { c ->
            c.app.workspaces.value.any { it.id == workspaceId } ||
                c.app.archivedWorkspaces.value.any { it.id == workspaceId }
        }?.app ?: activeApp()

    private fun requireHost(app: HostStore?): HostStore =
        app ?: error("No host connected")

    private fun sessionInfo(sessionId: String): SessionInfo? =
        _sessions.value.firstOrNull { it.id == sessionId }

    /** Workspace hosting [sessionId] as a chat view on [recordId] (or on the owning host). */
    fun workspaceForSession(recordId: String?, sessionId: String): WorkspaceDto? {
        val app = appForRecord(recordId) ?: appFor(sessionId) ?: return null
        return workspaceForSession(app.workspaces.value, sessionId)
    }

    // Per-session ------------------------------------------------------------------
    fun markRead(sessionId: String) { appFor(sessionId)?.markRead(sessionId) }
    fun interrupt(id: String) { appFor(id)?.interrupt(id) }
    fun rename(id: String, name: String) { appFor(id)?.rename(id, name) }
    fun setMute(id: String, muted: Boolean) { appFor(id)?.setMute(id, muted) }
    fun kill(id: String, onDone: () -> Unit = {}) { appFor(id)?.kill(id, onDone) ?: onDone() }
    fun appendLocalEcho(sessionId: String, text: String) { appFor(sessionId)?.appendLocalEcho(sessionId, text) }
    fun markPendingSend(sessionId: String) { appFor(sessionId)?.markPendingSend(sessionId) }
    fun sendMessage(sessionId: String, text: String, attachments: List<String> = emptyList()) {
        appFor(sessionId)?.sendMessage(sessionId, text, attachments)
    }
    fun sendWith(sessionId: String, text: String, attachments: List<String>) {
        appFor(sessionId)?.sendWith(sessionId, text, attachments)
    }
    fun setPendingFirst(sessionId: String, message: HostStore.PendingFirstMessage) {
        appFor(sessionId)?.setPendingFirst(sessionId, message)
    }
    fun consumePendingFirst(sessionId: String): HostStore.PendingFirstMessage? =
        appFor(sessionId)?.consumePendingFirst(sessionId)
    fun ensureMessagesLoaded(sessionId: String) { appFor(sessionId)?.ensureMessagesLoaded(sessionId) }
    // Settings writes go straight to [HostStoreDeps.settings] — the same place the reads below come
    // from — so a draft typed while every host is offline is still persisted.
    fun saveDraft(sessionId: String, text: String) {
        fleetScope.launch { deps.settings.putString(SettingsKeys.draft(sessionId), text.ifBlank { null }) }
    }
    /** One-shot read of the persisted draft (the composer restores it on open). */
    suspend fun loadDraft(sessionId: String): String = draft(sessionId).first().orEmpty()
    fun draft(sessionId: String): Flow<String?> = deps.settings.string(SettingsKeys.draft(sessionId))
    fun clearFinishJob(id: String) { appFor(id)?.clearFinishJob(id) }
    fun ackFinish(id: String, startedAt: Double) { appFor(id)?.ackFinish(id, startedAt) }
    fun isFinishAcked(id: String, startedAt: Double): Boolean = appFor(id)?.isFinishAcked(id, startedAt) == true
    fun consumeFirstUploads(sessionId: String): List<String> =
        appFor(sessionId)?.consumeFirstUploads(sessionId).orEmpty()

    fun connectTerminal(sessionId: String, terminalId: String = "main"): TerminalClient =
        requireHost(appFor(sessionId)).connectTerminal(sessionId, terminalId)
    fun connectAgentTerminal(sessionId: String): TerminalClient =
        requireHost(appFor(sessionId)).connectAgentTerminal(sessionId)
    suspend fun listTerminals(sessionId: String): List<TerminalSummary> =
        appFor(sessionId)?.listTerminals(sessionId).orEmpty()
    fun closeTerminal(sessionId: String, terminalId: String) { appFor(sessionId)?.closeTerminal(sessionId, terminalId) }

    suspend fun gitFetch(id: String): GitOpResult? = appFor(id)?.gitFetch(id)
    suspend fun gitPull(id: String): GitOpResult? = appFor(id)?.gitPull(id)
    suspend fun gitPush(id: String): GitOpResult? = appFor(id)?.gitPush(id)
    suspend fun gitPublish(id: String): GitOpResult? = appFor(id)?.gitPublish(id)

    // Callback forms for callers outside a coroutine (the chat header's git badge menu).
    fun gitFetch(id: String, onResult: (GitOpResult?) -> Unit) { fleetScope.launch { onResult(gitFetch(id)) } }
    fun gitPull(id: String, onResult: (GitOpResult?) -> Unit) { fleetScope.launch { onResult(gitPull(id)) } }
    fun gitPush(id: String, onResult: (GitOpResult?) -> Unit) { fleetScope.launch { onResult(gitPush(id)) } }
    fun gitPublish(id: String, onResult: (GitOpResult?) -> Unit) { fleetScope.launch { onResult(gitPublish(id)) } }

    /** Kick off a finish job; [onKickoff] reports whether the POST was ACCEPTED (progress lands on
     *  the finish_job frame). Fire-and-forget so the menu can call it outside a coroutine. */
    fun finish(
        id: String,
        action: String? = null,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        prTitle: String? = null,
        prBody: String? = null,
        draft: Boolean? = null,
        prRequiresGreen: Boolean? = null,
        onKickoff: (Boolean) -> Unit = {},
    ) {
        fleetScope.launch {
            val ok = appFor(id)?.finish(
                id, action.orEmpty(), skipVerify, commitFirst, commitMessage, prTitle, prBody, draft, prRequiresGreen,
            ) == true
            onKickoff(ok)
        }
    }

    suspend fun finishReadiness(id: String): FinishReadiness? = appFor(id)?.finishReadiness(id)
    suspend fun verifySuggest(id: String): VerifySuggestResult? = appFor(id)?.verifySuggest(id)
    suspend fun verifySave(id: String, content: String): VerifySaveResult? = appFor(id)?.verifySave(id, content)

    suspend fun sessionModels(id: String): ModelsResponse? = appFor(id)?.sessionModels(id)
    suspend fun sessionReasoning(id: String): ReasoningResponse? = appFor(id)?.sessionReasoning(id)
    /**
     * Switch the session's model / thinking level and report whether the broker ACCEPTED it.
     *
     * Suspending with a real Boolean (rather than the old fire-and-forget launch) so a caller can
     * tell an accepted switch from a rejected one: the shared chat panel optimistically rewrites
     * the shown catalog `current` on true, and must not on false. The broker's session_state
     * broadcast still confirms (or rolls back) the pill either way.
     */
    suspend fun switchModel(id: String, model: String): Boolean =
        appFor(id)?.switchModel(id, model) == true
    suspend fun switchReasoning(id: String, level: String): Boolean =
        appFor(id)?.switchReasoning(id, level) == true

    /** Fire-and-forget resume-from-archive, then re-pull that host's archived list so the row
     *  leaves the Archived screen (the resume produces no session_removed frame). */
    fun resume(id: String) {
        fleetScope.launch {
            val app = appFor(id) ?: activeApp() ?: return@launch
            app.resume(id)
            app.refreshArchived()
        }
    }
    suspend fun archivedLogs(sessionId: String): List<LogEntry> = appFor(sessionId)?.archivedLogs(sessionId).orEmpty()

    suspend fun uploadResumable(
        sessionId: String,
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String? = null,
        onProgress: (Long, Long) -> Unit,
    ): String? = appFor(sessionId)?.uploadResumable(sessionId, source, name, mime, kind, onProgress)

    /** Dictation: a session id routes to its host, null (the launcher) to the active host. */
    suspend fun transcribeAudio(sessionId: String?, bytes: ByteArray, filename: String): String? =
        (sessionId?.let(::appFor) ?: activeApp())?.transcribeAudio(sessionId, bytes, filename)?.text
    suspend fun transcribeDraft(sessionId: String?, draft: String): String? =
        (sessionId?.let(::appFor) ?: activeApp())?.transcribeDraft(sessionId, draft)?.text

    // Session-scoped filesystem / editor / review (id → SessionInfo on the owning host) ------
    suspend fun fsList(sessionId: String, path: String): List<FsEntry> =
        withSession(sessionId) { app, s -> app.fsList(s, path) }.orEmpty()
    suspend fun fsListResult(sessionId: String, path: String): Result<List<FsEntry>> =
        withSession(sessionId) { app, s -> app.fsListResult(s, path) }
            ?: Result.failure(IllegalStateException("host offline"))
    suspend fun fsRead(sessionId: String, path: String): Result<String> =
        withSession(sessionId) { app, s -> app.fsRead(s, path) } ?: Result.failure(IllegalStateException("host offline"))
    suspend fun fsWrite(sessionId: String, path: String, content: String): Boolean =
        withSession(sessionId) { app, s -> app.fsWrite(s, path, content) } == true
    suspend fun fsSearch(sessionId: String, q: String): List<FsSearchResult> =
        withSession(sessionId) { app, s -> app.fsSearch(s, q) }.orEmpty()
    suspend fun fsDiff(sessionId: String, base: String? = null): FsDiffResult? =
        withSession(sessionId) { app, s -> app.fsDiff(s, base) }
    suspend fun fsRefs(sessionId: String): FsRefsResult? = withSession(sessionId) { app, s -> app.fsRefs(s) }
    suspend fun reviewAddComment(sessionId: String, body: AddCommentBody): ReviewComment? =
        withSession(sessionId) { app, s -> app.reviewAddComment(s, body) }
    suspend fun reviewResolve(sessionId: String, commentId: String): Boolean =
        withSession(sessionId) { app, s -> app.reviewResolve(s, commentId) } == true
    suspend fun reviewSubmit(sessionId: String): ReviewSubmitResult? =
        withSession(sessionId) { app, s -> app.reviewSubmit(s) }

    fun editorOpen(sessionId: String) { withSessionSync(sessionId) { app, s -> app.editorOpen(s) } }
    fun editorClose(sessionId: String) { withSessionSync(sessionId) { app, s -> app.editorClose(s) } }
    fun lspStatusQuery(sessionId: String, path: String) {
        withSessionSync(sessionId) { app, s -> app.lspStatusQuery(s, path) }
    }
    fun lspOpen(sessionId: String, serverId: String) { withSessionSync(sessionId) { app, s -> app.lspOpen(s, serverId) } }
    fun lspRpcOut(sessionId: String, serverId: String, message: String) {
        withSessionSync(sessionId) { app, s -> app.lspRpcOut(s, serverId, message) }
    }
    fun lspClose(sessionId: String, serverId: String) { withSessionSync(sessionId) { app, s -> app.lspClose(s, serverId) } }

    private suspend fun <R> withSession(sessionId: String, block: suspend (HostStore, SessionInfo) -> R): R? {
        val app = appFor(sessionId) ?: return null
        val s = sessionInfo(sessionId) ?: return null
        return block(app, s)
    }

    private fun withSessionSync(sessionId: String, block: (HostStore, SessionInfo) -> Unit) {
        val app = appFor(sessionId) ?: return
        val s = sessionInfo(sessionId) ?: return
        block(app, s)
    }

    // Workspace-scoped -------------------------------------------------------------
    fun setActiveView(workspaceId: String, viewId: String) { appForWorkspace(workspaceId)?.setActiveView(workspaceId, viewId) }
    fun addWorkspaceView(
        workspaceId: String,
        kind: String,
        state: JsonObject,
        id: String? = null,
        groupId: String? = null,
    ) { appForWorkspace(workspaceId)?.addWorkspaceView(workspaceId, kind, state, id, groupId) }
    suspend fun addView(workspaceId: String, body: AddViewBody): ViewDto? =
        appForWorkspace(workspaceId)?.addView(workspaceId, body)
    fun closeWorkspaceView(workspaceId: String, viewId: String) {
        appForWorkspace(workspaceId)?.closeWorkspaceView(workspaceId, viewId)
    }
    fun bindChatView(workspaceId: String, viewId: String, sessionId: String) {
        appForWorkspace(workspaceId)?.bindChatView(workspaceId, viewId, sessionId)
    }
    fun archiveWorkspace(workspaceId: String) { appForWorkspace(workspaceId)?.archiveWorkspace(workspaceId) }
    fun restoreWorkspace(workspaceId: String) { appForWorkspace(workspaceId)?.restoreWorkspace(workspaceId) }
    fun moveViewToWorkspace(viewId: String, toWorkspaceId: String) {
        appForWorkspace(toWorkspaceId)?.moveViewToWorkspace(viewId, toWorkspaceId)
    }
    suspend fun patchWorkspaceLayout(workspaceId: String, layout: LayoutNodeDto) {
        appForWorkspace(workspaceId)?.patchWorkspaceLayout(workspaceId, layout)
    }
    fun reorderWorkspaces(orderedIds: List<String>) {
        val app = orderedIds.firstOrNull()?.let(::appForWorkspace) ?: activeApp()
        app?.reorderWorkspaces(orderedIds)
    }
    fun reorderSessions(orderedIds: List<String>) {
        val app = orderedIds.firstOrNull()?.let(::appFor) ?: activeApp()
        app?.reorderSessions(orderedIds)
    }
    fun connectWorkspaceTerminal(workspaceId: String, terminalId: String): TerminalClient =
        requireHost(appForWorkspace(workspaceId)).connectWorkspaceTerminal(workspaceId, terminalId)
    fun closeWorkspaceTerminal(workspaceId: String, terminalId: String) {
        appForWorkspace(workspaceId)?.closeWorkspaceTerminal(workspaceId, terminalId)
    }
    suspend fun listWorkspaceTerminals(workspaceId: String): List<TerminalSummary> =
        appForWorkspace(workspaceId)?.listWorkspaceTerminals(workspaceId).orEmpty()
    suspend fun workspaceFsList(workspaceId: String, path: String): List<FsEntry> =
        appForWorkspace(workspaceId)?.workspaceFsList(workspaceId, path).orEmpty()
    suspend fun workspaceFsListResult(workspaceId: String, path: String): Result<List<FsEntry>> =
        appForWorkspace(workspaceId)?.workspaceFsListResult(workspaceId, path)
            ?: Result.failure(IllegalStateException("host offline"))
    suspend fun workspaceFsRead(workspaceId: String, path: String): Result<String> =
        appForWorkspace(workspaceId)?.workspaceFsRead(workspaceId, path)
            ?: Result.failure(IllegalStateException("host offline"))
    suspend fun workspaceFsWrite(workspaceId: String, path: String, content: String): Boolean =
        appForWorkspace(workspaceId)?.workspaceFsWrite(workspaceId, path, content) == true
    suspend fun workspaceFsSearch(workspaceId: String, q: String): List<FsSearchResult> =
        appForWorkspace(workspaceId)?.workspaceFsSearch(workspaceId, q).orEmpty()
    suspend fun workspaceFsDiff(workspaceId: String, base: String? = null): FsDiffResult? =
        appForWorkspace(workspaceId)?.workspaceFsDiff(workspaceId, base)
    suspend fun workspaceFsRefs(workspaceId: String): FsRefsResult? =
        appForWorkspace(workspaceId)?.workspaceFsRefs(workspaceId)

    // Host-global (active host, or an explicit recordId where the caller knows it) ----------
    fun spawn(workdir: String, name: String?, agent: String, model: String? = null) {
        activeApp()?.spawn(workdir, name, agent, model)
    }
    fun saveName(n: String) { activeApp()?.saveName(n) }
    fun revoke(n: String) { activeApp()?.revoke(n) }
    /**
     * Restart the active host's broker; true when the POST was accepted (cluster E3).
     *
     * Was a fire-and-forget launch into [fleetScope]: a 5xx or an unreachable broker looked exactly
     * like a successful restart, so Android spun for four seconds and claimed success. The shared
     * `SystemSettingsScreen` surfaces the failure off this Boolean.
     */
    suspend fun restartBroker(): Boolean = activeApp()?.restartBroker() == true
    fun bindMessageTts() { activeApp()?.bindMessageTts() }
    fun saveVoiceStt(engine: String?) { fleetScope.launch { activeApp()?.saveVoiceStt(engine) } }
    fun saveVoiceTts(engine: String?) { fleetScope.launch { activeApp()?.saveVoiceTts(engine) } }
    fun saveVoiceCleanup(engine: String?, model: String?) {
        fleetScope.launch { activeApp()?.saveVoiceCleanup(engine, model) }
    }
    // Agents settings mutations (cluster E2). These used to be fire-and-forget `Unit`s that
    // launched into [fleetScope] and swallowed the result, so a failed save looked identical to a
    // successful one in the UI. They now carry [HostStore]'s typed suspend shapes — the shared
    // `AgentSettingsScreen` reports "couldn't save" off the returned Boolean.
    suspend fun agentSendCode(kind: String, code: String) { activeApp()?.sendAgentLoginCode(kind, code) }
    suspend fun agentCancelLogin(kind: String) { activeApp()?.cancelAgentLogin(kind) }
    suspend fun agentSaveSecret(kind: String, value: String): Boolean =
        activeApp()?.saveAgentSecret(kind, value) == true
    suspend fun openCodeSetKey(providerId: String, key: String): Boolean =
        activeApp()?.setOpenCodeKey(providerId, key) == true
    suspend fun openCodeFinishOAuth(providerId: String, method: Int, code: String): Boolean =
        activeApp()?.finishOpenCodeOAuth(providerId, method, code) == true
    /**
     * Disconnect a forge account; true when the broker accepted the delete (cluster E3).
     *
     * Was fire-and-forget, so Android dropped the row optimistically and a rejected delete left the
     * UI lying until the next reload. The shared `GitHostingScreen` keeps the row on false.
     */
    suspend fun forgeRemove(id: String): Boolean = activeApp()?.forgeRemove(id) == true
    /**
     * Proxies for the Settings section; `null` = the call failed (cluster E4).
     *
     * [proxies] stays the fire-and-forget list the session-links menu reads — it cannot tell a
     * transport failure from "no proxies", which is exactly the distinction the shared
     * `ProxiesSettingsScreen` needs.
     */
    suspend fun proxiesForSettings(): List<ProxyDto>? = activeApp()?.proxiesForSettings()

    /**
     * The three proxy mutations, typed as the broker answers them (cluster E4).
     *
     * All three used to be fire-and-forget `launch`es into [fleetScope]: a rejected create, a
     * refused visibility flip and a failed delete each looked exactly like success, so Android's
     * page flipped the switch locally and dropped the row whatever the broker said.
     */
    suspend fun createProxy(sessionName: String, port: Int, domain: String? = null): CreateProxyResponse? =
        activeApp()?.createProxy(sessionName, port, domain)
    suspend fun setProxyPublic(domain: String, isPublic: Boolean): Boolean =
        activeApp()?.setProxyPublic(domain, isPublic) == true
    suspend fun removeProxy(domain: String): Boolean = activeApp()?.removeProxy(domain) == true

    /**
     * Session names on the ACTIVE host — what the expose-port form offers.
     *
     * Mirrors Android's `activeHostSessions`: with a single paired host (or none selected) every
     * session belongs to it, so the whole list stands.
     */
    fun activeHostSessionNames(): List<String> {
        val active = activeHost.value
        val all = sessions.value
        val scoped = if (hostViews.value.size >= 2 && active != null) {
            all.filter { sessionHost.value[it.id] == active }
        } else {
            all
        }
        return scoped.map { it.name }
    }

    fun saveLauncherPrefs(prefs: LauncherPrefs) {
        fleetScope.launch { deps.settings.putString(SettingsKeys.LAUNCHER_PREFS, settingsJson.encodeToString(prefs)) }
    }
    val launcherPrefs: Flow<LauncherPrefs> =
        deps.settings.string(SettingsKeys.LAUNCHER_PREFS).map { raw ->
            raw?.let { runCatching { settingsJson.decodeFromString<LauncherPrefs>(it) }.getOrNull() } ?: LauncherPrefs()
        }
    fun saveLauncherDraft(draft: LauncherDraft) {
        fleetScope.launch {
            val encoded = if (draft == LauncherDraft()) null else settingsJson.encodeToString(draft)
            deps.settings.putString(SettingsKeys.LAUNCHER_DRAFT, encoded)
        }
    }
    val launcherDraft: Flow<LauncherDraft> =
        deps.settings.string(SettingsKeys.LAUNCHER_DRAFT).map { raw ->
            raw?.let { runCatching { settingsJson.decodeFromString<LauncherDraft>(it) }.getOrNull() } ?: LauncherDraft()
        }
    fun clearLauncherDraft() {
        fleetScope.launch { deps.settings.putString(SettingsKeys.LAUNCHER_DRAFT, null) }
    }

    suspend fun appConfig(): AppConfigDto? = activeApp()?.appConfig()
    suspend fun usageRaw(): String? = activeApp()?.usageRaw()
    suspend fun usage(): UsageResponse? = activeApp()?.usage()
    suspend fun redeemCodexReset(): CodexResetResult? = activeApp()?.redeemCodexReset()
    suspend fun fetchGlossary(): List<String> = activeApp()?.fetchGlossary().orEmpty()
    suspend fun updateGlossary(terms: List<String>): List<String>? = activeApp()?.updateGlossary(terms)
    suspend fun curatorSettings(): CuratorSettingsResponse? = activeApp()?.curatorSettings()
    suspend fun saveCurator(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        agent: String = "claude",
        model: String? = null,
        reasoningLevel: String? = null,
    ): CuratorSettingsResponse? = activeApp()?.saveCurator(enabled, hour, minute, agent, model, reasoningLevel)
    suspend fun runCuratorNow(): Boolean = activeApp()?.runCuratorNow() == true
    suspend fun assistantLoad(): Pair<String, String>? = activeApp()?.assistantLoad()
    suspend fun assistantSave(paName: String, soul: String): String? = activeApp()?.assistantSave(paName, soul)
    suspend fun personalAssistants(): List<PADto> = activeApp()?.personalAssistants().orEmpty()
    suspend fun createPersonalAssistant(name: String, agent: String, focus: String?): Boolean =
        activeApp()?.createPersonalAssistant(name, agent, focus) == true
    suspend fun killPersonalAssistant(id: String) { (appFor(id) ?: activeApp())?.killPersonalAssistant(id) }
    /** Paired devices; `null` = the call failed (cluster E4 — Android showed "No devices" for it). */
    suspend fun devices(): List<DeviceDto>? = activeApp()?.devices()
    suspend fun addDevice(name: String): AddDeviceResponse? = activeApp()?.addDevice(name)
    /** Revoke a device; true when the broker accepted the DELETE (cluster E4). */
    suspend fun revokeDevice(name: String): Boolean = activeApp()?.revokeDevice(name) == true
    suspend fun archived(): List<ArchivedDto> = activeApp()?.archived().orEmpty()
    suspend fun updateStatus(): UpdateStatus? = activeApp()?.updateStatus()
    suspend fun checkUpdate(): UpdateStatus? = activeApp()?.checkUpdate()
    suspend fun runUpdate(): RunUpdateResult? = activeApp()?.runUpdate()
    suspend fun fileBytes(fileId: String): ByteArray? = activeApp()?.fileBytes(fileId)

    suspend fun listProjects(): List<String> = activeApp()?.listProjects().orEmpty()
    suspend fun validatePath(path: String): PathValidation? = activeApp()?.validatePath(path)
    suspend fun launcherModels(agent: String): List<ModelInfo> = activeApp()?.launcherModels(agent).orEmpty()
    suspend fun launcherReasoning(agent: String, model: String? = null): ReasoningResponse? =
        activeApp()?.launcherReasoning(agent, model)
    suspend fun launcherRepoInfo(workdir: String, fetch: Boolean = false): RepoInfo? =
        activeApp()?.launcherRepoInfo(workdir, fetch)
    suspend fun launcherCommands(agent: String, workdir: String): List<SlashCommand> =
        activeApp()?.launcherCommands(agent, workdir).orEmpty()

    suspend fun lspLoad(): List<LspServer> = activeApp()?.lspLoad().orEmpty()
    suspend fun lspToggle(id: String, enabled: Boolean): List<LspServer>? = activeApp()?.lspToggle(id, enabled)
    suspend fun lspInstall(id: String): LspInstallResult? = activeApp()?.lspInstall(id)
    suspend fun lspAddCustom(
        id: String,
        label: String,
        command: String,
        extensions: List<String>,
        args: List<String> = emptyList(),
        languageId: String? = null,
        installCmd: String? = null,
    ): LspMutationResult? = activeApp()?.lspAddCustom(id, label, command, extensions, args, languageId, installCmd)
    suspend fun lspRemoveCustom(id: String): LspMutationResult? = activeApp()?.lspRemoveCustom(id)

    /**
     * Agent install/auth statuses off the active host.
     *
     * `null` — no active host, or the request failed — is DISTINCT from an empty list (a broker
     * that genuinely reports no agents), which is what the settings screen needs to tell "couldn't
     * load" from "nothing installed". Callers that only want kinds use `.orEmpty()`.
     */
    suspend fun agentStatuses(): List<AgentInstallStatus>? = activeApp()?.agentStatuses()
    suspend fun startAgentLogin(kind: String): AgentLoginState? = activeApp()?.startAgentLogin(kind)
    suspend fun startAgentInstall(kind: String): AgentInstallJob? = activeApp()?.startAgentInstall(kind)
    suspend fun agentInstallState(kind: String): AgentInstallJob? = activeApp()?.agentInstallState(kind)
    suspend fun agentLoginState(kind: String): AgentLoginState? = activeApp()?.agentLoginState(kind)
    suspend fun openCodeProviders(): List<OpenCodeProvider> = activeApp()?.openCodeProviders().orEmpty()
    suspend fun startOpenCodeOAuth(providerId: String, method: Int): OpenCodeOAuthStart? =
        activeApp()?.startOpenCodeOAuth(providerId, method)

    suspend fun forgesLoad(): ForgeConnectionsResponse? = activeApp()?.forgesLoad()
    suspend fun forgeAdd(kind: String, token: String, host: String?, transport: String): Boolean =
        activeApp()?.forgeAdd(kind, token, host, transport) == true
    suspend fun forgeImport(kind: String, transport: String): Boolean = activeApp()?.forgeImport(kind, transport) == true
    suspend fun listForges(): List<ForgeConnection> = activeApp()?.listForges().orEmpty()
    suspend fun searchForge(query: String): ForgeSearchResponse? = activeApp()?.searchForge(query)
    suspend fun cloneForge(connectionId: String, owner: String, name: String): String? =
        activeApp()?.cloneForge(connectionId, owner, name)
    suspend fun createLocalRepo(name: String): String? = activeApp()?.createLocalRepo(name)
    suspend fun createForge(connectionId: String, name: String): String? = activeApp()?.createForge(connectionId, name)

    suspend fun proxies(): List<ProxyDto> = activeApp()?.proxies().orEmpty()
    suspend fun listDisplays(): List<DisplayStream> = activeApp()?.listDisplays().orEmpty()
    suspend fun startDisplay(
        sessionName: String,
        provider: String? = null,
        device: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): DisplayStream? = activeApp()?.startDisplay(sessionName, provider, device, width, height)
    suspend fun stopDisplay(id: String) { activeApp()?.stopDisplay(id) }
    fun connectVnc(streamId: String): VncClient = requireHost(activeApp()).connectVnc(streamId)
    fun connectScrcpy(streamId: String): ScrcpyClient = requireHost(activeApp()).connectScrcpy(streamId)

    suspend fun createDraftSession(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        name: String? = null,
        reasoningLevel: String? = null,
        attachments: List<DraftAttachmentDto> = emptyList(),
        replaceDraftId: String? = null,
    ): String? = activeApp()?.createDraftSession(workdir, agent, model, text, name, reasoningLevel, attachments, replaceDraftId)

    suspend fun createSessionWithFirstMessage(
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        staged: List<StagedUpload>,
        worktree: Boolean,
        baseBranch: String?,
        replaceDraftId: String? = null,
        workspaceId: String? = null,
        name: String? = null,
        inheritFrom: String? = null,
        firstMessage: String? = null,
        hostRecordId: String? = null,
    ): String? {
        val app = appForRecord(hostRecordId) ?: activeApp() ?: return null
        val newId = app.createSessionWithFirstMessage(
            workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
            replaceDraftId, workspaceId, name, inheritFrom, firstMessage,
        ) ?: return null
        armPendingFirst(app, newId, text, firstMessage)
        return newId
    }

    /**
     * Throwing twin used by the launcher so the broker's own refusal reaches the screen
     * (invalid workdir / spawn 4xx) instead of a generic failure. Same side effects.
     */
    suspend fun createSessionWithFirstMessageOrThrow(
        workdir: String,
        agent: String,
        model: String?,
        reasoningLevel: String?,
        text: String,
        staged: List<StagedUpload>,
        worktree: Boolean,
        baseBranch: String?,
        replaceDraftId: String? = null,
        workspaceId: String? = null,
        name: String? = null,
        inheritFrom: String? = null,
        firstMessage: String? = null,
        hostRecordId: String? = null,
    ): String {
        val app = appForRecord(hostRecordId) ?: activeApp() ?: throw IllegalStateException("No host connected")
        val newId = app.createSessionWithFirstMessageOrThrow(
            workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
            replaceDraftId, workspaceId, name, inheritFrom, firstMessage,
        )
        armPendingFirst(app, newId, text, firstMessage)
        return newId
    }

    /**
     * Hand the composer the first message + its uploaded attachment ids so the chat sends them on
     * open. Skipped when [firstMessage] is set: the BROKER delivers that one after spawn, and a
     * client Send would duplicate it.
     */
    private fun armPendingFirst(app: HostStore, sessionId: String, text: String, firstMessage: String?) {
        if (!firstMessage.isNullOrBlank()) return
        app.setPendingFirst(
            sessionId,
            HostStore.PendingFirstMessage(text, app.consumeFirstUploads(sessionId)),
        )
    }

    /**
     * "Continue in a new conversation" on [recordId]: build the shared spawn request from the source
     * session, create it on that host, then activate the chat view the broker adds for it.
     * Throws (not null) so the dialog can show the reason.
     */
    suspend fun continueInNewConversation(
        recordId: String,
        sourceSessionId: String,
        handoff: ContinueHandoff,
    ): String {
        val app = appForRecord(recordId) ?: activeApp() ?: throw IllegalStateException("No host connected")
        val source = app.sessions.value.firstOrNull { it.id == sourceSessionId }
            ?: _sessions.value.firstOrNull { it.id == sourceSessionId }
            ?: throw IllegalStateException("Source session not found")
        val text = handoff.message.trim()
        if (text.isEmpty() || source.workdir.isBlank()) {
            throw IllegalArgumentException("Need a workdir and a handoff message")
        }
        setActiveHost(recordId)
        val req = continueSpawnRequest(
            sourceWorkdir = source.workdir,
            sourceSessionId = source.id,
            sourceName = source.name,
            sourceAgent = source.agent,
            workspaceId = workspaceForSession(app.workspaces.value, sourceSessionId)?.id,
            handoff = handoff,
        )
        val newId = app.createSessionWithFirstMessage(
            workdir = req.workdir,
            agent = req.agent ?: "claude",
            model = req.model,
            reasoningLevel = req.reasoningLevel,
            text = text,
            staged = emptyList(),
            worktree = false,
            baseBranch = null,
            workspaceId = req.workspaceId,
            name = req.name,
            inheritFrom = req.inheritFrom,
            firstMessage = req.firstMessage,
        ) ?: throw IllegalStateException("Broker refused to start the session")
        activateChatView(recordId, newId)
        return newId
    }

    /** Spec §9.1: spawn a blank chat that joins [workspaceId] in that workspace's workdir. */
    suspend fun newChatInWorkspace(
        recordId: String,
        workspaceId: String,
        workdir: String,
        agent: String? = null,
        model: String? = null,
    ): String {
        setActiveHost(recordId)
        val app = appForRecord(recordId) ?: activeApp() ?: throw IllegalStateException("No host connected")
        if (workdir.isBlank()) throw IllegalArgumentException("Need a working directory")
        val validation = app.validatePath(workdir) ?: throw IllegalArgumentException("Could not validate path")
        val resolvedPath = validation.path
        if (!validation.ok || resolvedPath.isNullOrBlank()) {
            throw IllegalArgumentException(validation.error ?: "Invalid working directory")
        }
        val workspace = app.workspaces.value.firstOrNull { it.id == workspaceId }
            ?: workspaces.value.firstOrNull { it.id == workspaceId }
        val primaryAgent = workspace?.primarySessionId?.let { sid ->
            app.sessions.value.firstOrNull { it.id == sid }?.agent
                ?: _sessions.value.firstOrNull { it.id == sid }?.agent
        }
        val resolvedAgent = agent?.trim()?.takeIf { it.isNotEmpty() } ?: HandoffPrefill.defaultAgent(primaryAgent)
        val sessionId = try {
            app.spawnRequest(newChatHereRequest(workspaceId, resolvedPath, resolvedAgent, model))
        } catch (t: Throwable) {
            remapSpawnFailure(t)
        } ?: throw IllegalStateException("Session created but id not available yet")
        activateChatView(recordId, sessionId)
        return sessionId
    }

    private suspend fun activateChatView(recordId: String, sessionId: String) {
        val target = awaitChatViewForSession(workspaces, sessionId)
            ?: throw IllegalStateException("Chat view didn't appear in the workspace ($recordId)")
        setActiveView(target.workspaceId, target.viewId)
    }

    /**
     * Add a pre-multi-host broker from its legacy `/pair?t=…` QR. That URL already carries a durable
     * device bearer, so sending it to `/pair/claim` would incorrectly reject it as an expired claim.
     * Validate the bearer with endpoints old brokers have, then persist it directly.
     */
    suspend fun addLegacyHost(pair: PairUrl): AddHostResult {
        val api = BrokerApi(pair.baseUrl, pair.token, http)
        val validPairJson = runCatching { api.pairJson(pair.token) }.getOrNull()?.token == pair.token
        val validMe = if (validPairJson) false else runCatching { api.me() }.getOrNull()?.paired == true
        if (!validPairJson && !validMe) {
            return AddHostResult.Error(
                "The host rejected this pairing token. Check that it is reachable and generate a fresh QR if needed.",
            )
        }
        val identity = runCatching { api.getHost() }.getOrNull()
        val displayName = identity?.name?.takeIf { it.isNotBlank() } ?: legacyHostDisplayName(pair.baseUrl)
        val host = synchronized(lock) {
            store.addOrUpdate(
                displayName = displayName,
                token = pair.token,
                relayUrl = pair.baseUrl.takeIf(::isRelayHostUrl),
                directUrl = pair.baseUrl.takeUnless(::isRelayHostUrl),
                hostId = identity?.hostId?.takeIf { it.isNotBlank() },
                platform = identity?.platform,
                version = identity?.version,
            )
        }
        onHostsChanged()
        return AddHostResult.Added(host)
    }

    private fun legacyHostDisplayName(url: String): String =
        runCatching { io.ktor.http.Url(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Host"

    private fun isRelayHostUrl(url: String): Boolean = runCatching {
        val host = io.ktor.http.Url(url).host.lowercase()
        host == "relay.supermux.dev" || host.endsWith(".relay.supermux.dev")
    }.getOrDefault(false)

    companion object {
        /** Transport preference for a paired host: relay first, else the direct/BYO URL (spec §3). */
        fun effectiveUrl(h: PairedHost): String? =
            h.relayUrl?.takeIf { it.isNotBlank() } ?: h.directUrl?.takeIf { it.isNotBlank() }

        /** Normalize a typed host URL (bare host → https://…, trim trailing slash); reject a
         *  non-http(s)/ws(s) scheme. Mirrors Android AppViewModel.normalizeHostUrl. */
        fun normalizeHostUrl(raw: String): String? {
            val t = raw.trim().trimEnd('/')
            if (t.isBlank()) return null
            return when {
                t.startsWith("http://") || t.startsWith("https://") ||
                    t.startsWith("ws://") || t.startsWith("wss://") -> t
                t.contains("://") -> null
                else -> "https://$t"
            }
        }
    }
}
