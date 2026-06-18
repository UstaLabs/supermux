package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SlashCommand
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── DTOs ────────────────────────────────────────────────────────────────────

@Serializable
data class AppConfigDto(
    val paName: String = "",
    val paWorkdir: String = "",
    val webPublicUrl: String = "",
    val telegramConfigured: Boolean = false,
    val claudeConfigured: Boolean = false,
    val anthropicConfigured: Boolean = false,
    val codexConfigured: Boolean = false,
    val cursorConfigured: Boolean = false,
    val onboarded: Boolean = false,
)

@Serializable
data class CuratorConfig(
    val enabled: Boolean = false,
    val hour: Int = 1,
    val minute: Int = 0,
)

@Serializable
data class CuratorSettingsResponse(
    val config: CuratorConfig = CuratorConfig(),
    val nextRun: String? = null,
)

@Serializable
data class DeviceDto(
    val name: String,
    val created_at: String? = null,
    val last_seen_at: String? = null,
)

@Serializable
data class ArchivedDto(
    val id: String,
    val name: String,
    val workdir: String = "",
    val agent: String = "claude",
    val killed_at: String? = null,
)

@Serializable
data class ModelInfo(val id: String, val displayName: String)

@Serializable
data class ModelsResponse(
    val agent: String,
    val current: String? = null,
    val models: List<ModelInfo> = emptyList(),
)

/** GET /models?agent= → models pickable in the launcher (no session yet). */
@Serializable
data class LauncherModels(val models: List<ModelInfo> = emptyList())

@Serializable
data class ReasoningLevel(val id: String, val description: String? = null)

@Serializable
data class ReasoningResponse(
    val agent: String,
    val current: String? = null,
    val levels: List<ReasoningLevel> = emptyList(),
    val visible: Boolean = true,
)

@Serializable
data class SpawnRequest(
    val workdir: String,
    val name: String? = null,
    val agent: String? = null,
    val model: String? = null,
)

@Serializable
data class SpawnResponse(
    val id: String = "",
    val name: String,
    val workdir: String,
    val agent: String,
    val model: String? = null,
)

@Serializable
data class UploadResponse(
    val file_id: String,
    val size: Long = 0,
    val mime: String = "",
    val name: String = "",
)

@Serializable
data class ProxyDto(
    val domain: String,
    val sessionName: String = "",
    val port: Int = 0,
    val createdAt: String? = null,
    val isPublic: Boolean = false,
    /// Canonical URL built by the broker (subdomain or /p/<slug>/ sub-path).
    val url: String? = null,
)

@Serializable
data class CreateProxyResponse(
    val url: String = "",
    val domain: String = "",
    val port: Int = 0,
)

/** POST /devices → one-time pairing URL for a freshly minted device token. */
@Serializable
data class AddDeviceResponse(val url: String = "", val name: String = "")

// ─── Usage (GET /usage → fetchAllUsage) ──────────────────────────────────────
// `resetsAt` is an ISO string for Claude but epoch-seconds for Codex, so each
// provider gets its own window type (kotlinx.serialization can't union them).
@Serializable
data class ClaudeWindow(val used: Double = 0.0, val resetsAt: String? = null)

@Serializable
data class ClaudeExtraUsage(
    val enabled: Boolean = false,
    val monthlyLimit: Double = 0.0,
    val usedCredits: Double = 0.0,
    val currency: String = "",
)

@Serializable
data class ClaudeUsage(
    val fiveHour: ClaudeWindow = ClaudeWindow(),
    val sevenDay: ClaudeWindow = ClaudeWindow(),
    val sevenDaySonnet: ClaudeWindow = ClaudeWindow(),
    val extraUsage: ClaudeExtraUsage? = null,
)

@Serializable
data class CodexWindow(val used: Double = 0.0, val resetsAt: Double? = null)

@Serializable
data class CodexCredits(val hasCredits: Boolean = false, val balance: String = "")

@Serializable
data class CodexUsage(
    val plan: String = "",
    val primaryWindow: CodexWindow = CodexWindow(),
    val secondaryWindow: CodexWindow = CodexWindow(),
    val credits: CodexCredits? = null,
    val limitReached: Boolean = false,
)

@Serializable
data class CursorUsage(
    val totalPercentUsed: Double = 0.0,
    val totalSpendCents: Double = 0.0,
    val includedCents: Double = 0.0,
    val limitCents: Double = 0.0,
    val billingCycleStart: String? = null,
    val billingCycleEnd: String? = null,
)

@Serializable
data class OpenCodeUsage(
    val sessions: Int = 0,
    val messages: Int = 0,
    val totalCostUsd: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
)

@Serializable
data class UsageResponse(
    val claude: ClaudeUsage? = null,
    val codex: CodexUsage? = null,
    val cursor: CursorUsage? = null,
    val opencode: OpenCodeUsage? = null,
    val errors: Map<String, String> = emptyMap(),
)

// ─── Git status + finish (chat header) ───────────────────────────────────────
@Serializable
data class GitRemoteStatus(
    val isRepo: Boolean = false,
    val hasRemote: Boolean = false,
    val branch: String? = null,
    val detachedSha: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
)

/** Flat result for git push/pull/fetch/publish — `status` discriminates. */
@Serializable
data class GitOpResult(
    val status: String = "",
    val message: String? = null,
    val files: List<String> = emptyList(),
)

/** Flat result for POST /sessions/<id>/finish — `status` discriminates the 9 variants. */
@Serializable
data class FinishResult(
    val status: String = "",
    val base: String? = null,
    val branch: String? = null,
    val mergedSha: String? = null,
    val verified: String? = null,
    val files: List<String> = emptyList(),
    val command: String? = null,
    val output: String? = null,
    val message: String? = null,
)

// ─── Personal Assistants (GET/POST /api/pas) ─────────────────────────────────
@Serializable
data class PADto(
    val id: String,
    val name: String,
    val workdir: String = "",
    val mute: Boolean = false,
    val connected: Boolean = false,
    val agent: String? = null,
    val model: String? = null,
    val role: String? = null,
    val isDefault: Boolean = false,
    val status: String? = null,
)

@Serializable
data class PAListResponse(val pas: List<PADto> = emptyList())

@Serializable
data class ProjectEntry(val path: String)

@Serializable
data class ProjectsResponse(val projects: List<ProjectEntry> = emptyList())

@Serializable
data class LauncherCommands(
    val commands: List<SlashCommand> = emptyList(),
    val resolved: Boolean = false,
)

@Serializable
data class PathValidation(val ok: Boolean = false, val path: String? = null, val error: String? = null)

@Serializable
data class FsEntry(
    val name: String,
    val type: String,            // "dir" | "file"
    val size: Long = 0,
    val modified: String? = null,
    val ignored: Boolean = false,
)

@Serializable
data class FsSearchResult(
    val path: String,
    val name: String,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class TerminalSummary(val id: String, val createdAt: Long)

@Serializable
data class TerminalListResponse(val terminals: List<TerminalSummary> = emptyList())

@Serializable
data class TermCloseBody(val session: String, val terminal: String)

@Serializable
data class DisplayStream(
    val id: String,
    val sessionName: String = "",
    val provider: String = "",
    val transport: String = "",     // "vnc" | "h264"
    val display: String = "",
    val status: String = "",        // "running" | "errored"
    val createdAt: String? = null,
)

// ─── Exceptions ────────────────────────────────────────────────────────────────

class FsException(val status: Int, message: String) : Exception(message)

// ─── Private request bodies ───────────────────────────────────────────────────

@Serializable
private data class ModelBody(val model: String)

@Serializable
private data class ReasoningLevelBody(val reasoningLevel: String)

@Serializable
private data class RenameBody(val name: String)

@Serializable
private data class MuteBody(val muted: Boolean)

@Serializable
private data class PaNameBody(val paName: String)

@Serializable
private data class CreateProxyBody(val sessionName: String, val port: Int, val domain: String? = null)

@Serializable
private data class SetProxyPublicBody(val isPublic: Boolean)

@Serializable
private data class AddDeviceBody(val name: String)

@Serializable
private data class FinishBody(
    val skipVerify: Boolean? = null, val commitFirst: Boolean? = null, val commitMessage: String? = null,
)

@Serializable
private data class MessageBody(val text: String)

@Serializable
private data class CreatePABody(
    val name: String, val agent: String? = null, val model: String? = null, val focusText: String? = null,
)

@Serializable
private data class PathBody(val path: String)

@Serializable
private data class StartDisplayBody(
    val sessionName: String,
    val provider: String? = null,
    val device: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

// ─── Client ──────────────────────────────────────────────────────────────────

/**
 * Thin REST wrapper around the Supermux broker's session-control endpoints.
 *
 * JSON encoding/decoding is done explicitly via [json] rather than relying on
 * ContentNegotiation being installed on the caller's [HttpClient]. This makes
 * [BrokerApi] self-contained: any HttpClient with only the core engine works.
 */
class BrokerApi(
    baseUrl: String,
    private val token: String,
    private val http: HttpClient,
) {
    internal val httpBase: String = baseUrl
        .replaceFirst("ws://", "http://")
        .replaceFirst("wss://", "https://")
        .trimEnd('/')

    private val json = Json { ignoreUnknownKeys = true }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun bearerHeader() = "Bearer $token"

    private suspend inline fun <reified T> getJson(url: String): T {
        val text = http.get(url) {
            header("Authorization", bearerHeader())
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    private suspend inline fun <reified B> postJson(url: String, body: B) {
        http.post(url) {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }
    }

    private suspend inline fun <reified B> putJson(url: String, body: B) {
        http.put(url) {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }
    }

    private suspend inline fun <reified B> patchJson(url: String, body: B) {
        http.patch(url) {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        }
    }

    private fun urlEncode(s: String): String = s
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("+", "%2B")
        .replace(" ", "%20")

    // ── public API ───────────────────────────────────────────────────────────

    /** GET /sessions/<id>/models */
    suspend fun models(id: String): ModelsResponse =
        getJson("$httpBase/sessions/$id/models")

    /** GET /models?agent= — models for the launcher (no session). */
    suspend fun listModels(agent: String): LauncherModels =
        getJson("$httpBase/models?agent=${urlEncode(agent)}")

    /** GET /commands/preview?agent=&workdir= — agent slash commands for the launcher (no session). */
    suspend fun previewCommands(agent: String, workdir: String): LauncherCommands =
        getJson("$httpBase/commands/preview?agent=${urlEncode(agent)}&workdir=${urlEncode(workdir)}")

    /** GET /api/term/list?session= — scratch terminals (source of truth = tmux). */
    suspend fun listTerminals(session: String): List<TerminalSummary> =
        getJson<TerminalListResponse>("$httpBase/api/term/list?session=${urlEncode(session)}").terminals

    /** POST /api/term/close {"session","terminal"} — destroy one scratch terminal. */
    suspend fun closeTerminal(session: String, terminal: String) =
        postJson("$httpBase/api/term/close", TermCloseBody(session, terminal))

    /** POST /sessions/<id>/model {"model": ...} */
    suspend fun switchModel(id: String, model: String) =
        postJson("$httpBase/sessions/$id/model", ModelBody(model))

    /** GET /sessions/<id>/reasoning-levels */
    suspend fun reasoningLevels(id: String): ReasoningResponse =
        getJson("$httpBase/sessions/$id/reasoning-levels")

    /** POST /sessions/<id>/reasoning-level {"reasoningLevel": ...} */
    suspend fun switchReasoning(id: String, level: String) =
        postJson("$httpBase/sessions/$id/reasoning-level", ReasoningLevelBody(level))

    /** POST /sessions/<id>/rename {"name": ...} */
    suspend fun rename(id: String, name: String) =
        postJson("$httpBase/sessions/$id/rename", RenameBody(name))

    /** POST /sessions/<id>/mute {"muted": ...} */
    suspend fun setMute(id: String, muted: Boolean) =
        postJson("$httpBase/sessions/$id/mute", MuteBody(muted))

    /** DELETE /sessions/<id> */
    suspend fun kill(id: String) {
        http.delete("$httpBase/sessions/$id") {
            header("Authorization", bearerHeader())
        }
    }

    /** POST /sessions */
    suspend fun spawn(req: SpawnRequest): SpawnResponse {
        val text = http.post("$httpBase/sessions") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(req))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** GET /settings/config */
    suspend fun getConfig(): AppConfigDto =
        getJson("$httpBase/settings/config")

    /** PUT /settings/config {"paName": ...} */
    suspend fun putConfig(paName: String) =
        putJson("$httpBase/settings/config", PaNameBody(paName))

    /** GET /settings/curator → {config:{enabled,hour,minute}, nextRun} */
    suspend fun getCuratorSettings(): CuratorSettingsResponse =
        getJson("$httpBase/settings/curator")

    /** PUT /settings/curator {enabled,hour,minute} → updated {config, nextRun} */
    suspend fun saveCuratorSettings(enabled: Boolean, hour: Int, minute: Int): CuratorSettingsResponse {
        val text = http.put("$httpBase/settings/curator") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CuratorConfig(enabled, hour, minute)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** POST /settings/curator/run-now */
    suspend fun runCuratorNow() {
        http.post("$httpBase/settings/curator/run-now") {
            header("Authorization", bearerHeader())
        }
    }

    /** GET /usage → raw JSON string */
    suspend fun usageRaw(): String =
        http.get("$httpBase/usage") {
            header("Authorization", bearerHeader())
        }.bodyAsText()

    /** GET /usage → typed per-provider usage (Claude / Codex / Cursor / opencode) */
    suspend fun usage(): UsageResponse = getJson("$httpBase/usage")

    /** GET /devices */
    suspend fun devices(): List<DeviceDto> =
        getJson("$httpBase/devices")

    /** POST /devices {name} → { url, name }: a one-time pairing URL for the device */
    suspend fun addDevice(name: String): AddDeviceResponse {
        val text = http.post("$httpBase/devices") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AddDeviceBody(name)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** DELETE /devices/<urlencoded name> */
    suspend fun revokeDevice(name: String) {
        http.delete("$httpBase/devices/${urlEncode(name)}") {
            header("Authorization", bearerHeader())
        }
    }

    /** GET /archived-sessions */
    suspend fun archived(): List<ArchivedDto> =
        getJson("$httpBase/archived-sessions")

    /** POST /sessions/<id>/resume */
    suspend fun resume(id: String) {
        http.post("$httpBase/sessions/$id/resume") {
            header("Authorization", bearerHeader())
        }
    }

    /** POST /sessions/<id>/interrupt — soft-stop the running agent */
    suspend fun interrupt(id: String) {
        http.post("$httpBase/sessions/$id/interrupt") {
            header("Authorization", bearerHeader())
        }
    }

    /** GET /sessions/<id>/git/status */
    suspend fun gitStatus(id: String): GitRemoteStatus =
        getJson("$httpBase/sessions/$id/git/status")

    private suspend fun gitOp(id: String, op: String): GitOpResult {
        val text = http.post("$httpBase/sessions/$id/git/$op") {
            header("Authorization", bearerHeader())
        }.bodyAsText()
        return json.decodeFromString(text)
    }
    suspend fun gitFetch(id: String): GitOpResult = gitOp(id, "fetch")
    suspend fun gitPublish(id: String): GitOpResult = gitOp(id, "publish")
    suspend fun gitPush(id: String): GitOpResult = gitOp(id, "push")
    suspend fun gitPull(id: String): GitOpResult = gitOp(id, "pull")

    /** POST /sessions/<id>/finish — sync → verify → merge the session branch. */
    suspend fun finish(
        id: String, skipVerify: Boolean? = null, commitFirst: Boolean? = null, commitMessage: String? = null,
    ): FinishResult {
        val text = http.post("$httpBase/sessions/$id/finish") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FinishBody(skipVerify, commitFirst, commitMessage)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** POST /sessions/<id>/message — post a message to the agent (e.g. a "Send to agent" fix request). */
    suspend fun sendMessage(id: String, text: String) {
        http.post("$httpBase/sessions/$id/message") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MessageBody(text)))
        }
    }

    /** GET /api/pas — the personal assistants. */
    suspend fun listPAs(): List<PADto> = getJson<PAListResponse>("$httpBase/api/pas").pas

    /** POST /api/pas {name, agent?, model?, focusText?} — spawn a personal assistant. */
    suspend fun createPA(name: String, agent: String? = null, model: String? = null, focusText: String? = null) =
        postJson("$httpBase/api/pas", CreatePABody(name, agent, model, focusText))

    /** GET /proxies */
    suspend fun proxies(): List<ProxyDto> =
        getJson("$httpBase/proxies")

    /** POST /proxies {sessionName, port, domain?} */
    suspend fun createProxy(sessionName: String, port: Int, domain: String? = null): CreateProxyResponse {
        val text = http.post("$httpBase/proxies") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateProxyBody(sessionName, port, domain)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** PATCH /proxies/<domain> {isPublic} */
    suspend fun setProxyPublic(domain: String, isPublic: Boolean) =
        patchJson("$httpBase/proxies/${urlEncode(domain)}", SetProxyPublicBody(isPublic))

    /** DELETE /proxies/<domain> */
    suspend fun removeProxy(domain: String) {
        http.delete("$httpBase/proxies/${urlEncode(domain)}") {
            header("Authorization", bearerHeader())
        }
    }

    /** POST /upload — multipart {file, session, kind?} */
    suspend fun upload(
        session: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        kind: String? = null,
    ): UploadResponse {
        val resp = http.post("$httpBase/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("session", session)
                if (kind != null) append("kind", kind)
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                })
            }))
        }
        return json.decodeFromString(resp.bodyAsText())
    }

    /** Upload from a base64 payload (iOS hands us `Data` as base64 — fast Kotlin decode). */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun uploadBase64(
        session: String, base64: String, filename: String, mime: String, kind: String? = null,
    ): UploadResponse =
        upload(session, kotlin.io.encoding.Base64.decode(base64), filename, mime, kind)

    /** GET /files/<urlencoded file_id> — raw bytes of a stored attachment. */
    suspend fun fileBytes(fileId: String): ByteArray? {
        val resp = http.get("$httpBase/files/${urlEncode(fileId)}") {
            header("Authorization", bearerHeader())
        }
        return if (resp.status.isSuccess()) resp.bodyAsBytes() else null
    }

    /** GET /sessions/<id>/messages — the message log for a (possibly archived) session. */
    suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        getJson("$httpBase/sessions/$sessionId/messages")

    /** GET /projects → known project working directories (absolute paths). */
    suspend fun listProjects(): List<String> =
        getJson<ProjectsResponse>("$httpBase/projects").projects.map { it.path }

    /** POST /paths/validate {path} → {ok, path?, error?}. Resolves ~ and checks existence. */
    suspend fun validatePath(path: String): PathValidation {
        val text = http.post("$httpBase/paths/validate") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PathBody(path)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    // ── Editor filesystem ──────────────────────────────────────────────────────

    /** GET /sessions/<id>/fs?path=<rel> → directory listing relative to the workdir. */
    suspend fun fsList(sessionId: String, path: String): List<FsEntry> =
        getJson("$httpBase/sessions/$sessionId/fs?path=${urlEncode(path)}")

    /** GET /sessions/<id>/fs/read?path=<rel> → file text. Throws FsException on non-2xx (413 too large / 415 binary). */
    suspend fun fsRead(sessionId: String, path: String): String {
        val resp = http.get("$httpBase/sessions/$sessionId/fs/read?path=${urlEncode(path)}") {
            header("Authorization", bearerHeader())
        }
        if (!resp.status.isSuccess()) {
            val body = resp.bodyAsText()
            throw FsException(resp.status.value, body.ifBlank { "read failed (${resp.status.value})" })
        }
        return resp.bodyAsText()
    }

    /** PUT /sessions/<id>/fs/write?path=<rel> (text/plain body) → true on success. */
    suspend fun fsWrite(sessionId: String, path: String, content: String): Boolean {
        val resp = http.put("$httpBase/sessions/$sessionId/fs/write?path=${urlEncode(path)}") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Text.Plain)
            setBody(content)
        }
        return resp.status.isSuccess()
    }

    /** GET /sessions/<id>/fs/search?q=<query> → filename matches relative to the workdir. */
    suspend fun fsSearch(sessionId: String, q: String): List<FsSearchResult> =
        getJson("$httpBase/sessions/$sessionId/fs/search?q=${urlEncode(q)}")

    // ── Displays ─────────────────────────────────────────────────────────────────

    /** GET /displays → active display streams. */
    suspend fun listDisplays(): List<DisplayStream> =
        getJson("$httpBase/displays")

    /** POST /displays {sessionName, provider?, device?, width?, height?} → the started stream. */
    suspend fun startDisplay(sessionName: String, provider: String? = null, device: String? = null, width: Int? = null, height: Int? = null): DisplayStream {
        val text = http.post("$httpBase/displays") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StartDisplayBody(sessionName, provider, device, width, height)))
        }.bodyAsText()
        return json.decodeFromString(text)
    }

    /** DELETE /displays/<id> */
    suspend fun stopDisplay(id: String) {
        http.delete("$httpBase/displays/${urlEncode(id)}") {
            header("Authorization", bearerHeader())
        }
    }
}
