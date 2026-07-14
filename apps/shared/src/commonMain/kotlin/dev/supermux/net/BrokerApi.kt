package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SlashCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─── DTOs ────────────────────────────────────────────────────────────────────

/** GET /pair.json?t=<token> → confirmed device bearer + its display name. */
@Serializable
data class PairJsonResult(val token: String = "", val name: String = "")

/** GET /me → bearer-validity probe. paired=true with the device name when the token is good. */
@Serializable
data class MeResult(val paired: Boolean = false, val device: String? = null)

/** GET /host → public broker identity (spec §3.3). `platform`/`version` are present only for
 *  an authed caller; unauthenticated pairing/adoption probes get identity fields only. */
@Serializable
data class HostIdentity(
    val hostId: String = "",
    val name: String = "",
    val protocolVersion: Int = 0,
    val platform: String? = null,
    val version: String? = null,
)

/** POST /pair/claim body — a one-time claimSecret + this device's chosen display name. */
@Serializable
data class PairClaimBody(val claimSecret: String, val deviceName: String)

/** POST /pair/claim → the minted device bearer plus the host's identity, so the client can
 *  verify `host.hostId` matches the scanned QR before persisting (spec §3.4). On a brand-new
 *  broker the trust-on-first-connect branch may omit `host`. */
@Serializable
data class PairClaimResult(
    val host: HostIdentity? = null,
    val deviceToken: String = "",
    val name: String = "",
)

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
    /** Direct-API engine for voice cleanup (codex | opencode-zen | opencode-go |
     *  cursor | claude). null = broker default (codex). */
    val voiceCleanupEngine: String? = null,
    /** Model the cleanup engine uses. null/empty = that engine's own default. */
    val voiceCleanupModel: String? = null,
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
    val repo_root: String? = null,
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
    /** Run the session in an isolated git worktree (only honored when the workdir is an eligible repo). */
    val worktree: Boolean? = null,
    /** Base branch the worktree is cut from (defaults to the repo's current branch when null). */
    val baseBranch: String? = null,
    /** Reasoning ("thinking") effort to start the session at (agent-specific; ignored when unsupported). */
    val reasoningLevel: String? = null,
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

/** POST /upload/init → a new resumable upload handle + the server-dictated chunk size. */
@Serializable
data class InitResponse(val upload_id: String, val offset: Long = 0, val chunk_size: Long = 0)

/** PATCH /upload/<id> → either the new byte offset, or (on the final chunk) the
 *  finalized attachment. `file_id != null` means the upload is complete. */
@Serializable
data class PatchResponse(
    val offset: Long? = null,
    val file_id: String? = null,
    val size: Long = 0,
    val mime: String = "",
    val name: String = "",
)

@Serializable
private data class InitRequest(
    val session: String, val mime: String, val name: String,
    val kind: String? = null, val total_size: Long,
)

/** POST /sessions/<id>/transcribe → cleaned composer text. `degraded`=true means cleanup
 *  was skipped/failed and `text` is the raw whisper draft. */
@Serializable
data class TranscribeResponse(val text: String = "", val degraded: Boolean = false)

/** GET/PUT /config/voice-glossary → { glossary: [...] }. */
@Serializable
data class GlossaryResponse(val glossary: List<String> = emptyList())

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

/** GET /me → paired status + optional relayUrl for native-push registration. */
@Serializable
data class MeResponse(
    val paired: Boolean = false,
    val device: String? = null,
    val relayUrl: String? = null,
)

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
    // Per-model weekly caps: null when Anthropic returns no such limit (row hidden).
    val sevenDaySonnet: ClaudeWindow? = null,
    val sevenDayFable: ClaudeWindow? = null,
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
    val resetCredits: Int = 0,
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

// Result of redeeming a banked Codex rate-limit reset (POST /usage/codex/reset).
// `code` ∈ reset | nothing_to_reset | no_credit | already_redeemed; `codex` is the
// refreshed usage so the card can update in place.
@Serializable
data class CodexResetResult(
    val code: String = "",
    val windowsReset: Int = 0,
    val codex: CodexUsage? = null,
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

/**
 * Flat result for a finish outcome — `status` discriminates the 15 variants
 * (integrated | pr_opened | branch_published | push_rejected | kept | discarded |
 *  push_auth_failed | nothing_to_do | sync_conflict | tests_failed | dirty_overlap |
 *  non_ff | no_verify | uncommitted | error).
 *
 * NOTE: `POST /sessions/<id>/finish` no longer returns this directly — it kicks off
 * an async finish *job* and returns a [dev.supermux.proto.FinishJobDto] (initially
 * `status:"running"`). The real outcome arrives on the WS `finish_job` frame as the
 * job's `outcome`. [FinishResult] is the shape of that outcome (and of the legacy
 * `finish()` decode, which — via ignoreUnknownKeys — sees only the job's top-level
 * `status:"running"`).
 */
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
    val prUrl: String? = null,
    val compareUrl: String? = null,
    val prError: String? = null,
    val draft: Boolean? = null,
    val cleanedUp: Boolean? = null,
)

// ─── Finish readiness + verify (chat finish menu) ────────────────────────────
/** GET /sessions/<id>/finish/readiness — preflight snapshot for the finish menu. */
@Serializable
data class FinishReadiness(
    val branch: String = "",
    val base: String = "",
    val ahead: Int = 0,
    val behind: Int = 0,
    val dirtyFiles: List<String> = emptyList(),
    val filesChanged: Int = 0,
    val insertions: Int = 0,
    val deletions: Int = 0,
    val hasRemote: Boolean = false,
    val baseHasUpstream: Boolean = false,
    val ghAvailable: Boolean = false,
    val conflictPreflight: String = "unknown", // "clean" | "will_conflict" | "unknown"
    val recommended: String = "merge",         // "merge" | "pr"
    val nothingToLand: Boolean = false,
    val prRequiresGreen: Boolean = false,
)

/** POST /sessions/<id>/verify/suggest → suggested verify command/content + its source. */
@Serializable
data class VerifySuggestResult(val content: String = "", val source: String = "")

/** POST /sessions/<id>/verify/save → { ok, reason? }. */
@Serializable
data class VerifySaveResult(val ok: Boolean = false, val reason: String? = null)

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

// ─── Repo info + worktree branches (GET /repos/info) ─────────────────────────
@Serializable
data class RepoBranches(
    val local: List<String> = emptyList(),
    val remote: List<String> = emptyList(),
)

/** GET /repos/info?path=&fetch= → git status for the launcher's worktree picker.
 *  `eligible` means the workdir is a repo we can cut an isolated worktree from. */
@Serializable
data class RepoInfo(
    val isGitRepo: Boolean = false,
    val eligible: Boolean = false,
    val repoRoot: String? = null,
    val currentBranch: String? = null,
    val branches: RepoBranches? = null,
)

// ─── Git hosting / forges (GET/POST /forge/*) ────────────────────────────────
@Serializable
data class ForgeAccount(
    val login: String = "",
    val name: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class ForgeSsh(val fingerprint: String = "", val registered: Boolean = false)

/** A configured GitHub/GitLab connection. `kind` is "github" | "gitlab". */
@Serializable
data class ForgeConnection(
    val id: String,
    val kind: String = "github",
    val host: String = "",
    val apiBase: String = "",
    val label: String = "",
    val account: ForgeAccount = ForgeAccount(),
    val source: String = "pat",        // "pat" | "cli"
    val transport: String = "https",   // "https" | "ssh"
    val ssh: ForgeSsh? = null,
    val status: String = "ok",         // "ok" | "needs_reconnect"
)

@Serializable
data class ForgeCliPresence(val available: Boolean = false, val login: String? = null)

@Serializable
data class ForgeCliStatus(
    val github: ForgeCliPresence = ForgeCliPresence(),
    val gitlab: ForgeCliPresence = ForgeCliPresence(),
)

@Serializable
data class ForgeConnectionsResponse(
    val connections: List<ForgeConnection> = emptyList(),
    val cli: ForgeCliStatus? = null,
)

/** A repository on a remote forge (search result / create result). */
@Serializable
data class RemoteRepo(
    val connectionId: String = "",
    val kind: String = "",
    val host: String = "",
    val owner: String = "",
    val name: String = "",
    val fullName: String = "",
    val private: Boolean = false,
    val description: String? = null,
    val defaultBranch: String = "",
    val language: String? = null,
    val updatedAt: String? = null,
    val cloneUrl: String = "",
    val webUrl: String = "",
)

@Serializable
data class ForgeSearchError(val connectionId: String = "", val code: String = "", val message: String = "")

@Serializable
data class ForgeSearchResponse(
    val repos: List<RemoteRepo> = emptyList(),
    val errors: List<ForgeSearchError> = emptyList(),
)

/** Result of resolving a clone/create to a local checkout (POST /forge/clone, /forge/create-local). */
@Serializable
data class ResolvedRepo(val localPath: String = "")

/** Result of creating a remote repo then cloning it (POST /forge/create). */
@Serializable
data class CreatedRepo(val repo: RemoteRepo? = null, val localPath: String = "")

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

// createdAt is epoch-millis (a JSON number), NOT an ISO string like the other
// *createdAt fields here — keep it Long so decoding the /api/term/list response works.
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

// ─── Editor diff + code review (GET /sessions/<id>/fs/diff, /review/*) ────────
@Serializable
data class FsDiffResult(
    val repos: List<RepoDiff> = emptyList(),
    val comments: List<ReviewComment> = emptyList(),
)

@Serializable
data class RepoDiff(
    val repo: String,
    val files: List<DiffFile> = emptyList(),
)

@Serializable
data class DiffFile(
    val path: String,
    val status: String,
    val diff: String,
    val binary: Boolean = false,
    val modeChange: Boolean = false,
)

/** GET /sessions/<id>/fs/refs → branches + recent commits per repo, to populate the
 *  diff base picker's "Previous commit…" / "Another branch…" submenus. */
@Serializable
data class FsRefsResult(
    val repos: List<RepoRefs> = emptyList(),
)

@Serializable
data class RepoRefs(
    val repo: String,
    val branches: List<String> = emptyList(),
    val commits: List<RefCommit> = emptyList(),
)

@Serializable
data class RefCommit(
    val sha: String,
    val subject: String = "",
)

@Serializable
data class ReviewComment(
    val id: String,
    val repo: String,
    val path: String,
    val side: String,
    val anchorLine: Int,
    val anchorContext: String = "",
    val body: String,
    val author: String = "",
    val status: String,
    val currentLine: Int? = null,
    val outdated: Boolean = false,
)

@Serializable
data class AddCommentBody(
    val repo: String,
    val path: String,
    val side: String,
    val anchorLine: Int,
    val anchorContext: String,
    val body: String,
    val diffHunkHeader: String? = null,
)

@Serializable
data class UpdateCommentBody(
    val status: String? = null,
    val body: String? = null,
    val resolvedBy: String? = null,
)

@Serializable
data class ReviewSubmitResult(
    val ok: Boolean = false,
    val delivered: Int = 0,
    val reason: String? = null,
)

// ─── Agents: install status + link/code login (GET/POST /agents/*) ─────────────
/** GET /agents/status → per-CLI install + auth state (detectAllAgents).
 *  Named `AgentInstallStatus` to avoid colliding with proto.AgentStatus (which is
 *  the agent's *runtime phase*). `kind`: "claude" | "codex" | "cursor" | "opencode". */
@Serializable
data class AgentInstallStatus(
    val kind: String = "",
    val installed: Boolean = false,
    val authed: Boolean = false,
)

/** Broker-owned background job for installing one agent CLI. */
@Serializable
data class AgentInstallJob(
    val state: String = "", // "running" | "done" | "failed"
    val log: String = "",
    val exitCode: Int? = null,
)

/** State of an in-progress agent CLI login (POST/GET /agents/<kind>/login).
 *  Mirrors the broker `LoginState` (src/core/agents/login/session.ts):
 *  `phase`: "starting" | "awaiting_user" | "success" | "failed" | "cancelled".
 *  `url`/`code` are the device-flow auth URL + code to show; `needsCode` means the
 *  CLI is waiting for the user to paste a code back (POST .../login/code). */
@Serializable
data class AgentLoginState(
    val kind: String = "",
    val phase: String = "",
    val url: String? = null,
    val code: String? = null,
    val needsCode: Boolean = false,
    val error: String? = null,
)

// ─── opencode providers (GET/POST /opencode/*) ────────────────────────────────
/** One auth method on an opencode provider. `index` is the method's position,
 *  passed back as the `method` arg to oauth start/finish. `type`: "oauth" | "api". */
@Serializable
data class OpenCodeAuthMethod(
    val type: String = "",
    val label: String = "",
    val index: Int = 0,
)

/** GET /opencode/providers → providers with their auth methods + configured flag.
 *  (There is NO top-level `label`; render `id` / a method's `label`.) */
@Serializable
data class OpenCodeProvider(
    val id: String = "",
    val configured: Boolean = false,
    val methods: List<OpenCodeAuthMethod> = emptyList(),
)

/** POST /opencode/auth/oauth/start → the authorization URL (+ optional instructions). */
@Serializable
data class OpenCodeOAuthStart(val url: String = "", val instructions: String? = null)

// ─── Editor / LSP settings (GET/PUT /settings/editor) ─────────────────────────
/** One language server row. `state`: "ready" | "missing" | "prereq-missing". */
@Serializable
data class LspServer(
    val id: String = "",
    val label: String = "",
    val extensions: List<String> = emptyList(),
    val enabled: Boolean = false,
    val state: String = "",
    val installLabel: String? = null,
    val installable: Boolean = false,
    val requires: String? = null,
    val custom: Boolean = false,
    val command: String? = null,
)

@Serializable
data class LspConfig(val servers: List<LspServer> = emptyList())

/** GET /settings/editor → { lsp: { servers: [...] } }. */
@Serializable
data class EditorSettingsResponse(val lsp: LspConfig = LspConfig())

/** POST /settings/editor/lsp/<id>/install → { ok, lines }. */
@Serializable
data class LspInstallResult(val ok: Boolean = false, val lines: List<String> = emptyList())

/** Result of add/remove custom LSP → { ok, error?, lsp? }. */
@Serializable
data class LspMutationResult(
    val ok: Boolean = false,
    val error: String? = null,
    val lsp: LspConfig? = null,
)

// ─── System: in-app updater (GET /api/update/status) ──────────────────────────
/** Mirrors the broker UpdateStatus (src/core/update/checker.ts).
 *  `mode`: "binary" | "source" | "docker".
 *  `state`: "idle" | "checking" | "downloading" | "swapping" | "restart-required" | "failed".
 *  `lastChecked` is epoch-millis. `disabled` is true only in the no-checker fallback. */
@Serializable
data class UpdateStatus(
    val current: String = "",
    val commit: String = "",
    val latest: String? = null,
    val updateAvailable: Boolean = false,
    val notesUrl: String? = null,
    val mode: String = "",
    val state: String = "",
    val lastChecked: Double? = null,
    val lastError: String? = null,
    val disabled: Boolean = false,
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
    val action: String? = null,
    val skipVerify: Boolean? = null,
    val commitFirst: Boolean? = null,
    val commitMessage: String? = null,
    val prTitle: String? = null,
    val prBody: String? = null,
    val draft: Boolean? = null,
    val prRequiresGreen: Boolean? = null,
)

@Serializable
private data class VerifySaveBody(val content: String)

@Serializable
private data class MessageBody(val text: String)

// Voice dictation request bodies.
@Serializable
private data class DraftBody(val draft: String)

@Serializable
private data class GlossaryBody(val glossary: List<String>)

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

@Serializable
private data class ForgeSearchBody(val query: String)

@Serializable
private data class ForgeCloneBody(val connectionId: String, val owner: String, val name: String)

@Serializable
private data class ForgeCreateBody(
    val connectionId: String, val name: String, val owner: String? = null, val private: Boolean = true,
)

@Serializable
private data class ForgeCreateLocalBody(val name: String)

// Agent login / opencode / config-patch / forge-write request bodies.
@Serializable
private data class AgentCodeBody(val code: String)

@Serializable
private data class OpenCodeKeyBody(val providerId: String, val key: String)

@Serializable
private data class OpenCodeOAuthStartBody(val providerId: String, val method: Int)

@Serializable
private data class OpenCodeOAuthFinishBody(val providerId: String, val method: Int, val code: String)

/** Partial PUT /settings/config body. explicitNulls=false omits unset fields, so
 *  this never clobbers config the caller didn't touch. */
@Serializable
private data class ConfigPatchBody(
    val onboarded: Boolean? = null,
    val paName: String? = null,
    val voiceCleanupModel: String? = null,
    val voiceCleanupEngine: String? = null,
    val claudeOauthToken: String? = null,
    val anthropicApiKey: String? = null,
    val codexApiKey: String? = null,
    val cursorApiKey: String? = null,
)

@Serializable
private data class LspServerEnable(val enabled: Boolean)

@Serializable
private data class LspEnablePatch(val servers: Map<String, LspServerEnable>)

@Serializable
private data class LspTogglePatch(val lsp: LspEnablePatch)

@Serializable
private data class AddCustomLspBody(
    val id: String,
    val label: String,
    val command: String,
    val args: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val languageId: String? = null,
    val installCmd: String? = null,
)

@Serializable
private data class AddForgeBody(
    val kind: String,
    val token: String,
    val host: String? = null,
    val source: String = "pat",
    val transport: String = "https",
)

@Serializable
private data class ImportForgeBody(val kind: String, val transport: String = "https")

/** POST $httpBase/push/device body. */
@Serializable
private data class RegisterPushDeviceBody(
    val platform: String,
    val routingToken: String,
    val pubkey: String,
)

/** POST $relayUrl/register body. */
@Serializable
private data class RegisterPushRelayBody(
    val platform: String,
    val pushToken: String,
)

/** Empty JSON object body (`{}`) for POSTs that take no params but return data. */
@Serializable
private class EmptyBody

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

    // explicitNulls=false: partial PATCH bodies (e.g. review-comment resolve) must OMIT unset
    // optional fields, not send them as JSON null — an explicit null would overwrite stored data.
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    internal var spawnTimeoutMillis: Long = 50_000

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun bearerHeader() = "Bearer $token"

    /**
     * Read [resp] into [T] WITHOUT ever aborting the app on failure.
     *
     * SKIE bridges each Swift→Kotlin suspend call into its OWN StandaloneCoroutine that has
     * NO CoroutineExceptionHandler, run on a dispatcher that masks cancellation from the body
     * (SwiftCoroutineDispatcher.executeWithoutCancellation). So any NON-CancellationException
     * that escapes this body — a 401 "unauthorized" text body fed to decodeFromString, a
     * transient transport/decode error, a mid-flight teardown during iPad multi-pane layout
     * churn — cannot reach the (already resumed/cancelled) Swift continuation and instead
     * crashes the whole process via handleJobException. (`ensureActive()` can't discriminate
     * here: executeWithoutCancellation hides the real cancel state.)
     *
     * CancellationException is the one throwable that completes that coroutine cleanly, so on
     * any non-2xx / decode / transport failure we log and surface it AS cancellation. Every
     * caller wraps the call in `try?`, so Swift just sees nil/empty (disconnected, no displays,
     * empty list, …) — graceful degradation instead of a SIGABRT.
     */
    private suspend inline fun <reified T> decode(resp: HttpResponse): T {
        try {
            val text = resp.bodyAsText()
            if (resp.status.isSuccess()) return json.decodeFromString(text)
            println("[BrokerApi] HTTP ${resp.status.value}: ${text.take(120)}")
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            println("[BrokerApi] request failed: ${e.message?.take(160)}")
        }
        throw CancellationException("BrokerApi request unavailable")
    }

    private suspend inline fun <reified T> getJson(url: String): T =
        decode(http.get(url) { header("Authorization", bearerHeader()) })

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

    /** POST a JSON body and decode the JSON response (for endpoints that return data). */
    private suspend inline fun <reified B, reified T> postReturningJson(url: String, body: B): T =
        decode(http.post(url) {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(body))
        })

    private fun urlEncode(s: String): String = s
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("+", "%2B")
        .replace(" ", "%20")

    /** Uppercase hex alphabet for [percentEncode] (RFC 3986 §2.1). */
    private val hexDigits = "0123456789ABCDEF"

    /**
     * RFC 3986 percent-encode [s] over its UTF-8 bytes so the broker can recover
     * the original name via `decodeURIComponent()`. Keeps the unreserved set
     * `A–Z a–z 0–9 - _ . ~` and encodes every other byte as `%XX`.
     *
     * Unlike [urlEncode] (a small ASCII-only replace chain for path segments) this
     * handles spaces and non-ASCII, so an arbitrary filename is safe to carry in
     * the `X-Mux-Filename` request header — a raw non-ASCII value would be a
     * malformed HTTP header.
     */
    private fun percentEncode(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val out = StringBuilder(s.length)
        for (byte in s.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && c.toChar() in unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(hexDigits[c shr 4])
                out.append(hexDigits[c and 0x0F])
            }
        }
        return out.toString()
    }

    // ── public API ───────────────────────────────────────────────────────────

    /**
     * GET /pair.json?t=<token> — confirms the candidate token against the broker
     * and echoes {token,name} (the native pairing shim; /pair only sets a cookie).
     * 401 → CancellationException (graceful) → caller treats as an invalid token.
     */
    suspend fun pairJson(token: String): PairJsonResult =
        getJson("$httpBase/pair.json?t=${urlEncode(token)}")

    /** GET /me — bearer-validity probe; {paired, device?}. Used to validate a manually-typed token. */
    suspend fun me(): MeResult =
        getJson("$httpBase/me")

    /** GET /host — public broker identity (spec §3.3). No auth required; used by the add-host
     *  typed-URL path to confirm a URL is a supermux broker and learn its hostId/name before
     *  claiming. Non-2xx/transport failure → CancellationException (caller treats as "not a host"). */
    suspend fun getHost(): HostIdentity =
        getJson("$httpBase/host")

    /**
     * POST /pair/claim {claimSecret, deviceName} → {host, deviceToken, name} (spec §3.4).
     * Consumes a one-time claim and mints a device bearer even on an already-paired host. A
     * blank/expired/reused secret 401s → CancellationException (caller treats as failure). The
     * caller MUST verify `host.hostId` equals the scanned payload's hostId before persisting.
     */
    suspend fun pairClaim(claimSecret: String, deviceName: String): PairClaimResult =
        postReturningJson("$httpBase/pair/claim", PairClaimBody(claimSecret, deviceName))

    /** GET /sessions/<id>/models */
    suspend fun models(id: String): ModelsResponse =
        getJson("$httpBase/sessions/$id/models")

    /** GET /models?agent= — models for the launcher (no session). */
    suspend fun listModels(agent: String): LauncherModels =
        getJson("$httpBase/models?agent=${urlEncode(agent)}")

    /** GET /reasoning-levels?agent=&model= — reasoning levels for the launcher (no session). */
    suspend fun getReasoningLevels(agent: String, model: String? = null): ReasoningResponse =
        getJson(
            "$httpBase/reasoning-levels?agent=${urlEncode(agent)}" +
                (if (model != null) "&model=${urlEncode(model)}" else ""),
        )

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
    suspend fun spawn(req: SpawnRequest): SpawnResponse = withTimeout(spawnTimeoutMillis) {
        decode(http.post("$httpBase/sessions") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(req))
        })
    }

    /** GET /settings/config */
    suspend fun getConfig(): AppConfigDto =
        getJson("$httpBase/settings/config")

    /** PUT /settings/config {"paName": ...} — back-compat shim; prefer [saveConfig]. */
    suspend fun putConfig(paName: String) =
        putJson("$httpBase/settings/config", PaNameBody(paName))

    /**
     * PUT /settings/config — partial patch. Only non-null args are serialized
     * (explicitNulls=false), so unset fields are never overwritten on the broker.
     * Secret fields (tokens) are write-only — they read back redacted, not echoed.
     */
    suspend fun saveConfig(
        onboarded: Boolean? = null,
        paName: String? = null,
        voiceCleanupModel: String? = null,
        voiceCleanupEngine: String? = null,
        claudeOauthToken: String? = null,
        anthropicApiKey: String? = null,
        codexApiKey: String? = null,
        cursorApiKey: String? = null,
    ) = putJson(
        "$httpBase/settings/config",
        ConfigPatchBody(
            onboarded = onboarded,
            paName = paName,
            voiceCleanupModel = voiceCleanupModel,
            voiceCleanupEngine = voiceCleanupEngine,
            claudeOauthToken = claudeOauthToken,
            anthropicApiKey = anthropicApiKey,
            codexApiKey = codexApiKey,
            cursorApiKey = cursorApiKey,
        ),
    )

    /** GET /settings/soul → soul.md text ("" on any failure — never throws). */
    suspend fun getSoul(): String {
        val resp = http.get("$httpBase/settings/soul") { header("Authorization", bearerHeader()) }
        return if (resp.status.isSuccess()) resp.bodyAsText() else ""
    }

    /** PUT /settings/soul (text/plain body) → true on success. */
    suspend fun putSoul(text: String): Boolean {
        val resp = http.put("$httpBase/settings/soul") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Text.Plain)
            setBody(text)
        }
        return resp.status.isSuccess()
    }

    // ── Agents: install status + link/code login ───────────────────────────────

    /** GET /agents/status → install + auth state per agent CLI. */
    suspend fun agentStatuses(): List<AgentInstallStatus> =
        getJson("$httpBase/agents/status")

    /** POST /agents/<kind>/install → start (or resume) the broker-owned install job. */
    suspend fun startAgentInstall(kind: String): AgentInstallJob {
        val response = http.post("$httpBase/agents/${urlEncode(kind)}/install") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EmptyBody()))
        }
        // The broker intentionally returns 409 with the live job when an install is
        // already running. Treat that as a resumable success, like the web client.
        if (response.status.isSuccess() || response.status.value == 409) {
            return json.decodeFromString(response.bodyAsText())
        }
        return decode(response)
    }

    /** GET /agents/<kind>/install → poll the latest install job. */
    suspend fun agentInstallState(kind: String): AgentInstallJob =
        getJson("$httpBase/agents/${urlEncode(kind)}/install")

    /** POST /agents/<kind>/login → starts a CLI login, returns the initial state. */
    suspend fun startAgentLogin(kind: String): AgentLoginState =
        postReturningJson("$httpBase/agents/${urlEncode(kind)}/login", EmptyBody())

    /** GET /agents/<kind>/login → poll the current login state. */
    suspend fun agentLoginState(kind: String): AgentLoginState =
        getJson("$httpBase/agents/${urlEncode(kind)}/login")

    /** POST /agents/<kind>/login/code {code} — hand the CLI a pasted device code. */
    suspend fun sendAgentLoginCode(kind: String, code: String) =
        postJson("$httpBase/agents/${urlEncode(kind)}/login/code", AgentCodeBody(code))

    /** POST /agents/<kind>/login/cancel — abort an in-progress login. */
    suspend fun cancelAgentLogin(kind: String) {
        http.post("$httpBase/agents/${urlEncode(kind)}/login/cancel") {
            header("Authorization", bearerHeader())
        }
    }

    // ── opencode providers (key + oauth) ───────────────────────────────────────

    /** GET /opencode/providers → providers with their auth methods (bare array). */
    suspend fun openCodeProviders(): List<OpenCodeProvider> =
        getJson("$httpBase/opencode/providers")

    /** POST /opencode/auth/key {providerId, key} — save an API key for a provider. */
    suspend fun setOpenCodeKey(providerId: String, key: String) =
        postJson("$httpBase/opencode/auth/key", OpenCodeKeyBody(providerId, key))

    /** POST /opencode/auth/oauth/start {providerId, method} → { url, instructions? }.
     *  `method` is the [OpenCodeAuthMethod.index] of the oauth method. */
    suspend fun startOpenCodeOAuth(providerId: String, method: Int): OpenCodeOAuthStart =
        postReturningJson("$httpBase/opencode/auth/oauth/start", OpenCodeOAuthStartBody(providerId, method))

    /** POST /opencode/auth/oauth/finish {providerId, method, code} — complete oauth. */
    suspend fun finishOpenCodeOAuth(providerId: String, method: Int, code: String) =
        postJson("$httpBase/opencode/auth/oauth/finish", OpenCodeOAuthFinishBody(providerId, method, code))

    // ── Editor / LSP settings ──────────────────────────────────────────────────

    /** GET /settings/editor → { lsp: { servers } }. */
    suspend fun getEditorSettings(): EditorSettingsResponse =
        getJson("$httpBase/settings/editor")

    /** PUT /settings/editor {lsp:{servers:{<id>:{enabled}}}} → updated settings.
     *  Partial: only the named server's `enabled` is changed. */
    suspend fun setLspEnabled(id: String, enabled: Boolean): EditorSettingsResponse =
        decode(http.put("$httpBase/settings/editor") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(LspTogglePatch(LspEnablePatch(mapOf(id to LspServerEnable(enabled))))))
        })

    /** POST /settings/editor/lsp/<id>/install → { ok, lines } (install log). */
    suspend fun installEditorLsp(id: String): LspInstallResult =
        postReturningJson("$httpBase/settings/editor/lsp/${urlEncode(id)}/install", EmptyBody())

    /** POST /settings/editor/lsp/custom — register a custom LSP server → { ok, error?, lsp? }. */
    suspend fun addCustomEditorLsp(
        id: String,
        label: String,
        command: String,
        extensions: List<String>,
        args: List<String> = emptyList(),
        languageId: String? = null,
        installCmd: String? = null,
    ): LspMutationResult =
        postReturningJson(
            "$httpBase/settings/editor/lsp/custom",
            AddCustomLspBody(id, label, command, args, extensions, languageId, installCmd),
        )

    /** DELETE /settings/editor/lsp/custom/<id> → { ok, error?, lsp? }. */
    suspend fun removeCustomEditorLsp(id: String): LspMutationResult =
        decode(http.delete("$httpBase/settings/editor/lsp/custom/${urlEncode(id)}") {
            header("Authorization", bearerHeader())
        })

    // ── System: restart + update status ────────────────────────────────────────

    /** POST /system/restart — restart the broker service (fire-and-forget). */
    suspend fun restartBroker() {
        http.post("$httpBase/system/restart") { header("Authorization", bearerHeader()) }
    }

    /** GET /api/update/status → in-app updater state. */
    suspend fun updateStatus(): UpdateStatus =
        getJson("$httpBase/api/update/status")

    /** GET /settings/curator → {config:{enabled,hour,minute}, nextRun} */
    suspend fun getCuratorSettings(): CuratorSettingsResponse =
        getJson("$httpBase/settings/curator")

    /** PUT /settings/curator {enabled,hour,minute} → updated {config, nextRun} */
    suspend fun saveCuratorSettings(enabled: Boolean, hour: Int, minute: Int): CuratorSettingsResponse =
        decode(http.put("$httpBase/settings/curator") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CuratorConfig(enabled, hour, minute)))
        })

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

    /** POST /usage/codex/reset → redeem one banked Codex rate-limit reset. */
    suspend fun redeemCodexReset(): CodexResetResult =
        postReturningJson("$httpBase/usage/codex/reset", EmptyBody())

    /** GET /devices */
    suspend fun devices(): List<DeviceDto> =
        getJson("$httpBase/devices")

    /** POST /devices {name} → { url, name }: a one-time pairing URL for the device */
    suspend fun addDevice(name: String): AddDeviceResponse =
        decode(http.post("$httpBase/devices") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AddDeviceBody(name)))
        })

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

    private suspend fun gitOp(id: String, op: String): GitOpResult =
        decode(http.post("$httpBase/sessions/$id/git/$op") {
            header("Authorization", bearerHeader())
        })
    suspend fun gitFetch(id: String): GitOpResult = gitOp(id, "fetch")
    suspend fun gitPublish(id: String): GitOpResult = gitOp(id, "publish")
    suspend fun gitPush(id: String): GitOpResult = gitOp(id, "push")
    suspend fun gitPull(id: String): GitOpResult = gitOp(id, "pull")

    /**
     * POST /sessions/<id>/finish — kick off the finish job for the session branch.
     * `action`: "merge" | "pr" | "keep" | "discard" (broker defaults to "merge").
     *
     * Returns the *initial* [FinishResult] decode of the launched job (typically
     * `status:"running"`); the terminal outcome is delivered on the WS `finish_job`
     * frame ([dev.supermux.proto.FinishJobFrame]).
     */
    suspend fun finish(
        id: String,
        action: String? = null,
        skipVerify: Boolean? = null,
        commitFirst: Boolean? = null,
        commitMessage: String? = null,
        prTitle: String? = null,
        prBody: String? = null,
        draft: Boolean? = null,
        prRequiresGreen: Boolean? = null,
    ): FinishResult =
        decode(http.post("$httpBase/sessions/$id/finish") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FinishBody(
                action, skipVerify, commitFirst, commitMessage, prTitle, prBody, draft, prRequiresGreen,
            )))
        })

    /** GET /sessions/<id>/finish/readiness — preflight for the finish menu. */
    suspend fun finishReadiness(id: String): FinishReadiness =
        getJson("$httpBase/sessions/$id/finish/readiness")

    /** POST /sessions/<id>/verify/suggest → { content, source }. */
    suspend fun verifySuggest(id: String): VerifySuggestResult =
        postReturningJson("$httpBase/sessions/$id/verify/suggest", EmptyBody())

    /** POST /sessions/<id>/verify/save {content} → { ok, reason? }. */
    suspend fun verifySave(id: String, content: String): VerifySaveResult =
        postReturningJson("$httpBase/sessions/$id/verify/save", VerifySaveBody(content))

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
    suspend fun createProxy(sessionName: String, port: Int, domain: String? = null): CreateProxyResponse =
        decode(http.post("$httpBase/proxies") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateProxyBody(sessionName, port, domain)))
        })

    /** PATCH /proxies/<domain> {isPublic} */
    suspend fun setProxyPublic(domain: String, isPublic: Boolean) =
        patchJson("$httpBase/proxies/${urlEncode(domain)}", SetProxyPublicBody(isPublic))

    /** DELETE /proxies/<domain> */
    suspend fun removeProxy(domain: String) {
        http.delete("$httpBase/proxies/${urlEncode(domain)}") {
            header("Authorization", bearerHeader())
        }
    }

    /**
     * POST /upload — raw-body streaming upload.
     *
     * Sends the file bytes verbatim as an `application/octet-stream` body with the
     * metadata in headers, so the broker can stream the body straight to disk
     * without buffering it (the streaming `/upload` contract). The signature is
     * unchanged from the old multipart form, so the iOS/Android call sites are
     * untouched.
     *
     *  - `X-Mux-Session`  — required; the owning session id.
     *  - `X-Mux-Mime`     — the real MIME (the octet-stream body type hides it).
     *  - `X-Mux-Filename` — RFC3986 percent-encoded (header-safe) original name.
     *  - `X-Mux-Kind`     — sent ONLY when [kind] is non-null; else the broker infers it.
     */
    suspend fun upload(
        session: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        kind: String? = null,
    ): UploadResponse {
        val resp = http.post("$httpBase/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Mux-Session", session)
            header("X-Mux-Mime", mime)
            header("X-Mux-Filename", percentEncode(filename))
            if (kind != null) header("X-Mux-Kind", kind)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        return decode(resp)
    }

    /** Upload from a base64 payload (iOS hands us `Data` as base64 — fast Kotlin decode). */
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    suspend fun uploadBase64(
        session: String, base64: String, filename: String, mime: String, kind: String? = null,
    ): UploadResponse =
        upload(session, kotlin.io.encoding.Base64.decode(base64), filename, mime, kind)

    // ── Resumable / chunked upload ───────────────────────────────────────────

    /** Threshold below which a file uploads in a single POST (no init round-trip).
     *  Only decides single-POST vs chunked entry; both handle any size ≤ server cap.
     *  `internal var` so commonTest can force the chunked path with tiny bodies. */
    internal var resumableThresholdBytes = 5L * 1024 * 1024

    /**
     * Upload [source] via the chunked/resumable protocol, reporting absolute
     * progress `(bytesAcked, total)` after each step. Small files take a single
     * POST /upload; large files init → PATCH loop → finalize, resuming from the
     * server offset (HEAD) if a chunk throws. Returns the finalized attachment.
     */
    suspend fun uploadResumable(
        session: String,
        source: ChunkSource,
        filename: String,
        mime: String,
        kind: String? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResponse {
        val total = source.size
        if (total <= resumableThresholdBytes) {
            onProgress(0, total)
            val res = upload(session, source.read(0, total.toInt()), filename, mime, kind)
            onProgress(total, total)
            return res
        }
        return uploadChunked(session, source, filename, mime, kind, total, onProgress)
    }

    private suspend fun uploadChunked(
        session: String, source: ChunkSource, filename: String, mime: String,
        kind: String?, total: Long, onProgress: (Long, Long) -> Unit,
    ): UploadResponse {
        // 1) init
        val init: InitResponse = decode(http.post("$httpBase/upload/init") {
            header(HttpHeaders.Authorization, bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InitRequest(session, mime, filename, kind, total)))
        })
        val uploadId = init.upload_id
        val chunkSize = if (init.chunk_size > 0) init.chunk_size else resumableThresholdBytes

        // 2) PATCH loop, resuming from the server offset on a network throw.
        var offset = init.offset
        var attempts = 0
        val maxAttempts = 5
        while (true) {
            val len = minOf(chunkSize, total - offset).toInt()
            val chunk = source.read(offset, len)
            try {
                val resp = http.patch("$httpBase/upload/$uploadId") {
                    header(HttpHeaders.Authorization, bearerHeader())
                    header("Upload-Offset", offset.toString())
                    contentType(ContentType.Application.OctetStream)
                    setBody(chunk)
                }
                when (resp.status.value) {
                    200 -> {
                        val pr: PatchResponse = decode(resp)
                        if (pr.file_id != null) {
                            onProgress(total, total)
                            return UploadResponse(pr.file_id, pr.size, pr.mime, pr.name)
                        }
                        offset = pr.offset ?: (offset + len)
                        attempts = 0
                        onProgress(offset, total)
                    }
                    409 -> offset = resp.headers["Upload-Offset"]?.toLongOrNull() ?: offset // resync
                    else -> throw IllegalStateException("resumable upload failed: HTTP ${resp.status.value}")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // Network drop: resync from the server offset and retry (capped).
                if (++attempts > maxAttempts) throw e
                val serverOffset = headUpload(uploadId)
                    ?: throw IllegalStateException("resumable upload lost (HEAD 404 after ${e.message})")
                offset = serverOffset
            }
        }
    }

    /** HEAD /upload/<id> → the server's current stored offset, or null if the
     *  upload is unknown (never created, or already finalized/GC'd). */
    private suspend fun headUpload(uploadId: String): Long? {
        val resp = http.head("$httpBase/upload/$uploadId") {
            header(HttpHeaders.Authorization, bearerHeader())
        }
        return if (resp.status.value == 200) resp.headers["Upload-Offset"]?.toLongOrNull() else null
    }

    /** GET /files/<urlencoded file_id> — raw bytes of a stored attachment. */
    suspend fun fileBytes(fileId: String): ByteArray? {
        val resp = http.get("$httpBase/files/${urlEncode(fileId)}") {
            header("Authorization", bearerHeader())
        }
        return if (resp.status.isSuccess()) resp.bodyAsBytes() else null
    }

    // ── Voice dictation (transcribe + cleanup glossary) ──────────────────────────

    /** POST {/sessions/<id>,}/transcribe — JSON { draft } → cleaned text. (on-device-STT path)
     *  `sessionId` is OPTIONAL: null/blank (e.g. the pre-spawn launcher) posts to the id-less
     *  `/transcribe`; the session only enriches cleanup context server-side, it isn't required. */
    suspend fun transcribeDraft(sessionId: String?, draft: String): TranscribeResponse =
        postReturningJson(transcribePath(sessionId), DraftBody(draft))

    /** POST {/sessions/<id>,}/transcribe — multipart field "audio" → cleaned text. (whisper path)
     *  Mirrors `upload()`'s multipart shape; field name is "audio" (NOT "file"), and there is
     *  no `kind`/`session` part — the route derives the session from the URL (or none).
     *  `sessionId` is OPTIONAL (see [transcribeDraft]). */
    suspend fun transcribeAudio(
        sessionId: String?, bytes: ByteArray, filename: String, mime: String = "audio/mp4",
    ): TranscribeResponse {
        val resp = http.post(transcribePath(sessionId)) {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("audio", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                })
            }))
        }
        return decode(resp)
    }

    /** Cleanup endpoint URL — id-less `/transcribe` when [sessionId] is null/blank, else
     *  `/sessions/<id>/transcribe`. The session id is never required (it only adds context). */
    private fun transcribePath(sessionId: String?): String =
        if (sessionId.isNullOrBlank()) "$httpBase/transcribe"
        else "$httpBase/sessions/$sessionId/transcribe"

    /** GET /config/voice-glossary → the glossary terms (default-seeded server-side). */
    suspend fun fetchGlossary(): List<String> =
        getJson<GlossaryResponse>("$httpBase/config/voice-glossary").glossary

    /** PUT /config/voice-glossary { glossary } → the persisted list. */
    suspend fun updateGlossary(terms: List<String>): List<String> =
        decode<GlossaryResponse>(http.put("$httpBase/config/voice-glossary") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GlossaryBody(terms)))
        }).glossary

    /** GET /sessions/<id>/messages — the message log for a (possibly archived) session. */
    suspend fun archivedLogs(sessionId: String): List<LogEntry> =
        getJson("$httpBase/sessions/$sessionId/messages")

    /** GET /projects → known project working directories (absolute paths). */
    suspend fun listProjects(): List<String> =
        getJson<ProjectsResponse>("$httpBase/projects").projects.map { it.path }

    /** POST /paths/validate {path} → {ok, path?, error?}. Resolves ~ and checks existence. */
    suspend fun validatePath(path: String): PathValidation =
        decode(http.post("$httpBase/paths/validate") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(PathBody(path)))
        })

    /** GET /repos/info?path=&fetch=1 — git repo status + branch lists for the worktree picker. */
    suspend fun getRepoInfo(path: String, fetch: Boolean = false): RepoInfo =
        getJson("$httpBase/repos/info?path=${urlEncode(path)}" + if (fetch) "&fetch=1" else "")

    // ── Git hosting / forges ───────────────────────────────────────────────────

    /** GET /forge/connections → configured GitHub/GitLab connections (+ CLI availability). */
    suspend fun listForges(): ForgeConnectionsResponse =
        getJson("$httpBase/forge/connections")

    /** POST /forge/search {query} → matching remote repos across all connections. */
    suspend fun searchForge(query: String): ForgeSearchResponse =
        postReturningJson("$httpBase/forge/search", ForgeSearchBody(query))

    /** POST /forge/clone {connectionId, owner, name} → { localPath } of the new local checkout. */
    suspend fun cloneForge(connectionId: String, owner: String, name: String): ResolvedRepo =
        postReturningJson("$httpBase/forge/clone", ForgeCloneBody(connectionId, owner, name))

    /** POST /forge/create {connectionId, name, private} → creates the remote repo and clones it.
     *  Param is `isPrivate` (not `private`) so the Swift call site isn't a keyword collision;
     *  the JSON field stays `private` via [ForgeCreateBody]. */
    suspend fun createForge(connectionId: String, name: String, isPrivate: Boolean = true): CreatedRepo =
        postReturningJson("$httpBase/forge/create", ForgeCreateBody(connectionId, name, null, isPrivate))

    /** POST /forge/create-local {name} → { localPath } of a freshly `git init`'d local repo. */
    suspend fun createLocalRepo(name: String): ResolvedRepo =
        postReturningJson("$httpBase/forge/create-local", ForgeCreateLocalBody(name))

    /** POST /forge/connections {kind, token, host?, source:"pat", transport} → the new connection.
     *  `host` is set for self-hosted GitLab/GitHub Enterprise; null = the public host. */
    suspend fun addForge(
        kind: String, token: String, host: String? = null, transport: String = "https",
    ): ForgeConnection =
        postReturningJson("$httpBase/forge/connections", AddForgeBody(kind, token, host, "pat", transport))

    /** POST /forge/connections/import {kind, transport} → connection imported from the CLI's auth. */
    suspend fun importForge(kind: String, transport: String = "https"): ForgeConnection =
        postReturningJson("$httpBase/forge/connections/import", ImportForgeBody(kind, transport))

    /** DELETE /forge/connections/<id> — disconnect a forge account. */
    suspend fun removeForge(id: String) {
        http.delete("$httpBase/forge/connections/${urlEncode(id)}") {
            header("Authorization", bearerHeader())
        }
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

    /** GET /sessions/<id>/fs/diff?base=<spec> → { repos: RepoDiff[], comments: ReviewComment[] }.
     *  [base] is the diff-base spec: null/"session-start" (default) · "head" · "commit:<sha>" · "branch:<name>". */
    suspend fun fsDiff(sessionId: String, base: String? = null): FsDiffResult =
        getJson("$httpBase/sessions/$sessionId/fs/diff" + if (base != null) "?base=${urlEncode(base)}" else "")

    /** GET /sessions/<id>/fs/refs → { repos: RepoRefs[] } (branches + recent commits per repo). */
    suspend fun fsRefs(sessionId: String): FsRefsResult =
        getJson("$httpBase/sessions/$sessionId/fs/refs")

    /** POST /sessions/<id>/review/comments {repo,path,side,anchorLine,anchorContext,body,diffHunkHeader?} → the created comment. */
    suspend fun reviewAddComment(sessionId: String, body: AddCommentBody): ReviewComment =
        postReturningJson("$httpBase/sessions/$sessionId/review/comments", body)

    /** PATCH /sessions/<id>/review/comments/<commentId> {status?,body?,resolvedBy?} → true on success (response ignored). */
    suspend fun reviewUpdateComment(sessionId: String, commentId: String, patch: UpdateCommentBody): Boolean {
        val resp = http.patch("$httpBase/sessions/$sessionId/review/comments/${urlEncode(commentId)}") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(patch))
        }
        return resp.status.isSuccess()
    }

    /** POST /sessions/<id>/review/submit {} → { ok, delivered, reason? }. */
    suspend fun reviewSubmit(sessionId: String): ReviewSubmitResult =
        postReturningJson("$httpBase/sessions/$sessionId/review/submit", EmptyBody())

    // ── Displays ─────────────────────────────────────────────────────────────────

    /** GET /displays → active display streams. */
    suspend fun listDisplays(): List<DisplayStream> =
        getJson("$httpBase/displays")

    /** POST /displays {sessionName, provider?, device?, width?, height?} → the started stream. */
    suspend fun startDisplay(sessionName: String, provider: String? = null, device: String? = null, width: Int? = null, height: Int? = null): DisplayStream =
        decode(http.post("$httpBase/displays") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(StartDisplayBody(sessionName, provider, device, width, height)))
        })

    /** DELETE /displays/<id> */
    suspend fun stopDisplay(id: String) {
        http.delete("$httpBase/displays/${urlEncode(id)}") {
            header("Authorization", bearerHeader())
        }
    }

    // ── Push registration ─────────────────────────────────────────────────────

    /**
     * GET /me → returns the `relayUrl` field (null when the broker is not configured
     * with a relay URL or the response can't be decoded).
     */
    suspend fun pushRelayUrl(): String? =
        getJson<MeResponse>("$httpBase/me").relayUrl

    /**
     * POST /push/device — registers this device with the broker after the app has
     * received its `routingToken` from the relay bootstrap push.
     * Body: `{platform, routingToken, pubkey}`.
     */
    suspend fun registerPushDevice(platform: String, routingToken: String, pubkey: String) =
        postJson("$httpBase/push/device", RegisterPushDeviceBody(platform, routingToken, pubkey))

    /**
     * POST <relayUrl>/register — tells the relay to issue a bootstrap push that
     * delivers the `routingToken` to this device (via FCM/APNs). The relay responds
     * 202 Accepted; the actual routingToken arrives asynchronously in the push payload.
     * Body: `{platform, pushToken}`.
     */
    suspend fun registerPushTokenWithRelay(relayUrl: String, platform: String, pushToken: String) {
        val url = relayUrl.trimEnd('/') + "/register"
        http.post(url) {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(RegisterPushRelayBody(platform, pushToken)))
        }
    }
}
