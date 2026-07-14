package dev.supermux.desktop.host

import dev.supermux.desktop.notify.AgentReplyEvent
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.PairingPayload
import dev.supermux.host.isLegacyHostDisplayName
import dev.supermux.net.BrokerApi
import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch

/**
 * Multi-host orchestrator for the desktop client (spec §5) — the desktop analogue of Android's
 * multi-host `AppViewModel`, built as a thin layer OVER the existing single-host [DesktopAppState]
 * rather than a rewrite of it. One [DesktopAppState] (its own BrokerApi + control WS + reducer) per
 * paired host in the [store]; their per-host `sessions`/`messages`/`agentState`/`agentReplies` flows
 * are folded into merged, recordId-tagged StateFlows the fleet list renders. Session ids are
 * globally unique across hosts, so the merge is a straight fold (see [mergeSessions]).
 *
 * Routing mirrors Android: per-session operations target the OWNING host's [DesktopAppState] (via
 * [appFor]); host-global operations (spawn/settings) target the ACTIVE host ([activeApp]). Existing
 * single-host desktop users are migrated to `PairedHost[0]` before this is built (see
 * [DesktopHostStores.migrateFromLegacyIfNeeded]).
 *
 * @param appFactory builds one host's [DesktopAppState] `(effectiveUrl, token, onConnectionChange)`;
 *   the production default opens a live connection, tests inject `connectOnInit = false` apps.
 * @param claimOverride / hostProbeOverride injectable network seams for the add-host flow (mirror
 *   [dev.supermux.desktop.pairing.PairingState]'s probeOverride) so add-host logic unit-tests
 *   without a live broker; default to a throwaway [BrokerApi] over [http].
 */
class FleetState(
    val store: PairedHostStore,
    scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val http: HttpClient = HttpClient(CIO),
    private val appFactory: (url: String, token: String, onConnectionChange: (Boolean) -> Unit) -> DesktopAppState =
        { url, token, onConn -> DesktopAppState(url, token, scope, onConnectionChange = onConn) },
    private val claimOverride: (suspend (url: String, secret: String, deviceName: String) -> PairClaimResult?)? = null,
    private val hostProbeOverride: (suspend (url: String) -> HostIdentity?)? = null,
) {
    /** Outcome of an add-host attempt (spec §3.4 / §5) — the desktop mirror of Android's AddHostResult. */
    sealed interface AddHostResult {
        data class Added(val host: PairedHost) : AddHostResult
        /** Typed-URL path reached a real supermux host, but it is already set up and needs a claim
         *  minted from its own UI (paste that link instead). */
        data class NeedsClaim(val identity: HostIdentity) : AddHostResult
        data class Error(val message: String) : AddHostResult
    }

    /** One host's live connection: its [DesktopAppState] plus the flow-collector jobs folding it in. */
    private class HostConn(val app: DesktopAppState, val jobs: MutableList<Job> = mutableListOf())

    // Own child scope (supervised, parented to the caller's) so [close] stops the folds without
    // tearing down the caller's scope, and one failed fold never cancels its siblings.
    private val fleetScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val lock = Any()

    // Insertion-ordered so the merged list / active-fallback follow the store's host order.
    private val conns = LinkedHashMap<String, HostConn>()
    private val sessionsByHost = LinkedHashMap<String, List<SessionInfo>>()
    private val messagesByHost = HashMap<String, Map<String, List<LogEntry>>>()
    private val agentByHost = HashMap<String, Map<String, AgentStatus>>()
    private val onlineHosts = HashMap<String, Boolean>()
    private var lastViewingHost: String? = null

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    /** sessionId → owning host recordId (drives per-row badges + per-session routing). */
    private val _sessionHost = MutableStateFlow<Map<String, String>>(emptyMap())
    val sessionHost: StateFlow<Map<String, String>> = _sessionHost.asStateFlow()

    private val _messages = MutableStateFlow<Map<String, List<LogEntry>>>(emptyMap())
    val messages: StateFlow<Map<String, List<LogEntry>>> = _messages.asStateFlow()

    private val _agentState = MutableStateFlow<Map<String, AgentStatus>>(emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> = _agentState.asStateFlow()

    /** The paired fleet as the list/chips render it (identity + reachability + badge slot). */
    private val _hostViews = MutableStateFlow<List<HostView>>(emptyList())
    val hostViews: StateFlow<List<HostView>> = _hostViews.asStateFlow()

    /** recordId of the host that host-global ops (settings/spawn/launcher) target. */
    private val _activeHost = MutableStateFlow<String?>(null)
    val activeHost: StateFlow<String?> = _activeHost.asStateFlow()

    // Agent replies merged across every host, for WorkspaceRoot's NotificationController. Same
    // replay-0 + bounded-DROP_OLDEST shape as DesktopAppState.agentReplies.
    private val _agentReplies = MutableSharedFlow<AgentReplyEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val agentReplies: SharedFlow<AgentReplyEvent> = _agentReplies.asSharedFlow()

    init {
        synchronized(lock) {
            _activeHost.value = store.list().firstOrNull()?.recordId
            sync(store.list())
            recomputeAll()
        }
    }

    // ── Connection lifecycle (mirror of Android HostConnections.sync) ────────────────

    /** Reconcile live connections against [hosts]: open one [DesktopAppState] per newly-added host,
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
        conn.jobs += fleetScope.launch { app.agentReplies.collect { _agentReplies.tryEmit(it) } }
        conns[recordId] = conn
    }

    private fun close(recordId: String) {
        val c = conns.remove(recordId) ?: return
        c.jobs.forEach { it.cancel() }
        c.app.close()
        onlineHosts[recordId] = false
    }

    // ── Per-host fold callbacks ──────────────────────────────────────────────────────

    private fun onHostSessions(recordId: String, sessions: List<SessionInfo>) = synchronized(lock) {
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
                DesktopHostBootstrap.defaultHostName()
            } else {
                identity.name
            }
            val merged = synchronized(lock) {
                val before = store.list().map { it.recordId }.toSet()
                store.backfillHostIdentity(recordId, hostId, displayName)
                (before - store.list().map { it.recordId }.toSet()).also { removed ->
                    removed.forEach {
                        sessionsByHost.remove(it); messagesByHost.remove(it)
                        agentByHost.remove(it); onlineHosts.remove(it)
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
        recomputeSessions(); recomputeMessages(); recomputeAgent(); rebuildHostViews()
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

    private fun rebuildHostViews() {
        _hostViews.value = hostViewsFrom(store.list(), onlineHosts)
    }

    // ── Routing ──────────────────────────────────────────────────────────────────────

    /** The [DesktopAppState] owning [sessionId] (per-session routing), or the active host as a
     *  fallback when the owner is unknown (a state frame racing ahead of its session_added). */
    fun appFor(sessionId: String): DesktopAppState? =
        _sessionHost.value[sessionId]?.let { conns[it]?.app } ?: activeApp()

    /** The [DesktopAppState] for a host recordId, or null if it isn't connected/known. */
    fun appForRecord(recordId: String?): DesktopAppState? = recordId?.let { conns[it]?.app }

    /** The active host's app (host-global ops), falling back to the first connected host. */
    fun activeApp(): DesktopAppState? = conns[_activeHost.value]?.app ?: conns.values.firstOrNull()?.app

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
    suspend fun addHostByUrl(rawUrl: String, deviceName: String): AddHostResult {
        val url = normalizeHostUrl(rawUrl) ?: return AddHostResult.Error("Enter a valid http(s) or ws(s) URL.")
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
            onlineHosts.remove(recordId)
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
        synchronized(lock) { conns.values.toList().forEach { it.app.close() }; conns.clear() }
        http.close()
    }

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
