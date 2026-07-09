// Ported from apps/android/.../AppViewModel.kt (M1 scope) — keep reducer semantics in sync.
//
// This is the LOGIC CORE of the desktop client: it wires the shared BrokerClient (WS) and
// BrokerApi (HTTP) and reduces inbound ServerFrames into StateFlows the Compose UI observes.
// The Milestone-1 surface is ported here — sessions / messages / activity / agentState / bgTasks /
// commands + the send/viewing/control paths — plus the M3 editor filesystem surface (fsList/fsRead/
// fsWrite/fsSearch, editorOpen/editorClose, and the fs_changed → [fsChanges] fold). Still-out-of-scope
// frames (finish, git, LSP, displays) and features (uploads beyond Send args, dictation, models/
// reasoning, drafts, push, notifications) are deliberately no-op'd with a milestone marker so the
// reducer stays a faithful subset of AppViewModel's `when (frame)`.
package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSummary
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SendArgs
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SlashCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * @param connectOnInit when false (tests), the constructor does NOT collect frames, launch the
 *   WS client, or start the viewing heartbeat — so [reduce] and the send helpers can run without
 *   a network. Production uses the default `true`.
 * @param sendFrameOverride injectable outbound-frame seam; defaults to `client.send`. Tests pass
 *   a capturing lambda to assert outbound ClientFrames without a live WebSocket.
 * @param apiOverride injectable HTTP seam mirroring [sendFrameOverride]. NOTE: BrokerApi is a
 *   FINAL concrete class (not open, no interface), so this cannot take a mock subclass — tests
 *   exercising HTTP paths construct a real BrokerApi against a ktor MockEngine HttpClient and
 *   pass it here.
 */
class DesktopAppState(
    val baseUrl: String,
    private val token: String,
    scope: CoroutineScope,
    connectOnInit: Boolean = true,
    sendFrameOverride: (suspend (ClientFrame) -> Unit)? = null,
    apiOverride: BrokerApi? = null,
) {
    /** Own child scope — supervised and parented to the caller's [scope] — so [close] can cancel
     *  the collector / WS run-loop / heartbeat without tearing down the caller's scope, and one
     *  failed child never cancels its siblings. */
    private val stateScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val http = HttpClient(CIO) { install(WebSockets) }
    val client = BrokerClient(baseUrl, token, http)
    val api = apiOverride ?: BrokerApi(baseUrl, token, http)
    private val sendFrame: suspend (ClientFrame) -> Unit = sendFrameOverride ?: { client.send(it) }

    // ── Viewing presence (mirrors iOS BrokerSession / web useViewing) ──────────────
    private var viewingSession: String? = null
    private var viewingVisible: Boolean = false
    private var lastSentViewing: Pair<String?, Boolean>? = null
    private var viewingHeartbeat: Job? = null

    // ── StateFlows (M1 read surface) ───────────────────────────────────────────────
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
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

    // ── Editor file-watch (M3) ─────────────────────────────────────────────────────
    // The reducer folds inbound fs_changed frames into this app-wide SharedFlow (mirrors Android's
    // AppViewModel.fsChanges). Each EditorPanel collects it and calls its EditorState.markChanged
    // FILTERED to its own session — the stale-on-disk banner is dead without this stream. A replay
    // of 0 (transient signal, not state) + a generous extraBufferCapacity so tryEmit from the
    // synchronous reducer never drops a change or suspends.
    private val _fsChanges = MutableSharedFlow<ServerFrame.FsChanged>(extraBufferCapacity = 64)
    val fsChanges: SharedFlow<ServerFrame.FsChanged> = _fsChanges.asSharedFlow()

    /** Whether the client has a fresh snapshot from the broker (i.e. we're synced/connected). */
    val connected: Boolean get() = client.sync.synced

    init {
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
                _messages.value = frame.logs
                _activity.value = frame.activity
                _bgTasks.value = frame.bgTasks
                _agentState.value = frame.agentState
                _commands.value = frame.commands
                _commandsResolved.value = frame.commandsResolved
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
            }
            is ServerFrame.SessionRemoved -> {
                _sessions.update { it.filterNot { s -> s.id == frame.id } }
                _bgTasks.update { it - frame.id }
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
            // Out of M1/M3 scope — reduced in later milestones: agent_error, finish_job, session_git,
            // display_*, lsp_* (see AppViewModel for the full reducer).
            else -> {}
        }
    }

    // ── Viewing presence ───────────────────────────────────────────────────────────

    /** Report the foreground chat (`null` = the session list) + whether the app is visible.
     *  Deduped; (lazily) starts the keep-alive heartbeat. */
    fun updateViewing(session: String?, visible: Boolean) {
        viewingSession = session
        viewingVisible = visible
        sendViewingIfChanged()
        ensureViewingHeartbeat()
    }

    private fun sendViewingIfChanged() {
        val next = viewingSession to viewingVisible
        if (lastSentViewing == next) return
        lastSentViewing = next
        stateScope.launch {
            runApi("viewing send") { sendFrame(ClientFrame.Viewing(viewingSession, viewingVisible)) }
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
                    runApi("viewing heartbeat") { sendFrame(ClientFrame.Viewing(viewingSession, true)) }
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

    // ── Editor filesystem + lifecycle (M3; mirrors AppViewModel.fsList/fsRead/fsWrite/fsSearch
    //    + editorOpen/editorClose) ─────────────────────────────────────────────────────
    // The EditorPanel binds these to path-only lambdas capturing the session, exactly as Android's
    // ChatScreen binds the AppViewModel wrappers. All broker calls run through [runApi] EXCEPT
    // [fsRead] (see its note — it must preserve the FsException message for the editor's error UI).

    /** GET /sessions/<id>/fs → directory listing (workdir-relative). Empty on any failure. */
    suspend fun fsList(session: SessionInfo, path: String): List<FsEntry> =
        runApi("fsList") { api.fsList(session.id, path) } ?: emptyList()

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

    /** Start the broker fs-watcher for this session (so fs_changed fires → the stale banner works).
     *  Sent on EditorPanel mount; the [editorClose] counterpart stops it on dispose. */
    fun editorOpen(session: SessionInfo) {
        stateScope.launch { runApi("editorOpen") { sendFrame(ClientFrame.EditorOpen(session.id)) } }
    }

    fun editorClose(session: SessionInfo) {
        stateScope.launch { runApi("editorClose") { sendFrame(ClientFrame.EditorClose(session.id)) } }
    }

    // ── Lazy transcript load ─────────────────────────────────────────────────────────

    private suspend fun archivedLogs(sessionId: String): List<LogEntry> =
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

    /** Stop all owned coroutines (collector, WS run-loop, heartbeat, in-flight ops) and release
     *  the shared HttpClient (WS + HTTP). Counterpart of AppViewModel.onCleared, plus the explicit
     *  scope cancel a plain (non-ViewModel) class needs. */
    fun close() {
        stateScope.cancel()
        http.close()
    }
}
