// Ported from apps/android/.../AppViewModel.kt (M1 scope) — keep reducer semantics in sync.
//
// This is the LOGIC CORE of the desktop client: it wires the shared BrokerClient (WS) and
// BrokerApi (HTTP) and reduces inbound ServerFrames into StateFlows the Compose UI observes.
// The Milestone-1 surface is ported here — sessions / messages / activity / agentState / bgTasks /
// commands + the send/viewing/control paths — plus the M3 editor filesystem surface (fsList/fsRead/
// fsWrite/fsSearch, editorOpen/editorClose, and the fs_changed → [fsChanges] fold) and the M4b finish
// surface (the finish_job + session_git reducer branches + finish/finishReadiness/verifySuggest/
// verifySave/clearFinishJob). Still-out-of-scope frames (LSP, displays) and features (uploads beyond
// Send args, dictation, models/reasoning, drafts, push, notifications) are deliberately no-op'd so the
// reducer stays a faithful subset of AppViewModel's `when (frame)`.
package dev.supermux.state

import dev.supermux.state.AgentReplyEvent
import dev.supermux.state.StagedUpload
import dev.supermux.net.AddCommentBody
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
import dev.supermux.net.ChunkSource
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.CuratorConfig
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
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
import dev.supermux.net.PathValidation
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RemoteRepo
import dev.supermux.net.RepoInfo
import dev.supermux.net.CodexResetResult
import dev.supermux.net.ReviewComment
import dev.supermux.net.ReviewSubmitResult
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.SpawnRequest
import dev.supermux.net.SpawnResponse
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSummary
import dev.supermux.net.TranscribeResponse
import dev.supermux.net.UpdateCommentBody
import dev.supermux.net.UpdateStatus
import dev.supermux.net.Walkthrough
import dev.supermux.net.UsageResponse
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.VncClient
import dev.supermux.host.viewingFramesFor
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.net.AddViewBody
import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.net.MoveViewBody
import dev.supermux.net.PatchViewBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.FinishJobDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SendArgs
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.activeViewPatchBody
import dev.supermux.workspace.chatSessionIds
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * @param connectOnInit when false (tests), the constructor does NOT collect frames, launch the
 *   WS client, or start the viewing heartbeat — so [reduce] and the send helpers can run without
 *   a network. Production uses the default `true`.
 * @param sendFrameOverride injectable outbound-frame seam; defaults to `client.send`. Tests pass
 *   a capturing lambda to assert outbound ClientFrames without a live WebSocket.
 * @param apiOverride injectable HTTP seam mirroring [sendFrameOverride]. NOTE: BrokerApi is a
 *   FINAL concrete class (not open, no interface), so this cannot take a mock subclass — tests
 *   exercising HTTP paths construct a real BrokerApi against a ktor MockEngine HttpClient and
 *   pass it here.
 * @param onConnectionChange optional per-connection reachability signal (multi-host fleet UI —
 *   [FleetStore]): invoked `true` right after the control socket opens
 *   and `false` when it drops, forwarded straight to [BrokerClient]. Default null keeps every
 *   existing single-host caller/test unchanged.
 */
class HostStore(
    val baseUrl: String,
    private val token: String,
    scope: CoroutineScope,
    private val deps: HostStoreDeps,
    connectOnInit: Boolean = true,
    sendFrameOverride: (suspend (ClientFrame) -> Unit)? = null,
    apiOverride: BrokerApi? = null,
    onConnectionChange: ((Boolean) -> Unit)? = null,
    private val walkthroughSeam: WalkthroughSeam<*>? = null,
    private val bindTts: ((
        resolveEngine: suspend () -> String,
        speakRemoteStream: suspend (String, (ByteArray) -> Unit) -> Unit,
    ) -> Unit)? = null,
) {
    /** Own child scope — supervised and parented to the caller's [scope] — so [close] can cancel
     *  the collector / WS run-loop / heartbeat without tearing down the caller's scope, and one
     *  failed child never cancels its siblings. */
    private val stateScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    /**
     * Collectors for [stateIn] projections. Sibling of [stateScope] (both children of the caller's
     * Job) so [close] can cancel WS/heartbeat without freezing projections, and so the caller
     * cancelling still tears projections down as a structured child.
     */
    private val projectionJob = SupervisorJob(scope.coroutineContext[Job])
    private val projectionScope = CoroutineScope(Dispatchers.Unconfined + projectionJob)
    internal val projectionsActive: Boolean get() = projectionJob.isActive

    private val http = deps.httpFactory(null)
    val client = BrokerClient(baseUrl, token, http, onConnectionChange = onConnectionChange)
    val api = apiOverride ?: BrokerApi(baseUrl, token, http)

    // CIO's default per-request timeout is 15s — too short for the mic-dictation POST (M5-1): the
    // broker's whisper /transcribe is a real ASR job that routinely runs 20-30s (longer on a
    // GPU-less host / cold model load), so a 15s ceiling would time the dictation out before any
    // text comes back. Rather than raise the timeout for EVERY desktop HTTP call — which would let
    // a genuinely hung fs/git/usage/session endpoint block the UI for 120s instead of failing fast
    // at 15s — [transcribeAudio] gets its own [HttpClient]/[BrokerApi] pair with a longer, still-
    // bounded 120s timeout; every other call keeps [api]'s snappy CIO default. In tests, [apiOverride]
    // (a MockEngine-backed BrokerApi) backs BOTH [api] and [apiDictate] so a single fake covers the
    // whole surface, same as before this split.
    private val httpDictate = deps.httpFactory(120_000)
    private val apiDictate = apiOverride ?: BrokerApi(baseUrl, token, httpDictate)
    private val sendFrame: suspend (ClientFrame) -> Unit = sendFrameOverride ?: { client.send(it) }

    // ── Viewing presence (mirrors iOS BrokerSession / web useViewing) ──────────────
    /** Session ids of chats currently on screen (one per visible group), or empty. */
    private var viewingSessionIds: List<String> = emptyList()
    /** True when the window is foregrounded on the session list (no chat selected). */
    private var viewingOnList: Boolean = false
    private var viewingVisible: Boolean = false
    private var lastSentViewing: List<ClientFrame.Viewing>? = null
    private var viewingHeartbeat: Job? = null

    // ── StateFlows (M1 read surface) ───────────────────────────────────────────────
    /** The public flows lag [_state] by one dispatch; read [state.value] for a synchronous view. */
    private val _state = MutableStateFlow(HostState())
    val state: StateFlow<HostState> = _state.asStateFlow()
    val sessions: StateFlow<List<SessionInfo>> =
        _state.map { it.sessions }.stateIn(projectionScope, SharingStarted.Eagerly, emptyList())
    val workspaces: StateFlow<List<WorkspaceDto>> =
        _state.map { it.workspaces }.stateIn(projectionScope, SharingStarted.Eagerly, emptyList())
    val archivedWorkspaces: StateFlow<List<WorkspaceDto>> =
        _state.map { it.archivedWorkspaces }.stateIn(projectionScope, SharingStarted.Eagerly, emptyList())
    val messages: StateFlow<Map<String, List<LogEntry>>> =
        _state.map { it.messages }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    val activity: StateFlow<Map<String, List<ActivityEvent>>> =
        _state.map { it.activity }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    val agentState: StateFlow<Map<String, AgentStatus>> =
        _state.map { it.agentState }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    val agentErrors: StateFlow<Map<String, ServerFrame.AgentError>> =
        _state.map { it.agentErrors }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    val bgTasks: StateFlow<Map<String, List<ServerFrame.BgTask>>> =
        _state.map { it.bgTasks }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    private val _pendingSend = MutableStateFlow<Set<String>>(emptySet())
    val pendingSend: StateFlow<Set<String>> = _pendingSend
    val commands: StateFlow<Map<String, List<SlashCommand>>> =
        _state.map { it.commands }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    /** Per-session resolution state of the slash-command set (true = fully resolved). */
    val commandsResolved: StateFlow<Map<String, Boolean>> =
        _state.map { it.commandsResolved }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())
    /** Per-session walkthrough holders. Created and updated by [walkthroughSeam] on first frame. */
    private val walkthroughs = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> walkthroughState(sessionId: String): T {
        val seam = requireNotNull(walkthroughSeam) { "HostStore was built without a WalkthroughSeam" } as WalkthroughSeam<Any>
        return walkthroughs.getOrPut(sessionId) { seam.create(sessionId) } as T
    }
    /**
     * Session id → ISO last_read_at. Seeded from snapshot `reads`, updated by `session_read`
     * frames and optimistic [markRead] when the user opens a chat (web/Android parity).
     */
    val lastRead: StateFlow<Map<String, String>> =
        _state.map { it.lastRead }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())

    // ── Finish flow (M4b) ──────────────────────────────────────────────────────────
    // The last/in-flight finish job per session, keyed by session id (Android AppViewModel
    // parity). Seeded from each SessionInfo.finish_job in the Snapshot and kept current by the
    // FinishJobFrame reducer; the FinishDialog drives its 3-state machine (menu/running/outcome)
    // off this flow. clearFinishJob drops an entry client-side once the user dismisses the outcome.
    val finishJobs: StateFlow<Map<String, FinishJobDto>> =
        _state.map { it.finishJobs }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())

    // Which finish result the user has "seen" (acked), per session id → the job's startedAt. The
    // header's unacked dot derives from this vs the live finishJobs entry. It lives HERE (not as
    // SessionDetail Compose state) because desktop reuses ONE SessionDetail across session
    // selections (AppShell renders it without key(session.id)), so a switch A→B→A would reset
    // per-composable ack state and wrongly re-show A's already-seen dot. Android sidesteps this via
    // its NavHost backstack; on desktop the ack must survive the switch — so it's app state.
    private val _ackedFinish = MutableStateFlow<Map<String, Double>>(emptyMap())
    val ackedFinish: StateFlow<Map<String, Double>> = _ackedFinish

    // ── Editor file-watch (M3) ─────────────────────────────────────────────────────
    // The reducer folds inbound fs_changed frames into this app-wide SharedFlow (mirrors Android's
    // AppViewModel.fsChanges). Each EditorPanel collects it and calls its EditorState.markChanged
    // FILTERED to its own session — the stale-on-disk banner is dead without this stream. A replay
    // of 0 (transient signal, not state) + a 64-deep buffer with DROP_OLDEST: the default overflow
    // policy (SUSPEND) makes tryEmit fail on a full buffer, dropping the NEWEST pulse — exactly the
    // one the banner needs. DROP_OLDEST keeps the freshest change flowing instead (trivially better
    // than Android's default-policy flow — backport candidate).
    private val _fsChanges = MutableSharedFlow<ServerFrame.FsChanged>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val fsChanges: SharedFlow<ServerFrame.FsChanged> = _fsChanges.asSharedFlow()

    // ── Notifications (M5-3) ────────────────────────────────────────────────────────
    // Raw agent-reply pulses (direction="outbound", op="reply" MessageAppend entries only),
    // folded by [reduce] and consumed by AppShell's NotificationController — see
    // NotifyDecision.kt for the PURE viewed/muted decision this flow feeds. Same replay-0 +
    // bounded-buffer shape as [fsChanges]: DROP_OLDEST keeps the freshest reply flowing rather
    // than suspending the reducer on a full buffer — a burst of replies while the collector is
    // briefly busy shouldn't block message delivery, and NotificationDedup coalesces the burst
    // into one toast regardless.
    private val _agentReplies = MutableSharedFlow<AgentReplyEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val agentReplies: SharedFlow<AgentReplyEvent> = _agentReplies.asSharedFlow()

    // ── LSP (M4g-3/M4g-4) ───────────────────────────────────────────────────────────
    // lsp_status keyed "session|path" (mirrors AppViewModel:163-166); lsp_ready/lsp_error/lsp_exit
    // patch matching entries via [markLspState] since they only carry session+serverId. lsp_rpc
    // (inbound) is a raw relay SharedFlow — LspBridge (Task 2) filters it by session+serverId.
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> =
        _state.map { it.lspStatus }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())

    private val _lspRpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 256)
    val lspRpc: SharedFlow<ServerFrame.LspRpcIn> = _lspRpc.asSharedFlow()

    // Live install progress/result per LSP serverId (M4g-4). Drives LspSettingsScreen's streamed
    // install log + terminal result row — mirrors AppViewModel:173-180.
    val lspInstallLog: StateFlow<Map<String, List<String>>> =
        _state.map { it.lspInstallLog }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())

    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> =
        _state.map { it.lspInstallDone }.stateIn(projectionScope, SharingStarted.Eagerly, emptyMap())

    // ── Displays (M5-2) ─────────────────────────────────────────────────────────────
    // Live display streams, kept in sync via display_added/display_removed frames (seeded on
    // demand by [listDisplays]) — mirrors AppViewModel:158-161.
    val displays: StateFlow<List<DisplayStream>> =
        _state.map { it.displays }.stateIn(projectionScope, SharingStarted.Eagerly, emptyList())

    private val _archivedSessions = MutableStateFlow<List<ArchivedDto>>(emptyList())
    val archivedSessions: StateFlow<List<ArchivedDto>> = _archivedSessions.asStateFlow()

    // Last GET /usage (or usage_updated) snapshot. The Usage popover renders this immediately
    // on open and updates in place when a usage_updated frame arrives — never waits on the
    // network to draw. Seeded by [usage]/[refreshUsage]; WS [ServerFrame.UsageUpdated] replaces it.
    private val _usage = MutableStateFlow<UsageResponse?>(null)
    val usageSnapshot: StateFlow<UsageResponse?> = _usage

    /**
     * Has this broker finished first-run setup? `null` until the first snapshot arrives — a host
     * that gates a setup wizard on this must not decide while it is null (it would flash the wizard
     * or the shell before the broker has spoken). Live: every snapshot republishes it, and
     * [setOnboarded] flips it on a successful write.
     *
     * Declared above `init` by this class's convention (state first, wiring after), not to dodge a
     * race: the frame collector `init` starts feeds [reduce], and `BrokerClient`'s frame flow has
     * replay 0, so no snapshot can be delivered into an uninitialised field however this is ordered.
     */
    private val _onboarded = MutableStateFlow<Boolean?>(null)
    val onboarded: StateFlow<Boolean?> = _onboarded.asStateFlow()

    /** Whether the client has a fresh snapshot from the broker (i.e. we're synced/connected). */
    val connected: Boolean get() = client.sync.synced

    init {
        attachMessageTts()
        if (connectOnInit) {
            // Guarded per-frame: one poison frame drops one update, never the whole collector.
            stateScope.launch { client.frames.collect { guarded("reduce") { reduce(it) } } }
            stateScope.launch { client.run() }
            ensureViewingHeartbeat()
        }
    }

    /** Run [block], swallowing (and logging) any failure — used to guard the frame collector so
     *  a throwing reducer branch never cancels the collecting coroutine. Internal for tests. */
    internal fun guarded(op: String, block: () -> Unit) {
        runCatching(block).onFailure { e -> println("[HostStore] $op error: $e") }
    }

    /**
     * Run a suspending broker call, logging any failure and returning null — EXCEPT a real
     * cancellation, which must propagate. A bare `runCatching { suspend call }` (the previous
     * pattern here) swallows the CancellationException a cancelled [stateScope] injects mid-call,
     * letting the coroutine "complete normally" after cancellation — a structured-concurrency trap.
     *
     * SUBTLETY: rethrowing every CancellationException would be wrong too. [BrokerApi.decode]
     * deliberately surfaces HTTP / decode / transport failures AS CancellationException (its SKIE
     * graceful-degradation contract — see its KDoc), so e.g. a 404 from /api/term/list arrives
     * here as a CancellationException that does NOT mean "cancelled". `ensureActive()`
     * discriminates: it rethrows only when THIS coroutine's job was actually cancelled; the
     * BrokerApi sentinel falls through to the log-and-null path (graceful degradation preserved).
     */
    private suspend fun <T> runApi(op: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive()
            println("[HostStore] $op failed: ${c.message}")
            null
        } catch (e: Throwable) {
            println("[HostStore] $op failed: $e")
            null
        }

    @Suppress("UNCHECKED_CAST")
    private fun applyWalkthroughFrame(sessionId: String, frame: ServerFrame) {
        val seam = (walkthroughSeam ?: return) as WalkthroughSeam<Any>
        seam.apply(walkthroughs.getOrPut(sessionId) { seam.create(sessionId) }, frame)
    }

    // ── ServerFrame reducer (ported subset of AppViewModel's when(frame)) ──────────

    /** Fold one inbound frame into HostState plus side effects. Public for reducer tests. */
    fun reduce(frame: ServerFrame) {
        _state.update { reduceHostFrame(it, frame) }
        onFrameEffects(frame)
    }

    private fun onFrameEffects(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Snapshot -> {
                _onboarded.value = frame.onboarded
                lastSentViewing = null
                sendViewingIfChanged()
            }
            is ServerFrame.SessionRemoved -> {
                walkthroughs.remove(frame.id)
                refreshArchived()
            }
            is ServerFrame.MessageAppend -> {
                if (frame.entry.direction == "outbound" && frame.entry.op == "reply") {
                    _agentReplies.tryEmit(AgentReplyEvent(frame.session, frame.entry))
                }
            }
            is ServerFrame.AgentState -> {
                _pendingSend.update { it - frame.session }
            }
            is ServerFrame.FsChanged -> _fsChanges.tryEmit(frame)
            is ServerFrame.WalkthroughUpdated -> applyWalkthroughFrame(frame.sessionId, frame)
            is ServerFrame.ReviewCommentFrame -> applyWalkthroughFrame(frame.sessionId, frame)
            is ServerFrame.LspRpcIn -> _lspRpc.tryEmit(frame)
            is ServerFrame.UsageUpdated -> _usage.value = frame.usage
            else -> {}
        }
    }

        // ── Viewing presence ───────────────────────────────────────────────────────────

    /**
     * Report the foreground chat (`null` = the session list) + whether the app is visible.
     * Single-chat path, still used by the multi-host fleet wrapper. Deduped; starts the
     * keep-alive heartbeat.
     */
    fun updateViewing(session: String?, visible: Boolean) {
        viewingSessionIds = if (visible && session != null) listOf(session) else emptyList()
        viewingOnList = visible && session == null
        viewingVisible = visible
        // Optimistic clear (web useUnread.markRead / Android parity). Server confirms via
        // session_read after the viewing frame advances the read pointer.
        if (visible && session != null) markRead(session)
        sendViewingIfChanged()
        ensureViewingHeartbeat()
    }

    /**
     * Report every chat session currently on screen (one per active group). Spec §11.
     * A workspace can show two chats at once, so this is the shell's normal path.
     * Background tabs are not in [sessionIds]. Empty + [visible]=true means the list;
     * empty + [visible]=false means the window is backgrounded.
     */
    fun updateViewingSessions(sessionIds: List<String>, visible: Boolean) {
        viewingSessionIds = if (visible) sessionIds.distinct() else emptyList()
        viewingOnList = visible && viewingSessionIds.isEmpty()
        viewingVisible = visible
        if (visible) {
            for (id in viewingSessionIds) markRead(id)
        }
        sendViewingIfChanged()
        ensureViewingHeartbeat()
    }

    /** Optimistically advance this session's read pointer to now so the list un-bolds immediately. */
    fun markRead(sessionId: String) {
        val now = deps.nowIso()
        _state.update { cur ->
            val next = dev.supermux.session.advanceLastRead(cur.lastRead[sessionId], now)
            if (cur.lastRead[sessionId] == next) cur else cur.copy(lastRead = cur.lastRead + (sessionId to next))
        }
    }

    private fun currentViewingFrames(): List<ClientFrame.Viewing> = when {
        viewingOnList -> listOf(ClientFrame.Viewing(null, true))
        !viewingVisible -> listOf(ClientFrame.Viewing(null, false))
        else -> viewingFramesFor(viewingSessionIds)
    }

    private fun sendViewingIfChanged() {
        val next = currentViewingFrames()
        if (lastSentViewing == next) return
        lastSentViewing = next
        stateScope.launch {
            // No diffing: each frame carries the COMPLETE current state, because
            // `Viewing(s, true)` replaces the broker's set and the multi-chat form
            // sets it atomically. Sending the whole truth every time is also what
            // makes the 60s heartbeat and the reconnect re-assert correct by
            // construction. Spec §11.
            for (frame in next) {
                runApi("viewing send") { sendFrame(frame) }
            }
        }
    }

    /**
     * Re-send the last viewing frame(s) verbatim, without changing any state.
     *
     * The broker forgets a device's viewing entry 5 minutes after it last heard about it
     * (`src/core/push/viewing-tracker.ts:22`), so a client that sits in one chat has to keep
     * saying so. [ensureViewingHeartbeat] does that from this store's own timer; this is the
     * same assertion exposed to a HOST that wants to drive the cadence itself — the browser,
     * whose tab can be throttled or restored from bfcache with a coroutine timer that never
     * fired. Nothing is sent before a first [updateViewing]/[updateViewingSessions]: there is
     * no presence to keep alive yet. No dedupe, deliberately — re-asserting the frame the
     * broker already has IS the point.
     */
    fun reassertViewing() {
        val frames = lastSentViewing ?: return
        stateScope.launch {
            for (frame in frames) {
                runApi("viewing re-assert") { sendFrame(frame) }
            }
        }
    }

    /** Re-assert the viewing frame every 60s so the broker's 5-min TTL never lapses while the user
     *  reads a long, quiet turn. Only refreshes while visible; [close] cancels it via [stateScope]. */
    private fun ensureViewingHeartbeat() {
        if (viewingHeartbeat?.isActive == true) return
        viewingHeartbeat = stateScope.launch {
            while (isActive) {
                delay(60_000)
                if (viewingVisible) {
                    for (frame in currentViewingFrames()) {
                        if (frame.session == null && !frame.visible) continue
                        runApi("viewing heartbeat") { sendFrame(frame) }
                    }
                }
            }
        }
    }

    // ── Send path ──────────────────────────────────────────────────────────────────

    /** ISO-8601 (UTC) timestamp so an optimistic entry sorts LAST under the broker's lexicographic
     *  `ts` ordering (the broker emits ISO-8601 too). */
    private fun nowIso(): String = deps.nowIso()

    /** Append an optimistic outbound bubble so the user's message shows instantly, before the
     *  broker echoes it back as an inbound message (iOS BrokerSession.send parity). Deduped in the
     *  MessageAppend reducer. Only echoes when there is text (attachments-only stay quiet). */
    fun appendLocalEcho(sessionId: String, text: String) {
        if (text.isEmpty()) return
        val optimistic = LogEntry(
            id = "local-${(_state.value.messages[sessionId]?.size ?: 0)}-${text.hashCode()}",
            ts = nowIso(),
            direction = "inbound",
            text = text,
        )
        _state.update {
            it.copy(messages = it.messages + (sessionId to ((it.messages[sessionId] ?: emptyList()) + optimistic)))
        }
    }

    /** Optimistic "Sending…" marker until the next agent_state clears it. */
    fun markPendingSend(sessionId: String) {
        _pendingSend.update { it + sessionId }
    }

    /** Send a reply over the WS (ClientFrame.Send op="reply"); optionally with attachment ids.
     *  NOTE: on send failure the optimistic local-echo bubble is NOT reconciled/removed — the
     *  message shows as sent even though it wasn't (same gap as Android). M4 follow-up: mark or
     *  retract the bubble on failure. */
    fun sendMessage(sessionId: String, text: String, attachments: List<String> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        appendLocalEcho(sessionId, text.trim())
        stateScope.launch {
            runApi("sendMessage") {
                sendFrame(ClientFrame.Send(sessionId, args = SendArgs(text, attachments.ifEmpty { null })))
                markPendingSend(sessionId)
            }
        }
    }

    /** Android name for [sendMessage] with attachments. */
    fun sendWith(sessionId: String, text: String, attachments: List<String>) =
        sendMessage(sessionId, text, attachments)

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

    /**
     * Fire-and-forget POST /sessions. Distinct from [createSessionWithFirstMessage] (validate path,
     * upload staged files, resolve spawn id). Android's launcher-picker `spawn` is this simpler
     * path, not a rename of createSessionWithFirstMessage.
     */
    fun spawn(workdir: String, name: String?, agent: String, model: String? = null) {
        stateScope.launch {
            runApi("spawn") {
                api.spawn(
                    SpawnRequest(
                        workdir = workdir.trim(),
                        name = name?.trim()?.ifBlank { null },
                        agent = agent,
                        model = model?.ifBlank { null },
                    ),
                )
            }
        }
    }

    fun saveName(n: String) {
        stateScope.launch { runApi("saveName") { api.putConfig(n) } }
    }

    fun setActiveView(workspaceId: String, viewId: String) {
        _state.update { st ->
            st.copy(
                workspaces = st.workspaces.map { w ->
                    if (w.id == workspaceId) w.copy(activeViewId = viewId) else w
                },
            )
        }
        stateScope.launch {
            runApi("setActiveView") { api.patchWorkspace(workspaceId, activeViewPatchBody(viewId)) }
        }
    }

    fun refreshArchived() {
        stateScope.launch {
            _archivedSessions.value = runApi("archived") { api.archived() } ?: emptyList()
        }
    }

    fun agentSendCode(kind: String, code: String) {
        stateScope.launch { runApi("agentSendCode") { api.sendAgentLoginCode(kind, code) } }
    }

    fun agentCancelLogin(kind: String) {
        stateScope.launch { runApi("agentCancelLogin") { api.cancelAgentLogin(kind) } }
    }

    fun agentSaveSecret(kind: String, value: String) {
        stateScope.launch { saveAgentSecret(kind, value) }
    }

    fun openCodeSetKey(providerId: String, key: String) {
        stateScope.launch { runApi("openCodeSetKey") { api.setOpenCodeKey(providerId, key) } }
    }

    fun openCodeFinishOAuth(providerId: String, method: Int, code: String) {
        stateScope.launch { runApi("openCodeFinishOAuth") { api.finishOpenCodeOAuth(providerId, method, code) } }
    }

    /** Re-bind read-aloud using GET /settings/config + POST /speak. No-op without [bindTts]. */
    fun bindMessageTts() = attachMessageTts()

    private fun attachMessageTts() {
        bindTts?.invoke(
            { runCatching { api.getConfig().voiceTtsEngine }.getOrNull()?.ifBlank { null } ?: "platform" },
            { text, onChunk -> api.speakStream(text = text, engine = "codex", onChunk = onChunk) },
        )
    }

    fun saveDraft(sessionId: String, text: String) {
        stateScope.launch { deps.settings.putString(SettingsKeys.draft(sessionId), text.ifBlank { null }) }
    }

    fun draft(sessionId: String): Flow<String?> = deps.settings.string(SettingsKeys.draft(sessionId))

    // The launcher's prefs/draft used to live here as well as on [FleetStore], over the SAME two
    // settings keys. Cluster F1 gave them ONE owner — `dev.supermux.ui.prefs.UiPrefs` — so the
    // shared launcher reads and writes them without asking which store it was handed.

    // ── Session controls (HTTP via BrokerApi) ───────────────────────────────────────

    /** Soft-stop the running agent (POST /sessions/<id>/interrupt). */
    fun interrupt(id: String) {
        stateScope.launch { runApi("interrupt") { api.interrupt(id) } }
    }

    fun rename(id: String, name: String) {
        stateScope.launch { runApi("rename") { api.rename(id, name) } }
    }

    fun setMute(id: String, muted: Boolean) {
        stateScope.launch { runApi("setMute") { api.setMute(id, muted) } }
    }

    /** [onDone] fires even when the DELETE fails — mirrors Android AppViewModel.kill, which
     *  invokes the callback unconditionally after the guarded call. (On a REAL scope cancellation
     *  the coroutine dies before onDone — acceptable: the whole app state is being torn down.) */
    fun kill(id: String, onDone: () -> Unit = {}) {
        stateScope.launch {
            runApi("kill") { api.kill(id) }
            onDone()
        }
    }

    // ── Git ops + proxies (M4c; mirrors AppViewModel.gitFetch/gitPush/gitPull/gitPublish:566-569
    //    + proxies:871) ───────────────────────────────────────────────────────────────────────
    // Android exposes these as fire-and-forget calls taking an `onResult` callback
    // (`runCatching { api.gitOp(id) }.getOrNull()`); desktop instead exposes plain suspend funs
    // returning the same getOrNull-degraded result through [runApi] — the caller (the header's
    // GitBadgeMenu, M4c Task 2) awaits it directly from a coroutine rather than passing a lambda.
    // All four are bare `POST /sessions/<id>/git/<op>` with no body, decoding to [GitOpResult]
    // (BrokerApi.gitOp) — Android's naming (GitOpResult vs a push/pull-specific type) doesn't
    // apply here; the real BrokerApi has ONE flat result shape for all four ops.

    /** POST /sessions/<id>/git/fetch. Null on any failure. */
    suspend fun gitFetch(id: String): GitOpResult? =
        runApi("gitFetch") { api.gitFetch(id) }

    /** POST /sessions/<id>/git/pull. Null on any failure. */
    suspend fun gitPull(id: String): GitOpResult? =
        runApi("gitPull") { api.gitPull(id) }

    /** POST /sessions/<id>/git/push. Null on any failure. */
    suspend fun gitPush(id: String): GitOpResult? =
        runApi("gitPush") { api.gitPush(id) }

    /** POST /sessions/<id>/git/publish. Null on any failure. */
    suspend fun gitPublish(id: String): GitOpResult? =
        runApi("gitPublish") { api.gitPublish(id) }

    /** GET /proxies — all exposed proxies (session-links menu filters by session). Empty on
     *  any failure. */
    suspend fun proxies(): List<ProxyDto> =
        runApi("proxies") { api.proxies() } ?: emptyList()

    // ── Proxies management (desktop-parity Task 5) ─────────────────────────────────────────────
    // Session-links menu only *reads* proxies; the Settings Proxies section creates/toggles/removes.
    // [proxiesForSettings] returns null on failure so the UI can distinguish Error from Empty
    // (same contract as [devices]).

    /**
     * GET /proxies for the Settings Proxies section.
     * `null` = transport/decode failure; empty list = none configured.
     */
    suspend fun proxiesForSettings(): List<ProxyDto>? =
        runApi("proxiesForSettings") { api.proxies() }

    /** POST /proxies {sessionName, port, domain?} — null on failure. */
    suspend fun createProxy(sessionName: String, port: Int, domain: String? = null): CreateProxyResponse? =
        runApi("createProxy") { api.createProxy(sessionName, port, domain) }

    /** PATCH /proxies/<domain> {isPublic}. False on failure. */
    suspend fun setProxyPublic(domain: String, isPublic: Boolean): Boolean =
        runApi("setProxyPublic") { api.setProxyPublic(domain, isPublic); true } ?: false

    /** DELETE /proxies/<domain>. False on failure. */
    suspend fun removeProxy(domain: String): Boolean =
        runApi("removeProxy") { api.removeProxy(domain); true } ?: false

    // ── Assistant identity + curator (desktop-parity Task 5) ───────────────────────────────────
    // Backs the Assistant section: PA name + soul.md + nightly curator. Mirrors AppViewModel
    // assistantLoad/assistantSave/curatorSettings/saveCurator/runCuratorNow.

    /**
     * Load PA name + soul.md together.
     * `null` = config **or** soul load failed (do not enter Ready — empty soul is only valid
     * when the GET succeeded). Pair of empty strings is a legitimate empty assistant.
     */
    suspend fun assistantLoad(): Pair<String, String>? {
        val cfg = runApi("assistantLoadConfig") { api.getConfig() } ?: return null
        val soul = runApi("assistantLoadSoul") { api.getSoul() } ?: return null
        return cfg.paName to soul
    }

    /**
     * PUT /settings/config {paName} then PUT /settings/soul.
     * Returns null on full success; a human-readable error when either write fails
     * (config failure is reported before soul is attempted).
     */
    suspend fun assistantSave(paName: String, soul: String): String? {
        val configOk = runApi("assistantSaveConfig") { api.saveConfig(paName = paName); true } ?: false
        if (!configOk) return "Couldn't save PA name — check connection and try again"
        val soulOk = runApi("assistantSaveSoul") { api.putSoul(soul) } ?: false
        if (!soulOk) return "Couldn't save soul.md — check connection and try again"
        return null
    }

    /** GET /settings/curator. Null on failure. */
    suspend fun curatorSettings(): CuratorSettingsResponse? =
        runApi("curatorSettings") { api.getCuratorSettings() }

    /** PUT /settings/curator. Null on failure. */
    suspend fun saveCurator(
        enabled: Boolean,
        hour: Int,
        minute: Int,
        agent: String = "claude",
        model: String? = null,
        reasoningLevel: String? = null,
    ): CuratorSettingsResponse? =
        runApi("saveCurator") {
            api.saveCuratorSettings(
                CuratorConfig(
                    enabled = enabled,
                    hour = hour,
                    minute = minute,
                    agent = agent,
                    model = model,
                    reasoningLevel = reasoningLevel,
                ),
            )
        }

    /** POST /settings/curator/run-now. False on failure. */
    suspend fun runCuratorNow(): Boolean =
        runApi("runCuratorNow") { api.runCuratorNow(); true } ?: false

    // ── Voice settings (desktop-parity Task 5) ─────────────────────────────────────────────────
    // STT / TTS / cleanup engines + glossary. MessageTts already reads voiceTtsEngine via
    // getConfig() (init above); Dictation posts multipart audio. Saving config here integrates —
    // do not reimplement speak/transcribe in the settings UI.

    /** GET /settings/config. Null on failure. */
    suspend fun appConfig(): AppConfigDto? =
        runApi("appConfig") { api.getConfig() }

    /** PUT /settings/config {"onboarded": v} (partial patch). False on failure, flow unchanged. */
    suspend fun setOnboarded(value: Boolean): Boolean =
        runApi("setOnboarded") {
            api.saveConfig(onboarded = value)
            _onboarded.value = value
            true
        } ?: false

    /** Persist STT engine (null = broker default). False on failure. */
    suspend fun saveVoiceStt(engine: String?): Boolean =
        runApi("saveVoiceStt") { api.saveConfig(voiceSttEngine = engine); true } ?: false

    /** Persist read-aloud engine (platform | codex). False on failure. */
    suspend fun saveVoiceTts(engine: String?): Boolean =
        runApi("saveVoiceTts") { api.saveConfig(voiceTtsEngine = engine); true } ?: false

    /** Persist cleanup engine and/or model. False on failure. */
    suspend fun saveVoiceCleanup(engine: String?, model: String?): Boolean =
        runApi("saveVoiceCleanup") {
            api.saveConfig(voiceCleanupEngine = engine, voiceCleanupModel = model)
            true
        } ?: false

    /**
     * GET /config/voice-glossary.
     * `null` = transport/decode failure (UI Error + Retry); empty list = no terms yet.
     * Never collapse failure into empty — adding a term after a failed load would overwrite
     * the real glossary.
     */
    suspend fun fetchGlossary(): List<String>? =
        runApi("fetchGlossary") { api.fetchGlossary() }

    /**
     * PUT /config/voice-glossary. Returns the persisted list, or null on failure so the UI can
     * revert (Android VoiceGlossaryPage parity).
     */
    suspend fun updateGlossary(terms: List<String>): List<String>? =
        runApi("updateGlossary") { api.updateGlossary(terms) }

    // ── Finish flow (M4b; mirrors AppViewModel.finish/finishReadiness/verifySuggest/verifySave) ──
    // The FinishDialog drives the whole job lifecycle off the [finishJobs] StateFlow; [finish] only
    // KICKS OFF the async job — its terminal outcome arrives on the WS finish_job frame ([reduce]).
    // The readiness/verify helpers getOrNull-degrade through [runApi] like the launcher wrappers.

    /**
     * Kick off a finish job for the session's branch. `action`: "merge" | "pr" | "keep" | "discard".
     * Fire-and-forget: returns only whether the POST was ACCEPTED (the job's progress/outcome lands
     * on the finish_job frame, not here). Mirrors Android's `runCatching{api.finish}.isSuccess` — a
     * non-2xx makes BrokerApi.decode throw (SKIE-safe), so isSuccess is the kickoff-accepted signal.
     */
    suspend fun finish(
        id: String,
        action: String,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        prTitle: String? = null,
        prBody: String? = null,
        draft: Boolean? = null,
        prRequiresGreen: Boolean? = null,
    ): Boolean =
        runCatching {
            api.finish(id, action, skipVerify, commitFirst, commitMessage, prTitle, prBody, draft, prRequiresGreen)
        }.isSuccess

    /**
     * Fire-and-forget finish kickoff on the STORE's own scope, reporting acceptance through
     * [onKickoff]. The shared Finish flow calls this rather than awaiting [finish] on the panel's
     * `rememberCoroutineScope`, which a view/session switch mid-kickoff would cancel. Mirrors
     * [FleetStore.finish].
     */
    fun kickoffFinish(
        id: String,
        action: String,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        onKickoff: (Boolean) -> Unit = {},
    ) {
        stateScope.launch {
            onKickoff(finish(id, action, skipVerify, commitFirst, commitMessage))
        }
    }

    /** Preflight snapshot for the finish menu (branch sync / diff / conflict / dirty). Null on failure. */
    suspend fun finishReadiness(id: String): FinishReadiness? =
        runApi("finishReadiness") { api.finishReadiness(id) }

    /** Suggest a `.mux/verify.sh` for the no_verify recovery path. Null on failure. */
    suspend fun verifySuggest(id: String): VerifySuggestResult? =
        runApi("verifySuggest") { api.verifySuggest(id) }

    /** Save an edited verify script (the FinishDialog auto-runs merge when `ok`). Null on failure. */
    suspend fun verifySave(id: String, content: String): VerifySaveResult? =
        runApi("verifySave") { api.verifySave(id, content) }

    /** Dismiss a finished/failed job's card (client-side only; mirrors web `finishJob.clear(id)`).
     *  The broker keeps its record — this only drops the local overlay so the dialog closes. Also
     *  drops the ack entry: the card is gone, so its acked-startedAt no longer needs remembering. */
    fun clearFinishJob(id: String) {
        _state.update { it.copy(finishJobs = it.finishJobs - id) }
        _ackedFinish.update { it - id }
    }

    /** Record that the user has SEEN (acked) the finish result for [id] at [startedAt] — bumped on
     *  the FinishButton click. Survives session switches (unlike per-composable state). */
    fun ackFinish(id: String, startedAt: Double) {
        _ackedFinish.update { it + (id to startedAt) }
    }

    /** Whether the finish result for [id] at [startedAt] has been acked (its dot is "seen"). */
    fun isFinishAcked(id: String, startedAt: Double): Boolean = _ackedFinish.value[id] == startedAt

    // ── Scratch / agent terminals (Android AppViewModel:439-444 parity) ──────────────

    /** Factory for a scratch (shell) terminal client bound to one tmux terminal id. Called once
     *  per tab and remembered by the desktop terminal panel; the broker
     *  defaults [terminalId] to "main" when connecting a scratch kind. */
    fun connectTerminal(sessionId: String, terminalId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId, terminalId = terminalId)

    /** Factory for the raw agent-PTY terminal (kind="agent") behind the Native tab; the scratch
     *  tabs use [connectTerminal]. */
    fun connectAgentTerminal(sessionId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId, kind = "agent")

    /** Factory for a workspace-scoped scratch terminal (`/ws/term?workspace=`). Spec §7.3. */
    fun connectWorkspaceTerminal(workspaceId: String, terminalId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId = "", terminalId = terminalId, workspaceId = workspaceId)

    /** GET /api/term/list — the session's persisted scratch terminals (source of truth = tmux),
     *  used to rebuild the tab strip on open. Never throws (except real cancellation): any
     *  failure logs and yields []. */
    suspend fun listTerminals(sessionId: String): List<TerminalSummary> =
        runApi("listTerminals") { api.listTerminals(sessionId) } ?: emptyList()

    /** POST /api/term/close — destroy one scratch terminal (its tmux session + viewers).
     *  Fire-and-forget; the tab is removed locally regardless of the outcome (best-effort, web
     *  parity: the tmux session may already be gone). */
    fun closeTerminal(sessionId: String, terminalId: String) {
        stateScope.launch { runApi("closeTerminal") { api.closeTerminal(sessionId, terminalId) } }
    }

    /** GET /api/term/list?workspace= — workspace-scoped scratch terminals. */
    suspend fun listWorkspaceTerminals(workspaceId: String): List<TerminalSummary> =
        runApi("listWorkspaceTerminals") { api.listWorkspaceTerminals(workspaceId) } ?: emptyList()

    /** POST /api/term/close for a workspace terminal. */
    fun closeWorkspaceTerminal(workspaceId: String, terminalId: String) {
        stateScope.launch {
            runApi("closeWorkspaceTerminal") { api.closeWorkspaceTerminal(workspaceId, terminalId) }
        }
    }

    // ── Displays (M5-2; mirrors AppViewModel.listDisplays/connectVnc/startDisplay/stopDisplay:
    //    446-470) ─────────────────────────────────────────────────────────────────────────────

    /** GET /displays. Also seeds [displays] (the StateFlow then stays live via
     *  display_added/display_removed frames). On failure, returns (and leaves) the CURRENT flow
     *  value rather than clobbering it with an empty list — a transient GET failure must not blank
     *  out streams the WS frames already told us are running. */
    suspend fun listDisplays(): List<DisplayStream> {
        val list = runApi("listDisplays") { api.listDisplays() } ?: return _state.value.displays
        _state.update { it.copy(displays = list) }
        return list
    }

    /** Factory for this session's VNC transport client; called once per connected stream and
     *  remembered by the Display panel (mirrors [connectAgentTerminal]). */
    fun connectVnc(streamId: String): VncClient = VncClient(baseUrl, token, http, streamId)
    fun connectScrcpy(streamId: String): ScrcpyClient = ScrcpyClient(baseUrl, token, http, streamId)

    /** POST /displays → the started stream (the display_added frame also folds it into [displays]
     *  for every connected client, including this one). Null on any failure. [provider] defaults
     *  to null so the broker picks the right transport for its own host OS (linux-xvfb /
     *  macos-screen) — desktop has no provider picker (see this plan's Goal, scoping decision 3). */
    suspend fun startDisplay(
        sessionName: String,
        provider: String? = null,
        device: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): DisplayStream? =
        runApi("startDisplay") { api.startDisplay(sessionName, provider, device, width, height) }

    /** DELETE /displays/<id> — stop a running display stream (tears down the broker-host Xvfb/VNC
     *  process or macOS Screen Sharing session). Fire-and-forget; the display_removed frame updates
     *  [displays] for every connected client once the broker confirms the teardown. */
    suspend fun stopDisplay(id: String) {
        runApi("stopDisplay") { api.stopDisplay(id) }
    }

    // ── Editor filesystem + lifecycle (M3; mirrors AppViewModel.fsList/fsRead/fsWrite/fsSearch
    //    + editorOpen/editorClose) ─────────────────────────────────────────────────────
    // The EditorPanel binds these to path-only lambdas capturing the session, exactly as Android's
    // ChatScreen binds the AppViewModel wrappers. All broker calls run through [runApi] EXCEPT
    // [fsRead] (see its note — it must preserve the FsException message for the editor's error UI).

    /** GET /sessions/<id>/fs → directory listing (workdir-relative). Empty on any failure — use
     *  [fsListResult] where a failed listing must be TOLD APART from an empty directory. */
    suspend fun fsList(session: SessionInfo, path: String): List<FsEntry> =
        fsListResult(session, path).getOrElse { emptyList() }

    /**
     * GET /sessions/<id>/fs as a Result — same shape and rationale as [fsRead]: NOT run through
     * [runApi], because the failure message has to reach the file tree's error row (a swallowed
     * failure renders as an empty directory, which is what made that row unreachable until cluster
     * C1). runApi's cancellation discipline is preserved inline.
     */
    suspend fun fsListResult(session: SessionInfo, path: String): Result<List<FsEntry>> =
        try {
            Result.success(api.fsList(session.id, path))
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive() // real cancel → propagate
            Result.failure(c)
        } catch (e: Throwable) {
            println("[HostStore] fsList failed: $e") // runApi's log, kept now that runApi is bypassed
            Result.failure(e)
        }

    /** GET /workspaces/<id>/fs → directory listing (workspace workdir). Empty on any failure — use
     *  [workspaceFsListResult] where a failed listing must be told apart from an empty directory. */
    suspend fun workspaceFsList(workspaceId: String, path: String): List<FsEntry> =
        workspaceFsListResult(workspaceId, path).getOrElse { emptyList() }

    /** GET /workspaces/<id>/fs as a Result — the workspace twin of [fsListResult]. */
    suspend fun workspaceFsListResult(workspaceId: String, path: String): Result<List<FsEntry>> =
        try {
            Result.success(api.workspaceFsList(workspaceId, path))
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(c)
        } catch (e: Throwable) {
            println("[HostStore] workspaceFsList failed: $e")
            Result.failure(e)
        }

    /**
     * GET /workspaces/<id>/fs/read → file text. Same Result shape as [fsRead] (preserves
     * FsException messages for the editor load-error UI).
     */
    suspend fun workspaceFsRead(workspaceId: String, path: String): Result<String> =
        try {
            Result.success(api.workspaceFsRead(workspaceId, path))
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(c)
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** PUT /workspaces/<id>/fs/write → true on success. */
    suspend fun workspaceFsWrite(workspaceId: String, path: String, content: String): Boolean =
        runApi("workspaceFsWrite") { api.workspaceFsWrite(workspaceId, path, content) } ?: false

    /** GET /workspaces/<id>/fs/search → filename matches. Empty on any failure. */
    suspend fun workspaceFsSearch(workspaceId: String, q: String): List<FsSearchResult> =
        runApi("workspaceFsSearch") { api.workspaceFsSearch(workspaceId, q) } ?: emptyList()

    /** GET /workspaces/<id>/fs/diff. Null on any failure. */
    suspend fun workspaceFsDiff(workspaceId: String, base: String? = null): FsDiffResult? =
        runApi("workspaceFsDiff") { api.workspaceFsDiff(workspaceId, base) }

    /** GET /workspaces/<id>/fs/refs. Null on any failure. */
    suspend fun workspaceFsRefs(workspaceId: String): FsRefsResult? =
        runApi("workspaceFsRefs") { api.workspaceFsRefs(workspaceId) }

    /**
     * GET /sessions/<id>/fs/read → file text as a Result (mirrors AppViewModel.fsRead). Deliberately
     * NOT run through [runApi]: the FsException message (413 too large / 415 binary) must reach the
     * editor's load-error UI, and runApi log-and-nulls it. runApi's cancellation discipline is
     * preserved inline — a REAL scope cancel rethrows (structured concurrency), any other failure is
     * captured into Result.failure so the caller can surface `err.message`.
     */
    suspend fun fsRead(session: SessionInfo, path: String): Result<String> =
        try {
            Result.success(api.fsRead(session.id, path))
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive() // real cancel → propagate
            Result.failure(c)
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /** PUT /sessions/<id>/fs/write → true on success, false on any failure. */
    suspend fun fsWrite(session: SessionInfo, path: String, content: String): Boolean =
        runApi("fsWrite") { api.fsWrite(session.id, path, content) } ?: false

    /** GET /sessions/<id>/fs/search → filename matches. Empty on any failure. */
    suspend fun fsSearch(session: SessionInfo, q: String): List<FsSearchResult> =
        runApi("fsSearch") { api.fsSearch(session.id, q) } ?: emptyList()

    // ── Diff + inline code-review (M4g-2; mirrors AppViewModel.fsDiff/reviewAddComment/
    //    reviewResolve/reviewSubmit:805-819) ────────────────────────────────────────────
    // Pure HTTP, like fsList/fsRead/fsWrite/fsSearch above — no ServerFrame/reduce()/WS
    // involvement. All four take a [SessionInfo] (the DiffView call site in
    // SessionDetail.DesktopEditorPanel already has it in hand), degrading through [runApi]
    // exactly like the fs* wrappers.

    /** GET /sessions/<id>/fs/diff?base=<spec> → repos + existing review comments. [base] is the
     *  diff-base spec (null/"session-start" default · "head" · "commit:<sha>" · "branch:<name>"); the
     *  compare target always stays the working tree. Null on any failure. */
    suspend fun fsDiff(session: SessionInfo, base: String? = null): FsDiffResult? =
        runApi("fsDiff") { api.fsDiff(session.id, base) }

    /** GET the current authored walkthrough. Null on a missing/failed endpoint. */
    suspend fun getWalkthrough(session: SessionInfo): Walkthrough? =
        runApi("getWalkthrough") { api.getWalkthrough(session.id) }

    /** GET /sessions/<id>/fs/refs → branches + recent commits per repo, for the diff-base picker's
     *  "Previous commit…" / "Another branch…" submenus. Null on any failure. */
    suspend fun fsRefs(session: SessionInfo): FsRefsResult? =
        runApi("fsRefs") { api.fsRefs(session.id) }

    /** POST /sessions/<id>/review/comments → the created comment. Null on any failure. */
    suspend fun reviewAddComment(session: SessionInfo, body: AddCommentBody): ReviewComment? =
        runApi("reviewAddComment") { api.reviewAddComment(session.id, body) }

    /** GET existing roots and replies so a reopened walkthrough is complete before live frames. */
    suspend fun reviewComments(session: SessionInfo): List<ReviewComment> =
        runApi("reviewComments") { api.reviewComments(session.id) } ?: emptyList()

    /** PATCH a comment to status="resolved" (iOS/Android reviewResolve parity). False on any failure. */
    suspend fun reviewResolve(session: SessionInfo, commentId: String): Boolean =
        runApi("reviewResolve") { api.reviewUpdateComment(session.id, commentId, UpdateCommentBody(status = "resolved")) }
            ?: false

    /** POST /sessions/<id>/review/submit → delivers open comments to the agent. Null on any
     *  failure. DANGER: this is the one remote-MUTATING op in this group — callers must never
     *  fire it outside an explicit user "Submit review" click (see DiffView's submit bar). */
    suspend fun reviewSubmit(session: SessionInfo): ReviewSubmitResult? =
        runApi("reviewSubmit") { api.reviewSubmit(session.id) }

    /** Start the broker fs-watcher for this session (so fs_changed fires → the stale banner works).
     *  Sent on EditorPanel mount; the [editorClose] counterpart stops it on dispose. */
    fun editorOpen(session: SessionInfo) {
        stateScope.launch { runApi("editorOpen") { sendFrame(ClientFrame.EditorOpen(session.id)) } }
    }

    fun editorClose(session: SessionInfo) {
        stateScope.launch { runApi("editorClose") { sendFrame(ClientFrame.EditorClose(session.id)) } }
    }

    // ── LSP control-plane senders (M4g-3; mirrors AppViewModel.lspStatusQuery/lspOpen/lspRpcOut/
    //    lspClose:832-843) ───────────────────────────────────────────────────────────────────────
    // lspClose is threaded for parity but NOT called by the connect flow in this milestone (Android
    // doesn't call it either — EditorScreen only ever calls engine.lspDisconnect() on the JS side);
    // reserved for a future explicit-teardown / settings-screen path.

    fun lspStatusQuery(session: SessionInfo, path: String) {
        stateScope.launch { runApi("lspStatusQuery") { sendFrame(ClientFrame.LspStatusQuery(session.id, path)) } }
    }

    fun lspOpen(session: SessionInfo, serverId: String) {
        stateScope.launch { runApi("lspOpen") { sendFrame(ClientFrame.LspOpen(session.id, serverId)) } }
    }

    fun lspRpcOut(session: SessionInfo, serverId: String, message: String) {
        stateScope.launch { runApi("lspRpcOut") { sendFrame(ClientFrame.LspRpcOut(session.id, serverId, message)) } }
    }

    fun lspClose(session: SessionInfo, serverId: String) {
        stateScope.launch { runApi("lspClose") { sendFrame(ClientFrame.LspClose(session.id, serverId)) } }
    }

    // ── Archived sessions (M4e; mirrors AppViewModel.archived/resume:677-678) ─────────
    // Backs the ArchivedScreen (M4e Task 2): a searchable, project-filtered list of archived
    // sessions with a read-only transcript (via [archivedLogs] below) + resume.

    /** GET /archived-sessions — every killed/archived session. Empty on any failure. */
    fun reorderSessions(orderedIds: List<String>) {
        // Optimistic sort_order so the list doesn't snap back while the PATCH is
        // in flight. Peers re-sort from the sessions_reordered WS frame.
        _state.update { current ->
            val order = orderedIds.withIndex().associate { (i, id) -> id to i }
            current.copy(sessions = current.sessions.map { s -> order[s.id]?.let { s.copy(sortOrder = it) } ?: s })
        }
        stateScope.launch {
            runCatching { api.reorderSessions(orderedIds) }
        }
    }

    /** PATCH /workspaces/reorder — optimistic sortOrder; peers via workspaces_reordered. */
    fun reorderWorkspaces(orderedIds: List<String>) {
        val order = orderedIds.withIndex().associate { (i, id) -> id to i }
        if (order.isEmpty()) return
        _state.update { current ->
            current.copy(workspaces = current.workspaces.map { w -> order[w.id]?.let { w.copy(sortOrder = it) } ?: w })
        }
        stateScope.launch {
            runCatching { api.reorderWorkspaces(orderedIds) }
        }
    }

    /**
     * Add a view to a workspace as a new tab in [groupId].
     *
     * Terminal / editor / display only — a chat needs an agent session, so that
     * path goes through the launcher (spec §9.2). The broker answers with
     * view_added + workspace_changed, which is what actually draws the tab.
     */
    fun addWorkspaceView(
        workspaceId: String,
        kind: dev.supermux.workspace.NewViewKind,
        groupId: String,
        /** Called with the new view id once the broker has created it. */
        onCreated: (String) -> Unit = {},
    ) {
        val state: JsonObject = when (kind) {
            dev.supermux.workspace.NewViewKind.TERMINAL -> buildJsonObject {
                put("scope", JsonPrimitive("workspace"))
                // Unique per tab so two terminals in one workspace are two shells.
                put("terminalId", JsonPrimitive("t" + deps.nowMs().toString().takeLast(6)))
            }
            dev.supermux.workspace.NewViewKind.EDITOR -> buildJsonObject { put("mode", JsonPrimitive("tree")) }
            // Same `editor` kind, different mode — a diff pane. No `diffBase`: the pane defaults to
            // the working tree and the base picker writes one when the user chooses another.
            dev.supermux.workspace.NewViewKind.DIFF -> buildJsonObject { put("mode", JsonPrimitive("diff")) }
            dev.supermux.workspace.NewViewKind.DISPLAY -> buildJsonObject { put("displayId", JsonPrimitive("")) }
            // A pending chat: no sessionId yet. The tab renders the new-session
            // composer, and binds to a real session on first send (bindChatView).
            dev.supermux.workspace.NewViewKind.CHAT -> buildJsonObject { }
        }
        stateScope.launch {
            runCatching { api.addView(workspaceId, AddViewBody(kind = kind.wire, state = state, groupId = groupId)) }
                .onSuccess { onCreated(it.id) }
                .onFailure { println("[HostStore] addWorkspaceView failed: $it") }
        }
    }

    /**
     * Close a view. Used for a pending chat tab the user backed out of — it has no
     * session behind it, so nothing is ended; the broker just drops the view.
     */
    fun closeWorkspaceView(workspaceId: String, viewId: String) {
        stateScope.launch {
            runCatching { api.closeView(workspaceId, viewId) }
                .onFailure { println("[HostStore] closeWorkspaceView failed: $it") }
        }
    }

    /**
     * Bind a pending chat view to the session that was just created for it, so
     * the tab stops being a composer and becomes the conversation. Same tab, same
     * position — only its contents change.
     */
    fun bindChatView(workspaceId: String, viewId: String, sessionId: String) {
        stateScope.launch {
            runCatching {
                api.patchView(
                    workspaceId, viewId,
                    PatchViewBody(state = buildJsonObject { put("sessionId", JsonPrimitive(sessionId)) }),
                )
            }.onFailure { println("[HostStore] bindChatView failed: $it") }
        }
    }

    /**
     * Archive a whole workspace: the broker archives its chat sessions AND the
     * workspace row, then broadcasts workspace_removed.
     *
     * Killing the chat sessions one by one is NOT equivalent and was the bug the
     * user hit: a workspace whose sessions were already archived had nothing left
     * to kill, so the row never left the sidebar and looked un-archivable.
     */
    fun archiveWorkspace(workspaceId: String) {
        // Optimistic: live list drops it, archived fold gains it. workspace_removed
        // is authoritative for peers (they still have the DTO in live list).
        _state.update { st ->
            val moving = st.workspaces.find { it.id == workspaceId } ?: return@update st
            val archived = moving.copy(status = "archived")
            st.copy(
                workspaces = st.workspaces.filter { it.id != workspaceId },
                archivedWorkspaces = if (st.archivedWorkspaces.any { it.id == workspaceId }) {
                    st.archivedWorkspaces
                } else {
                    st.archivedWorkspaces + archived
                },
            )
        }
        stateScope.launch {
            runCatching { api.archiveWorkspace(workspaceId) }
                .onFailure { println("[HostStore] archiveWorkspace failed: $it") }
        }
    }

    /**
     * Restore an archived workspace: unarchive the row and resume every chat.
     * Optimistic move live; workspace_added is authoritative.
     */
    fun restoreWorkspace(workspaceId: String) {
        _state.update { st ->
            val moving = st.archivedWorkspaces.find { it.id == workspaceId } ?: return@update st
            val live = moving.copy(status = "active", archivedAt = null)
            st.copy(
                archivedWorkspaces = st.archivedWorkspaces.filter { it.id != workspaceId },
                workspaces = if (st.workspaces.any { it.id == workspaceId }) {
                    st.workspaces.map { if (it.id == workspaceId) live else it }
                } else {
                    st.workspaces + live
                },
            )
        }
        stateScope.launch {
            runCatching { api.restoreWorkspace(workspaceId) }
                .onFailure { println("[HostStore] restoreWorkspace failed: $it") }
        }
    }

    /**
     * Move a view to another workspace via POST /views/:id/move.
     *
     * Spec §9.4: the session's work directory does NOT change — a chat view in a
     * workspace with a different workdir is valid. The broker broadcasts the
     * resulting workspace_changed frames; we do not optimistically edit layouts
     * here (the move can land in any group on the target).
     */
    fun moveViewToWorkspace(viewId: String, toWorkspaceId: String) {
        stateScope.launch {
            runCatching {
                api.moveView(viewId, MoveViewBody(toWorkspaceId = toWorkspaceId))
            }.onFailure { println("[HostStore] moveViewToWorkspace failed: $it") }
        }
    }

    suspend fun createDraftSession(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        name: String? = null,
        reasoningLevel: String? = null,
        attachments: List<dev.supermux.net.DraftAttachmentDto> = emptyList(),
        replaceDraftId: String? = null,
    ): String? = runCatching {
        if (!replaceDraftId.isNullOrBlank()) {
            runCatching { api.kill(replaceDraftId) }
        }
        api.spawn(
            dev.supermux.net.SpawnRequest(
                workdir = workdir,
                name = name?.ifBlank { null },
                agent = agent,
                model = model?.ifBlank { null },
                reasoningLevel = reasoningLevel,
                userStatus = "draft",
                draftPayload = dev.supermux.net.DraftPayloadDto(
                    text = text,
                    attachments = attachments.ifEmpty { null },
                ),
            ),
        )?.id
    }.getOrNull()

    suspend fun archived(): List<ArchivedDto> =
        runApi("archived") { api.archived() } ?: emptyList()

    /**
     * Kick off a resume for an archived session. Fire-and-forget like [finish]: returns only
     * whether the POST completed — the resumed session itself arrives live via a
     * session_added/snapshot frame on the WS ([reduce]), not in this response. Mirrors Android's
     * `runCatching{api.resume}` (fire-and-forget, no return). The desktop ArchivedScreen closes the
     * overlay unconditionally on tap (matching Android — it never waits on this Boolean); the return
     * is surfaced only so callers/tests that DO care can observe transport success.
     *
     * NOTE unlike [finish]/[gitFetch]/etc, [BrokerApi.resume] is a bare `http.post` with no
     * [BrokerApi.decode]/status check, so a 4xx/5xx from the broker does NOT throw — this only
     * degrades to false on a genuine transport failure (connection refused, timeout, ...), not on
     * an HTTP error status. See DesktopArchivedTest for both cases.
     */
    suspend fun resume(id: String): Boolean =
        runCatching { api.resume(id) }.isSuccess

    // ── Lazy transcript load ─────────────────────────────────────────────────────────

    /** GET /sessions/<id>/messages — a (possibly archived) session's transcript. Empty on any
     *  failure. Public: also backs the ArchivedScreen's read-only chat view (M4e Task 2), not
     *  just [ensureMessagesLoaded] below. */
    suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        runApi("archivedLogs") { api.archivedLogs(sessionId) } ?: emptyList()

    /**
     * Lazily fetch a session's transcript when we don't already have it. The WS Snapshot seeds
     * [messages] for every session live at connect time, and MessageAppend keeps them current —
     * but a session resumed from archive arrives via SessionAdded (no history), so its transcript
     * stays empty until the next snapshot. Calling this on chat-open closes that gap. No-op when
     * the snapshot already populated it. Web/iOS parity: ChatView.loadMessages /
     * BrokerSession.ensureMessagesLoaded (GET /sessions/:id/messages).
     */
    fun ensureMessagesLoaded(sessionId: String) {
        if (_state.value.messages[sessionId]?.isNotEmpty() == true) return
        stateScope.launch {
            val fetched = archivedLogs(sessionId)
            // Re-check after the await: a live MessageAppend / optimistic send / fresh snapshot may
            // have populated the buffer while the fetch was in flight — don't clobber it.
            if (fetched.isNotEmpty() && _state.value.messages[sessionId]?.isNotEmpty() != true) {
                _state.update { it.copy(messages = it.messages + (sessionId to fetched)) }
            }
        }
    }

    // ── Usage panel (M4f Task 1) ───────────────────────────────────────────────────────
    // Backs the header's Usage overlay (M4f Task 2): per-provider rate-limit windows +
    // the banked Codex reset redemption. Both go through [runApi] and getOrNull-degrade like
    // [archived]/[finishReadiness] — BrokerApi.usage/redeemCodexReset decode a typed body and
    // throw (SKIE-safe) on a non-2xx, so a broker hiccup here yields null, not an exception.

    /** GET /usage — per-provider usage (Claude / Codex / Cursor / opencode) + partial-failure
     *  [UsageResponse.errors]. Null on any transport/decode failure. A successful decode is
     *  also held on [usageSnapshot] so the popover can paint immediately on the next open. */
    suspend fun usage(): UsageResponse? =
        runApi("usage") { api.usage() }?.also { _usage.value = it }

    /** POST /usage/refresh — kick a live refresh (force ignores the 5-min throttle) and return
     *  the current snapshot immediately with [UsageResponse.refreshing] populated. Null on
     *  any transport/decode failure. */
    suspend fun refreshUsage(providers: List<String>? = null, force: Boolean = true): UsageResponse? =
        runApi("refreshUsage") { api.refreshUsage(providers, force) }?.also { _usage.value = it }

    /** Replace the held snapshot (Codex redeem updates one provider in place). */
    fun applyUsage(usage: UsageResponse) { _usage.value = usage }

    /** POST /usage/codex/reset — redeem one banked Codex rate-limit reset; returns the refreshed
     *  Codex usage so the card can update in place. Null on any failure. */
    suspend fun redeemCodexReset(): CodexResetResult? =
        runApi("redeemCodexReset") { api.redeemCodexReset() }

    suspend fun personalAssistants(): List<PADto> =
        runApi("personalAssistants") { api.listPAs() } ?: emptyList()

    suspend fun createPersonalAssistant(name: String, agent: String, focus: String?): Boolean =
        runApi("createPersonalAssistant") { api.createPA(name, agent, focusText = focus); true } ?: false

    suspend fun killPersonalAssistant(id: String) {
        runApi("killPersonalAssistant") { api.kill(id); true }
    }

    // ── Agents settings (desktop-parity Task 1) ───────────────────────────────────────────
    // Backs the Agents section of the Settings hub. Mirrors AppViewModel.agent* +
    // openCode* (Android) and BrokerSession agent install/login (iOS). All go through [runApi]
    // and degrade to empty/null — never throw into the UI.

    /**
     * GET /agents/status — install + auth state per agent CLI.
     * Returns `null` on transport/decode failure so the UI can distinguish Error from a
     * legitimate empty list (both used to collapse to `emptyList()`, leaving Settings stale).
     */
    suspend fun agentStatuses(): List<AgentInstallStatus>? =
        runApi("agentStatuses") { api.agentStatuses() }

    /** POST /agents/<kind>/install — start (or resume) the broker-owned install job. */
    suspend fun startAgentInstall(kind: String): AgentInstallJob? =
        runApi("startAgentInstall") { api.startAgentInstall(kind) }

    /** GET /agents/<kind>/install — poll the latest install job. */
    suspend fun agentInstallState(kind: String): AgentInstallJob? =
        runApi("agentInstallState") { api.agentInstallState(kind) }

    /** POST /agents/<kind>/login — start a CLI device-code / link login. */
    suspend fun startAgentLogin(kind: String): AgentLoginState? =
        runApi("startAgentLogin") { api.startAgentLogin(kind) }

    /** GET /agents/<kind>/login — poll the current login state. */
    suspend fun agentLoginState(kind: String): AgentLoginState? =
        runApi("agentLoginState") { api.agentLoginState(kind) }

    /** POST /agents/<kind>/login/code — hand the CLI a pasted device code. */
    suspend fun sendAgentLoginCode(kind: String, code: String) {
        runApi("sendAgentLoginCode") { api.sendAgentLoginCode(kind, code); true }
    }

    /** POST /agents/<kind>/login/cancel — abort an in-progress login. */
    suspend fun cancelAgentLogin(kind: String) {
        runApi("cancelAgentLogin") { api.cancelAgentLogin(kind); true }
    }

    /**
     * Save an API key / OAuth token for a CLI-login agent via PUT /settings/config.
     * Mirrors AppViewModel.agentSaveSecret: claude → claudeOauthToken, codex → codexApiKey,
     * cursor → cursorApiKey. Returns false for unknown kinds or transport failure.
     */
    suspend fun saveAgentSecret(kind: String, value: String): Boolean =
        runApi("saveAgentSecret") {
            when (kind) {
                "claude" -> api.saveConfig(claudeOauthToken = value)
                "codex" -> api.saveConfig(codexApiKey = value)
                "cursor" -> api.saveConfig(cursorApiKey = value)
                else -> return@runApi false
            }
            true
        } ?: false

    /** GET /opencode/providers — providers with auth methods. Empty on failure. */
    suspend fun openCodeProviders(): List<OpenCodeProvider> =
        runApi("openCodeProviders") { api.openCodeProviders() } ?: emptyList()

    /** POST /opencode/auth/key — save an API key for a provider. */
    suspend fun setOpenCodeKey(providerId: String, key: String): Boolean =
        runApi("setOpenCodeKey") { api.setOpenCodeKey(providerId, key); true } ?: false

    /** POST /opencode/auth/oauth/start — begin browser OAuth for a provider method. */
    suspend fun startOpenCodeOAuth(providerId: String, method: Int): OpenCodeOAuthStart? =
        runApi("startOpenCodeOAuth") { api.startOpenCodeOAuth(providerId, method) }

    /** POST /opencode/auth/oauth/finish — complete OAuth with a pasted code. */
    suspend fun finishOpenCodeOAuth(providerId: String, method: Int, code: String): Boolean =
        runApi("finishOpenCodeOAuth") { api.finishOpenCodeOAuth(providerId, method, code); true } ?: false

    // ── Devices settings (desktop-parity Task 2) ───────────────────────────────────────────
    // Backs the Devices section of the Settings hub. Mirrors AppViewModel devices / addDevice /
    // revokeDevice (Android MoreScreens). All go through [runApi] and degrade to null/false.

    /**
     * GET /devices — paired devices with last_seen.
     * Returns `null` on transport/decode failure so the UI can distinguish Error from empty.
     */
    suspend fun devices(): List<DeviceDto>? =
        runApi("devices") { api.devices() }

    /** POST /devices {name} → one-time pairing URL. Null on failure. */
    suspend fun addDevice(name: String): AddDeviceResponse? =
        runApi("addDevice") { api.addDevice(name) }

    /** DELETE /devices/<name> — revoke a paired device. False on failure. */
    suspend fun revokeDevice(name: String): Boolean =
        runApi("revokeDevice") { api.revokeDevice(name); true } ?: false

    /** Fire-and-forget Android name for [revokeDevice]. */
    fun revoke(n: String) {
        stateScope.launch { runApi("revoke") { api.revokeDevice(n) } }
    }

    // ── System / maintenance (desktop-parity Task 3) ───────────────────────────────────
    // Backs the System section of the Settings hub. Mirrors AppViewModel updateStatus /
    // checkUpdate / runUpdate / restartBroker. Broker self-update is distinct from the
    // desktop app's own AppUpdate (File ▸ "Check for Updates…").

    /** GET /api/update/status — cached broker updater state. Null on transport/decode failure. */
    suspend fun updateStatus(): UpdateStatus? =
        runApi("updateStatus") { api.updateStatus() }

    /**
     * POST /api/update/check — force the broker to poll versions.json and return post-check
     * status. Null on failure. Used by System "Recheck" so the UI does not only re-read cache.
     */
    suspend fun checkUpdate(): UpdateStatus? =
        runApi("checkUpdate") { api.checkUpdate() }

    /** POST /api/update/run — start broker self-update (binary mode). Null on transport failure. */
    suspend fun runUpdate(): RunUpdateResult? =
        runApi("runUpdate") { api.runUpdate() }

    /**
     * POST /system/restart — ask the broker to restart. Kills this client's connection;
     * [BrokerClient] reconnects when the broker is back.
     *
     * @return true when the POST is accepted (2xx); false on 4xx/5xx or transport failure so the
     *   System settings UI can surface an error instead of a blind "Restarting…" spinner.
     */
    suspend fun restartBroker(): Boolean =
        runApi("restartBroker") { api.restartBroker(); true } ?: false


    // ── LSP settings (M4g-4 Task 1) ────────────────────────────────────────────────────
    // Backs the LspSettingsScreen overlay (M4g-4 Task 2/3): enable/disable + install + add/remove
    // custom language servers. [lspInstallLog]/[lspInstallDone] (above) already stream the live
    // install progress/result via lsp_install_progress/lsp_install_done frames; these wrappers are
    // the HTTP half — mirrors AppViewModel.lspLoad/lspToggle/lspInstall/lspAddCustom/
    // lspRemoveCustom:736-747.

    /** GET /settings/editor → the server list. Empty (not null) on any failure, mirroring
     *  Android's `?: emptyList()` — a load failure shows an empty list rather than an error
     *  banner, since this is the FIRST load and there is no prior state to preserve. */
    suspend fun lspLoad(): List<LspServer> =
        runApi("lspLoad") { api.getEditorSettings().lsp.servers } ?: emptyList()

    /** PUT /settings/editor {lsp:{servers:{id:{enabled}}}} → the updated server list. Null (not a
     *  fallback list) on failure — the caller leaves the row exactly as it was rather than
     *  guessing at the new state. */
    suspend fun lspToggle(id: String, enabled: Boolean): List<LspServer>? =
        runApi("lspToggle") { api.setLspEnabled(id, enabled).lsp.servers }

    /** POST /settings/editor/lsp/<id>/install → {ok, lines}. The LIVE install log/result the
     *  caller actually renders arrives over the WS as lsp_install_progress/lsp_install_done
     *  ([lspInstallLog]/[lspInstallDone] above); this response only signals the HTTP round-trip
     *  finished so the caller can reload the server list (mirrors AppViewModel.lspInstall +
     *  EditorLspSection's `lspInstall(id); reload()` idiom). DANGER: runs a REAL install command
     *  on the broker host — see this plan's Ground rules. */
    suspend fun lspInstall(id: String): LspInstallResult? =
        runApi("lspInstall") { api.installEditorLsp(id) }

    /** POST /settings/editor/lsp/custom → {ok, error?, lsp?}. Null only on a transport failure —
     *  a validation rejection from the broker still decodes 2xx with ok=false + error (see
     *  BrokerApi.addCustomEditorLsp), which [runApi] does NOT swallow; the caller surfaces
     *  `.error` in the add-form. */
    suspend fun lspAddCustom(
        id: String,
        label: String,
        command: String,
        extensions: List<String>,
        args: List<String> = emptyList(),
        languageId: String? = null,
        installCmd: String? = null,
    ): LspMutationResult? =
        runApi("lspAddCustom") {
            api.addCustomEditorLsp(id, label, command, extensions, args, languageId, installCmd)
        }

    /** DELETE /settings/editor/lsp/custom/<id> → {ok, error?, lsp?}. */
    suspend fun lspRemoveCustom(id: String): LspMutationResult? =
        runApi("lspRemoveCustom") { api.removeCustomEditorLsp(id) }

    // ── New-session launcher + spawn (M4a; mirrors AppViewModel.launcher* +
    //    createSessionWithFirstMessage) ────────────────────────────────────────────────
    // These back the SessionLauncherScreen (M4a Task 4/5). All go through [runApi] and
    // getOrNull-degrade like Android's launcher helpers — a broker hiccup yields an empty/null
    // result, never an exception the launcher UI has to catch.

    /** GET /projects → known project working directories (absolute paths). Empty on any failure. */
    suspend fun listProjects(): List<String> =
        runApi("listProjects") { api.listProjects() } ?: emptyList()

    suspend fun launcherAgents(): List<String> =
        runApi("launcherAgents") { api.agentStatuses().filter { it.installed }.map { it.kind } } ?: emptyList()

    /** POST /paths/validate → {ok, path?, error?} (resolves ~, checks existence). Null on any
     *  transport/decode failure; an *invalid* path is still a non-null PathValidation(ok=false). */
    suspend fun validatePath(path: String): PathValidation? =
        runApi("validatePath") { api.validatePath(path) }

    /** GET /models?agent= → models pickable in the launcher (no session yet). Empty on failure. */
    suspend fun launcherModels(agent: String): List<ModelInfo> =
        runApi("launcherModels") { api.listModels(agent).models } ?: emptyList()

    /** GET /reasoning-levels?agent=&model= → thinking levels for the launcher. Null on failure. */
    suspend fun launcherReasoning(agent: String, model: String? = null): ReasoningResponse? =
        runApi("launcherReasoning") { api.getReasoningLevels(agent, model) }

    /** GET /repos/info?path= → git status for the launcher's worktree picker. Null on failure. */
    suspend fun launcherRepoInfo(workdir: String, fetch: Boolean = false): RepoInfo? =
        runApi("launcherRepoInfo") { api.getRepoInfo(workdir, fetch) }

    /** GET /commands/preview?agent=&workdir= → the agent's slash commands for the launcher (no
     *  session yet). Empty on failure OR a blank workdir (AppViewModel.launcherCommands parity —
     *  a blank workdir would 4xx, so short-circuit it). */
    suspend fun launcherCommands(agent: String, workdir: String): List<SlashCommand> =
        if (workdir.isBlank()) emptyList()
        else runApi("launcherCommands") { api.previewCommands(agent, workdir).commands } ?: emptyList()

    // ── Git hosting / forges (desktop-parity Task 4; mirrors AppViewModel.forges* + listForges) ──
    // Settings hub manages accounts; the New-Session launcher project picker uses the search /
    // clone / create half. All go through [runApi] and getOrNull-degrade like Android.

    /** GET /forge/connections → configured accounts + CLI availability. Null on transport failure. */
    suspend fun forgesLoad(): ForgeConnectionsResponse? =
        runApi("forgesLoad") { api.listForges() }

    /** POST /forge/connections — connect with a PAT. True on success. */
    suspend fun forgeAdd(kind: String, token: String, host: String?, transport: String): Boolean =
        runApi("forgeAdd") { api.addForge(kind, token, host, transport); true } ?: false

    /** POST /forge/connections/import — import from `gh`/`glab` CLI auth. True on success. */
    suspend fun forgeImport(kind: String, transport: String): Boolean =
        runApi("forgeImport") { api.importForge(kind, transport); true } ?: false

    /**
     * DELETE /forge/connections/<id> — disconnect. True only when the account is gone afterwards.
     * [BrokerApi.removeForge] does not check HTTP status, so we re-list to distinguish a 5xx no-op
     * from a real removal (and surface failures in the settings UI).
     */
    suspend fun forgeRemove(id: String): Boolean =
        runApi("forgeRemove") {
            api.removeForge(id)
            val stillThere = api.listForges().connections.any { it.id == id }
            if (stillThere) error("forge $id still present after remove")
            true
        } ?: false

    /** GET /forge/connections → connection list only (launcher omnibox). Empty on failure. */
    suspend fun listForges(): List<ForgeConnection> =
        runApi("listForges") { api.listForges().connections } ?: emptyList()

    /**
     * POST /forge/search → remote repos (+ per-connection errors) across connected forges.
     * Null on transport/5xx so the UI can distinguish failure from an empty success.
     */
    suspend fun searchForge(query: String): ForgeSearchResponse? =
        runApi("searchForge") { api.searchForge(query) }

    /** POST /forge/clone → local path of the new checkout. Null on failure / blank path. */
    suspend fun cloneForge(connectionId: String, owner: String, name: String): String? =
        runApi("cloneForge") { api.cloneForge(connectionId, owner, name).localPath }
            ?.ifBlank { null }

    /** POST /forge/create-local → local path of a fresh `git init`. Null on failure / blank path. */
    suspend fun createLocalRepo(name: String): String? =
        runApi("createLocalRepo") { api.createLocalRepo(name).localPath }?.ifBlank { null }

    /** POST /forge/create → create remote + clone; returns local path. Null on failure / blank. */
    suspend fun createForge(connectionId: String, name: String): String? =
        runApi("createForge") { api.createForge(connectionId, name).localPath }?.ifBlank { null }

    // ── In-session model + reasoning selection (mirrors AppViewModel's per-session model/reasoning
    //    helpers) ─────────────────────────────────────────────────────────────────────────────────
    // Back DesktopComposer's model/reasoning pills. All go through [runApi] and degrade to
    // null/false so a broker hiccup just leaves the pills showing their last-known state.

    /** GET /sessions/<id>/models → the session's pickable models + current selection. Null on
     *  failure. */
    suspend fun sessionModels(id: String): ModelsResponse? =
        runApi("sessionModels") { api.models(id) }

    /** GET /sessions/<id>/reasoning-levels → the session's thinking levels + current + visibility.
     *  Null on failure. */
    suspend fun sessionReasoning(id: String): ReasoningResponse? =
        runApi("sessionReasoning") { api.reasoningLevels(id) }

    /** POST /sessions/<id>/model {"model"} — switch the session's model (persists broker-side).
     *  Returns true on success, false on any failure. */
    /** Optimistic local update (web parity): the pill flips as soon as the PUT succeeds; the
     *  broker's session_state broadcast confirms it (or rolls it back on a failed live switch). */
    suspend fun switchModel(id: String, model: String): Boolean {
        val ok = runApi("switchModel") { api.switchModel(id, model); true } ?: false
        if (ok) patchSession(id) { it.copy(model = model) }
        return ok
    }

    /** POST /sessions/<id>/reasoning-level {"reasoningLevel"} — switch the session's thinking level.
     *  Returns true on success, false on any failure. */
    suspend fun switchReasoning(id: String, level: String): Boolean {
        val ok = runApi("switchReasoning") { api.switchReasoning(id, level); true } ?: false
        if (ok) patchSession(id) { it.copy(reasoningLevel = level) }
        return ok
    }

    /** Patch one session row in place (optimistic pill updates). No-op for an unknown id. */
    private fun patchSession(id: String, f: (SessionInfo) -> SessionInfo) {
        _state.update { st ->
            if (st.sessions.none { it.id == id }) st
            else st.copy(sessions = st.sessions.map { if (it.id == id) f(it) else it })
        }
    }

    // ── Voice dictation (M5-1) ──────────────────────────────────────────────────────────────
    // Backs DesktopComposer's MicButton (chat) and SessionLauncherScreen's MicButton (launcher,
    // id-less pre-spawn /transcribe) — mirrors AppViewModel's transcribeAudio wrapper; the shared
    // multipart wire shape (BrokerApi.transcribeAudio) is already proven by BrokerApiVoiceTest.

    /** POST {/sessions/<id>,}/transcribe (multipart "audio") → cleaned dictation text (whisper
     *  path). [sessionId] is OPTIONAL — null routes to the id-less `/transcribe` (the pre-spawn
     *  launcher composer); a live chat session passes its id so the broker's cleanup pass gets
     *  session context. Null (not an empty TranscribeResponse) on any failure — the caller keeps
     *  showing its own "mic unavailable"/"transcription failed" state rather than silently
     *  succeeding with empty text. */
    suspend fun transcribeAudio(
        sessionId: String?,
        bytes: ByteArray,
        filename: String,
        mime: String = "audio/wav",
    ): TranscribeResponse? =
        runApi("transcribeAudio") { apiDictate.transcribeAudio(sessionId, bytes, filename, mime) }

    /** GET /files/<id> — raw attachment bytes for chat media download / open. Null on any failure. */
    suspend fun fileBytes(fileId: String): ByteArray? =
        runApi("fileBytes") {
            // api.fileBytes already returns null on non-2xx; promote to a throw so runApi's T is
            // non-null ByteArray (avoids a nested ByteArray?? return type).
            api.fileBytes(fileId) ?: error("file not found")
        }

    /**
     * Resumable/chunked upload from a [ChunkSource] (bounded RAM), reporting absolute progress
     * `(bytesAcked, total)`. Returns the finalized file_id, or null on any failure.
     *
     * DELIBERATELY THROUGH [runApi] (unlike [fsRead], which bypasses it to preserve the FsException
     * message for the editor's error UI): the launcher only needs the id-or-null result, never the
     * failure message — a failed upload just drops that attachment chip. runApi's log-and-null +
     * cancellation discipline is exactly right (Android's AppViewModel.uploadResumable does the
     * same via `runCatching{…}.getOrNull()`).
     */
    suspend fun uploadResumable(
        session: String,
        source: ChunkSource,
        name: String,
        mime: String,
        kind: String? = null,
        onProgress: (Long, Long) -> Unit,
    ): String? =
        runApi("uploadResumable") {
            api.uploadResumable(session, source, name, mime, kind, onProgress).file_id
        }

    /** The uploaded attachment file_ids from the most recent [createSessionWithFirstMessage],
     *  keyed by the new session id, awaiting the caller's first-message send. See that method's
     *  KDoc for why the desktop handoff is a consumable holder (not Android's setPendingFirst). */
    private var firstUploads: Pair<String, List<String>>? = null

    /**
     * Take (and clear) the attachment file_ids that [createSessionWithFirstMessage] uploaded for
     * [sessionId], for the caller to pass into [sendMessage] as the first message's attachments.
     * Returns [] when nothing was staged for this session (or it was already consumed). Single-slot
     * by design — only one launcher submit is ever in flight. Mirrors the *shape* of Android's
     * consumePendingFirst, but carries ONLY the file_ids (the first-message TEXT stays with the
     * caller on desktop — see [createSessionWithFirstMessage]'s divergence note).
     */
    fun consumeFirstUploads(sessionId: String): List<String> {
        val entry = firstUploads ?: return emptyList()
        if (entry.first != sessionId) return emptyList()
        firstUploads = null
        return entry.second
    }

    /**
     * Create a new session and stage its first message's attachments; returns the new session id,
     * or null when the workdir is invalid or the spawn fails.
     *
     * Flow (Android AppViewModel.createSessionWithFirstMessage parity): validate the workdir
     * (POST /paths/validate) and resolve the real path → POST /sessions with the launcher's
     * agent / model / reasoning / worktree / baseBranch → resolve the (possibly-BLANK) spawn id
     * against the live session list ([resolveSpawnId]) → upload each staged file post-spawn
     * (uploads need a session id) via [uploadResumable]. A staged file that fails to upload is
     * skipped — session creation never blocks on an attachment. [worktree]/[baseBranch] are only
     * honored when the workdir is an eligible git repo (the broker ignores them otherwise);
     * baseBranch null → cut from the repo's current branch.
     *
     * DIVERGENCE FROM ANDROID: Android queues the first message via `setPendingFirst` and lets
     * `ChatScreen` send it on open. Desktop has no pending-first plumbing — this method deliberately
     * does NOT send [text]. The caller (the launcher, M4a Task 5) selects the returned session and
     * sends the first message itself via [sendMessage] (the SM_SMOKE_SEND path), passing the
     * uploaded attachment ids it takes from [consumeFirstUploads]. [text] is accepted here only so
     * the launcher's onSubmit signature stays aligned with Android's; it is neither sent nor stored.
     *
     * The whole body runs through [runApi]: any broker failure (invalid path, spawn 4xx, transport)
     * logs and yields null, so the launcher can surface "couldn't create session" without a catch.
     */
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
    ): String? = runApi("createSessionWithFirstMessage") {
        createSessionWithFirstMessageOrThrow(
            workdir, agent, model, reasoningLevel, text, staged, worktree, baseBranch,
            replaceDraftId, workspaceId, name, inheritFrom, firstMessage,
        )
    }

    /**
     * Throwing twin of [createSessionWithFirstMessage] for the launcher, which shows the broker's
     * OWN reason instead of a generic "couldn't create the session": an unusable workdir raises
     * [IllegalArgumentException] carrying [PathValidation.error], and a refused POST /sessions goes
     * through [remapSpawnFailure] so the JSON `error` field surfaces. Same side effects otherwise
     * (draft replaced, staged files uploaded after spawn, [consumeFirstUploads] armed).
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
        /** Join this workspace rather than creating a new one (spec decision 5). */
        workspaceId: String? = null,
        /** Display-name seed (e.g. Continue handoff reuses the source session's name base). */
        name: String? = null,
        /** Source session id for "Continue in new conversation" (broker inheritFrom). */
        inheritFrom: String? = null,
        /** Broker delivers this after spawn (continue handoff). Not sent on the client WS. */
        firstMessage: String? = null,
    ): String {
        if (!replaceDraftId.isNullOrBlank()) {
            runCatching { api.kill(replaceDraftId) }
        }
        val validation = runCatching { api.validatePath(workdir) }.getOrNull()
            ?: throw IllegalArgumentException("Could not validate path")
        val resolvedPath = validation.path
        if (!validation.ok || resolvedPath.isNullOrBlank()) {
            throw IllegalArgumentException(validation.error ?: "Invalid working directory")
        }
        val resp = try {
            api.spawn(
                SpawnRequest(
                    workdir = resolvedPath,
                    name = name?.ifBlank { null },
                    agent = agent,
                    model = model?.ifBlank { null },
                    worktree = if (worktree) true else null,
                    baseBranch = baseBranch?.ifBlank { null },
                    reasoningLevel = reasoningLevel?.ifBlank { null },
                    workspaceId = workspaceId,
                    inheritFrom = inheritFrom?.ifBlank { null },
                    firstMessage = firstMessage?.ifBlank { null },
                ),
            )
        } catch (t: Throwable) {
            remapSpawnFailure(t)
        }
        val sessionId = resolveSpawnId(resp, _state.value.sessions)
            ?: throw IllegalStateException("Session created but id not available yet")
        // Attachments need a session id, so they upload *after* spawn (mirrors iOS
        // NewSessionView.spawn() and the web launcher). A file that fails to upload is skipped.
        val attachmentIds = staged.mapNotNull { s ->
            uploadResumable(sessionId, s.source, s.name, s.mime, s.kind) { _, _ -> }
        }
        firstUploads = sessionId to attachmentIds
        return sessionId
    }

    /**
     * "Continue in a new conversation": same workdir as [source], no new worktree, inherit
     * display/worktree metadata via [SpawnRequest.inheritFrom], and pass [message] as
     * [SpawnRequest.firstMessage] so the broker delivers the first turn after spawn.
     * [agent]/[model]/[reasoningLevel] come from the continue dialog (web/iOS parity);
     * blank [agent] falls back to [dev.supermux.session.HandoffPrefill.defaultAgent].
     * Returns the new session id, or null on failure.
     */
    suspend fun continueConversation(
        source: SessionInfo,
        message: String,
        agent: String? = null,
        model: String? = null,
        reasoningLevel: String? = null,
    ): String? {
        val text = message.trim()
        if (text.isEmpty() || source.workdir.isBlank()) return null
        val chosen = agent?.trim()?.ifEmpty { null }
            ?: dev.supermux.session.HandoffPrefill.defaultAgent(source.agent)
        val workspaceId = workspaceIdForSession(source.id)
        val newId = createSessionWithFirstMessage(
            workdir = source.workdir,
            agent = chosen,
            model = model,
            reasoningLevel = reasoningLevel,
            text = text,
            staged = emptyList(),
            worktree = false,
            baseBranch = null,
            workspaceId = workspaceId,
            name = source.name,
            inheritFrom = source.id,
            firstMessage = text,
        ) ?: return null
        consumeFirstUploads(newId)
        return newId
    }

    /** Workspace that currently hosts [sessionId] as a chat view, if any. */
    internal fun workspaceIdForSession(sessionId: String): String? =
        _state.value.workspaces.firstOrNull { w ->
            w.status != "archived" && w.chatSessionIds().contains(sessionId)
        }?.id

    // ── Android-parity extras (Task 5 cutover) ─────────────────────────────────────

    /** POST /sessions with an already-built request; resolves the new session id (falling back to a
     *  name match when the broker returns a blank id). Throws on a broker refusal so the caller can
     *  surface it via [remapSpawnFailure] — unlike the fire-and-forget [spawn]. */
    suspend fun spawnRequest(request: SpawnRequest): String? =
        resolveSpawnId(api.spawn(request), _state.value.sessions)

    /** On-device-STT path: JSON draft → cleaned text (long-timeout dictation client). */
    suspend fun transcribeDraft(sessionId: String?, draft: String): TranscribeResponse? =
        runApi("transcribeDraft") { apiDictate.transcribeDraft(sessionId, draft) }

    /** POST /workspaces/:id/views with an explicit wire body (Android's view host builds its own
     *  state object). Returns the created view, or null on failure. */
    suspend fun addView(workspaceId: String, body: AddViewBody): dev.supermux.proto.ViewDto? =
        runApi("addView") { api.addView(workspaceId, body) }

    /** Add a view from an already-built wire `kind` + `state` (Android's WorkspaceScreen). */
    fun addWorkspaceView(
        workspaceId: String,
        kind: String,
        state: JsonObject,
        id: String? = null,
        groupId: String? = null,
    ) {
        stateScope.launch { addView(workspaceId, AddViewBody(kind = kind, state = state, id = id, groupId = groupId)) }
    }

    /** PATCH /workspaces/:id with a new layout tree (drag-resize / split commits). */
    suspend fun patchWorkspaceLayout(workspaceId: String, layout: dev.supermux.proto.LayoutNodeDto) {
        runApi("patchWorkspaceLayout") { api.patchWorkspace(workspaceId, PatchWorkspaceBody(layout = layout)) }
    }

    /** Stop all owned coroutines (collector, WS run-loop, heartbeat, in-flight ops) and release
     *  the shared HttpClients (WS + HTTP, and the dictation-only long-timeout client). Counterpart
     *  of AppViewModel.onCleared, plus the explicit scope cancel a plain (non-ViewModel) class needs. */
    fun close(cancelProjections: Boolean = true) {
        stateScope.cancel()
        if (cancelProjections) projectionJob.cancel()
        http.close()
        httpDictate.close()
    }
}

/**
 * Resolve the session id from a [SpawnResponse]. The broker sometimes returns a BLANK id on the
 * early (pre-register) session_added, so fall back to matching the response name against the known
 * session list (Android AppViewModel:593 pattern) — returns null when neither yields an id yet.
 *
 * Pure + top-level (no [HostStore] state captured) so it's unit-testable without a broker.
 */
internal fun resolveSpawnId(resp: SpawnResponse, sessions: List<SessionInfo>): String? =
    if (resp.id.isNotBlank()) resp.id
    else sessions.firstOrNull { it.name == resp.name }?.id
