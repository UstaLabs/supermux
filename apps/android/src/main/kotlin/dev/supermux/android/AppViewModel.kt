package dev.supermux.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.supermux.net.AppConfigDto
import dev.supermux.net.ArchivedDto
import dev.supermux.net.BrokerApi
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.BrokerClient
import dev.supermux.net.DeviceDto
import dev.supermux.net.DisplayStream
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import dev.supermux.net.ModelsResponse
import dev.supermux.net.PathValidation
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.SpawnRequest
import dev.supermux.net.TerminalClient
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModel(private val baseUrl: String, private val token: String) : ViewModel() {
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
                    }
                    is ServerFrame.CommandsChanged -> {
                        _commands.value = _commands.value.toMutableMap().apply {
                            this[f.session] = f.commands
                        }
                    }
                    else -> {}
                }
            }
        }
        viewModelScope.launch { client.run() }
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
    suspend fun usage(): String? = runCatching { api.usageRaw() }.getOrNull()
    suspend fun curatorSettings(): CuratorSettingsResponse? = runCatching { api.getCuratorSettings() }.getOrNull()
    suspend fun saveCurator(enabled: Boolean, hour: Int, minute: Int): CuratorSettingsResponse? =
        runCatching { api.saveCuratorSettings(enabled, hour, minute) }.getOrNull()
    suspend fun runCuratorNow() { runCatching { api.runCuratorNow() } }
    suspend fun devices(): List<DeviceDto> = runCatching { api.devices() }.getOrNull() ?: emptyList()
    fun revoke(n: String) { viewModelScope.launch { runCatching { api.revokeDevice(n) } } }
    suspend fun archived(): List<ArchivedDto> = runCatching { api.archived() }.getOrNull() ?: emptyList()
    fun resume(id: String) { viewModelScope.launch { runCatching { api.resume(id) } } }

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
