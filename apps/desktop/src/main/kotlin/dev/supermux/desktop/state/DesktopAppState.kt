// Ported from apps/android/.../AppViewModel.kt (M1 scope) — keep reducer semantics in sync.
//
// This is the LOGIC CORE of the desktop client: it wires the shared BrokerClient (WS) and
// BrokerApi (HTTP) and reduces inbound ServerFrames into StateFlows the Compose UI observes.
// Only the Milestone-1 surface is ported here — sessions / messages / activity / agentState /
// bgTasks / commands + the send/viewing/control paths. Out-of-scope frames (finish, git, LSP,
// displays, fs) and features (uploads beyond Send args, dictation, models/reasoning, drafts,
// push, notifications) are deliberately no-op'd with a milestone marker so the reducer stays a
// faithful subset of AppViewModel's `when (frame)`.
package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 */
class DesktopAppState(
    val baseUrl: String,
    token: String,
    private val scope: CoroutineScope,
    connectOnInit: Boolean = true,
    sendFrameOverride: (suspend (ClientFrame) -> Unit)? = null,
) {
    private val http = HttpClient(CIO) { install(WebSockets) }
    val client = BrokerClient(baseUrl, token, http)
    val api = BrokerApi(baseUrl, token, http)
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

    /** Whether the client has a fresh snapshot from the broker (i.e. we're synced/connected). */
    val connected: Boolean get() = client.sync.synced

    init {
        if (connectOnInit) {
            scope.launch { client.frames.collect { reduce(it) } }
            scope.launch { client.run() }
            ensureViewingHeartbeat()
        }
    }

    // ── ServerFrame reducer (ported subset of AppViewModel's when(frame)) ──────────

    /** Fold one inbound frame into the StateFlows. Public for reducer tests. */
    fun reduce(frame: ServerFrame) {
        when (frame) {
            is ServerFrame.Snapshot -> {
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
            }
            is ServerFrame.SessionRemoved -> {
                _sessions.value = _sessions.value.filterNot { it.id == frame.id }
                _bgTasks.update { it - frame.id }
            }
            is ServerFrame.MessageAppend -> {
                // Optimistic-echo dedup (iOS BrokerSession parity): when the real inbound message
                // lands, drop the matching local-… placeholder we appended on send.
                _messages.value = _messages.value.toMutableMap().apply {
                    val prev = this[frame.session] ?: emptyList()
                    val pruned = if (frame.entry.direction.startsWith("in")) {
                        prev.filterNot { it.id.startsWith("local-") && it.text == frame.entry.text }
                    } else prev
                    this[frame.session] = pruned + frame.entry
                }
            }
            is ServerFrame.ActivityAppend -> {
                _activity.value = _activity.value.toMutableMap().apply {
                    this[frame.session] = (this[frame.session] ?: emptyList()) + frame.event
                }
            }
            is ServerFrame.BgTasks -> {
                _bgTasks.update { it + (frame.session to frame.tasks) }
            }
            is ServerFrame.AgentState -> {
                _agentState.value = _agentState.value.toMutableMap().apply {
                    this[frame.session] = AgentStatus(
                        phase = frame.phase, state = frame.state, working = frame.working,
                        detail = frame.detail, tool = frame.tool, since = frame.since,
                        workingSince = frame.workingSince, waiting = frame.waiting, bgOpen = frame.bgOpen,
                    )
                }
                _pendingSend.update { it - frame.session }   // first real state clears the client-local "Sending…"
            }
            is ServerFrame.CommandsChanged -> {
                _commands.value = _commands.value.toMutableMap().apply {
                    this[frame.session] = frame.commands
                }
                _commandsResolved.update { it + (frame.session to frame.resolved) }
            }
            // Out of M1 scope — reduced in later milestones: agent_error, finish_job, session_git,
            // fs_changed, display_*, lsp_* (see AppViewModel for the full reducer).
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
        scope.launch { runCatching { sendFrame(ClientFrame.Viewing(viewingSession, viewingVisible)) } }
    }

    /** Re-assert the viewing frame every 60s so the broker's 5-min TTL never lapses while the user
     *  reads a long, quiet turn. Only refreshes while visible; [scope] cancels it on close. */
    private fun ensureViewingHeartbeat() {
        if (viewingHeartbeat?.isActive == true) return
        viewingHeartbeat = scope.launch {
            while (isActive) {
                delay(60_000)
                if (viewingVisible) runCatching { sendFrame(ClientFrame.Viewing(viewingSession, true)) }
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

    /** Send a reply over the WS (ClientFrame.Send op="reply"); optionally with attachment ids. */
    fun sendMessage(sessionId: String, text: String, attachments: List<String> = emptyList()) {
        if (text.isBlank() && attachments.isEmpty()) return
        appendLocalEcho(sessionId, text.trim())
        scope.launch {
            runCatching {
                sendFrame(ClientFrame.Send(sessionId, args = SendArgs(text, attachments.ifEmpty { null })))
                markPendingSend(sessionId)
            }
        }
    }

    // ── Session controls (HTTP via BrokerApi) ───────────────────────────────────────

    /** Soft-stop the running agent (POST /sessions/<id>/interrupt). */
    fun interrupt(id: String) { scope.launch { runCatching { api.interrupt(id) } } }
    fun rename(id: String, name: String) { scope.launch { runCatching { api.rename(id, name) } } }
    fun setMute(id: String, muted: Boolean) { scope.launch { runCatching { api.setMute(id, muted) } } }
    fun kill(id: String, onDone: () -> Unit = {}) { scope.launch { runCatching { api.kill(id) }; onDone() } }

    // ── Lazy transcript load ─────────────────────────────────────────────────────────

    private suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        runCatching { api.archivedLogs(sessionId) }.getOrNull() ?: emptyList()

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
        scope.launch {
            val fetched = archivedLogs(sessionId)
            // Re-check after the await: a live MessageAppend / optimistic send / fresh snapshot may
            // have populated the buffer while the fetch was in flight — don't clobber it.
            if (fetched.isNotEmpty() && _messages.value[sessionId]?.isNotEmpty() != true) {
                _messages.update { it + (sessionId to fetched) }
            }
        }
    }

    /** Release the shared HttpClient (WS + HTTP). Mirrors AppViewModel.onCleared. */
    fun close() { http.close() }
}
