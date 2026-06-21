package dev.supermux.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.BrokerClient
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
import dev.supermux.net.FinishReadiness
import dev.supermux.net.ForgeConnectionsResponse
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
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.SpawnRequest
import dev.supermux.net.TerminalClient
import dev.supermux.net.UpdateStatus
import dev.supermux.net.VerifySaveResult
import dev.supermux.net.VerifySuggestResult
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

class AppViewModel(private val baseUrl: String, private val token: String) : ViewModel() {
    companion object {
        /** Factory so the VM can be Activity-scoped via viewModel(factory = …) and survive config changes. */
        fun factory(baseUrl: String, token: String) = viewModelFactory {
            initializer { AppViewModel(baseUrl, token) }
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
                    is ServerFrame.SessionAdded -> _sessions.value = _sessions.value + f.session
                    is ServerFrame.SessionRemoved -> _sessions.value = _sessions.value.filterNot { it.id == f.id }
                    is ServerFrame.MessageAppend -> {
                        _messages.value = _messages.value.toMutableMap().apply {
                            this[f.session] = (this[f.session] ?: emptyList()) + f.entry
                        }
                    }
                    is ServerFrame.ActivityAppend -> {
                        _activity.value = _activity.value.toMutableMap().apply {
                            this[f.session] = (this[f.session] ?: emptyList()) + f.event
                        }
                    }
                    is ServerFrame.AgentState -> {
                        _agentState.value = _agentState.value.toMutableMap().apply {
                            this[f.session] = AgentStatus(f.phase, f.since ?: f.workingSince)
                        }
                        // Clear a prior agent error once the agent leaves the error phase.
                        if (f.phase != "error" && _agentErrors.value.containsKey(f.session)) {
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

    fun send(sessionId: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { client.send(ClientFrame.Send(sessionId, args = SendArgs(text))) }
    }

    fun connectTerminal(sessionId: String): TerminalClient =
        TerminalClient(baseUrl, token, http, sessionId)

    suspend fun listDisplays(): List<DisplayStream> =
        runCatching { api.listDisplays() }.getOrNull() ?: emptyList()
    fun connectScrcpy(streamId: String): ScrcpyClient =
        ScrcpyClient(baseUrl, token, http, streamId)

    suspend fun upload(
        sessionId: String,
        bytes: ByteArray,
        name: String,
        mime: String,
        kind: String? = null,
    ): String? = runCatching { api.upload(sessionId, bytes, name, mime, kind).file_id }.getOrNull()

    // ── Voice dictation ──────────────────────────────────────────────────────────

    /** Whisper path: multipart audio → cleaned text. Returns null on failure (caller keeps draft). */
    suspend fun transcribeAudio(sessionId: String, bytes: ByteArray, filename: String): String? =
        runCatching { api.transcribeAudio(sessionId, bytes, filename).text }.getOrNull()

    /** On-device-STT path: JSON draft → cleaned text. Returns null on failure. */
    suspend fun transcribeDraft(sessionId: String, draft: String): String? =
        runCatching { api.transcribeDraft(sessionId, draft).text }.getOrNull()

    fun sendWith(sessionId: String, text: String, attachments: List<String>) {
        viewModelScope.launch {
            runCatching {
                client.send(ClientFrame.Send(sessionId, args = SendArgs(text, attachments.ifEmpty { null })))
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

    /** Create a session then queue the first message for [ChatScreen] to send on open. */
    suspend fun createSessionWithFirstMessage(
        workdir: String,
        agent: String,
        model: String?,
        text: String,
        attachments: List<String> = emptyList(),
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

    /** GET /models?agent= — Claude models for the voice cleanup picker (no session). */
    suspend fun launcherModels(agent: String): List<ModelInfo> =
        runCatching { api.listModels(agent).models }.getOrNull() ?: emptyList()

    /** PUT /settings/config { voiceCleanupModel }. null/"" → broker default (Haiku). */
    fun saveVoiceCleanupModel(model: String?) {
        viewModelScope.launch { runCatching { api.saveConfig(voiceCleanupModel = model?.ifBlank { null }) } }
    }

    /** Voice-cleanup glossary (shared across devices). */
    suspend fun fetchGlossary(): List<String> = runCatching { api.fetchGlossary() }.getOrNull() ?: emptyList()
    suspend fun updateGlossary(terms: List<String>): List<String>? =
        runCatching { api.updateGlossary(terms) }.getOrNull()
    suspend fun usage(): String? = runCatching { api.usageRaw() }.getOrNull()
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

    // ── Editor filesystem ──────────────────────────────────────────────────────

    suspend fun fsList(sessionId: String, path: String): List<FsEntry> =
        runCatching { api.fsList(sessionId, path) }.getOrNull() ?: emptyList()
    suspend fun fsRead(sessionId: String, path: String): Result<String> =
        runCatching { api.fsRead(sessionId, path) }
    suspend fun fsWrite(sessionId: String, path: String, content: String): Boolean =
        runCatching { api.fsWrite(sessionId, path, content) }.getOrDefault(false)
    suspend fun fsSearch(sessionId: String, q: String): List<FsSearchResult> =
        runCatching { api.fsSearch(sessionId, q) }.getOrNull() ?: emptyList()

    suspend fun listProjects(): List<String> = runCatching { api.listProjects() }.getOrNull() ?: emptyList()
    suspend fun validatePath(path: String): PathValidation? = runCatching { api.validatePath(path) }.getOrNull()

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
