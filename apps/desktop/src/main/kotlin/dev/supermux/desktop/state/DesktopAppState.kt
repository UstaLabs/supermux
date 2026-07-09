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

import dev.supermux.desktop.session.StagedUpload
import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
import dev.supermux.net.ChunkSource
import dev.supermux.net.FinishReadiness
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ModelInfo
import dev.supermux.net.PathValidation
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.RepoInfo
import dev.supermux.net.SpawnRequest
import dev.supermux.net.SpawnResponse
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalSummary
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
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

    // ── Finish flow (M4b) ──────────────────────────────────────────────────────────
    // The last/in-flight finish job per session, keyed by session id (Android AppViewModel
    // parity). Seeded from each SessionInfo.finish_job in the Snapshot and kept current by the
    // FinishJobFrame reducer; the FinishDialog drives its 3-state machine (menu/running/outcome)
    // off this flow. clearFinishJob drops an entry client-side once the user dismisses the outcome.
    private val _finishJobs = MutableStateFlow<Map<String, FinishJobDto>>(emptyMap())
    val finishJobs: StateFlow<Map<String, FinishJobDto>> = _finishJobs

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
            // Out of M1/M3/M4b scope — reduced in later milestones: agent_error, display_*, lsp_*
            // (see AppViewModel for the full reducer). Deferred to M4g/M5; they must still not crash.
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
     *  The broker keeps its record — this only drops the local overlay so the dialog closes. */
    fun clearFinishJob(id: String) { _finishJobs.update { it - id } }

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

    // ── New-session launcher + spawn (M4a; mirrors AppViewModel.launcher* +
    //    createSessionWithFirstMessage) ────────────────────────────────────────────────
    // These back the SessionLauncherScreen (M4a Task 4/5). All go through [runApi] and
    // getOrNull-degrade like Android's launcher helpers — a broker hiccup yields an empty/null
    // result, never an exception the launcher UI has to catch.

    /** GET /projects → known project working directories (absolute paths). Empty on any failure. */
    suspend fun listProjects(): List<String> =
        runApi("listProjects") { api.listProjects() } ?: emptyList()

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
    suspend fun launcherRepoInfo(workdir: String): RepoInfo? =
        runApi("launcherRepoInfo") { api.getRepoInfo(workdir) }

    /** GET /commands/preview?agent=&workdir= → the agent's slash commands for the launcher (no
     *  session yet). Empty on failure OR a blank workdir (AppViewModel.launcherCommands parity —
     *  a blank workdir would 4xx, so short-circuit it). */
    suspend fun launcherCommands(agent: String, workdir: String): List<SlashCommand> =
        if (workdir.isBlank()) emptyList()
        else runApi("launcherCommands") { api.previewCommands(agent, workdir).commands } ?: emptyList()

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
    ): String? = runApi("createSessionWithFirstMessage") {
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
                agent = agent,
                model = model?.ifBlank { null },
                worktree = if (worktree) true else null,
                baseBranch = baseBranch?.ifBlank { null },
                reasoningLevel = reasoningLevel?.ifBlank { null },
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

    /** Stop all owned coroutines (collector, WS run-loop, heartbeat, in-flight ops) and release
     *  the shared HttpClient (WS + HTTP). Counterpart of AppViewModel.onCleared, plus the explicit
     *  scope cancel a plain (non-ViewModel) class needs. */
    fun close() {
        stateScope.cancel()
        http.close()
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
