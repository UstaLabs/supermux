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
package dev.supermux.desktop.state

import dev.supermux.desktop.notify.AgentReplyEvent
import dev.supermux.desktop.session.StagedUpload
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
import dev.supermux.net.UsageResponse
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
import dev.supermux.net.VncClient
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.net.AddViewBody
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The Viewing frames to send for the currently visible chat views (spec §11).
 *
 * Before workspaces there was exactly one open chat. A workspace can show two at
 * once, so the whole visible set goes out in ONE frame via [ClientFrame.Viewing.sessions]
 * — never a workspace id, and never a chat sitting in an inactive tab.
 *
 * One frame, not one per chat: bare `Viewing(s, true)` means "viewing exactly s"
 * and REPLACES the broker's set, because every other client switches chats that
 * way. Sending two such frames would leave only the last one. `session` is still
 * filled with the first id so an older broker that ignores `sessions` degrades to
 * correct single-chat behaviour instead of nothing.
 *
 * With nothing visible, send the null-session frame the list view sends, so the
 * broker clears this device's state instead of keeping a stale one.
 */
fun viewingFramesFor(visibleChatSessionIds: List<String>): List<ClientFrame.Viewing> = when {
    visibleChatSessionIds.isEmpty() -> listOf(ClientFrame.Viewing(null, false))
    else -> listOf(
        ClientFrame.Viewing(
            session = visibleChatSessionIds.first(),
            visible = true,
            // Only when there really are several. One visible chat — the case
            // every client and every existing test already covers — puts the
            // exact same bytes on the wire as before workspaces existed.
            sessions = visibleChatSessionIds.takeIf { it.size > 1 },
        ),
    )
}

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
 *   [dev.supermux.desktop.host.FleetState]): invoked `true` right after the control socket opens
 *   and `false` when it drops, forwarded straight to [BrokerClient]. Default null keeps every
 *   existing single-host caller/test unchanged.
 */
class DesktopAppState(
    val baseUrl: String,
    private val token: String,
    scope: CoroutineScope,
    connectOnInit: Boolean = true,
    sendFrameOverride: (suspend (ClientFrame) -> Unit)? = null,
    apiOverride: BrokerApi? = null,
    onConnectionChange: ((Boolean) -> Unit)? = null,
) {
    /** Own child scope — supervised and parented to the caller's [scope] — so [close] can cancel
     *  the collector / WS run-loop / heartbeat without tearing down the caller's scope, and one
     *  failed child never cancels its siblings. */
    private val stateScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val http = HttpClient(CIO) { install(WebSockets) }
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
    private val httpDictate = HttpClient(CIO) { engine { requestTimeout = 120_000 } }
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
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _workspaces = MutableStateFlow<List<WorkspaceDto>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceDto>> = _workspaces
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
    /**
     * Session id → ISO last_read_at. Seeded from snapshot `reads`, updated by `session_read`
     * frames and optimistic [markRead] when the user opens a chat (web/Android parity).
     */
    private val _lastRead = MutableStateFlow<Map<String, String>>(emptyMap())
    val lastRead: StateFlow<Map<String, String>> = _lastRead

    // ── Finish flow (M4b) ──────────────────────────────────────────────────────────
    // The last/in-flight finish job per session, keyed by session id (Android AppViewModel
    // parity). Seeded from each SessionInfo.finish_job in the Snapshot and kept current by the
    // FinishJobFrame reducer; the FinishDialog drives its 3-state machine (menu/running/outcome)
    // off this flow. clearFinishJob drops an entry client-side once the user dismisses the outcome.
    private val _finishJobs = MutableStateFlow<Map<String, FinishJobDto>>(emptyMap())
    val finishJobs: StateFlow<Map<String, FinishJobDto>> = _finishJobs

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
    // (inbound) is a raw relay SharedFlow — DesktopLspBridge (Task 2) filters it by session+serverId.
    private val _lspStatus = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = _lspStatus

    private val _lspRpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 256)
    val lspRpc: SharedFlow<ServerFrame.LspRpcIn> = _lspRpc.asSharedFlow()

    // Live install progress/result per LSP serverId (M4g-4). Drives LspSettingsScreen's streamed
    // install log + terminal result row — mirrors AppViewModel:173-180.
    private val _lspInstallLog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val lspInstallLog: StateFlow<Map<String, List<String>>> = _lspInstallLog

    private val _lspInstallDone = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(emptyMap())
    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> = _lspInstallDone

    // ── Displays (M5-2) ─────────────────────────────────────────────────────────────
    // Live display streams, kept in sync via display_added/display_removed frames (seeded on
    // demand by [listDisplays]) — mirrors AppViewModel:158-161.
    private val _displays = MutableStateFlow<List<DisplayStream>>(emptyList())
    val displays: StateFlow<List<DisplayStream>> = _displays

    /** Whether the client has a fresh snapshot from the broker (i.e. we're synced/connected). */
    val connected: Boolean get() = client.sync.synced

    init {
        // Read-aloud: platform OS TTS or ChatGPT /speak stream depending on app-config.
        dev.supermux.desktop.chat.MessageTts.resolveEngine = {
            runCatching { api.getConfig().voiceTtsEngine }.getOrNull()?.ifBlank { null } ?: "platform"
        }
        dev.supermux.desktop.chat.MessageTts.speakRemoteStream = { text, onChunk ->
            api.speakStream(text = text, engine = "codex", onChunk = onChunk)
        }
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
        runCatching(block).onFailure { e -> println("[DesktopAppState] $op error: $e") }
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
            println("[DesktopAppState] $op failed: ${c.message}")
            null
        } catch (e: Throwable) {
            println("[DesktopAppState] $op failed: $e")
            null
        }

    // ── ServerFrame reducer (ported subset of AppViewModel's when(frame)) ──────────

    /** Fold one inbound frame into the StateFlows. Public for reducer tests. All read-modify-
     *  write mutations go through atomic `.update {}` — appendLocalEcho (caller thread) and the
     *  reducer coroutine can race on [_messages], and a lost update here is a lost message. */
    fun reduce(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Snapshot -> {
                // Straight replacement (not read-modify-write) — plain assignment is atomic.
                _sessions.value = frame.sessions
                _workspaces.value = frame.workspaces
                _messages.value = frame.logs
                _activity.value = frame.activity
                _bgTasks.value = frame.bgTasks
                _agentState.value = frame.agentState
                _commands.value = frame.commands
                _commandsResolved.value = frame.commandsResolved
                // Monotonic merge of read pointers (web unread.seed / Android parity): never
                // rewind an optimistic local mark with a slightly-older server timestamp.
                if (frame.reads.isNotEmpty()) {
                    _lastRead.update { cur ->
                        val next = cur.toMutableMap()
                        for ((id, ts) in frame.reads) {
                            next[id] = dev.supermux.session.advanceLastRead(next[id], ts)
                        }
                        next
                    }
                }
                // Seed finish jobs from each session's snapshot record (keyed by session id).
                _finishJobs.value = frame.sessions
                    .mapNotNull { s -> s.finish_job?.let { s.id to it } }
                    .toMap()
                // A (re)connect always begins with a snapshot; re-assert viewing presence so the
                // broker's per-device tracker is current after a reconnect (reset the dedup cache).
                lastSentViewing = null
                sendViewingIfChanged()
            }
            is ServerFrame.SessionAdded -> {
                // The broker re-broadcasts session_added for the SAME session (early add on spawn,
                // then the authoritative post-register add carrying repo_root / session_branch).
                // Dedup by id and backfill omitted fields rather than appending a duplicate row.
                val incoming = frame.session
                _sessions.update { current ->
                    if (current.none { it.id == incoming.id }) {
                        current + incoming
                    } else {
                        current.map { s ->
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
                }
                // A session resumed from archive can arrive carrying a finish_job — seed it
                // (Android AppViewModel parity) so the FinishDialog sees it before the next snapshot.
                incoming.finish_job?.let { job -> _finishJobs.update { it + (incoming.id to job) } }
            }
            is ServerFrame.SessionRemoved -> {
                _sessions.update { it.filterNot { s -> s.id == frame.id } }
                _bgTasks.update { it - frame.id }
            }
            is ServerFrame.SessionRenamed -> {
                _sessions.update { current ->
                    current.map { s -> if (s.id == frame.id) s.copy(name = frame.newName) else s }
                }
            }
            is ServerFrame.SessionsReordered -> {
                // Live fan-out of PATCH /sessions/reorder (peer clients re-sort).
                val order = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
                if (order.isNotEmpty()) {
                    _sessions.update { current ->
                        current.map { s -> order[s.id]?.let { s.copy(sortOrder = it) } ?: s }
                    }
                }
            }
            is ServerFrame.WorkspaceAdded -> {
                // The broker re-broadcasts the same workspace (early add on spawn, then the
                // authoritative one carrying repo_root / branch). Replace, never duplicate —
                // the same trap SessionAdded documents above.
                _workspaces.update { cur ->
                    if (cur.none { it.id == frame.workspace.id }) cur + frame.workspace
                    else cur.map { if (it.id == frame.workspace.id) frame.workspace else it }
                }
            }
            is ServerFrame.WorkspaceChanged -> {
                // Unknown id = a workspace this client never saw added. Ignore rather than
                // append: appending would put it at the end, out of sort order.
                _workspaces.update { cur ->
                    cur.map { if (it.id == frame.workspace.id) frame.workspace else it }
                }
            }
            is ServerFrame.WorkspaceRemoved -> {
                _workspaces.update { cur -> cur.filter { it.id != frame.id } }
            }
            is ServerFrame.WorkspacesReordered -> {
                val rank = frame.orderedIds.withIndex().associate { (i, id) -> id to i }
                _workspaces.update { cur ->
                    cur.map { w -> rank[w.id]?.let { w.copy(sortOrder = it) } ?: w }
                }
            }
            is ServerFrame.ViewAdded -> updateViews(frame.workspaceId) { it + frame.view }
            is ServerFrame.ViewRemoved -> updateViews(frame.workspaceId) { vs -> vs.filter { it.id != frame.viewId } }
            is ServerFrame.ViewChanged -> updateViews(frame.workspaceId) { vs ->
                vs.map { if (it.id == frame.view.id) frame.view else it }
            }
            is ServerFrame.ViewMoved -> {
                // Do NOT rebuild either workspace's layout. The broker sends workspace_changed
                // for both workspaces right after view_moved, carrying the authoritative trees.
                _workspaces.update { cur ->
                    var moved: ViewDto? = null
                    val stripped = cur.map { w ->
                        if (w.id != frame.fromWorkspaceId) w
                        else {
                            moved = w.views.firstOrNull { it.id == frame.viewId }
                            w.copy(views = w.views.filter { it.id != frame.viewId })
                        }
                    }
                    val v = moved ?: return@update stripped
                    stripped.map { w ->
                        if (w.id != frame.toWorkspaceId) w
                        else w.copy(views = w.views + v.copy(workspaceId = frame.toWorkspaceId))
                    }
                }
            }
            is ServerFrame.MessageAppend -> {
                // Optimistic-echo dedup (iOS BrokerSession parity): when the real inbound message
                // lands, drop the matching local-… placeholder we appended on send.
                _messages.update { current ->
                    val prev = current[frame.session] ?: emptyList()
                    val pruned = if (frame.entry.direction.startsWith("in")) {
                        prev.filterNot { it.id.startsWith("local-") && it.text == frame.entry.text }
                    } else prev
                    current + (frame.session to (pruned + frame.entry))
                }
                // M5-3: broadcast AGENT REPLIES ONLY (direction="outbound", op="reply" — mirrors
                // the broker's push/hook.ts firePushForReply guard) so AppShell's
                // NotificationController can decide whether to raise a tray toast. The user's own
                // echoed message (direction="inbound") and non-reply outbound entries
                // (op="react"/"edit_message") never reach this flow.
                if (frame.entry.direction == "outbound" && frame.entry.op == "reply") {
                    _agentReplies.tryEmit(AgentReplyEvent(frame.session, frame.entry))
                }
            }
            is ServerFrame.SessionRead -> {
                _lastRead.update { cur ->
                    val next = dev.supermux.session.advanceLastRead(cur[frame.session], frame.lastReadAt)
                    if (cur[frame.session] == next) cur else cur + (frame.session to next)
                }
            }
            is ServerFrame.ActivityAppend -> {
                _activity.update { current ->
                    current + (frame.session to ((current[frame.session] ?: emptyList()) + frame.event))
                }
            }
            is ServerFrame.BgTasks -> {
                _bgTasks.update { it + (frame.session to frame.tasks) }
            }
            is ServerFrame.AgentState -> {
                _agentState.update { current ->
                    current + (frame.session to AgentStatus(
                        phase = frame.phase, state = frame.state, working = frame.working,
                        detail = frame.detail, tool = frame.tool, since = frame.since,
                        workingSince = frame.workingSince, waiting = frame.waiting, bgOpen = frame.bgOpen,
                    ))
                }
                _pendingSend.update { it - frame.session }   // first real state clears the client-local "Sending…"
            }
            is ServerFrame.CommandsChanged -> {
                _commands.update { it + (frame.session to frame.commands) }
                _commandsResolved.update { it + (frame.session to frame.resolved) }
            }
            // M3 editor: broadcast the disk-change pulse to whichever EditorPanel is watching this
            // session (it filters by session id). tryEmit never suspends the reducer; a full buffer
            // (64) would drop the oldest pulse, harmless since the banner only needs "something
            // changed", and editor_open/close bounds how long the watcher fires at all.
            is ServerFrame.FsChanged -> {
                _fsChanges.tryEmit(frame)
            }
            // M4b finish flow: the async job's progress/outcome arrives here. Update the finishJobs
            // flow the FinishDialog drives AND write the job back onto the session's finish_job so a
            // list row (and any later snapshot round-trip) stays consistent (AppViewModel:275 parity).
            is ServerFrame.FinishJobFrame -> {
                val job = frame.job
                if (job != null) {
                    _finishJobs.update { it + (frame.session to job) }
                    _sessions.update { current ->
                        current.map { s -> if (s.id == frame.session) s.copy(finish_job = job) else s }
                    }
                }
            }
            // M4b: live per-session git divergence delta → the sidebar/header git badge
            // (AppViewModel:301 parity). Match on session id like every other session mutation.
            is ServerFrame.SessionGit -> {
                _sessions.update { current ->
                    current.map { s -> if (s.id == frame.session) s.copy(git = frame.git) else s }
                }
            }
            is ServerFrame.LspStatus ->
                _lspStatus.update { it + ("${frame.session}|${frame.path}" to frame) }
            is ServerFrame.LspReady -> markLspState(frame.session, frame.serverId, "ready")
            is ServerFrame.LspError -> markLspState(frame.session, frame.serverId, "error", frame.error)
            is ServerFrame.LspRpcIn -> _lspRpc.tryEmit(frame)
            is ServerFrame.LspExit -> markLspState(frame.session, frame.serverId, "exited")
            is ServerFrame.LspInstallProgress ->
                _lspInstallLog.update { it + (frame.serverId to ((it[frame.serverId] ?: emptyList()) + frame.line)) }
            is ServerFrame.LspInstallDone -> _lspInstallDone.update { it + (frame.serverId to frame) }
            // M5-2: display stream lifecycle — the broker broadcasts these as displays start/stop
            // (via startDisplay/stopDisplay OR another device's own display action); dedup by id
            // like SessionAdded rather than appending a duplicate (AppViewModel:286-289 parity).
            is ServerFrame.DisplayAdded ->
                _displays.update { list -> list.filterNot { it.id == frame.display.id } + frame.display }
            is ServerFrame.DisplayRemoved ->
                _displays.update { list -> list.filterNot { it.id == frame.id } }
            // Out of M1/M3/M4b/M5-2 scope — reduced in later milestones: agent_error (see
            // AppViewModel for the full reducer). Must still not crash.
            else -> {}
        }
    }

    /** Replace one workspace's view list. A frame for an unknown workspace is a no-op. */
    private fun updateViews(workspaceId: String, edit: (List<ViewDto>) -> List<ViewDto>) {
        _workspaces.update { cur ->
            cur.map { if (it.id == workspaceId) it.copy(views = edit(it.views)) else it }
        }
    }

    /** Patch the `state` (and optionally `error`) of every [ServerFrame.LspStatus] entry matching
     *  [session] + [serverId]; used by the lsp_ready/lsp_error/lsp_exit frames, which only carry
     *  session+serverId while [_lspStatus] is keyed by "session|path" (AppViewModel:345-359 port). */
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

    // ── Viewing presence ───────────────────────────────────────────────────────────

    /**
     * Report the foreground chat (`null` = the session list) + whether the app is visible.
     * Classic single-chat path (SM_WORKSPACES unset). Deduped; starts the keep-alive heartbeat.
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
     * Used when SM_WORKSPACES is on and a workspace can show two chats at once.
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
        val now = Instant.now().toString()
        _lastRead.update { cur ->
            val next = dev.supermux.session.advanceLastRead(cur[sessionId], now)
            if (cur[sessionId] == next) cur else cur + (sessionId to next)
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
    private fun nowIso(): String = Instant.now().toString()

    /** Append an optimistic outbound bubble so the user's message shows instantly, before the
     *  broker echoes it back as an inbound message (iOS BrokerSession.send parity). Deduped in the
     *  MessageAppend reducer. Only echoes when there is text (attachments-only stay quiet). */
    fun appendLocalEcho(sessionId: String, text: String) {
        if (text.isEmpty()) return
        val optimistic = LogEntry(
            id = "local-${(_messages.value[sessionId]?.size ?: 0)}-${text.hashCode()}",
            ts = nowIso(),
            direction = "inbound",
            text = text,
        )
        _messages.update { it + (sessionId to ((it[sessionId] ?: emptyList()) + optimistic)) }
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
        _finishJobs.update { it - id }
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
     *  per tab and remembered by [dev.supermux.desktop.terminal.DesktopTerminalPanel]; the broker
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
        val list = runApi("listDisplays") { api.listDisplays() } ?: return _displays.value
        _displays.value = list
        return list
    }

    /** Factory for this session's VNC transport client; called once per connected stream and
     *  remembered by the Display panel (mirrors [connectAgentTerminal]). */
    fun connectVnc(streamId: String): VncClient = VncClient(baseUrl, token, http, streamId)

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

    /** GET /sessions/<id>/fs → directory listing (workdir-relative). Empty on any failure. */
    suspend fun fsList(session: SessionInfo, path: String): List<FsEntry> =
        runApi("fsList") { api.fsList(session.id, path) } ?: emptyList()

    /** GET /workspaces/<id>/fs → directory listing (workspace workdir). Empty on any failure. */
    suspend fun workspaceFsList(workspaceId: String, path: String): List<FsEntry> =
        runApi("workspaceFsList") { api.workspaceFsList(workspaceId, path) } ?: emptyList()

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

    /** GET /sessions/<id>/fs/refs → branches + recent commits per repo, for the diff-base picker's
     *  "Previous commit…" / "Another branch…" submenus. Null on any failure. */
    suspend fun fsRefs(session: SessionInfo): FsRefsResult? =
        runApi("fsRefs") { api.fsRefs(session.id) }

    /** POST /sessions/<id>/review/comments → the created comment. Null on any failure. */
    suspend fun reviewAddComment(session: SessionInfo, body: AddCommentBody): ReviewComment? =
        runApi("reviewAddComment") { api.reviewAddComment(session.id, body) }

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
        _sessions.update { current ->
            val order = orderedIds.withIndex().associate { (i, id) -> id to i }
            current.map { s -> order[s.id]?.let { s.copy(sortOrder = it) } ?: s }
        }
        stateScope.launch {
            runCatching { api.reorderSessions(orderedIds) }
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
        kind: dev.supermux.desktop.shell.NewViewKind,
        groupId: String,
        /** Called with the new view id once the broker has created it. */
        onCreated: (String) -> Unit = {},
    ) {
        val state: JsonObject = when (kind) {
            dev.supermux.desktop.shell.NewViewKind.TERMINAL -> buildJsonObject {
                put("scope", JsonPrimitive("workspace"))
                // Unique per tab so two terminals in one workspace are two shells.
                put("terminalId", JsonPrimitive("t" + Instant.now().toEpochMilli().toString().takeLast(6)))
            }
            dev.supermux.desktop.shell.NewViewKind.EDITOR -> buildJsonObject { put("mode", JsonPrimitive("tree")) }
            dev.supermux.desktop.shell.NewViewKind.DISPLAY -> buildJsonObject { put("displayId", JsonPrimitive("")) }
            // A pending chat: no sessionId yet. The tab renders the new-session
            // composer, and binds to a real session on first send (bindChatView).
            dev.supermux.desktop.shell.NewViewKind.CHAT -> buildJsonObject { }
        }
        stateScope.launch {
            runCatching { api.addView(workspaceId, AddViewBody(kind = kind.wire, state = state, groupId = groupId)) }
                .onSuccess { onCreated(it.id) }
                .onFailure { println("[DesktopAppState] addWorkspaceView failed: $it") }
        }
    }

    /**
     * Close a view. Used for a pending chat tab the user backed out of — it has no
     * session behind it, so nothing is ended; the broker just drops the view.
     */
    fun closeWorkspaceView(workspaceId: String, viewId: String) {
        stateScope.launch {
            runCatching { api.closeView(workspaceId, viewId) }
                .onFailure { println("[DesktopAppState] closeWorkspaceView failed: $it") }
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
            }.onFailure { println("[DesktopAppState] bindChatView failed: $it") }
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
        // Optimistic removal so the row leaves immediately; the workspace_removed
        // frame is authoritative and peers get it too.
        _workspaces.update { cur -> cur.filter { it.id != workspaceId } }
        stateScope.launch {
            runCatching { api.archiveWorkspace(workspaceId) }
                .onFailure { println("[DesktopAppState] archiveWorkspace failed: $it") }
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
            }.onFailure { println("[DesktopAppState] moveViewToWorkspace failed: $it") }
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
        if (_messages.value[sessionId]?.isNotEmpty() == true) return
        stateScope.launch {
            val fetched = archivedLogs(sessionId)
            // Re-check after the await: a live MessageAppend / optimistic send / fresh snapshot may
            // have populated the buffer while the fetch was in flight — don't clobber it.
            if (fetched.isNotEmpty() && _messages.value[sessionId]?.isNotEmpty() != true) {
                _messages.update { it + (sessionId to fetched) }
            }
        }
    }

    // ── Usage panel (M4f Task 1) ───────────────────────────────────────────────────────
    // Backs the header's Usage overlay (M4f Task 2): per-provider rate-limit windows +
    // the banked Codex reset redemption. Both go through [runApi] and getOrNull-degrade like
    // [archived]/[finishReadiness] — BrokerApi.usage/redeemCodexReset decode a typed body and
    // throw (SKIE-safe) on a non-2xx, so a broker hiccup here yields null, not an exception.

    /** GET /usage — per-provider usage (Claude / Codex / Cursor / opencode) + partial-failure
     *  [UsageResponse.errors]. Null on any transport/decode failure. */
    suspend fun usage(): UsageResponse? =
        runApi("usage") { api.usage() }

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
    suspend fun switchModel(id: String, model: String): Boolean =
        runApi("switchModel") { api.switchModel(id, model); true } ?: false

    /** POST /sessions/<id>/reasoning-level {"reasoningLevel"} — switch the session's thinking level.
     *  Returns true on success, false on any failure. */
    suspend fun switchReasoning(id: String, level: String): Boolean =
        runApi("switchReasoning") { api.switchReasoning(id, level); true } ?: false

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
        /** Join this workspace rather than creating a new one (spec decision 5). */
        workspaceId: String? = null,
        /** Display-name seed (e.g. Continue handoff reuses the source session's name base). */
        name: String? = null,
        /** Source session id for "Continue in new conversation" (broker inheritFrom). */
        inheritFrom: String? = null,
    ): String? = runApi("createSessionWithFirstMessage") {
        if (!replaceDraftId.isNullOrBlank()) {
            runCatching { api.kill(replaceDraftId) }
        }
        val validation = api.validatePath(workdir)
        val resolvedPath = validation.path
        if (!validation.ok || resolvedPath.isNullOrBlank()) {
            println("[DesktopAppState] createSessionWithFirstMessage: invalid workdir '$workdir': " +
                (validation.error ?: "unknown"))
            return@runApi null
        }
        val resp = api.spawn(
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
            ),
        )
        val sessionId = resolveSpawnId(resp, _sessions.value)
        if (sessionId == null) {
            println("[DesktopAppState] createSessionWithFirstMessage: spawn ok but id unavailable " +
                "(name='${resp.name}')")
            return@runApi null
        }
        // Attachments need a session id, so they upload *after* spawn (mirrors iOS
        // NewSessionView.spawn() and the web launcher). A file that fails to upload is skipped.
        val attachmentIds = staged.mapNotNull { s ->
            uploadResumable(sessionId, s.source, s.name, s.mime, s.kind) { _, _ -> }
        }
        firstUploads = sessionId to attachmentIds
        sessionId
    }

    /**
     * "Continue in a new conversation": same workdir as [source], no new worktree, inherit
     * display/worktree metadata via [SpawnRequest.inheritFrom], then send [message] as the first
     * turn. Returns the new session id, or null on failure.
     */
    suspend fun continueConversation(source: SessionInfo, message: String): String? {
        val text = message.trim()
        if (text.isEmpty() || source.workdir.isBlank()) return null
        val agent = dev.supermux.session.HandoffPrefill.defaultAgent(source.agent)
        val sameAgent = source.agent.equals(agent, ignoreCase = true)
        val newId = createSessionWithFirstMessage(
            workdir = source.workdir,
            agent = agent,
            model = if (sameAgent) source.model else null,
            reasoningLevel = if (sameAgent) source.reasoningLevel else null,
            text = text,
            staged = emptyList(),
            worktree = false,
            baseBranch = null,
            name = source.name,
            inheritFrom = source.id,
        ) ?: return null
        sendMessage(newId, text, consumeFirstUploads(newId))
        return newId
    }

    /** Stop all owned coroutines (collector, WS run-loop, heartbeat, in-flight ops) and release
     *  the shared HttpClients (WS + HTTP, and the dictation-only long-timeout client). Counterpart
     *  of AppViewModel.onCleared, plus the explicit scope cancel a plain (non-ViewModel) class needs. */
    fun close() {
        stateScope.cancel()
        http.close()
        httpDictate.close()
    }
}

/**
 * Resolve the session id from a [SpawnResponse]. The broker sometimes returns a BLANK id on the
 * early (pre-register) session_added, so fall back to matching the response name against the known
 * session list (Android AppViewModel:593 pattern) — returns null when neither yields an id yet.
 *
 * Pure + top-level (no [DesktopAppState] state captured) so it's unit-testable without a broker.
 */
internal fun resolveSpawnId(resp: SpawnResponse, sessions: List<SessionInfo>): String? =
    if (resp.id.isNotBlank()) resp.id
    else sessions.firstOrNull { it.name == resp.name }?.id
