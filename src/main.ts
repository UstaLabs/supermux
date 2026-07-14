// src/main.ts
import { TelegramChannel } from "./channels/telegram"
import { WhatsAppChannel } from "./channels/whatsapp"
import { WebChannel } from "./channels/web"
import { buildProxyPublicUrl } from "./channels/web/proxy"
import { EMBEDDED_STATIC } from "./channels/web/static-manifest.generated"
import { requireAtLeastOneChannel } from "./shared/channels"
import { handleWebInbound } from "./channels/web/inbound-handler"
import type { Channel, InboundMessage, OutboundAction } from "./channels/channel"
import { classifyInbound, transformOutbound } from "./core/routing"
import { handleSlash } from "./core/commands"
import { Registry, type ProxyEntry } from "./core/session-manager/registry"
import { makeReadAdvancer } from "./core/session-manager/read-status"
import { ProxyLivenessMonitor, type ProxyStatus } from "./core/proxy/liveness"

function proxyWsPayload(entry: ProxyEntry, status: ProxyStatus = "unknown") {
  return {
    domain: entry.domain,
    sessionName: entry.sessionName,
    port: entry.port,
    createdAt: entry.createdAt,
    isPublic: entry.isPublic,
    status,
    url: buildProxyPublicUrl(entry.domain, {
      baseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
      publicUrl: process.env.MUX_WEB_PUBLIC_URL,
    }),
  }
}

import { startSocketServer } from "./core/session-manager/socket-server"
import { createSupervisor, reconcileOnStartup } from "./core/session-manager/supervisor"
import { acquirePidFile, releasePidFile } from "./core/session-manager/pid-file"
import { spawnSessionWindow, killWindowById, listSessionWindows, livePanePid, sendKeysToWindowId, resolveWindowIdByName } from "./core/session-manager/tmux"
import { ensureWindowId } from "./core/session-manager/window-id"
import { liveWindowId } from "./core/session-manager/live-window"
import { spawnSession as spawnSessionHelper, spawnPA, resumeOpenCodeSession } from "./core/session-manager/spawn-helper"
import { RuntimeRegistry, type SessionRuntime } from "./core/session-manager/runtime"
import { buildClaudeSpawnCommand } from "./core/session-manager/spawn-command"
import { createAgentRpc } from "./core/agent-rpc"
import { buildRpcPrompt } from "./core/agent-rpc/prompts"
import { transcribeAudio } from "./core/transcription/whisper"
import { buildVoicePayload } from "./core/transcription/voice-context"
import { cleanupDraft, VOICE_CLEANUP_MODEL } from "./core/transcription/voice-cleanup"
import { cursorSpawnArgs, codexSpawnArgs, claudeSpawnArgs, codexPrepareGlobal, codexPrepareSessionHome, opencodeConfigEntries, ensureOpenCodePluginScopes } from "./core/plugins"
import { ensureMuxCoreSkills, ensureMuxCoreRegistered } from "./core/plugins/mux-core"
import { CommandRegistry, ClaudeCommandProvider, CodexCommandProvider, CursorCommandProvider, OpenCodeCommandProvider } from "./core/slash-commands"
import { AgentKind, isAgentKind } from "./shared/agents"
import { sendChannelConsentEnter } from "./core/session-manager/post-spawn-keys"
import { preAcceptTrust, writeRpcWorkerMcpConfig } from "./core/session-manager/trust"
import { waitForRegisteredSession } from "./core/session-manager/spawn-registration"
import { normalizeExistingWorkdir } from "./core/session-manager/workdir-paths"
import { resolveDownloadAttachment } from "./core/session-manager/download"
import { runInterrupt } from "./core/session-manager/interrupt"
import { RecentInboundIds } from "./core/session-manager/recent-inbound-ids"
import { PendingReapply, shouldDeferReapply, changedSince } from "./core/session-manager/pending-reapply"
import { deliverInbound as deliverInboundCore, type InboundDeliveryResult } from "./core/session-manager/inbound-delivery"
import { buildMenuEntries } from "./channels/telegram/menu"
import { MessageStore } from "./core/session-manager/messages"
import { appendSoulSetupInvocation, readSoulSetupState, shouldAutoSendSoulSetup } from "./core/session-manager/soul-setup"
import { openDb, runMigrations } from "./core/storage/db"
import { MIGRATIONS } from "./core/storage/migrations"
import { checkSchemaStamp, writeSchemaStamp } from "./core/storage/schema-stamp"
import { sweepRuntimeAssets } from "./core/runtime-assets-gc"
import { BUILD_VERSION, BUILD_COMMIT } from "./shared/build-info"
import { loadOrCreateHostKey } from "./core/host-identity"
import { ClaimStore } from "./channels/web/pair-claim"
import { NullRelayProvider } from "./core/relay/provider"
import { FrpRelayProvider, parentBoundFrpcCommand } from "./core/relay/frp-provider"
import { UpdateChecker } from "./core/update/checker"
import { detectUpdateMode } from "./core/update/mode"
import { ReviewStore } from "./core/review/store"
import { serializeReview } from "./core/review/serialize"
import { FileStore } from "./core/files/store"
import { loadOrGenerateVapid } from "./core/push/vapid"
import { PushSubscriptionStore } from "./core/push/subscriptions"
import { createPushSender } from "./core/push/sender"
import { firePushForReply } from "./core/push/hook"
import { DevicePushTokenStore } from "./core/push/device-tokens"
import { createRelayClient } from "./core/push/relay-adapter"
import { ViewingTracker } from "./core/push/viewing-tracker"
import {
  MUX_HOME, STATE_DIR, PID_FILE, SOCKETS_DIR, ENV_FILE, INBOX_DIR, DEVICES_FILE, HOST_KEY_FILE,
} from "./shared/paths"
import { validateWebEnv } from "./shared/web-env"
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, cpSync, chmodSync, watch as fsWatch } from "fs"
import { randomBytes, randomUUID } from "crypto"
import { execSync as _execSync, spawn as nodeSpawn, execFileSync } from "child_process"
import { makeLogger } from "./shared/log"
import { checkPreflight, hasBinary } from "./shared/preflight"
import { detectAllAgents, detectAgent } from "./core/agents/detect"
import { createInstallManager } from "./core/agents/install"
import { withAgentBinDirs } from "./core/agents/bin-dirs"
import { homedir, hostname } from "os"
import { home } from "./shared/home"
import { join, dirname, resolve, isAbsolute } from "path"
import { fileURLToPath } from "url"
import { ClaudeCodeAdapter } from "./core/agents/claude/index"
import { wireClaudeStateEvents } from "./core/agents/claude/state-projection"
import { applyClaudeLiveSwitch } from "./core/agents/claude/live-switch"
import { writeClaudeHooksSettings, resolveInternalHookSecret, CLAUDE_HOOKS_SETTINGS_PATH } from "./core/agents/claude/hooks-settings"
import type { AgentAdapter } from "./core/agents/types"
import { resolveCodexAuth } from "./core/agents/codex/auth"
import { spawnCodexAppServer, type CodexSpawnHandle } from "./core/agents/codex/spawn"
import { CodexAdapter } from "./core/agents/codex/adapter"
import { resolveCursorAuth } from "./core/agents/cursor/auth"
import { makeRealCursorRunner } from "./core/agents/cursor/runner"
import { CursorAdapter } from "./core/agents/cursor/adapter"
import { ModelCache } from "./core/models/cache"
import { discoverClaudeModels, discoverCodexModels, discoverCursorModels, discoverOpenCodeModels } from "./core/models/discovery"
import { refreshModelCache, type ModelDiscoverers } from "./core/models/refresh"
import { listOpenCodeProviders, setOpenCodeApiKey, startOpenCodeOAuth, finishOpenCodeOAuth } from "./core/agents/opencode/auth-ops"
import { OpenCodeAdapter } from "./core/agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "./core/agents/opencode/spawn"
import { resolveSessionEffort, clampSessionReasoningLevel } from "./core/models/session-agent-settings"
import { supportedReasoningLevels, shouldShowReasoningControl } from "./core/models/reasoning-levels"
import { DeviceStore } from "./channels/web/device-store"
import { TerminalManager } from "./core/terminal/manager"
import { DisplayManager } from "./core/display/manager"
import type { ProviderName } from "./core/display/types"
import { LinuxXvfbProvider } from "./core/display/providers/linux-xvfb"
import { MacosScreenProvider } from "./core/display/providers/macos-screen"
import { listDevices } from "./core/display/scrcpy/adb"
import { FsWatcher } from "./core/editor/fs-watcher"
import { scanRepos } from "./core/editor/repo-scanner"
import { ActivityStore } from "./core/session-manager/activity-store"
import { AgentStateStore } from "./core/session-manager/agent-state-store"
import { toAgentStateFrame } from "./core/session-manager/agent-state-frame"
import { BackgroundTaskStore } from "./core/session-manager/background-task-store"
import { TranscriptTailer } from "./core/agents/claude/transcript-tailer"
import { BgTaskDetector } from "./core/agents/claude/bg-task-detector"
import { claudeTranscriptPath } from "./core/agents/claude/transcript-path"
import { renderTranscript } from "./core/search/transcript-render"
import { normalizeToolName } from "./core/agents/tool-normalize"
import { gcOrphanAgentHomes, reclaimCursorHomes } from "./core/agents/shared-runtime"
import { CuratorScheduler } from "./core/curator/scheduler"
import { runCurator, type CuratorDeps } from "./core/curator/run"
import { curatorPromptPath } from "./core/runtime-assets"
import { SettingsStore } from "./core/settings/store"
import { SearchStore } from "./core/search/store"
import { ForgeStore } from "./core/forge/store"
import { ForgeService } from "./core/forge/service"
import { detectForgeClis, importCliToken } from "./core/forge/cli-import"
import { installCredentialLauncher } from "./core/forge/launcher"
import { SETTINGS_KEY_CURATOR, parseCuratorConfig, type CuratorConfig } from "./core/settings/curator-config"
import { listLspServerSettingsRows } from "./core/lsp/editor-settings"
import { getServerById } from "./core/lsp/registry"
import { isServerInstalled } from "./core/lsp/detect"
import { runInstallToCompletion } from "./core/lsp/install-sync"
import { hydrateCredentialEnv, applyCredentialEnv } from "./core/settings/app-config"
import { reverseProxySnippets } from "./core/settings/exposure"
import { toActivityEvents } from "./core/agents/adapter-activity"
import { LoginManager } from "./core/agents/login/manager"
import { claudeLoginSpawnCommand } from "./core/agents/login/spawn-command"
import { getRepoInfo } from "./core/git/repo-info"
import { createWorktree, removeWorktree, ensureWorktreeAt, type WorktreeHandle } from "./core/worktree/manager"
import { isWorktreeReclaimable } from "./core/worktree/gc"
import { startFinishJob, getFinishJob, clearFinishJob, type FinishJob, type FinishJobOpts, type FinishAction } from "./core/worktree/finish-job"
import { computeReadiness, type FinishReadiness } from "./core/worktree/readiness"
import { suggestVerify } from "./core/worktree/verify-suggest"
import { loadFinishConfig } from "./core/worktree/finish-config"
import { computeLiteStatus } from "./core/worktree/lite-status"
import { GitStatusService, type ServiceSession } from "./core/worktree/git-status-service"
import { deriveName } from "./core/session-manager/naming"

const log = makeLogger("main")

const __dirname = dirname(fileURLToPath(import.meta.url))
const STATIC_DIR = join(__dirname, "channels/web/static")
const SOUL_PATH = join(MUX_HOME, "soul.md")

// A freshly-installed agent CLI (via the settings install button) lands in a
// per-user bin dir the installer added to shell rc — which this long-running
// process never sourced. Put those dirs on PATH up front so both detection
// (hasBinary) and spawning can see an agent the user installs at runtime.
process.env.PATH = withAgentBinDirs(process.env.PATH, homedir())

// Fail fast before any filesystem side-effects (state dirs, pid file, db).
const preflight = checkPreflight(hasBinary)
for (const w of preflight.warnings) log.warn("preflight", { warning: w })
if (preflight.fatal.length) {
  for (const f of preflight.fatal) log.error("preflight", { error: f })
  process.exit(1)
}

mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 })
mkdirSync(SOCKETS_DIR, { recursive: true, mode: 0o700 })

// load .env
try {
  for (const line of readFileSync(ENV_FILE, "utf8").split("\n")) {
    const m = line.match(/^(\w+)=(.*)$/)
    if (m && process.env[m[1]!] === undefined) process.env[m[1]!] = m[2]!
  }
} catch {}

acquirePidFile(PID_FILE)
process.on("exit", () => releasePidFile(PID_FILE))

// Schema downgrade guard: refuse to start if state was written by a newer build.
// Must run BEFORE openDb so we never touch a DB we can't safely migrate.
// NOTE: this is also the rollback-into-older-migration tripwire — if a forward
// update added migrations and the user then runs `supermux rollback`, the older
// binary lands here and exits. Recovery is a forward `supermux update`, not another rollback.
{
  const stampCheck = checkSchemaStamp(STATE_DIR, MIGRATIONS.length)
  if (!stampCheck.ok) {
    log.error("schema_downgrade_refused", {
      stamp: stampCheck.stamp,
      supported: MIGRATIONS.length,
      hint: `This binary is OLDER than the schema the on-disk state was migrated to (stamp=${stampCheck.stamp} > supported=${MIGRATIONS.length}). An older binary cannot safely run against forward-migrated state. Recover by installing a build at least as new as the one that wrote this state (e.g. \`supermux update\`). NOTE: \`supermux rollback\` will NOT help here — it selects an even older binary.`,
    })
    process.exit(1)
  }
}

// DB creation BEFORE registry
const dbPath = join(STATE_DIR, "db.sqlite3")
let db: ReturnType<typeof openDb>
try {
  db = openDb(dbPath)
  runMigrations(db, MIGRATIONS)
  writeSchemaStamp(STATE_DIR, MIGRATIONS.length)
} catch (err: any) {
  log.error("storage_init_failed", { dbPath, err: err?.message ?? String(err) })
  process.exit(1)
}

const registry = new Registry(db)
const reviewStore = new ReviewStore(db)
const settings = new SettingsStore(db)
const credentialHelperPath = join(STATE_DIR, "bin", "mux-credential")
try { installCredentialLauncher(join(STATE_DIR, "bin"), join(import.meta.dir, "..")) }
catch (err) { log.error("forge_credential_launcher_failed", { err: String(err) }) }
const forgeStore = new ForgeStore(db)
const forgeService = new ForgeService(forgeStore, {
  projectsRoot: join(STATE_DIR, "projects"),
  sshRoot: join(STATE_DIR, "ssh"),
  credentialHelperPath,
})
// Onboarding-editable config: stored values layer over env over built-in defaults.
// Empty store (every existing install) ⇒ resolves exactly to the old env reads.
const appConfigEnv = {
  MUX_PA_NAME: process.env.MUX_PA_NAME,
  MUX_PA_WORKDIR: process.env.MUX_PA_WORKDIR,
  MUX_TELEGRAM_BOT_TOKEN: process.env.MUX_TELEGRAM_BOT_TOKEN,
  MUX_WEB_PUBLIC_URL: process.env.MUX_WEB_PUBLIC_URL,
  MUX_WEB_PORT: process.env.MUX_WEB_PORT,
  MUX_WHATSAPP_GOWA_URL: process.env.MUX_WHATSAPP_GOWA_URL,
  MUX_WHATSAPP_GOWA_BASIC_AUTH: process.env.MUX_WHATSAPP_GOWA_BASIC_AUTH,
  MUX_WHATSAPP_GOWA_DEVICE_ID: process.env.MUX_WHATSAPP_GOWA_DEVICE_ID,
  MUX_WHATSAPP_WEBHOOK_PORT: process.env.MUX_WHATSAPP_WEBHOOK_PORT,
  MUX_WHATSAPP_WEBHOOK_SECRET: process.env.MUX_WHATSAPP_WEBHOOK_SECRET,
}
const appConfig = settings.getAppConfig(appConfigEnv)
// Inject stored agent credentials into the broker env (non-clobbering) so every
// spawn path inherits them: claude's `bash -lc` pane inherits process.env; codex
// reads OPENAI_API_KEY; cursor reads CURSOR_API_KEY. Empty store ⇒ sets nothing
// ⇒ existing-install behavior unchanged.
const appliedCreds = hydrateCredentialEnv(appConfig, process.env)
if (appliedCreds.length) log.info("credentials_hydrated", { vars: appliedCreds })
const TG_TOKEN = appConfig.telegramBotToken || undefined
const hasTelegram = !!TG_TOKEN
// First-boot seed: curator config comes from env once, then the DB is the source
// of truth (edited from the settings page).
if (!settings.has(SETTINGS_KEY_CURATOR)) {
  settings.setCurator(
    parseCuratorConfig({
      enabled: process.env.MUX_CURATOR_ENABLED === "1",
      hour: process.env.MUX_CURATOR_HOUR ? Number(process.env.MUX_CURATOR_HOUR) : undefined,
      chatId: process.env.MUX_CURATOR_CHAT_ID,
    }),
  )
}
// Curator scheduler + run-now are built later (they need spawn/registry helpers),
// but the web settings routes (constructed earlier) reference them at request
// time via these holders.
let curatorScheduler: CuratorScheduler | undefined
let runCuratorNow: () => Promise<void> = async () => {}

// registry.json one-time migration
const REGISTRY_FILE = join(STATE_DIR, "registry.json")
if (existsSync(REGISTRY_FILE)) {
  try {
    const raw = JSON.parse(readFileSync(REGISTRY_FILE, "utf8"))
    for (const s of raw.sessions ?? []) {
      if (!s.agent) s.agent = "claude"
      try {
        registry.register({
          name: s.name,
          agent: s.agent,
          workdir: s.workdir,
          tmux_target: s.tmux_target ?? `${process.env.MUX_TMUX_SESSION ?? "mux"}:${s.name}`,
          pid: s.pid ?? 0,
          model: s.model,
          can_orchestrate: s.can_orchestrate,
          role: s.role ?? "worker",
          is_default: s.is_default ?? false,
          agent_session_id: s.agent_session_id,
          agent_home: s.agent_home,
        })
      } catch (err: any) {
        log.warn("registry_import_session_failed", { name: s.name, err: String(err) })
      }
    }
    // Import chat routing
    const chats = raw.chats ?? {}
    for (const [chat_id, state] of Object.entries(chats)) {
      const cs = state as { active?: string; history?: string[] }
      // Migrate legacy un-namespaced chat_ids
      const normalizedChatId = /^-?\d+$/.test(chat_id) ? `telegram:${chat_id}` : chat_id
      if (cs.active) {
        try {
          registry.setActive(normalizedChatId, cs.active)
        } catch (err: any) {
          log.warn("registry_import_chat_failed", { chat_id, active: cs.active, err: String(err) })
        }
      }
    }
    const { unlinkSync } = await import("fs")
    unlinkSync(REGISTRY_FILE)
    log.info("registry_json_imported_and_deleted")
  } catch (err: any) {
    log.warn("registry_json_import_failed", { err: String(err) })
  }
}

const filesDir = join(STATE_DIR, "files")
const fileStore = new FileStore(db, filesDir)
const messageLog = new MessageStore(db, fileStore)
const searchStore = new SearchStore(db, MUX_HOME)
try {
  searchStore.rebuildKnowledge()
  searchStore.rebuildSessions()
} catch (err: any) {
  log.warn("search_index_rebuild_failed", { err: err?.message ?? String(err) })
}
// Keep find_sessions fresh: index each new broker message as it lands.
messageLog.on("append", (sessionId: string, entry: any) => {
  try { searchStore.indexMessage(sessionId, entry.ts, entry.text ?? "") } catch { /* index is rebuildable */ }
})
const activityStore = new ActivityStore()
const agentStateStore = new AgentStateStore()
const bgTaskStore = new BackgroundTaskStore()

function resolveGitDirs(workdir: string): { gitDir: string; commonDir: string } | null {
  try {
    const gitDir = execFileSync("git", ["rev-parse", "--absolute-git-dir"], { cwd: workdir, encoding: "utf-8" }).trim()
    const raw = execFileSync("git", ["rev-parse", "--git-common-dir"], { cwd: workdir, encoding: "utf-8" }).trim()
    return { gitDir, commonDir: isAbsolute(raw) ? raw : resolve(workdir, raw) }
  } catch { return null }
}

const gitStatusService = new GitStatusService({
  compute: (s) => computeLiteStatus(s),
  resolveGitDirs: (s) => resolveGitDirs(s.workdir),
  watch: (dir, onEvent) => {
    try {
      const w = fsWatch(dir, { persistent: false }, () => onEvent())
      w.on("error", () => {})   // dir may vanish (worktree removed) — degrade silently
      return { close: () => { try { w.close() } catch {} } }
    } catch { return { close: () => {} } }
  },
  onChange: (id, git) => webChannel?.broadcastToAll({ type: "session_git", session: id, git }),
  schedule: (fn, ms) => setTimeout(fn, ms),
  cancel: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
  debounceMs: 400,
})

function gitServiceSessions(): ServiceSession[] {
  return registry.listVisible().map((s) => ({
    id: s.id, workdir: s.workdir,
    repo_root: s.repo_root, base_branch: s.base_branch, session_branch: s.session_branch,
    // base_commits is a { repoRelPath -> HEAD-at-creation } map; a worktree session is single-repo,
    // so its base commit is the sole value. Used for the `touched` (pristine-vs-did-work) flag.
    base_commit: s.base_commits ? Object.values(s.base_commits)[0] ?? null : null,
  }))
}

const tailers = new Map<string, TranscriptTailer>()  // keyed by session UUID
const bgDetectors = new Map<string, BgTaskDetector>()  // keyed by session UUID

function ensureClaudeTailer(sessionUuid: string, _name: string, workdir: string, seekToEnd = false): void {
  const session = registry.get(sessionUuid)
  if (!session || (session.agent ?? "claude") !== "claude") return
  const claudeSid = session.agent_session_id
  if (!claudeSid || tailers.has(sessionUuid)) return
  const detector = new BgTaskDetector({
    onOpen: (t) => bgTaskStore.upsertOpen(sessionUuid, t),
    onClose: (c) => bgTaskStore.close(sessionUuid, c),
    // Notification delivery = the harness waking claude; reflect it immediately
    // (same transcript-as-signal channel as interrupt detection).
    onWake: () => agentStateStore.applyEvent(sessionUuid, "turn-start"),
  })
  bgDetectors.set(sessionUuid, detector)
  const tailer = new TranscriptTailer({
    path: claudeTranscriptPath(workdir, claudeSid),
    onLine: (line) => bgDetectors.get(sessionUuid)?.feedLine(line),
    onEvent: (event) => {
      // The transcript interrupt marker is the SOLE interrupt signal (no hook fires
      // on ESC) — and it catches terminal-direct ESC too. It is state, not activity.
      if (event.kind === "interrupt") { agentStateStore.applyEvent(sessionUuid, "interrupt"); return }
      activityStore.append(sessionUuid, event)
    },
    seekToEnd,
  })
  tailer.start()
  tailers.set(sessionUuid, tailer)
}

function stopClaudeTailer(sessionUuid: string): void {
  tailers.get(sessionUuid)?.stop()
  tailers.delete(sessionUuid)
  bgDetectors.delete(sessionUuid)
  activityStore.clear(sessionUuid)
  bgTaskStore.clear(sessionUuid)
}

const modelCache = new ModelCache()

const modelDiscoverers: ModelDiscoverers = {
  [AgentKind.Claude]: discoverClaudeModels,
  [AgentKind.Codex]: discoverCodexModels,
  [AgentKind.Cursor]: discoverCursorModels,
  [AgentKind.OpenCode]: discoverOpenCodeModels,
}

// The model cache is otherwise frozen at boot. If discovery fails transiently
// at startup (OAuth token not yet refreshed, network not ready), the picker
// would stay empty until the next broker restart. Re-discover every agent on a
// timer to recover, and to pick up newly-installed/updated CLIs (e.g. a new
// Codex model) without a restart. All discoverers are async (Claude over the
// network; the CLI-based ones via non-blocking exec), so polling never stalls
// the event loop.
const MODEL_REFRESH_INTERVAL_MS = 15 * 60_000

function refreshModels(discoverers: ModelDiscoverers = modelDiscoverers): Promise<void> {
  return refreshModelCache(modelCache, discoverers, {
    onEmpty: (agent) => log.warn("model_discovery_empty", { agent }),
  })
}

function lookupModels(agent: AgentKind) {
  return modelCache.get(agent)
}

function sessionEffort(session: { agent: AgentKind; model?: string; reasoningLevel?: string }): string | undefined {
  return resolveSessionEffort(session, lookupModels)
}

async function maybeAutoSendSoulSetup(sessionId: string): Promise<void> {
  const session = registry.get(sessionId)
  if (!session) return
  const state = readSoulSetupState()
  const messages = messageLog.get(session.id, 20)
  if (!shouldAutoSendSoulSetup({
    session,
    messages,
    state,
    alreadyQueued: soulSetupQueued.has(session.id),
  })) return

  soulSetupQueued.add(session.id)
  const deliver = async (id: string, text: string, meta: Record<string, string>) => {
    const current = registry.get(id)
    const adapter = current ? adapters.get(current.id) : undefined
    if (adapter) {
      await adapter.send(text, meta)
    } else {
      await server.sendInbound(id, { content: text, meta })
    }
  }

  try {
    await appendSoulSetupInvocation({ session, messageLog, deliver })
    log.info("soul_setup_auto_sent", { session: session.name, id: session.id })
  } catch (err: any) {
    soulSetupQueued.delete(session.id)
    log.warn("soul_setup_auto_send_failed", { session: session.name, err: err?.message ?? String(err) })
  }
}

const vapidSubject = process.env.MUX_WEB_VAPID_SUBJECT ?? (() => {
  try {
    const u = new URL(process.env.MUX_WEB_PUBLIC_URL ?? "https://localhost")
    return `mailto:supermux@${u.hostname}`
  } catch { return "mailto:supermux@localhost" }
})()
const vapid = loadOrGenerateVapid(join(STATE_DIR, "push-keys.json"), vapidSubject)
const pushStore = new PushSubscriptionStore(db)
const pushSender = createPushSender({ vapid, store: pushStore })
const deviceTokenStore = new DevicePushTokenStore(db)
const relayUrl = process.env.MUX_PUSH_RELAY_URL ?? "https://push.supermux.dev"
const nativeSender = createRelayClient({ store: deviceTokenStore, relayUrl })
const nativeDevices = () => deviceTokenStore.all().filter((r) => r.routing_token).map((r) => r.device)
const viewingTracker = new ViewingTracker()
log.info("push_ready", { publicKey: vapid.publicKey.slice(0, 16) + "…", subject: vapid.subject })

// hourly GC sweep — orphan attachments (24h) + abandoned in-flight uploads (TTL)
const pendingTtlHours = Number(process.env.MUX_UPLOAD_PENDING_TTL_HOURS ?? 24)
const gcInterval = setInterval(() => {
  fileStore.gcOnce({ graceHours: 24 }).catch((err) => log.error("filestore_gc_failed", { err: err?.message ?? String(err) }))
  fileStore.gcPendingOnce({ ttlHours: pendingTtlHours }).catch((err) => log.error("filestore_gc_pending_failed", { err: err?.message ?? String(err) }))
}, 60 * 60 * 1000)

const TMUX_SESSION = process.env.MUX_TMUX_SESSION ?? "mux"
// The addressable tmux window id for a session, healing a missing id once via a
// name->id resolve (then persisted), so every kill/interrupt/liveness/consent
// path routes by id — never by window name. Returns null when no live window
// can be found, so callers no-op + log instead of routing by a stale name.
const widOf = (s: { id: string; name: string; tmux_window_id?: string }) =>
  ensureWindowId(s, {
    tmuxSession: TMUX_SESSION,
    resolve: resolveWindowIdByName,
    persist: (id, wid) => registry.sessions.setTmuxWindowId(id, wid),
  })
const replyOwner = new Map<string, string>()              // key: `${chat_id}:${message_id}`
const pendingSpawnActive = new Map<string, string>()      // expectedName → channelChatId
const pendingClaudeSessionId = new Map<string, string>()  // brokerSessionId → claudeSessionId
const pendingTmuxWindowId = new Map<string, string>()      // brokerSessionId → tmux window id
// Claude sessions register asynchronously via the shim's onRegister (not in the
// spawn helper), so a worker that must be marked broker-internal records the
// intent here keyed by broker session id; onRegister consumes it. Same deferred
// pattern as pendingTmuxWindowId/pendingClaudeSessionId above.
const pendingInternal = new Set<string>()                  // brokerSessionId (internal=true)
// agent-rpc registry. Assigned once below, after spawnSession/killSession/
// deliverInbound (its deps) are all defined; declared here so it's in scope for
// the orchestration dispatch (rpc_resolve / rpc_reject) further up.
let agentRpc: ReturnType<typeof createAgentRpc>

const telegram: TelegramChannel | undefined = hasTelegram
  ? new TelegramChannel({ token: TG_TOKEN!, fileStore })
  : undefined
const WA_GOWA_URL = appConfig.whatsappGowaUrl || undefined
const hasWhatsApp = !!WA_GOWA_URL
const whatsapp: WhatsAppChannel | undefined = hasWhatsApp
  ? new WhatsAppChannel({
      gowaUrl: WA_GOWA_URL!,
      gowaBasicAuth: appConfig.whatsappGowaBasicAuth || undefined,
      gowaDeviceId: appConfig.whatsappGowaDeviceId || undefined,
      webhookPort: Number(appConfig.whatsappWebhookPort) || 3001,
      webhookSecret: appConfig.whatsappWebhookSecret || "secret",
      fileStore,
    })
  : undefined
const channels: Record<string, Channel> = {
  ...(telegram ? { telegram } : {}),
  ...(whatsapp ? { whatsapp } : {}),
}

// adapters keyed by session UUID (not session name)
const adapters = new Map<string, AgentAdapter>()
const runtimes = new RuntimeRegistry()
const soulSetupQueued = new Set<string>()
const SOUL_SETUP_AUTO_SEND_DELAY_MS = 3_000

function registerRuntime(sessionId: string, runtime: SessionRuntime): void {
  runtimes.set(sessionId, runtime)
  adapters.set(sessionId, runtime.adapter)
}

function deleteRuntime(sessionId: string): void {
  runtimes.delete(sessionId)
  adapters.delete(sessionId)
}

function registerClaudeRuntime(sessionId: string, adapter: ClaudeCodeAdapter): void {
  registerRuntime(sessionId, { kind: AgentKind.Claude, adapter })
}

function registerCodexRuntime(sessionId: string, name: string, adapter: CodexAdapter, handle: CodexSpawnHandle): void {
  registerRuntime(sessionId, { kind: AgentKind.Codex, adapter, handle })
  handle.onExit?.((code: number | null) => {
    log.info("codex_app_server_exited", { name, code })
    deleteRuntime(sessionId)
  })
}

function registerCursorRuntime(sessionId: string, adapter: CursorAdapter): void {
  registerRuntime(sessionId, { kind: AgentKind.Cursor, adapter })
}

function registerOpenCodeRuntime(sessionId: string, name: string, adapter: OpenCodeAdapter, handle: OpenCodeSpawnHandle): void {
  registerRuntime(sessionId, { kind: AgentKind.OpenCode, adapter, handle })
  handle.onExit?.((code: number | null) => {
    log.info("opencode_serve_exited", { name, code })
    deleteRuntime(sessionId)
  })
}

// Slash-command discovery: per-session command list (control + agent commands
// tapped from each CLI's native protocol). Broadcast to web on change.
function opencodePluginDirs(sessionName: string): string[] {
  return opencodeConfigEntries({ sessionName }).pluginPaths
}

const cursorCommandProvider = new CursorCommandProvider()
const commandRegistry = new CommandRegistry({
  providers: {
    claude: new ClaudeCommandProvider(),
    codex: new CodexCommandProvider(),
    cursor: cursorCommandProvider,
    opencode: new OpenCodeCommandProvider(),
  },
  resolveSession: (name) => {
    const s = registry.resolveName(name)
    if (!s) return undefined
    const kind = ((s.agent ?? "claude") as AgentKind)
    const pluginSpawnArgs =
      kind === "codex" ? codexSpawnArgs({ sessionName: s.name }).args
      : kind === "cursor" ? cursorSpawnArgs({ sessionName: s.name }).args
      : kind === "opencode" ? []
      : claudeSpawnArgs({ sessionName: s.name }).args
    const adapter = adapters.get(s.id) as { rpc?: import("./core/slash-commands/types").CodexRpc; commandClient?: import("./core/slash-commands/types").OpenCodeCommandClient } | undefined
    return {
      name: s.name,
      kind,
      workdir: s.workdir,
      muted: !!s.mute,
      pluginSpawnArgs,
      codexClient: kind === "codex" ? adapter?.rpc : undefined,
      opencodeClient: kind === "opencode" ? adapter?.commandClient : undefined,
      opencodePluginDirs: kind === "opencode" ? opencodePluginDirs(s.name) : undefined,
    }
  },
  // Key the broadcast by session *id* (UUID), not name: the snapshot frame and
  // every frontend lookup (route /s/:id) are keyed by id. Keying this by name
  // wrote to commands[name], which the UI never reads → new sessions sat stuck
  // on "loading agent commands…" until a reconnect resent the (id-keyed)
  // snapshot. See registry.onChange, which hands us the name.
  onChange: (name, commands) => {
    const id = registry.resolveName(name)?.id ?? name
    webChannel?.broadcastToAll({ type: "commands_changed", session: id, commands, resolved: true })
  },
})

function findCodexClient(): import("./core/slash-commands/types").CodexRpc | undefined {
  for (const s of registry.list()) {
    if (s.agent !== "codex") continue
    const adapter = adapters.get(s.id) as { rpc?: import("./core/slash-commands/types").CodexRpc } | undefined
    if (adapter?.rpc) return adapter.rpc
  }
  return undefined
}

function pluginSpawnArgsForAgent(kind: import("./core/agents/types").AgentKind): string[] {
  return kind === "codex" ? codexSpawnArgs({ sessionName: "__preview__" }).args
    : kind === "cursor" ? cursorSpawnArgs({ sessionName: "__preview__" }).args
    : kind === "opencode" ? []
    : claudeSpawnArgs({ sessionName: "__preview__" }).args
}

function opencodePluginDirsForPreview(): string[] {
  return opencodeConfigEntries({ sessionName: "__preview__" }).pluginPaths
}

function unregisterSession(id: string): void {
  const s = registry.get(id)
  registry.unregister(id)  // archives the session (resumable via resumeFromArchive)
  if (s) deleteRuntime(s.id)
  commandRegistry.remove(id)
  agentStateStore.clear(id)  // drop any lingering working/dead state for the now-archived session
  bgTaskStore.clear(id)      // archived sessions cannot be "waiting"
  // NOTE: do NOT delete agent_home here — archived sessions are resumable, so
  // their home (cursor runtime symlink + per-session state/history) must
  // survive. Truly orphaned dirs (no registry entry) are reclaimed by the
  // startup orphan-GC instead.
  gitStatusService.sync(gitServiceSessions())  // release fs-watch for the now-archived session
}

// Resolve an inbound attachment file_id to a local path, for codex/cursor
// sessions. Claude gets attachments via the download_attachment MCP tool; the
// other agents have no such tool, so their adapters resolve the file themselves
// and feed it into the turn (codex: localImage item / path-in-prompt; cursor:
// path-in-prompt). Mirrors the download_attachment op resolution below.
async function resolveAttachmentPath(file_id: string): Promise<string> {
  const r = await resolveDownloadAttachment({
    file_id,
    fileStore,
    telegramApi: telegram ? { token: TG_TOKEN!, getFile: (id: string) => telegram.getFile(id) } : undefined,
    inboxDir: INBOX_DIR,
  })
  return r.path
}

async function onAssistantMessage(
  sessionId: string,
  ev: { text: string; chat_id?: string; reply_to?: string; files?: string[]; format?: "text" | "markdownv2"; keyboard?: string[] },
): Promise<void> {
  agentStateStore.applyEvent(sessionId, "Stop")
  const sessionEntry = registry.get(sessionId)
  const sessionName = sessionEntry?.name ?? sessionId

  // Resolve file paths relative to the session's working directory.
  // Agents pass relative paths (e.g. "./output.png") from their cwd, but
  // transformOutbound reads from the broker's cwd which is different.
  const workdir = sessionEntry?.workdir ?? process.cwd()
  const resolvedFiles = ev.files?.map((fp) => resolve(workdir, fp))

  // If chat_id is explicit (shim reply path), dispatch to that single chat.
  // Otherwise (stream-derived), fan out to all chats where this session is active.
  const targets: { channelName: string; chat_id: string }[] = []
  if (ev.chat_id) {
    // "web" is the single logical web channel (no colon). A colon-prefixed id
    // names its channel directly. A bare legacy id is a telegram chat.
    const channelName = ev.chat_id === "web" ? "web" : ev.chat_id.includes(":") ? ev.chat_id.split(":", 1)[0]! : "telegram"
    const chat_id = ev.chat_id === "web" || ev.chat_id.includes(":") ? ev.chat_id : `telegram:${ev.chat_id}`
    targets.push({ channelName, chat_id })
  } else {
    for (const [chat_id, state] of registry.chats.allChats()) {
      if (state.active_session_id !== sessionId) continue
      const channelName = chat_id.split(":", 1)[0]!
      if (!channels[channelName]) continue
      targets.push({ channelName, chat_id })
    }
  }
  let dispatchedCount = 0
  let lastError: string | undefined
  for (const { channelName, chat_id } of targets) {
    const ch = channels[channelName]!
    const initial: OutboundAction = {
      op: "reply", chat_id, text: ev.text,
      reply_to: ev.reply_to, files: resolvedFiles, format: ev.format, keyboard: ev.keyboard,
    }
    try {
      const action = await transformOutbound(initial, sessionId, ch.capabilities, fileStore, registry)
      if (action.op !== "reply") continue
      const res = await ch.send(action)
      if (!res.ok) {
        log.warn("dispatch_reply_failed", { sessionName, chat_id, err: res.error })
        lastError = res.error ?? "channel send failed"
        continue
      }
      dispatchedCount++
      firePushForReply({
        sender: pushSender, action, sessionName, sessionId,
        isMuted: () => !!registry.get(sessionId)?.mute,
        isInternal: () => !!registry.get(sessionId)?.internal,
        devices: () => pushStore.all().map((s) => s.device),
        anyPresent: (sid) => viewingTracker.isAnyPresentFor(sid),
        nativeSender,
        nativeDevices,
        hostId: hostIdentity.hostId,
      }).catch((err) => log.warn("push_hook_failed", { err: err?.message ?? String(err) }))
      const mid = (res.value as any)?.message_id
      // Store the immutable session ID, not the name: classifyInbound's quote-reply
      // path looks this up via registry.get() (id-only), and names change via /rename.
      // Storing the name made every reply-to silently miss → fell through to the
      // active session (masked on Telegram when replying to the already-active one).
      if (mid) replyOwner.set(`${chat_id}:${mid}`, sessionId)
      messageLog.append(sessionId, {
        id: mid ? `out:${chat_id}:${mid}` : `out:${chat_id}:ts:${Date.now()}`,
        ts: new Date().toISOString(),
        direction: "outbound", channel: channelName, chat_id,
        message_id: mid ? String(mid) : undefined,
        op: "reply", text: action.text,
        attachments: action.attachments?.map((a) => ({
          file_id: a.file_id, kind: a.kind, mime: a.mime, size: a.size, name: a.name,
        })),
      })
    } catch (err: any) {
      log.warn("dispatch_reply_threw", { sessionName, chat_id, err: String(err) })
      lastError = String(err)
    }
  }
  if (dispatchedCount === 0) {
    throw new Error(lastError ?? "no dispatch targets succeeded")
  }
}

async function notifyAgentError(sessionId: string, sessionName: string, errorType: string, errorMessage: string): Promise<void> {
  // clear the live status (the turn ended in failure)
  agentStateStore.applyEvent(sessionId, "Stop")
  // in-app toast for any open PWA
  webChannel?.broadcastToAll({ type: "agent_error", session: sessionName, errorType, errorMessage })
  // push to the session's most-recent web chat (so it lands even if the PWA is closed)
  try {
    const entries = messageLog.get(sessionId)
    let lastWeb: string | undefined
    for (let i = entries.length - 1; i >= 0; i--) {
      const c = entries[i]?.chat_id
      if (typeof c === "string" && c.startsWith("web:")) { lastWeb = c; break }
    }
    if (lastWeb && !registry.get(sessionId)?.mute) {
      await pushSender.sendToChat(lastWeb, { session: sessionName, text: `⚠️ ${errorType}: ${errorMessage}`.slice(0, 180), ts: new Date().toISOString() })
      for (const r of deviceTokenStore.all()) if (r.routing_token) void nativeSender.sendToDevice(r.device, { session: sessionName, text: `⚠️ ${errorType}: ${errorMessage}`.slice(0, 180), ts: new Date().toISOString() })
    }
  } catch (err) { log.warn("agent_error_push_failed", { session: sessionName, err: String(err) }) }
}

// Soft-interrupt a Claude session by sending a single Esc to its tmux pane —
// Claude's native "stop generating" key. The pane runs Claude as the foreground
// process, so send-keys to its window reaches the REPL. Addressed strictly by
// window id (healed from the registry) so a rename can't aim us at a stale name.
async function interruptClaudePane(sessionId: string): Promise<void> {
  const s = registry.get(sessionId)
  if (!s || s.agent !== AgentKind.Claude) return
  const wid = await widOf(s)
  if (!wid) { log.warn("claude_interrupt_no_tmux", { sessionId }); return }
  await sendKeysToWindowId(wid, ["Escape"])
}

// The one funnel every Stop surface (web button, /stop command) routes through:
// dispatch to the agent's own interrupt(). The broker does NOT flip the live
// status itself — idle is reflected from the session: Claude's interrupt marker
// in the transcript, or codex/cursor turn-complete.
async function interruptSessionById(sessionId: string): Promise<{ ok: boolean; reason?: string }> {
  return runInterrupt({ adapter: adapters.get(sessionId) })
}

type FinishRequest = { action: FinishAction; skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string; draft?: boolean; prRequiresGreen?: boolean; prTitle?: string; prBody?: string }

async function finishSessionById(sessionId: string, req: FinishRequest): Promise<FinishJob | { error: string }> {
  const s = registry.get(sessionId)
  if (!s) return { error: "no such session" }
  if (!s.repo_root || !s.session_branch || !s.base_branch) return { error: "session is not worktree-backed" }
  const prev = getFinishJob(sessionId)
  if (prev && prev.status !== "running") clearFinishJob(sessionId)
  // discard must stop the live agent BEFORE its worktree is force-removed
  if (req.action === "discard") await killSession(sessionId).catch(() => {})
  const session = { id: sessionId, repoRoot: s.repo_root, worktreeDir: s.workdir, sessionBranch: s.session_branch, baseBranch: s.base_branch }
  const cfg = loadFinishConfig(s.repo_root)
  const opts: FinishJobOpts = { ...req, cleanup: false, prRequiresGreen: req.prRequiresGreen ?? cfg.prRequiresGreen }  // worktree removal handled by the archive path below
  const sessionName = s.name
  return startFinishJob(session, opts, {
    onUpdate: (job) => webChannel?.broadcastToAll({ type: "finish_job", session: sessionId, job }),
    persist: (job) => { try { registry.sessions.setFinishJob(sessionId, job) } catch {} },
    notify: (job) => { void onFinishTerminal(sessionId, sessionName, job) },
  })
}

async function onFinishTerminal(sessionId: string, sessionName: string, job: FinishJob): Promise<void> {
  fireFinishPush(sessionName, sessionId, job)
  const status = job.outcome?.status
  const s = registry.get(sessionId)
  const archiveMerge = job.action === "merge" && status === "integrated" && (!s?.repo_root || loadFinishConfig(s.repo_root).archiveOnMerge)
  const archiveDiscard = job.action === "discard" && status === "discarded"
  if (archiveMerge || archiveDiscard) {
    try {
      if (archiveMerge) await killSession(sessionId).catch(() => {})  // discard already killed before the job
      unregisterSession(sessionId)
      await refreshTelegramMenu().catch(() => {})
      webChannel?.broadcastToAll({ type: "session_removed", id: sessionId })
    } catch (e) { log.warn("finish_archive_failed", { id: sessionId, err: String(e) }) }
  }
}

function fireFinishPush(sessionName: string, sessionId: string, job: FinishJob): void {
  const o = job.outcome
  if (!o) return
  let text: string | null = null
  const br = sessionName
  switch (o.status) {
    case "integrated": text = `✅ Merged ${br} into ${o.base}`; break
    case "pr_opened": text = `📤 PR opened for ${br}`; break
    case "branch_published": text = `📤 Pushed ${br} — open the PR manually`; break
    case "discarded": text = `🗑️ Discarded ${br}`; break
    case "tests_failed": text = `❌ Tests failed in ${br}`; break
    case "sync_conflict": case "dirty_overlap": text = `⚠️ Conflicts finishing ${br}`; break
    case "push_auth_failed": text = `🔒 Push auth failed for ${br}`; break
    case "push_rejected": text = `⚠️ Push rejected (diverged) for ${br}`; break
    case "uncommitted": text = `⚠️ Uncommitted changes in ${br}`; break
    case "no_verify": text = `⚠️ No verify configured for ${br}`; break
    case "non_ff": text = `⚠️ ${br} — base moved, retry the merge`; break
    case "error": text = `❌ Finish failed for ${br}`; break
    // kept / nothing_to_do → no push
  }
  if (!text) return
  try { for (const sub of pushStore.all()) void pushSender.sendToDevice(sub.device, { session: sessionName, sessionId, text, ts: new Date().toISOString() }) } catch {}
  try { for (const r of deviceTokenStore.all()) if (r.routing_token) void nativeSender.sendToDevice(r.device, { session: sessionName, sessionId, text, ts: new Date().toISOString() }) } catch {}
}

function finishReadinessById(sessionId: string): FinishReadiness | { error: string } {
  const s = registry.get(sessionId)
  if (!s) return { error: "no such session" }
  if (!s.repo_root || !s.session_branch || !s.base_branch) return { error: "session is not worktree-backed" }
  const cfg = loadFinishConfig(s.repo_root)
  return computeReadiness({ repoRoot: s.repo_root, worktreeDir: s.workdir, sessionBranch: s.session_branch, baseBranch: s.base_branch, defaultAction: cfg.defaultAction, prRequiresGreen: cfg.prRequiresGreen })
}

// Wire a codex/cursor adapter's structured events into the agent-agnostic
// activity timeline + live status. (Claude uses its own transcript/hook path.)
function wireAdapterEvents(adapter: AgentAdapter, sessionId: string): void {
  adapter.on("assistant-message", (ev: any) => {
    onAssistantMessage(sessionId, ev).catch((err) => log.warn("dispatch_assistant_failed", { err: String(err) }))
  })
  adapter.on("tool-call", (ev: any) => {
    const now = Date.now()
    try {
      for (const a of toActivityEvents(adapter.kind, ev, now)) activityStore.append(sessionId, a)
    } catch (err) { log.warn("adapter_tool_call_activity_failed", { err: String(err) }) }
    if (ev?.phase === "started") agentStateStore.applyEvent(sessionId, "PreToolUse", normalizeToolName(adapter.kind, ev.tool), now)
    else agentStateStore.applyEvent(sessionId, "PostToolUse", undefined, now)
  })
  adapter.on("turn-start", () => agentStateStore.applyEvent(sessionId, "turn-start"))
  adapter.on("turn-complete", () => agentStateStore.applyEvent(sessionId, "Stop"))
  adapter.on("error", (ev: any) => {
    const session = registry.get(sessionId)
    void notifyAgentError(sessionId, session?.name ?? sessionId, "error", String(ev?.error?.message ?? ev?.error ?? "agent error"))
  })
  // The codex/cursor adapter is now wired (and, for codex, its app-server client
  // is reachable) — discover this session's slash commands.
  const wiredName = registry.get(sessionId)?.name
  if (wiredName) void commandRegistry.refresh(wiredName)
}

const webEnv = validateWebEnv(process.env.MUX_WEB_PORT, process.env.MUX_WEB_PUBLIC_URL)
if (webEnv.error) { log.error("web_env_invalid", { error: webEnv.error }); process.exit(1) }
const channelCheck = requireAtLeastOneChannel(hasTelegram, webEnv.enabled, hasWhatsApp)
if (channelCheck.error) { log.error("no_channel_configured", { error: channelCheck.error }); process.exit(1) }
const MUX_WEB_PORT = process.env.MUX_WEB_PORT ? parseInt(process.env.MUX_WEB_PORT, 10) : undefined
const MUX_WEB_PUBLIC_URL = process.env.MUX_WEB_PUBLIC_URL
let webChannel: WebChannel | undefined
// Background liveness poller for exposed proxies. Constructed BEFORE the
// WebChannel so the channel opts (listProxies/createProxy/updateProxy) can call
// monitor.getStatus; its onChange closes over webChannel (assigned just below)
// and only fires after start(), well after webChannel is set — same pattern the
// proxy create/remove opts already use. Started after webChannel.start().
const proxyLivenessMonitor = new ProxyLivenessMonitor({
  listTargets: () => registry.listProxies().map((p) => ({ domain: p.domain, port: p.port })),
  onChange: (domain, status) => webChannel?.broadcastToAll({ type: "proxy_status", domain, status }),
})
// loginManager is declared here (before webChannel opts, which close over it) and
// assigned after webChannel is constructed (so its onChange can reference webChannel).
// Both closures are arrow functions that run at request/event time — well after both
// are assigned. TypeScript sees the definite-assignment gap; the nullable guard on
// webChannel inside onChange covers the window before webChannel is assigned.
let loginManager: LoginManager
// respawnPAsAfterOnboarding is assigned after supervisor is created (below).
// setAppConfig closes over this binding — safe since it's only invoked at request-time.
let respawnPAsAfterOnboarding: () => Promise<void> = async () => {}
const terminalManager = new TerminalManager()
const displayManager = new DisplayManager({
  providers: [new LinuxXvfbProvider(), new MacosScreenProvider()],
  onAdded: (info) => webChannel?.broadcastToAll({ type: "display_added", display: info }),
  onRemoved: (id) => webChannel?.broadcastToAll({ type: "display_removed", id }),
})
const fsWatcher = new FsWatcher()

function spawnLoginProc(kind: string) {
  const h = homedir()
  let cmd: string, args: string[]
  let detached = false
  const env = { ...process.env } as Record<string, string>
  if (kind === "codex") { cmd = "codex"; args = ["login", "--device-auth"]; env.CODEX_HOME = `${h}/.codex` }
  else if (kind === "cursor") { cmd = "cursor-agent"; args = ["login"]; env.NO_OPEN_BROWSER = "1" }
  else if (kind === "claude") {
    const spec = claudeLoginSpawnCommand()
    ;({ cmd, args } = spec)
    detached = spec.detached ?? false
  }
  else { cmd = ""; args = [] } // unknown kind handled below
  let outCb: (c: string) => void = () => {}
  let exitCb: (code: number | null) => void = () => {}
  if (!cmd) {
    queueMicrotask(() => { outCb(`no device login for ${kind}`); exitCb(127) })
    return { onStdout: (cb: (c: string) => void) => { outCb = cb }, onExit: (cb: (code: number | null) => void) => { exitCb = cb }, kill: () => {}, write: () => {} }
  }
  let child: ReturnType<typeof nodeSpawn>
  try {
    child = nodeSpawn(cmd, args, { env, detached })
  } catch (err: any) {
    // Missing binary (ENOENT) etc — report failure instead of crashing the broker.
    log.warn("login_spawn_failed", { kind, cmd, err: err?.message ?? String(err) })
    queueMicrotask(() => { outCb(`${cmd} is not available: ${err?.message ?? err}`); exitCb(127) })
    return { onStdout: (cb: (c: string) => void) => { outCb = cb }, onExit: (cb: (code: number | null) => void) => { exitCb = cb }, kill: () => {}, write: () => {} }
  }
  // Async spawn errors (rare path) must also not throw unhandled.
  child.on("error", (err: any) => { outCb(`${cmd} error: ${err?.message ?? err}`); exitCb(1) })
  return {
    onStdout: (cb: (c: string) => void) => {
      outCb = cb
      child.stdout?.on("data", (d) => cb(d.toString()))
      child.stderr?.on("data", (d) => cb(d.toString()))
    },
    onExit: (cb: (code: number | null) => void) => { exitCb = cb; child.on("exit", cb) },
    kill: () => {
      try {
        if (detached && child.pid) process.kill(-child.pid, "SIGTERM")
        else child.kill()
      } catch {}
    },
    write: (data: string) => { try { child.stdin?.write(data) } catch {} },
  }
}

// Secret gating the localhost-only /internal/agent-hook endpoint (embedded in
// the Claude hook curl URLs). Stable across restarts — Claude Code snapshots
// hook config at CLI startup, so rotating this per boot would silently 403 the
// hooks of every session that outlives a restart, freezing their status at
// "idle". Generated once, persisted next to the hooks file it's embedded in.
const INTERNAL_SECRET = resolveInternalHookSecret(() => randomBytes(24).toString("hex"))

// In-app update checker. Kill switch MUX_UPDATE_CHECK=0 → no checker at all
// (the web routes then report disabled). Otherwise it polls versions.json on a
// boot-jittered interval; failures are recorded as lastError, never thrown, so a
// refused/offline update host can't crash the broker. Surfaced to the PWA via
// the web channel's /api/update/* routes; stopped in gracefulShutdown.
const updateChecker = process.env.MUX_UPDATE_CHECK === "0" ? null : new UpdateChecker({
  url: process.env.MUX_UPDATE_URL ?? "https://supermux.dev/versions.json",
  currentVersion: BUILD_VERSION,
  commit: BUILD_COMMIT,
  mode: detectUpdateMode(),
})
updateChecker?.start()

// Read status: a session is "read" up to its newest message whenever a device
// is actively viewing it. advanceRead persists last_read_at and broadcasts
// session_read to every client — idempotent, so it no-ops when nothing changed.
const advanceRead = makeReadAdvancer({
  sessions: registry.sessions,
  messages: messageLog,
  broadcast: (frame) => webChannel?.broadcastToAll(frame),
})

// Host identity (Ed25519 keypair, derived hostId) + one-time pairing claims.
const hostIdentity = loadOrCreateHostKey(HOST_KEY_FILE)
const claimStore = new ClaimStore()
setInterval(() => claimStore.sweep(), 60_000).unref()
const hostPlatform = process.platform === "darwin" ? "macos" : process.platform === "win32" ? "windows" : "linux"
const advertisedHostName = process.env.MUX_HOST_NAME?.trim() || hostname()

// Relay data plane (frp). Opt-in via MUX_RELAY_DOMAIN until the spike passes and
// a relay box exists; otherwise LAN/direct only. Swappable behind RelayProvider.
const relayProvider = process.env.MUX_RELAY_DOMAIN
  ? new FrpRelayProvider({
      identity: hostIdentity,
      relayBase: process.env.MUX_RELAY_BASE ?? `https://control.${process.env.MUX_RELAY_DOMAIN}`,
      relayDomain: process.env.MUX_RELAY_DOMAIN,
      localPort: MUX_WEB_PORT ?? 9898,
      getNonce: async () => {
        const r = await fetch(`${process.env.MUX_RELAY_BASE ?? `https://control.${process.env.MUX_RELAY_DOMAIN}`}/relay/nonce`)
        return ((await r.json()) as { nonce: string }).nonce
      },
      spawn: (argv) => Bun.spawn(parentBoundFrpcCommand(argv), { stdout: "ignore", stderr: "ignore" }),
      writeConfig: (toml) => { const p = join(STATE_DIR, "frpc.toml"); writeFileSync(p, toml, { mode: 0o600 }); return p },
    })
  : new NullRelayProvider()
void relayProvider.start()

if (MUX_WEB_PORT && MUX_WEB_PUBLIC_URL) {
  // One install job per agent; "installed" is re-probed (binary on PATH) after
  // the installer exits. Referenced lazily by the startAgentInstall closures.
  const installManager = createInstallManager({
    isInstalled: (kind) => detectAgent(kind, { hasBinary, fileExists: existsSync }, { home: homedir() }).installed,
  })
  webChannel = new WebChannel({
    updateChecker,
    getHostInfo: () => ({
      hostId: hostIdentity.hostId,
      name: advertisedHostName,
      platform: hostPlatform,
      version: BUILD_VERSION,
      protocolVersion: 1,
    }),
    claimStore,
    getRelayUrl: () => relayProvider.status().relayUrl,
    port: MUX_WEB_PORT,
    devicesFile: DEVICES_FILE,
    publicUrl: MUX_WEB_PUBLIC_URL,
    staticDir: STATIC_DIR,
    staticEmbedded: EMBEDDED_STATIC,
    internalSecret: INTERNAL_SECRET,
    getCuratorSettings: () => ({ config: settings.getCurator(), nextRun: curatorScheduler?.nextRun()?.toISOString() ?? null }),
    setCuratorSettings: (cfg) => {
      const c = parseCuratorConfig(cfg, settings.getCurator())
      settings.setCurator(c)
      curatorScheduler?.reconfigure(c)
      return { config: c, nextRun: curatorScheduler?.nextRun()?.toISOString() ?? null }
    },
    runCuratorNow: () => runCuratorNow(),
    getEditorConfig: () => settings.getEditorConfig(),
    getEditorSettings: () => ({ lsp: { servers: listLspServerSettingsRows(settings.getEditorConfig()) } }),
    setEditorSettings: (patch) => {
      settings.setEditorConfig(patch as import("./core/settings/editor-config").EditorConfig)
      return { lsp: { servers: listLspServerSettingsRows(settings.getEditorConfig()) } }
    },
    installEditorLspServer: async (serverId) => {
      const cfg = settings.getEditorConfig()
      const spec = getServerById(serverId, cfg)
      if (!spec?.install) return { ok: false, lines: ["not installable"] }
      const result = await runInstallToCompletion(spec.install)
      const ok = result.ok && isServerInstalled(spec)
      return { ok, lines: result.lines }
    },
    addCustomEditorLspServer: (body) => {
      try {
        const o = (body ?? {}) as Record<string, unknown>
        settings.addCustomLspServer(String(o.id ?? ""), {
          label: o.label,
          command: o.command,
          args: o.args,
          extensions: o.extensions,
          languageId: o.languageId,
          installCmd: o.installCmd,
        } as import("./core/settings/editor-config").CustomLspServerDef)
        return { ok: true, lsp: { servers: listLspServerSettingsRows(settings.getEditorConfig()) } }
      } catch (e: any) {
        return { ok: false, error: e?.message ?? "invalid server" }
      }
    },
    removeCustomEditorLspServer: (serverId) => {
      try {
        settings.removeCustomLspServer(serverId)
        return { ok: true, lsp: { servers: listLspServerSettingsRows(settings.getEditorConfig()) } }
      } catch (e: any) {
        return { ok: false, error: e?.message ?? "not found" }
      }
    },
    listChatIds: () =>
      (db.query("SELECT DISTINCT chat_id FROM messages WHERE chat_id LIKE 'web:%' ORDER BY chat_id").all() as { chat_id: string }[]).map((r) => r.chat_id),
    getSessionsSnapshot: () => {
      gitStatusService.sync(gitServiceSessions())
      return registry.listVisible().map((s) => ({
        id: s.id,
        name: s.name,
        workdir: s.workdir,
        mute: !!s.mute,
        connected: !!s.connected,
        agent: s.agent,
        role: s.role,
        isDefault: s.is_default,
        model: s.model,
        reasoningLevel: s.reasoningLevel,
        status: s.status,
        session_branch: s.session_branch || undefined,
        repo_root: s.repo_root || undefined,
        git: gitStatusService.get(s.id),
        finish_job: s.finish_job,
      }))
    },
    getSessionLog: (id) => {
      const s = registry.get(id)
      return messageLog.get(s?.id ?? id)
    },
    getSessionActivity: (id) => {
      const s = registry.get(id)
      return s ? activityStore.get(s.id) : []
    },
    getSessionAgentState: (id) => {
      const s = registry.get(id)
      const st = s ? agentStateStore.get(s.id) : { phase: "idle" as const, since: 0 }
      const { type: _type, session: _session, ...payload } = toAgentStateFrame(s?.id ?? id, st, bgTaskStore.openCount(s?.id ?? id))
      return payload
    },
    getSessionBgTasks: (id) => {
      const s = registry.get(id)
      return s ? bgTaskStore.get(s.id) : []
    },
    getSessionCommands: (id) => {
      const s = registry.get(id)
      return s ? commandRegistry.get(s.name) : []
    },
    getSessionCommandsResolved: (id) => {
      const s = registry.get(id)
      return s ? commandRegistry.isResolved(s.name) : false
    },
    previewAgentCommands: async ({ agent, workdir }) => {
      const kind = agent as import("./core/agents/types").AgentKind
      await commandRegistry.refreshPreview({
        kind,
        workdir,
        pluginSpawnArgs: pluginSpawnArgsForAgent(kind),
        codexClient: kind === "codex" ? findCodexClient() : undefined,
        opencodePluginDirs: kind === "opencode" ? opencodePluginDirsForPreview() : undefined,
      })
      return {
        commands: commandRegistry.getPreview(kind, workdir),
        resolved: commandRegistry.isPreviewResolved(kind, workdir),
      }
    },
    onAgentHook: (event, body) => {
      const claudeSid = body?.session_id
      if (typeof claudeSid !== "string") return
      const s = registry.list().find((x) => x.agent_session_id === claudeSid)
      if (!s) return
      const adapter = adapters.get(s.id)
      if (!(adapter instanceof ClaudeCodeAdapter)) return
      if (event === "StopFailure") {
        // Field shape isn't firmly documented — accept flat (error_type/error_message),
        // nested (error_details/error.{type,message}), or reason/message.
        const pick = (...vals: unknown[]) => vals.find((v) => typeof v === "string" && v) as string | undefined
        const det = (body?.error_details ?? body?.error) as Record<string, unknown> | undefined
        const errorType = pick(body?.error_type, det?.type, body?.reason) ?? "error"
        const errorMessage = pick(body?.error_message, det?.message, body?.message) ?? "Agent turn failed"
        adapter.ingestHook("StopFailure", { errorType, errorMessage })
        return
      }
      const tool = typeof body?.tool_name === "string" ? body.tool_name : undefined
      log.info("agent_hook", { session: s.name, event, tool })
      adapter.ingestHook(event, { tool })
    },
    setMute: (id, muted) => {
      const s = registry.get(id)
      if (s) registry.setMuted(s.id, muted)
    },
    onSendFromWeb: () => {},                                // wired below via webChannel.on('inbound')
    fileStore,
    pushStore,
    deviceTokenStore,
    relayUrl,
    vapidPublicKey: vapid.publicKey,
    viewingTracker,
    getReads: () => registry.sessions.allReads(),
    getDrafts: () => registry.sessions.allDrafts(),
    setDraft: (id, text) => registry.sessions.setDraft(id, text),
    markRead: (id) => advanceRead(id),
    getModels: (agent) => modelCache.get(agent).map((m) => ({ id: m.id, displayName: m.displayName })),
    switchModel: async (id, model, applyNow) => {
      const s = registry.get(id)
      if (!s) return { ok: false, error: "session not found" }
      return switchSessionModel(s.id, model, { applyNow })
    },
    getSessionReasoningLevels: (id) => {
      const s = registry.get(id)
      if (!s) return undefined
      const models = lookupModels(s.agent)
      const levels = supportedReasoningLevels(s.agent, models, s.model)
      if (!shouldShowReasoningControl(s.agent, models, s.model)) {
        return { agent: s.agent, current: sessionEffort(s), levels: [], visible: false }
      }
      return {
        agent: s.agent,
        current: sessionEffort(s),
        levels,
        visible: true,
      }
    },
    switchReasoningLevel: async (id, reasoningLevel, applyNow) => {
      const s = registry.get(id)
      if (!s) return { ok: false, error: "session not found" }
      return switchSessionReasoningLevel(s.id, reasoningLevel, { applyNow })
    },
    getReasoningLevels: (agent, model) => {
      const models = lookupModels(agent)
      const visible = shouldShowReasoningControl(agent, models, model)
      return { agent, levels: visible ? supportedReasoningLevels(agent, models, model) : [], visible }
    },
    getSessionAgent: (id) => {
      const s = registry.get(id)
      if (!s) return undefined
      return { agent: s.agent, model: s.model, reasoningLevel: sessionEffort(s) }
    },
    interruptSession: async (id) => {
      const s = registry.get(id)
      if (!s) return { ok: false, reason: "session not found" }
      return interruptSessionById(s.id)
    },
    finishSession: async (id, req) => finishSessionById(id, req),
    finishReadiness: (id) => finishReadinessById(id),
    spawnSession: async (args) => {
      const r = await spawnSession({
        workdir: args.workdir,
        requestedName: args.name,
        agent: (args.agent as any) ?? "claude",
        model: args.model,
        reasoningLevel: args.reasoningLevel,
        worktree: args.worktree,
        baseBranch: args.baseBranch,
      })
      const entry = registry.get(r.session_id)
      await refreshTelegramMenu()
      // Codex/cursor register synchronously; Claude registers via onRegister.
      // onRegister's reconnect path does not broadcast, so always notify web
      // clients here (sessions.add dedupes duplicate session_added frames).
      if (entry) {
        webChannel?.broadcastToAll({
          type: "session_added",
          session: {
            id: entry.id,
            name: entry.name,
            workdir: entry.workdir,
            mute: !!entry.mute,
            connected: true,
            agent: entry.agent,
            model: entry.model,
            reasoningLevel: sessionEffort(entry),
            repo_root: entry.repo_root || undefined,
            session_branch: entry.session_branch || undefined,
            finish_job: entry.finish_job,
          },
        })
      }
      return {
        id: entry?.id ?? r.session_id,
        name: r.name,
        workdir: entry?.workdir ?? args.workdir,
        agent: entry?.agent ?? "claude",
        model: entry?.model,
        reasoningLevel: entry ? sessionEffort(entry) : undefined,
      }
    },
    spawnPA: async (args) => {
      const r = await spawnPA({
        registry,
        name: args.name,
        agent: (args.agent as any) ?? "claude",
        workdir: args.workdir,
        model: args.model,
        reasoningLevel: args.reasoningLevel,
        bind: (sid: string) => server.bind(sid),
        spawnTmux: spawnSessionWindow,
        tmuxSession: TMUX_SESSION,
        resolveEffort: (s) => sessionEffort(s),
        registerAdapter: (name, adapter, handle) => {
          const session = registry.resolveName(name)
          const sid = session?.id ?? name
          if (adapter instanceof CodexAdapter) {
            registerCodexRuntime(sid, name, adapter, handle as CodexSpawnHandle)
          } else if (adapter instanceof CursorAdapter) {
            registerCursorRuntime(sid, adapter)
          } else if (adapter instanceof OpenCodeAdapter) {
            registerOpenCodeRuntime(sid, name, adapter, handle as OpenCodeSpawnHandle)
          }
          wireAdapterEvents(adapter, sid)
        },
        onClaudeSessionId: (brokerSessionId, claudeSessionId) => {
          const session = registry.get(brokerSessionId)
          if (session) registry.sessions.setAgentSessionId(session.id, claudeSessionId)
          else pendingClaudeSessionId.set(brokerSessionId, claudeSessionId)
        },
        onCodexSessionId: (brokerSessionId, sessionId) => {
          const session = registry.get(brokerSessionId)
          if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
        },
        onCursorSessionId: (name, sessionId) => {
          const session = registry.resolveName(name)
          if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
        },
        onOpenCodeSessionId: (name, sessionId) => {
          const session = registry.resolveName(name)
          if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
        },
        codexResolveAuth: resolveCodexAuth,
        codexSpawnAppServer: spawnCodexAppServer,
        codexAdapterFactory: (opts) => new CodexAdapter(opts),
        cursorResolveAuth: resolveCursorAuth,
        cursorRunnerFactory: makeRealCursorRunner,
        cursorAdapterFactory: (opts) => new CursorAdapter(opts),
        resolveAttachment: resolveAttachmentPath,
      })
      const entry = registry.get(r.id)
      await refreshTelegramMenu()
      if (entry) {
        webChannel?.broadcastToAll({
          type: "session_added",
          session: {
            id: entry.id,
            name: entry.name,
            workdir: entry.workdir,
            mute: !!entry.mute,
            connected: true,
            agent: entry.agent,
            model: entry.model,
            reasoningLevel: sessionEffort(entry),
            repo_root: entry.repo_root || undefined,
            session_branch: entry.session_branch || undefined,
            finish_job: entry.finish_job,
          },
        })
      }
      return {
        id: entry?.id ?? r.id,
        name: r.name,
        workdir: entry?.workdir ?? args.workdir,
        agent: entry?.agent ?? "claude",
        model: entry?.model,
        reasoningLevel: entry ? sessionEffort(entry) : undefined,
      }
    },
    listPAs: () => registry.listPAs().map((s) => ({
      id: s.id,
      name: s.name,
      workdir: s.workdir,
      mute: !!s.mute,
      connected: !!s.connected,
      agent: s.agent,
      role: s.role,
      isDefault: s.is_default,
      model: s.model,
      reasoningLevel: s.reasoningLevel,
      status: s.status,
    })),
    updatePA: async (name, patch) => {
      const s = registry.resolveName(name)
      if (!s) return { ok: false, error: "session not found" }
      if (patch.model) {
        const modelResult = await switchSessionModel(s.id, patch.model)
        if (!modelResult.ok) return modelResult
      }
      if (patch.reasoningLevel) {
        const reasoningResult = await switchSessionReasoningLevel(s.id, patch.reasoningLevel)
        if (!reasoningResult.ok) return reasoningResult
      }
      return { ok: true }
    },
    killSession: async (id) => {
      const s = registry.get(id)
      if (!s) throw new Error("session not found")
      await killSession(s.id)
      unregisterSession(s.id)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({ type: "session_removed", id: s.id })
    },
    renameSession: async (id, newName) => {
      const s = registry.get(id)
      if (!s) throw new Error("session not found")
      const oldName = s.name
      registry.rename(s.id, newName)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({ type: "session_renamed", id: s.id, old: oldName, new: newName })
    },
    proxyBaseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
    proxyMainHost: MUX_WEB_PUBLIC_URL ? new URL(MUX_WEB_PUBLIC_URL).host : undefined,
    proxyLookup: (domain: string) => {
      const entry = registry.getProxy(domain)
      if (!entry) return undefined
      return { port: entry.port, sessionName: entry.sessionName, isPublic: entry.isPublic }
    },
    proxyAuth: (token: string) => {
      const store = new DeviceStore(DEVICES_FILE)
      return !!store.verify(token)
    },
    listProxies: () => registry.listProxies().map((e) => proxyWsPayload(e, proxyLivenessMonitor.getStatus(e.domain))),
    createProxy: (args) => {
      const session = registry.resolveName(args.sessionName)
      if (!session) throw new Error(`no such session: ${args.sessionName}`)
      let domain = args.domain
      if (!domain) {
        domain = "px-" + randomBytes(4).toString("hex")
      }
      const entry = registry.addProxy({ domain, sessionId: session.id, port: args.port })
      webChannel?.broadcastToAll({ type: "proxy_created", proxy: proxyWsPayload(entry, proxyLivenessMonitor.getStatus(entry.domain)) })
      // Probe the new exposure promptly so its badge reflects reality within a
      // tick instead of waiting for the next poll interval (a live port → up,
      // so no false "Down" flash).
      void proxyLivenessMonitor.refresh()
      return {
        url: buildProxyPublicUrl(entry.domain, {
          baseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
          publicUrl: process.env.MUX_WEB_PUBLIC_URL,
        }),
        domain: entry.domain,
        port: entry.port,
      }
    },
    updateProxy: (domain, isPublic) => {
      const entry = registry.setProxyPublic(domain, isPublic)
      const status = proxyLivenessMonitor.getStatus(entry.domain)
      webChannel?.broadcastToAll({ type: "proxy_updated", proxy: proxyWsPayload(entry, status) })
      return proxyWsPayload(entry, status)
    },
    removeProxy: (domain) => {
      const existing = registry.getProxy(domain)
      if (!existing) throw new Error(`no proxy registered for "${domain}"`)
      registry.removeProxy(domain)
      webChannel?.broadcastToAll({ type: "proxy_removed", domain })
    },
    terminalManager,
    getDisplayPort: (id) => displayManager.getPort(id),
    getScrcpy: (id) => displayManager.getScrcpy(id),
    listDisplays: () => displayManager.list(),
    startDisplay: (args) => displayManager.start({ sessionDisplayName: args.sessionName, provider: args.provider as any, device: args.device, width: args.width, height: args.height }),
    stopDisplay: (id) => displayManager.stop(id),
    fsWatcher,
    getSessionWorkdir: (id) => registry.get(id)?.workdir,
    getSessionTmuxTarget: async (id) => {
      const s = registry.get(id)
      if (!s || s.agent !== AgentKind.Claude) return undefined
      // Heal-on-read: resolve and persist the window-id if not yet stored.
      // For sessions that already have tmux_window_id, widOf short-circuits with
      // no tmux call. Legacy/unhealed sessions resolve by name once, then persist,
      // so the agent terminal no longer 404s on first attach.
      return (await widOf(s)) ?? undefined
    },
    getSessionBaseCommits: (id) => registry.get(id)?.base_commits,
    getSessionCreatedAt: (id) => registry.get(id)?.created_at,
    listArchivedSessions: () =>
      registry.sessions.listArchived().map((s) => ({
        id: s.id,
        name: s.name,
        workdir: s.workdir,
        agent: s.agent,
        model: s.model,
        killed_at: s.killed_at,
        repo_root: s.repo_root,
      })),
    resumeFromArchive: (id: string) => resumeFromArchive(id),
    getAppConfig: () => settings.getAppConfig(appConfigEnv),
    setAppConfig: (patch) => {
      const wasOnboarded = settings.getAppConfig(appConfigEnv).onboarded
      settings.setAppConfig(patch)
      const next = settings.getAppConfig(appConfigEnv)
      // Explicit PUT is authoritative — overwrite so a corrected/rotated key
      // takes effect for new spawns. Already-running sessions keep their old
      // env until respawn (acceptable for v1).
      applyCredentialEnv(next, process.env)
      if (!wasOnboarded && next.onboarded) { void respawnPAsAfterOnboarding() }
      return next
    },
    getAgentStatuses: () => {
      const c = settings.getAppConfig(appConfigEnv)
      const hasCredential = (kind: AgentKind) =>
        kind === "claude" ? !!(c.claudeOauthToken || c.anthropicApiKey)
        : kind === "codex" ? !!c.codexApiKey
        : kind === "cursor" ? !!c.cursorApiKey
        : false
      return detectAllAgents(
        { hasBinary, fileExists: existsSync, hasCredential },
        { home: homedir(), xdgConfigHome: process.env.XDG_CONFIG_HOME, xdgDataHome: process.env.XDG_DATA_HOME },
      )
    },
    startAgentLogin: (kind) => loginManager.start(kind as any),
    getAgentLogin: (kind) => loginManager.get(kind as any),
    cancelAgentLogin: (kind) => loginManager.cancel(kind as any),
    sendAgentLoginCode: (kind, code) => loginManager.sendCode(kind as any, code),
    startAgentInstall: (kind) => installManager.start(kind as AgentKind),
    getAgentInstall: (kind) => installManager.get(kind as AgentKind),
    listOpenCodeProviders: () => listOpenCodeProviders(),
    setOpenCodeApiKey: (id, key) => setOpenCodeApiKey(id, key),
    startOpenCodeOAuth: (id, method) => startOpenCodeOAuth(id, method),
    finishOpenCodeOAuth: (id, method, code) => finishOpenCodeOAuth(id, method, code),
    getSoul: async () => { try { return await Bun.file(SOUL_PATH).text() } catch { return "" } },
    setSoul: async (content) => { await Bun.write(SOUL_PATH, content) },
    getExposure: () => {
      const c = settings.getAppConfig(appConfigEnv)
      return { exposureMode: c.exposureMode, publicUrl: c.webPublicUrl, snippets: reverseProxySnippets({ publicUrl: c.webPublicUrl, port: c.webPort }) }
    },
    validateExposure: async () => {
      const url = settings.getAppConfig(appConfigEnv).webPublicUrl
      if (!url) return { reachable: false, error: "no public URL set" }
      try {
        const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), 4000)
        const r = await fetch(url.replace(/\/$/, "") + "/me", { signal: ctrl.signal, redirect: "manual" })
        clearTimeout(t)
        return { reachable: r.status === 200 || r.status === 401, status: r.status }
      } catch (e: any) { return { reachable: false, error: e?.message ?? String(e) } }
    },
    reviewList: (id) => reviewStore.list(id),
    reviewAdd: (id, c) => reviewStore.add({ ...c, sessionId: id }),
    reviewUpdate: (cid, patch) => reviewStore.update(cid, patch),
    reviewDelete: (cid) => reviewStore.delete(cid),
    reviewSubmit: (id) => submitReview(id),
    sendUserMessage: (id, text) => deliverUserMessage(id, text),
    reviewSession: (id) => { const s = registry.get(id); return s ? { workdir: s.workdir, repoRoot: s.repo_root ?? undefined, baseCommits: s.base_commits ?? undefined } : undefined },
    verifySuggest: (id) => { const s = registry.get(id); return s?.repo_root && s.session_branch ? suggestVerify(s.workdir) : undefined },
    verifySave: (id, content) => {
      const s = registry.get(id)
      if (!s?.repo_root || !s.session_branch) return { ok: false, reason: "session is not worktree-backed" }
      try {
        const dir = join(s.workdir, ".mux")
        mkdirSync(dir, { recursive: true })
        const p = join(dir, "verify.sh")
        writeFileSync(p, content.endsWith("\n") ? content : content + "\n")
        chmodSync(p, 0o755)
        return { ok: true }
      } catch (err: any) { return { ok: false, reason: err?.message ?? String(err) } }
    },
    getForgeConnections: () => forgeService.connections(),
    getForgeCliStatus: () => detectForgeClis(),
    addForgeConnection: (o) => forgeService.addConnection(o as any),
    importForgeCli: async (kind, transport) => {
      if (kind !== "github" && kind !== "gitlab") throw new Error(`unsupported forge kind: ${kind}`)
      return forgeService.addConnection({ kind, token: importCliToken(kind), source: "cli", transport })
    },
    removeForgeConnection: (id) => forgeService.removeConnection(id),
    searchForgeRepos: (q) => forgeService.search(q),
    cloneForgeRepo: (id, owner, name) => forgeService.clone(id, owner, name),
    createForgeRepo: (input) => forgeService.create(input as any),
    createLocalRepo: (name) => forgeService.createLocal(name),
    listClonedRepos: () => forgeService.listCloned(),
    removeClonedRepo: (p) => forgeService.removeCloned(p),
    pullClonedRepo: (p) => forgeService.pullCloned(p),
    // Voice: whisper STT (when audio) → agent cleanup pass → composer-ready text.
    // Closes over the `agentRpc` `let` binding; runs at request time, by which
    // point agentRpc is assigned (same pattern as the login closures below).
    transcribe: async (sessionId, input) => {
      // The session id is OPTIONAL (id-less /transcribe is used by the pre-spawn launcher). When
      // present it enriches the cleanup with prior messages + the session's agent skills; when
      // absent the cleanup still runs off the draft + global glossary/engine/model.
      const s = sessionId ? registry.get(sessionId) : undefined
      const cfg = settings.getAppConfig(appConfigEnv)
      let draft = input.draft ?? ""
      let whisperMs = 0
      if (input.audioPath) {
        const t0 = Date.now()
        const r = await transcribeAudio(input.audioPath, { model: cfg.whisperModel, lang: cfg.whisperLang })
        whisperMs = Date.now() - t0
        draft = r.text
      }
      const source = input.audioPath ? "whisper" : "client"
      if (!draft.trim()) { log.info("voice_transcribe_empty", { sessionId: sessionId ?? null, source, whisperMs }); return { text: "" } }
      const skills = s ? commandRegistry.get(s.name).filter((c) => c.family === "agent").map((c) => c.name) : []
      const messages = sessionId ? messageLog.get(s?.id ?? sessionId, 10) : []
      const payload = buildVoicePayload(draft, messages, skills)
      // Full visibility into exactly what the cleanup is fed + the whisper/cleanup timing split.
      log.info("voice_transcribe_in", { sessionId: sessionId ?? null, source, draft, whisperMs, ctxMsgs: messages.length, skills, model: cfg.voiceCleanupModel ?? VOICE_CLEANUP_MODEL })
      try {
        const t1 = Date.now()
        const out = await cleanupDraft(
          { draft, recentMessages: payload.context.recentMessages, skills, glossary: cfg.voiceCleanupGlossary ?? [] },
          { engine: cfg.voiceCleanupEngine, model: cfg.voiceCleanupModel },
        )
        const cleanupMs = Date.now() - t1
        const text = out.text || draft
        log.info("voice_transcribe_out", { sessionId: sessionId ?? null, draft, text, whisperMs, cleanupMs, engine: out.engine, model: cfg.voiceCleanupModel ?? VOICE_CLEANUP_MODEL })
        return { text }
      } catch (e) {
        log.warn("voice_cleanup_failed", { sessionId: sessionId ?? null, draft, whisperMs, err: String(e) })
        return { text: draft, degraded: true }
      }
    },
  })
  // loginManager constructed AFTER webChannel so its onChange can reference webChannel.
  // The startAgentLogin/getAgentLogin/cancelAgentLogin closures above close over `loginManager`
  // (the `let` binding) — they run at request time, by which point loginManager is assigned.
  loginManager = new LoginManager({
    paths: { home: homedir(), xdgConfigHome: process.env.XDG_CONFIG_HOME },
    fileExists: existsSync,
    spawnLogin: spawnLoginProc,
    onChange: (kind, st) => webChannel?.broadcastToAll({ type: "agent_login_state", kind, state: st }),
  })
  channels.web = webChannel as Channel
  writeClaudeHooksSettings(MUX_WEB_PORT, INTERNAL_SECRET)
} else {
  try { rmSync(CLAUDE_HOOKS_SETTINGS_PATH, { force: true }) } catch {}
}

async function refreshTelegramMenu() {
  if (!telegram) return
  try {
    await telegram.refreshMenu(buildMenuEntries(registry))
  } catch (err: any) {
    log.warn("refresh_telegram_menu_failed", { err: err?.message ?? String(err) })
  }
}

async function killSession(id: string) {
  const s = registry.get(id)
  if (!s) return
  const displayName = s.name

  terminalManager.killAllForSession(displayName)
  void displayManager.killAllForSession(displayName)
  fsWatcher.killSession(displayName)

  const removedProxies = registry.removeProxiesForSession(s.id)
  if (removedProxies.length > 0) {
    for (const domain of removedProxies) {
      webChannel?.broadcastToAll({ type: "proxy_removed", domain })
    }
  }

  if (s.agent === "claude") {
    const wid = await widOf(s)
    if (wid) await killWindowById(wid)
    else log.warn("kill_session_no_tmux", { name: displayName })
  } else if (s.agent === "codex") {
    const runtime = runtimes.get(s.id)
    if (runtime?.kind === AgentKind.Codex) runtime.handle.kill()
  } else if (s.agent === AgentKind.Cursor) {
    // No persistent process or tmux pane to kill.
  } else if (s.agent === "opencode") {
    const runtime = runtimes.get(s.id)
    if (runtime?.kind === AgentKind.OpenCode) runtime.handle.kill()
  }
  deleteRuntime(s.id)
  stopClaudeTailer(s.id)   // also clears the session's background tasks
  agentStateStore.clear(s.id)
  recentInboundIds.clear(s.id)
  pendingReapply.clear(s.id)
  // Do NOT delete agent_home — needed for resume

  // Reclaim this session's worktree if it has no unsaved/unmerged work; otherwise
  // keep it (recoverable). Only on kill — never on suspend.
  if (s.repo_root && s.session_branch && s.workdir) {
    if (isWorktreeReclaimable(s.workdir, s.session_branch, s.base_branch || "HEAD")) {
      await removeWorktree(s.repo_root, s.workdir, s.session_branch).catch(() => {})
    } else {
      log.warn("worktree_kept_unclean", { id, workdir: s.workdir })
    }
  }
}

async function waitForSessionConnected(sessionId: string, timeoutMs = 20_000): Promise<boolean> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (registry.get(sessionId)?.connected) return true
    await new Promise<void>(r => setTimeout(r, 100))
  }
  log.warn("wait_session_connected_timeout", { sessionId, timeoutMs })
  return false
}

// When a session's worktree was removed (e.g. its branch was merged via finish),
// the recorded workdir no longer exists. Spawning `claude --resume` into a missing
// cwd makes it die on startup, and the inbound message is then silently queued to a
// dead session (no reply, spinner forever). Recreate the worktree at the SAME path —
// so claude's cwd-keyed transcript still matches — before any resume/respawn spawns
// into it. No-op when the workdir is healthy or the session isn't worktree-backed.
async function ensureSessionWorktree(session: { id: string; name: string; workdir: string; repo_root?: string | null; session_branch?: string | null; base_branch?: string | null }): Promise<void> {
  if (!session.repo_root || !session.session_branch) return
  if (existsSync(session.workdir)) return
  log.warn("worktree_missing_recreating", { id: session.id, name: session.name, workdir: session.workdir, branch: session.session_branch })
  await ensureWorktreeAt({
    repoRoot: session.repo_root,
    workdir: session.workdir,
    sessionBranch: session.session_branch,
    baseBranch: session.base_branch || "HEAD",
  })
  log.info("worktree_recreated", { id: session.id, name: session.name, workdir: session.workdir })
}

async function resumeSuspendedSession(session: { id: string; name: string; agent: string; workdir: string; model?: string; reasoningLevel?: string; pid?: number; agent_session_id?: string; agent_home?: string; tmux_window_id?: string | null; repo_root?: string | null; session_branch?: string | null; base_branch?: string | null }): Promise<boolean> {
  try {
    log.info("resume_suspended_begin", {
      name: session.name,
      id: session.id,
      agent: session.agent,
      status: registry.get(session.id)?.status,
      has_agent_session_id: !!session.agent_session_id,
    })
    await ensureSessionWorktree(session)
    if (session.agent === "claude") {
      await server.bind(session.id)
      preAcceptTrust(session.workdir)
      // Clear ONLY our own prior window, by id — never kill by name. The old
      // `while (listSessionWindows().includes(name)) killSessionWindow({window:name})`
      // loop could kill a sibling window that happens to share this display name;
      // 65b1049 removed the same destructive pattern from the new-spawn path. Then
      // pick a window name that doesn't collide with any live window (mirrors it).
      if (session.tmux_window_id) await killWindowById(session.tmux_window_id).catch(() => {})
      const { ensureUnique } = await import("./core/session-manager/naming")
      const windowName = ensureUnique(session.name, new Set(await listSessionWindows(TMUX_SESSION)))
      log.info("resume_suspended_claude", { name: session.name, window: windowName })
      const effort = sessionEffort(session as any)
      const cmd = buildClaudeSpawnCommand({
        name: session.name, model: session.model, effort, sessionId: session.id,
        claudeSessionId: session.agent_session_id, resume: !!session.agent_session_id,
        workdir: session.workdir,
      })
      const tmuxWindow = await spawnSessionWindow({
        session: TMUX_SESSION,
        window: windowName,
        workdir: session.workdir,
        command: cmd,
      })
      if (tmuxWindow.windowId) registry.sessions.setTmuxWindowId(session.id, tmuxWindow.windowId)
      if (tmuxWindow.windowId) await sendChannelConsentEnter(tmuxWindow.windowId)
      await waitForSessionConnected(session.id, 25_000)
    } else if (session.agent === "codex" && session.agent_session_id && session.agent_home) {
      await server.bind(session.id)
      const auth = await resolveCodexAuth({
        apiKey: process.env.OPENAI_API_KEY,
        userCodexHome: `${home()}/.codex`,
        sessionCodexHome: session.agent_home,
      })
      await codexPrepareSessionHome(session.agent_home)
      const effort = sessionEffort(session as any)
      const handle = spawnCodexAppServer({
        codexHome: session.agent_home,
        workdir: session.workdir,
        authEnv: auth.env,
        model: session.model,
        reasoningLevel: effort,
        pluginConfigArgs: codexSpawnArgs({ sessionName: session.name }).args,
      })
      const adapter = new CodexAdapter({
        sessionName: session.name,
        workdir: session.workdir,
        client: handle.client,
        persistThreadId: async () => {},
        initialThreadId: session.agent_session_id,
        resolveAttachment: resolveAttachmentPath,
      })
      await adapter.resume()
      registerCodexRuntime(session.id, session.name, adapter, handle)
      wireAdapterEvents(adapter, session.id)
    } else if (session.agent === "cursor" && session.agent_home) {
      const auth = await resolveCursorAuth({
        apiKey: process.env.CURSOR_API_KEY,
        userCursorDir: `${home()}/.cursor`,
        sessionHome: session.agent_home,
      })
      const runner = makeRealCursorRunner({ home: session.agent_home, authEnv: auth.env })
      const adapter = new CursorAdapter({
        sessionName: session.name,
        workdir: session.workdir,
        runner,
        persistSessionId: async (id) => {
          registry.sessions.setAgentSessionId(session.id, id)
        },
        initialSessionId: session.agent_session_id,
        pluginArgs: cursorSpawnArgs({ sessionName: session.name }).args,
        resolveAttachment: resolveAttachmentPath,
      })
      registerCursorRuntime(session.id, adapter)
      wireAdapterEvents(adapter, session.id)
    } else {
      log.warn("resume_suspended_no_path", { name: session.name, agent: session.agent })
      return false
    }
    registry.sessions.activate(session.id, session.pid || process.pid)
    return true
  } catch (err: any) {
    log.error("resume_suspended_failed", { name: session.name, err: String(err) })
    return false
  }
}

async function resumeFromArchive(sessionId: string): Promise<{ ok: boolean; name?: string; error?: string }> {
  const session = registry.sessions.getById(sessionId) // bypasses archived filter in registry.get()
  if (!session || session.status !== "archived") {
    return { ok: false, error: "Session not found or not archived" }
  }

  let name = session.name
  const { ensureUnique } = await import("./core/session-manager/naming")
  if (registry.takenNames().has(name)) {
    name = ensureUnique(name, registry.takenNames())
  }

  let resumedTmuxWindowId: string | undefined
  try {
    await ensureSessionWorktree(session)
    if (session.agent === "claude") {
      await server.bind(sessionId)
      const effort = sessionEffort(session)
      const cmd = buildClaudeSpawnCommand({
        name, model: session.model, effort, sessionId,
        claudeSessionId: session.agent_session_id, resume: !!session.agent_session_id,
        workdir: session.workdir,
      })
      const tmuxWindow = await spawnSessionWindow({
        session: TMUX_SESSION,
        window: name,
        workdir: session.workdir,
        command: cmd,
      })
      resumedTmuxWindowId = tmuxWindow.windowId
      if (tmuxWindow.windowId) void sendChannelConsentEnter(tmuxWindow.windowId)
    } else if (session.agent === "codex" && session.agent_session_id && session.agent_home) {
      await server.bind(sessionId)
      const auth = await resolveCodexAuth({
        apiKey: process.env.OPENAI_API_KEY,
        userCodexHome: `${home()}/.codex`,
        sessionCodexHome: session.agent_home,
      })
      await codexPrepareSessionHome(session.agent_home)
      const effort = sessionEffort(session)
      const handle = spawnCodexAppServer({
        codexHome: session.agent_home,
        workdir: session.workdir,
        authEnv: auth.env,
        model: session.model,
        reasoningLevel: effort,
        pluginConfigArgs: codexSpawnArgs({ sessionName: name }).args,
      })
      const adapter = new CodexAdapter({
        sessionName: name,
        workdir: session.workdir,
        client: handle.client,
        persistThreadId: async () => {},
        initialThreadId: session.agent_session_id,
        resolveAttachment: resolveAttachmentPath,
      })
      await adapter.resume()
      registerCodexRuntime(sessionId, name, adapter, handle)
      wireAdapterEvents(adapter, sessionId)
    } else if (session.agent === "cursor" && session.agent_home) {
      const auth = await resolveCursorAuth({
        apiKey: process.env.CURSOR_API_KEY,
        userCursorDir: `${home()}/.cursor`,
        sessionHome: session.agent_home,
      })
      const runner = makeRealCursorRunner({ home: session.agent_home, authEnv: auth.env })
      const adapter = new CursorAdapter({
        sessionName: name,
        workdir: session.workdir,
        runner,
        persistSessionId: async (id) => {
          registry.sessions.setAgentSessionId(sessionId, id)
        },
        initialSessionId: session.agent_session_id,
        pluginArgs: cursorSpawnArgs({ sessionName: name }).args,
        resolveAttachment: resolveAttachmentPath,
      })
      registerCursorRuntime(sessionId, adapter)
      wireAdapterEvents(adapter, sessionId)
    } else if (session.agent === "opencode" && session.agent_home) {
      await server.bind(sessionId)
      const { adapter, handle } = await resumeOpenCodeSession(
        {
          resolveAttachment: resolveAttachmentPath,
          onOpenCodeSessionId: (_name, sid) => { registry.sessions.setAgentSessionId(sessionId, sid) },
        },
        { id: sessionId, name, workdir: session.workdir, agent_home: session.agent_home, model: session.model, agent_session_id: session.agent_session_id },
      )
      registerOpenCodeRuntime(sessionId, name, adapter, handle)
      wireAdapterEvents(adapter, sessionId)
    } else {
      return { ok: false, error: `Cannot resume agent type: ${session.agent}` }
    }

    registry.sessions.resume(sessionId, name, process.pid)
    if (resumedTmuxWindowId) registry.sessions.setTmuxWindowId(sessionId, resumedTmuxWindowId)

    webChannel?.broadcastToAll({
      type: "session_added",
      session: { id: sessionId, name, workdir: session.workdir, agent: session.agent, status: "active", repo_root: session.repo_root || undefined, session_branch: session.session_branch || undefined, finish_job: session.finish_job },
    })

    await refreshTelegramMenu()
    return { ok: true, name }
  } catch (err: any) {
    return { ok: false, error: (err as Error).message }
  }
}

function stringArg(args: Record<string, unknown>, key: string): string {
  const value = args[key]
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArg(args: Record<string, unknown>, key: string): string | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "string") throw new Error(`${key} must be a string`)
  return value
}

function optionalStringArrayArg(args: Record<string, unknown>, key: string): string[] | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) {
    throw new Error(`${key} must be an array of strings`)
  }
  return value
}

function optionalFormatArg(args: Record<string, unknown>, key: string): "text" | "markdownv2" | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (value === "text" || value === "markdownv2") return value
  throw new Error(`${key} must be text or markdownv2`)
}

function optionalNumberArg(args: Record<string, unknown>, key: string): number | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "number") throw new Error(`${key} must be a number`)
  return value
}

function optionalBooleanArg(args: Record<string, unknown>, key: string): boolean | undefined {
  const value = args[key]
  if (value === undefined) return undefined
  if (typeof value !== "boolean") throw new Error(`${key} must be a boolean`)
  return value
}

function optionalProviderArg(args: Record<string, unknown>, key: string): ProviderName | undefined {
  const value = optionalStringArg(args, key)
  if (value === undefined || value === "linux-xvfb" || value === "macos-screen" || value === "scrcpy") return value
  throw new Error(`${key} must be a known display provider`)
}

const server = await startSocketServer({
  socketsDir: SOCKETS_DIR,
  onStatusChange: (session_id, connected, last_pong_at) => {
    // session_id is the UUID from the socket. NOTE: liveness can fire slightly
    // ahead of registration (markAlive runs before onRegister completes) — that's
    // safe here: "connected" is a no-op unless the session was "dead", and "dead"
    // only applies to a registered, non-suspended session.
    registry.sessions.setConnectionStatus(session_id, connected, last_pong_at)
    const s = registry.get(session_id)
    webChannel?.broadcastToAll({ type: "session_state", session: session_id, connected, model: s?.model })
    if (connected) {
      agentStateStore.applyEvent(session_id, "connected")          // revives a dead session; no-op otherwise
    } else if (s && s.status !== "suspended") {
      agentStateStore.applyEvent(session_id, "dead")               // crash/shim-gone — but NOT an intentional suspend
      bgTaskStore.clear(session_id)  // a dead harness can never deliver its wakes — no fake "waiting"
    }
  },
  // Safety net: a queued inbound that can't reach a live channel shim within the
  // grace window means the session crashed / never came up. Tell the user in the
  // chat that sent it, instead of silently dropping the message.
  onUndeliverable: (session_id, payload) => {
    const chat_id = payload.meta?.chat_id
    if (!chat_id) return
    const name = registry.get(session_id)?.name ?? session_id
    const text = `⚠️ Couldn't deliver your message to "${name}" — it didn't come up (it may have crashed). Please try again.`
    if (chat_id.startsWith("telegram")) void telegram?.send({ op: "reply", chat_id, text })
    else void webChannel?.send({ op: "reply", chat_id, text })
  },
  handler: {
    onRegister: async (msg) => {
      const sessionUuid = msg.session_id as string  // UUID from MUX_SESSION_ID
      const requested = msg.requested_name as string | undefined
      const workdir = msg.workdir as string
      const agentSessionId = msg.agent_session_id as string | undefined

      // Reconnect path: look up by UUID first
      const existing = registry.get(sessionUuid)
      if (existing) {
        log.info("shim_reconnect", { name: existing.name, id: sessionUuid, old_pid: existing.pid, new_pid: msg.pid })
        if (agentSessionId) {
          registry.sessions.setAgentSessionId(sessionUuid, agentSessionId)
        }
        const pendingWindow = pendingTmuxWindowId.get(sessionUuid)
        if (pendingWindow) {
          registry.sessions.setTmuxWindowId(sessionUuid, pendingWindow)
          pendingTmuxWindowId.delete(sessionUuid)
        }
        // Rebuild adapter if missing (after broker restart)
        if (!adapters.has(sessionUuid) && (existing.agent ?? "claude") === "claude") {
          const adapter = new ClaudeCodeAdapter({
            sessionName: existing.name,
            workdir: existing.workdir,
            sendInboundSocket: (payload) => server.sendInbound(sessionUuid, payload),
            interruptSocket: () => interruptClaudePane(sessionUuid),
          })
          registerClaudeRuntime(sessionUuid, adapter)
          wireClaudeStateEvents(adapter, {
            onState: (event, tool) => agentStateStore.applyEvent(sessionUuid, event, tool),
            onError: (errorType, message) => {
              const s = registry.get(sessionUuid)
              void notifyAgentError(sessionUuid, s?.name ?? sessionUuid, errorType, message)
            },
          })
        }
        if (existing.status === "suspended") {
          registry.sessions.activate(sessionUuid, msg.pid as number)
        }
        ensureClaudeTailer(sessionUuid, existing.name, existing.workdir, true)
        void commandRegistry.refresh(existing.name)
        if (existing.role === "personal_assistant" && existing.is_default) {
          setTimeout(() => { void maybeAutoSendSoulSetup(existing.id) }, SOUL_SETUP_AUTO_SEND_DELAY_MS)
        }
        return { name: existing.name, session_id: sessionUuid }
      }

      // spawnSession (or the supervisor) reserved this name and bound the
      // socket before claude started, so we register the real name as-is.
      // If for some reason no reservation exists (manual /claude in a tmux
      // window?), fall back to deriving+uniquifying here.
      let finalName: string
      if (requested && registry.takenNames().has(requested) && !registry.resolveName(requested)) {
        // Reserved by our spawn path — claim it.
        finalName = requested
      } else {
        const { deriveName, ensureUnique } = await import("./core/session-manager/naming")
        const base = requested ?? deriveName(workdir)
        finalName = ensureUnique(base, registry.takenNames())
      }

      // Preserve the stored role/is_default so a backfilled PA (role='personal_assistant')
      // re-registers as PA → can_orchestrate stays true via policy. Brand-new sessions
      // default to worker/false.
      const prior = registry.get(sessionUuid)
      const wasInternal = pendingInternal.delete(sessionUuid)
      const session = registry.register({
        id: sessionUuid,  // Use the UUID from the socket
        name: finalName,
        workdir,
        tmux_target: `${TMUX_SESSION}:${finalName}`,
        pid: msg.pid,
        agent_session_id: agentSessionId,
        role: prior?.role ?? "worker",
        is_default: prior?.is_default ?? false,
        internal: wasInternal,
        base_commits: (() => {
          const out: Record<string, string> = {}
          for (const repo of scanRepos(workdir)) {
            try { out[repo.relPath] = _execSync("git rev-parse HEAD", { cwd: repo.absPath, encoding: "utf-8", timeout: 5000 }).trim() } catch {}
          }
          return out
        })(),
      })

      if (!wasInternal) webChannel?.broadcastToAll({
        type: "session_added",
        session: { id: session.id, name: finalName, workdir, mute: false, connected: true, agent: session.agent },
      })

      if (!adapters.has(sessionUuid)) {
        const adapter = new ClaudeCodeAdapter({
          sessionName: finalName,
          workdir,
          sendInboundSocket: (payload) => server.sendInbound(sessionUuid, payload),
          interruptSocket: () => interruptClaudePane(sessionUuid),
        })
        registerClaudeRuntime(sessionUuid, adapter)
        wireClaudeStateEvents(adapter, {
          onState: (event, tool) => agentStateStore.applyEvent(sessionUuid, event, tool),
          onError: (errorType, message) => {
            const s = registry.get(sessionUuid)
            void notifyAgentError(sessionUuid, s?.name ?? sessionUuid, errorType, message)
          },
        })
      }
      // If this registration matches a pending /spawn, flip the chat's active.
      const pendingChat = pendingSpawnActive.get(finalName)
      if (pendingChat) {
        registry.setActive(pendingChat, session.id)
        pendingSpawnActive.delete(finalName)
      }
      // Apply deferred Claude session ID (supervisor/spawn fire before onRegister)
      const pendingClaude = pendingClaudeSessionId.get(sessionUuid) ?? pendingClaudeSessionId.get(finalName)
      if (pendingClaude) {
        registry.sessions.setAgentSessionId(sessionUuid, pendingClaude)
        pendingClaudeSessionId.delete(sessionUuid)
        pendingClaudeSessionId.delete(finalName)
      }
      const pendingWindow = pendingTmuxWindowId.get(sessionUuid)
      if (pendingWindow) {
        registry.sessions.setTmuxWindowId(sessionUuid, pendingWindow)
        pendingTmuxWindowId.delete(sessionUuid)
      }
      // Start transcript tailer for Claude sessions
      ensureClaudeTailer(sessionUuid, finalName, workdir)
      await refreshTelegramMenu()
      void commandRegistry.refresh(finalName)
      if (session.role === "personal_assistant" && session.is_default) {
        setTimeout(() => { void maybeAutoSendSoulSetup(session.id) }, SOUL_SETUP_AUTO_SEND_DELAY_MS)
      }
      return { name: finalName, session_id: sessionUuid }
    },
    onOutbound: async (msg) => {
      const fromSession = msg.session_id
      // fromSession is now UUID; adapters are keyed by UUID
      // Resolve channel from chat_id, falling back to telegram for legacy
      // (pre-namespacing) values held by long-lived shim sessions across
      // a broker upgrade.
      function resolveChannel(rawChatId: string): { channelName: string; chat_id: string } {
        if (!rawChatId.includes(":")) return { channelName: "telegram", chat_id: `telegram:${rawChatId}` }
        return { channelName: rawChatId.split(":", 1)[0]!, chat_id: rawChatId }
      }
      try {
        const op = msg.op
        if (op.name === "reply") {
          const adapter = adapters.get(fromSession)
          const files = optionalStringArrayArg(op.args, "files")
          const hasFiles = !!files?.length
          // Claude uses reply for all user-facing output. Codex/cursor normally
          // stream assistant text, but allow reply when delivering outbound files
          // (e.g. screen recordings) that cannot be attached via the text stream.
          if (!adapter || (adapter.kind !== "claude" && !hasFiles)) {
            const kind = adapter?.kind ?? "unknown"
            const hint = kind === "codex" || kind === "cursor"
              ? " — use your normal assistant output for text; reply is only for files[]"
              : ""
            return { ok: false, error: `reply not allowed from agent kind ${kind}${hint}` }
          }
          // Synchronously await onAssistantMessage so the shim's reply tool
          // call doesn't return until the channel send completes.
          try {
            await onAssistantMessage(fromSession, {
              text: stringArg(op.args, "text"),
              chat_id: stringArg(op.args, "chat_id"),
              reply_to: optionalStringArg(op.args, "reply_to"),
              files,
              format: optionalFormatArg(op.args, "format"),
              keyboard: optionalStringArrayArg(op.args, "keyboard"),
            })
            return { ok: true, value: { message_id: undefined } }
          } catch (err) {
            return { ok: false, error: String(err instanceof Error ? err.message : err) }
          }
        } else if (op.name === "react") {
          const rawChatId = stringArg(op.args, "chat_id")
          const messageId = stringArg(op.args, "message_id")
          const emoji = stringArg(op.args, "emoji")
          const { channelName, chat_id } = resolveChannel(rawChatId)
          const ch = channels[channelName]
          if (!ch) return { ok: false, error: `unknown channel for chat_id ${chat_id}` }
          const initial: OutboundAction = { op: "react", chat_id, message_id: messageId, emoji }
          const action = await transformOutbound(initial, fromSession, ch.capabilities, fileStore, registry)
          if (action.op !== "react") return { ok: false, error: "transformOutbound dropped react" }
          const res = await ch.send(action)
          if (res.ok) {
            messageLog.addReaction(fromSession, `out:${chat_id}:${messageId}`, emoji, new Date().toISOString())
          }
          return res.ok ? { ok: true, value: res.value } : { ok: false, error: res.error }
        } else if (op.name === "edit_message") {
          const rawChatId = stringArg(op.args, "chat_id")
          const messageId = stringArg(op.args, "message_id")
          const { channelName, chat_id } = resolveChannel(rawChatId)
          const ch = channels[channelName]
          if (!ch) return { ok: false, error: `unknown channel for chat_id ${chat_id}` }
          const initial: OutboundAction = { op: "edit_message", chat_id, message_id: messageId, text: stringArg(op.args, "text"), format: optionalFormatArg(op.args, "format") }
          const action = await transformOutbound(initial, fromSession, ch.capabilities, fileStore, registry)
          if (action.op !== "edit_message") return { ok: false, error: "transformOutbound dropped edit_message" }
          const res = await ch.send(action)
          if (res.ok) {
            messageLog.update(fromSession, `out:${chat_id}:${messageId}`, { text: action.text, edited_at: new Date().toISOString() })
          }
          return res.ok ? { ok: true, value: res.value } : { ok: false, error: res.error }
        } else if (op.name === "download_attachment") {
          try {
            const fileId = stringArg(op.args, "file_id")
            const r = await resolveDownloadAttachment({
              file_id: fileId,
              fileStore,
              telegramApi: telegram
                ? { token: TG_TOKEN!, getFile: (id: string) => telegram.getFile(id) }
                : undefined,
              inboxDir: INBOX_DIR,
            })
            log.info(r.via === "filestore" ? "download.completed.synthetic" : "download.completed", {
              session: fromSession, file_id: fileId, path: r.path,
            })
            return { ok: true, value: { path: r.path } }
          } catch (err: any) {
            return { ok: false, error: String(err?.message ?? err) }
          }
        }
        return { ok: false, error: `unknown op: ${op.name}` }
      } catch (err) {
        return { ok: false, error: String(err instanceof Error ? err.message : err) }
      }
    },
    onOrchestration: async (msg) => {
      // Permission check — fromSession is UUID
      const fromSession = msg.session_id
      const s = registry.get(fromSession)  // Look up by UUID
      const op = msg.op
      const NO_ORCHESTRATE_REQUIRED = new Set(["rename_session", "expose_port", "unexpose_port", "set_proxy_public", "start_display", "stop_display", "list_devices", "rpc_resolve", "rpc_reject", "memory_search", "find_sessions", "read_session"])
      if (!s?.can_orchestrate && !NO_ORCHESTRATE_REQUIRED.has(op.name)) {
        return { ok: false, error: "permission denied (can_orchestrate=false)" }
      }
      try {
      switch (op.name) {
        case "spawn_session":  {
          try {
            const requestedAgent = op.args.agent
            if (requestedAgent != null && !isAgentKind(requestedAgent)) {
              return { ok: false, error: `unknown agent kind: ${String(requestedAgent)}` }
            }
            const agent = requestedAgent ?? undefined
            const r = await spawnSession({ workdir: stringArg(op.args, "workdir"), requestedName: optionalStringArg(op.args, "name"), agent })
            await refreshTelegramMenu()
            // Notify web clients so the session list updates immediately.
            const entry = registry.get(r.session_id)
            if (entry) {
              webChannel?.broadcastToAll({
                type: "session_added",
                session: { id: entry.id, name: entry.name, workdir: entry.workdir, mute: !!entry.mute, connected: true, agent: entry.agent, model: entry.model, repo_root: entry.repo_root || undefined, session_branch: entry.session_branch || undefined, finish_job: entry.finish_job },
              })
            }
            // Auto-bind the requesting chat to the new session if the
            // caller included a chat_id.  Claude sessions register
            // asynchronously via socket, so poll until the session
            // appears in the registry before calling setActive.
            const chatId = optionalStringArg(op.args, "chat_id")
            if (chatId) {
              const pollSetActive = async (sessionId: string, chatId: string, attempts = 60) => {
                for (let i = 0; i < attempts; i++) {
                  if (registry.get(sessionId)) {
                    registry.setActive(chatId, sessionId)
                    return
                  }
                  await new Promise(res => setTimeout(res, 50))
                }
                log.warn("spawn_session_route_timeout", { sessionId, chatId })
              }
              pollSetActive(r.session_id, chatId)
            }
            return { ok: true, value: r }
          } catch (err: any) {
            return { ok: false, error: String(err?.message ?? err) }
          }
        }
        case "kill_session": {
          const name = stringArg(op.args, "name")
          const killed = registry.resolveName(name)
          if (killed) {
            await killSession(killed.id)
            unregisterSession(killed.id)
            await refreshTelegramMenu()
            webChannel?.broadcastToAll({ type: "session_removed", id: killed.id })
          }
          return { ok: true, value: "killed" }
        }
        case "rename_session": {
          // Self-targeting: a session renames *itself* (resolved from its UUID),
          // so no `old` is needed and any session — including can_orchestrate=false
          // workers — can name itself.
          if (!s) return { ok: false, error: "unknown session" }
          const { resolveSelfRename } = await import("./core/session-manager/naming")
          const res = resolveSelfRename(stringArg(op.args, "name"), s.name, registry.list().map((x) => x.name))
          if (!res.ok) return { ok: false, error: res.error }
          const oldName = s.name
          if (res.name !== oldName) {
            registry.rename(s.id, res.name)
            await refreshTelegramMenu()
            webChannel?.broadcastToAll({ type: "session_renamed", id: s.id, old: oldName, new: res.name })
          }
          return { ok: true, value: { name: res.name } }
        }
        case "mute_session": {
          const name = stringArg(op.args, "name")
          const mutedValue = optionalBooleanArg(op.args, "muted")
          if (mutedValue === undefined) return { ok: false, error: "muted must be a boolean" }
          const muted = registry.resolveName(name)
          if (!muted) return { ok: false, error: `no such session: ${name}` }
          registry.setMuted(muted.id, mutedValue)
          webChannel?.broadcastToAll({ type: "session_state", session: muted.id, mute: mutedValue })
          return { ok: true, value: "ok" }
        }
        case "list_sessions":  { return { ok: true, value: registry.listVisible().map((s: any) => ({ name: s.name, workdir: s.workdir, mute: s.mute })) } }
        case "set_active":     { const t = registry.resolveName(stringArg(op.args, "name")); if (!t) return { ok: false, error: "no such session" }; registry.setActive(stringArg(op.args, "chat_id"), t.id); return { ok: true, value: "ok" } }
        case "get_active":     { return { ok: true, value: registry.getActive(stringArg(op.args, "chat_id")) } }
        case "memory_search": {
          const q = stringArg(op.args, "query")
          const limit = typeof op.args?.limit === "number" ? op.args.limit : 10
          const includePersonal = s?.role === "personal_assistant"
          return { ok: true, value: searchStore.searchKnowledge(q, { includePersonal, limit }) }
        }
        case "find_sessions": {
          const q = stringArg(op.args, "query")
          const limit = typeof op.args?.limit === "number" ? op.args.limit : 10
          return { ok: true, value: searchStore.searchSessions(q, {
            project: typeof op.args?.project === "string" ? op.args.project : undefined,
            since: typeof op.args?.since === "string" ? op.args.since : undefined,
            agent: typeof op.args?.agent === "string" ? op.args.agent : undefined,
            limit,
          }) }
        }
        case "read_session": {
          const id = stringArg(op.args, "session_id")
          const row = db.query("SELECT workdir, agent, agent_session_id FROM sessions WHERE id = ? AND internal = 0").get(id) as { workdir: string; agent: string; agent_session_id: string | null } | null
          if (!row) return { ok: false, error: "no such session" }
          if (row.agent !== "claude" || !row.agent_session_id) {
            return { ok: true, value: { transcript: false, note: "no JSONL transcript for this agent; use the broker message history", messages: messageLog.get(id, 200) } }
          }
          const includeToolCalls = op.args?.include_tool_calls !== false
          const grep = typeof op.args?.grep === "string" ? op.args.grep : undefined
          const text = renderTranscript(claudeTranscriptPath(row.workdir, row.agent_session_id), { includeToolCalls, grep })
          return { ok: true, value: { transcript: true, session_id: id, text } }
        }
        case "expose_port": {
          if (!s) return { ok: false, error: "unknown session" }
          const port = optionalNumberArg(op.args, "port")
          if (!port || port < 1 || port > 65535) return { ok: false, error: "port must be 1-65535" }
          let domain = optionalStringArg(op.args, "domain")
          if (!domain) {
            const { randomBytes } = await import("crypto")
            domain = "px-" + randomBytes(4).toString("hex")
          }
          try {
            const isPublic = optionalBooleanArg(op.args, "public") === true
            const entry = registry.addProxy({ domain, sessionId: s.id, port, isPublic })
            const url = buildProxyPublicUrl(entry.domain, {
              baseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
              publicUrl: process.env.MUX_WEB_PUBLIC_URL,
            })
            webChannel?.broadcastToAll({ type: "proxy_created", proxy: proxyWsPayload(entry, proxyLivenessMonitor.getStatus(entry.domain)) })
            void proxyLivenessMonitor.refresh()
            return { ok: true, value: { url, domain: entry.domain, port: entry.port, isPublic: entry.isPublic } }
          } catch (err: any) {
            return { ok: false, error: err?.message ?? String(err) }
          }
        }
        case "unexpose_port": {
          if (!s) return { ok: false, error: "unknown session" }
          const domain = stringArg(op.args, "domain")
          if (!domain) return { ok: false, error: "domain required" }
          const existing = registry.getProxy(domain)
          if (!existing) return { ok: false, error: `no proxy registered for domain "${domain}"` }
          if (existing.sessionName !== s.name) return { ok: false, error: "can only remove your own proxies" }
          registry.removeProxy(domain)
          webChannel?.broadcastToAll({ type: "proxy_removed", domain })
          return { ok: true, value: { removed: true } }
        }
        case "set_proxy_public": {
          if (!s) return { ok: false, error: "unknown session" }
          const domain = stringArg(op.args, "domain")
          if (!domain) return { ok: false, error: "domain required" }
          const publicValue = optionalBooleanArg(op.args, "public")
          if (publicValue === undefined) return { ok: false, error: "public (boolean) required" }
          const existing = registry.getProxy(domain)
          if (!existing) return { ok: false, error: `no proxy registered for domain "${domain}"` }
          if (existing.sessionName !== s.name) return { ok: false, error: "can only update your own proxies" }
          try {
            const entry = registry.setProxyPublic(domain, publicValue)
            webChannel?.broadcastToAll({ type: "proxy_updated", proxy: proxyWsPayload(entry, proxyLivenessMonitor.getStatus(entry.domain)) })
            return { ok: true, value: { domain: entry.domain, isPublic: entry.isPublic } }
          } catch (err: any) {
            return { ok: false, error: err?.message ?? String(err) }
          }
        }
        case "list_devices": {
          return { ok: true, value: await listDevices() }
        }
        case "start_display": {
          if (!s) return { ok: false, error: "unknown session" }
          try {
            const info = await displayManager.start({
              sessionDisplayName: s.name,
              provider: optionalProviderArg(op.args, "provider"),
              device: optionalStringArg(op.args, "device"),
              width: optionalNumberArg(op.args, "width"),
              height: optionalNumberArg(op.args, "height"),
            })
            const hint = info.provider === "linux-xvfb" ? `run apps with DISPLAY=${info.display}`
              : info.provider === "scrcpy" ? `streaming device ${info.display} via scrcpy`
              : "streaming the macOS real screen"
            return { ok: true, value: { id: info.id, provider: info.provider, display: info.display, hint } }
          } catch (err: any) {
            return { ok: false, error: err?.message ?? String(err) }
          }
        }
        case "stop_display": {
          if (!s) return { ok: false, error: "unknown session" }
          const id = stringArg(op.args, "id")
          if (!id) return { ok: false, error: "id required" }
          const existing = displayManager.get(id)
          if (!existing) return { ok: false, error: `no display stream "${id}"` }
          if (existing.sessionName !== s.name) return { ok: false, error: "can only stop your own display streams" }
          await displayManager.stop(id)
          return { ok: true, value: { stopped: true } }
        }
        case "rpc_resolve": { agentRpc.settle(String(op.args.request_id), op.args.data); return { ok: true, value: "ok" } }
        case "rpc_reject":  { agentRpc.fail(String(op.args.request_id), String(op.args.error ?? "rejected")); return { ok: true, value: "ok" } }
      }
      return { ok: false, error: "unknown orchestration op" }
      } catch (err) {
        return { ok: false, error: String(err instanceof Error ? err.message : err) }
      }
    },
  },
})

const recentInboundIds = new RecentInboundIds()
const pendingReapply = new PendingReapply()
function deliverInbound(sessionId: string, text: string, meta: any): Promise<InboundDeliveryResult> {
  return deliverInboundCore({
    getAdapter: (id) => adapters.get(id),
    isClaude: (id) => (registry.get(id)?.agent ?? "claude") === "claude",
    sendInboundSocket: (id, payload) => server.sendInbound(id, payload),
    seen: recentInboundIds,
    // Re-broadcast the session's CURRENT agent_state on a successful hand-off so clients clear
    // their local "Sending…" bubble even when the turn-start UserPromptSubmit hook is dropped
    // (fire-and-forget curl) and the turn emits no other state change. Mutates nothing — it just
    // re-emits the same frame the change-listener would send (keeps delivery a pure reflector).
    onDelivered: (id) => webChannel?.broadcastToAll(toAgentStateFrame(id, agentStateStore.get(id), bgTaskStore.openCount(id))),
  }, sessionId, text, meta)
}

async function submitReview(sessionId: string): Promise<{ ok: boolean; delivered: number; reason?: string }> {
  const s = registry.get(sessionId)
  if (!s) return { ok: false, delivered: 0, reason: "no such session" }
  const open = reviewStore.listOpen(sessionId)
  if (!open.length) return { ok: true, delivered: 0 }
  const text = serializeReview(open)
  // Record a readable summary in the chat transcript so the submission is VISIBLE
  // (the agent receives the full structured review separately, below).
  const messageId = `review-${Date.now()}`
  const summary = `📝 Submitted code review — ${open.length} comment${open.length === 1 ? "" : "s"}:\n` +
    open.map((c) => `• ${c.repo ? c.repo + "/" : ""}${c.path}:${c.anchorLine} — ${c.body}`).join("\n")
  try {
    messageLog.append(s.id, {
      id: `in:web:${messageId}`,
      ts: new Date().toISOString(),
      direction: "inbound",
      channel: "web",
      chat_id: "web",
      message_id: messageId,
      text: summary,
    })
  } catch (err: any) {
    log.error("review_submit_append_failed", { session: s.name, err: err?.message ?? String(err) })
  }
  // Deliver the full review to the agent as a normal user turn (same path as a web
  // message): adapter.send for codex/cursor/opencode; server.sendInbound for claude.
  // chat_id "web" so the agent's reply routes back to the visible web chat.
  const meta = { channel: "web", chat_id: "web", message_id: messageId }
  const r = await deliverInbound(s.id, text, meta)
  if (!r.ok) return { ok: false, delivered: 0, reason: "agent adapter not ready" }
  for (const c of open) reviewStore.update(c.id, { status: "submitted" })
  return { ok: true, delivered: open.length }
}

// Deliver a plain message to a session's agent AND record it in the chat
// transcript (so it's visible), using the same path as a web user message.
async function deliverUserMessage(sessionId: string, text: string): Promise<{ ok: boolean; reason?: string }> {
  const s = registry.get(sessionId)
  if (!s) return { ok: false, reason: "no such session" }
  if (!text.trim()) return { ok: false, reason: "empty message" }
  const messageId = `msg-${Date.now()}`
  try {
    messageLog.append(s.id, { id: `in:web:${messageId}`, ts: new Date().toISOString(), direction: "inbound", channel: "web", chat_id: "web", message_id: messageId, text })
  } catch (err: any) { log.error("deliver_message_append_failed", { session: s.name, err: err?.message ?? String(err) }) }
  const meta = { channel: "web", chat_id: "web", message_id: messageId }
  const r = await deliverInbound(s.id, text, meta)
  if (!r.ok) return { ok: false, reason: "agent adapter not ready" }
  return { ok: true }
}

async function spawnSession(args: { workdir: string; requestedName?: string; agent?: AgentKind; model?: string; reasoningLevel?: string; worktree?: boolean; baseBranch?: string; internal?: boolean; rpcMcpConfig?: string }) {
  const agent = args.agent ?? AgentKind.Claude
  const workdir = normalizeExistingWorkdir(args.workdir)
  // Worktree-by-default: when the path is a single git repo and worktree isn't
  // explicitly disabled, spawn the agent in an isolated external worktree.
  let effectiveWorkdir = workdir
  let wt: WorktreeHandle | undefined
  if (args.worktree !== false) {
    const info = getRepoInfo(workdir)
    if (info.eligible && info.repoRoot) {
      try {
        wt = await createWorktree({
          repoRoot: info.repoRoot,
          baseBranch: args.baseBranch || info.currentBranch || "HEAD",
          sessionName: args.requestedName || deriveName(workdir),
        })
        effectiveWorkdir = wt.worktreeDir
      } catch (err) {
        log.warn("worktree_create_failed", { workdir, error: String(err) })
        // Fall back to spawning directly in the repo (today's behavior).
      }
    }
  }
  const effort = resolveSessionEffort(
    { agent, model: args.model, reasoningLevel: args.reasoningLevel },
    lookupModels,
  )
  const r = await spawnSessionHelper(
    {
      registry,
      bind: (sid: string) => server.bind(sid),
      spawnTmux: spawnSessionWindow,
      tmuxSession: TMUX_SESSION,
      resolveAttachment: resolveAttachmentPath,
      registerAdapter: (name, adapter, handle) => {
        const session = registry.resolveName(name)
        const sid = session?.id ?? name
        if (adapter instanceof CodexAdapter) {
          registerCodexRuntime(sid, name, adapter, handle as CodexSpawnHandle)
        } else if (adapter instanceof CursorAdapter) {
          registerCursorRuntime(sid, adapter)
        } else if (adapter instanceof OpenCodeAdapter) {
          registerOpenCodeRuntime(sid, name, adapter, handle as OpenCodeSpawnHandle)
        }
        wireAdapterEvents(adapter, sid)
      },
      onThreadId: (name: string, threadId: string) => {
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, threadId)
      },
      onCursorSessionId: (name: string, sessionId: string) => {
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
      },
      onOpenCodeSessionId: (name: string, sessionId: string) => {
        // Persist opencode's session id so it can be RESUMED (history intact)
        // after a broker restart, like codex/cursor. Without this the id is lost
        // and a restart can only start a fresh opencode session.
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
      },
      onClaudeSessionId: (name: string, claudeSessionId: string) => {
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, claudeSessionId)
        else pendingClaudeSessionId.set(name, claudeSessionId)
      },
      onTmuxWindowId: (brokerSessionId: string, windowId: string) => {
        const session = registry.get(brokerSessionId)
        if (session) registry.sessions.setTmuxWindowId(brokerSessionId, windowId)
        else pendingTmuxWindowId.set(brokerSessionId, windowId)
      },
    },
    // Worktree-backed: derive the session name from the ORIGINAL repo, not the
    // worktree dir (whose basename is a uuid) — otherwise the session is named after the uuid.
    { workdir: effectiveWorkdir, requestedName: args.requestedName ?? (wt ? deriveName(workdir) : undefined), agent: args.agent, model: args.model, reasoningLevel: args.reasoningLevel, effort, internal: args.internal, rpcMcpConfig: args.rpcMcpConfig },
  )
  // Claude registers async via the shim — record the internal intent so
  // onRegister can stamp the row. Non-claude agents are already registered
  // synchronously by the helper (internal threaded through there).
  if (args.internal && (args.agent ?? AgentKind.Claude) === AgentKind.Claude) pendingInternal.add(r.session_id)
  let registered = registry.get(r.session_id)
  if ((args.agent ?? "claude") === "claude") {
    // No blind sleep: wait for the shim to register (proof the window survived
    // AND the agent came up), while polling window liveness so an instant death
    // fast-fails instead of waiting out the full registration timeout.
    registered = await waitForRegisteredSession({
      id: r.session_id,
      name: r.name,
      lookup: (id, name) => registry.get(id) ?? registry.resolveName(name),
      stillAlive: async () => {
        // Pre-registration the row isn't in the registry yet — the freshly
        // spawned window id lives in pendingTmuxWindowId until onRegister drains
        // it. Check both, else liveness fails on every claude spawn (~50ms in).
        const wid = liveWindowId(
          r.session_id,
          (id) => registry.get(id)?.tmux_window_id,
          (id) => pendingTmuxWindowId.get(id),
        )
        return wid ? (await livePanePid(wid)) !== null : false
      },
    }).catch((err) => {
      log.warn("spawn_post_check_failed", { name: r.name, workdir })
      throw err
    })
  }
  if (args.model && registered) {
    registry.sessions.setModel(registered.id, args.model)
  }
  if (args.reasoningLevel && registered) {
    registry.sessions.setReasoningLevel(registered.id, args.reasoningLevel)
  }
  if (wt && registry.get(r.session_id)) {
    registry.sessions.setWorktree(r.session_id, {
      repo_root: wt.repoRoot, base_branch: wt.baseBranch, session_branch: wt.sessionBranch,
    })
  }
  gitStatusService.sync(gitServiceSessions())
  return r
}

async function reapplySessionAgentConfig(sessionId: string, changed?: { model: boolean; effort: boolean }): Promise<{ ok: true } | { ok: false; error: string }> {
  const session = registry.get(sessionId)
  if (!session) return { ok: false, error: `no such session: ${sessionId}` }

  const effort = sessionEffort(session)

  if (session.agent === "claude") {
    // Live switch: type /model and/or /effort into the running TUI — never a
    // kill+respawn (user decision 2026-07-10). Failure is an explicit error;
    // callers roll the registry back. `changed` narrows to what the user
    // actually touched so a model-only switch doesn't re-type /effort.
    const wid = await widOf(session)
    if (!wid) return { ok: false, error: "session window not found" }
    const result = await applyClaudeLiveSwitch(wid, {
      model: changed?.model === false ? undefined : session.model,
      effort: changed?.effort === false ? undefined : effort,
    })
    if (!result.ok) return result
    webChannel?.broadcastToAll({
      type: "session_state",
      session: session.id,
      model: session.model,
      reasoningLevel: effort,
    })
    return { ok: true }
  }

  if (session.agent === "codex") {
    try {
      const runtime = runtimes.get(session.id)
      if (runtime?.kind === AgentKind.Codex) runtime.handle.kill()
      deleteRuntime(session.id)

      const sessionHome = session.agent_home
      if (!sessionHome) return { ok: false, error: "codex session missing agent_home" }

      const auth = await resolveCodexAuth({
        apiKey: process.env.OPENAI_API_KEY,
        userCodexHome: `${home()}/.codex`,
        sessionCodexHome: sessionHome,
      })
      await codexPrepareSessionHome(sessionHome)
      const handle = spawnCodexAppServer({
        codexHome: sessionHome,
        workdir: session.workdir,
        authEnv: auth.env,
        model: session.model,
        reasoningLevel: effort,
        pluginConfigArgs: codexSpawnArgs({ sessionName: session.name }).args,
      })
      const newAdapter = new CodexAdapter({
        sessionName: session.name,
        workdir: session.workdir,
        client: handle.client,
        persistThreadId: async () => {},
        initialThreadId: session.agent_session_id,
        resolveAttachment: resolveAttachmentPath,
      })
      if (session.agent_session_id) {
        await newAdapter.resume()
      } else {
        await newAdapter.start()
      }
      registerCodexRuntime(session.id, session.name, newAdapter, handle)
      wireAdapterEvents(newAdapter, session.id)

      webChannel?.broadcastToAll({
        type: "session_state",
        session: session.id,
        model: session.model,
        reasoningLevel: effort,
      })
      return { ok: true }
    } catch (err: any) {
      return { ok: false, error: `agent config apply failed: ${err?.message ?? String(err)}` }
    }
  }

  return { ok: false, error: `agent does not support reasoning level: ${session.agent}` }
}

// Apply a model/effort change now if the session is idle (or applyNow), else
// record a deferred respawn to run when the turn ends. The registry was already
// updated by the caller; `olds` are restored only if a (now or deferred) apply fails.
async function applyOrDeferReapply(
  sessionId: string,
  olds: { oldModel?: string; oldReasoningLevel?: string },
  applyNow: boolean,
): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
  const phase = agentStateStore.get(sessionId).phase
  if (shouldDeferReapply(phase, applyNow)) {
    pendingReapply.mark(sessionId, olds)
    return { ok: true, status: "queued" }
  }
  const current = registry.get(sessionId)
  const result = await reapplySessionAgentConfig(sessionId, current ? changedSince(olds, current) : undefined)
  if (!result.ok) {
    registry.setModel(sessionId, olds.oldModel)
    registry.setReasoningLevel(sessionId, olds.oldReasoningLevel)
    return result
  }
  return { ok: true, status: "applied" }
}

async function switchSessionModel(sessionId: string, newModel: string, opts?: { applyNow?: boolean }): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
  const session = registry.get(sessionId)
  if (!session) return { ok: false, error: `no such session: ${sessionId}` }

  const oldModel = session.model
  const oldReasoningLevel = session.reasoningLevel
  registry.setModel(sessionId, newModel)
  if (session.reasoningLevel) {
    const clamped = clampSessionReasoningLevel({ ...session, model: newModel }, newModel, lookupModels)
    if (clamped !== session.reasoningLevel) {
      registry.setReasoningLevel(sessionId, clamped)
    }
  }

  // cursor and opencode read their adapter's `model` field fresh on each turn
  // (opencode re-parses it in send() via parseModel()), so a model switch is a
  // live in-process field update — no process/serve restart, no config reapply.
  if (session.agent === "cursor" || session.agent === "opencode") {
    const adapter = adapters.get(session.id) as any
    if (adapter && "model" in adapter) {
      adapter.model = newModel
    }
    webChannel?.broadcastToAll({ type: "session_state", session: session.id, model: newModel })
    return { ok: true, status: "applied" }
  }

  // Claude switches are typed into the TUI, which is only safe on an idle
  // composer — force queue-until-idle (user decision: never type mid-turn).
  const applyNow = session.agent === "claude" ? false : opts?.applyNow ?? false
  return applyOrDeferReapply(sessionId, { oldModel, oldReasoningLevel }, applyNow)
}

async function switchSessionReasoningLevel(sessionId: string, newLevel: string, opts?: { applyNow?: boolean }): Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }> {
  const session = registry.get(sessionId)
  if (!session) return { ok: false, error: `no such session: ${sessionId}` }

  if (session.agent === "cursor") {
    return { ok: false, error: "cursor sessions use model selection for reasoning depth" }
  }

  const models = lookupModels(session.agent)
  const levels = supportedReasoningLevels(session.agent, models, session.model)
  if (!levels.some((l) => l.id === newLevel)) {
    return { ok: false, error: `unsupported reasoning level: ${newLevel}` }
  }

  const oldReasoningLevel = session.reasoningLevel
  registry.setReasoningLevel(sessionId, newLevel)

  // Same queue-until-idle rule as switchSessionModel for claude (typed /effort).
  const applyNow = session.agent === "claude" ? false : opts?.applyNow ?? false
  return applyOrDeferReapply(sessionId, { oldModel: session.model, oldReasoningLevel }, applyNow)
}

// Wire telegram inbound through routing
// Channel-agnostic inbound pipeline — registered for every configured channel.
// The body only touches Channel-interface methods (ch.send) plus in-scope
// closures (classifyInbound/handleSlash/deliverInbound/registry/messageLog/…),
// so the same routing applies to Telegram and WhatsApp alike.
const wireInbound = (ch: Channel) => {
ch.on("inbound", async (msg: InboundMessage) => {
  log.debug(`${ch.name}.inbound`, {
    chat_id: msg.chat_id,
    user_id: msg.user_id,
    text: (msg.text ?? "").slice(0, 80),
    reply_to: msg.reply_to_message_id,
  })
  const decision = classifyInbound(
    { chat_id: msg.chat_id, text: msg.text ?? "", reply_to: msg.reply_to_message_id },
    registry,
    (cid, mid) => replyOwner.get(`${cid}:${mid}`),
  )
  log.debug("classify", {
    kind: decision.kind,
    ...(decision.kind === "session" ? { name: decision.name } : {}),
    ...(decision.kind === "slash"   ? { cmd:  decision.command } : {}),
    ...(decision.kind === "error"   ? { reason: decision.reason } : {}),
  })

  if (decision.kind === "slash") {
    // Wrap spawnSession for the slash dispatcher: when /spawn fires, we don't
    // yet have a registered session, but we want the new session to become
    // this chat's active once the shim connects back. Record a pending intent.
    const cmdCtx = {
      registry,
      messageLog,
      chat_id: msg.chat_id,
      fromSession: undefined,
      spawnSession: async (workdir: string, name?: string, agent?: AgentKind, model?: string, reasoningLevel?: string) => {
        const r = await spawnSession({ workdir, requestedName: name, agent, model, reasoningLevel })
        // Record pending intent BEFORE the shim's eventual onRegister so it
        // sees the chat to flip active onto. spawnSession resolved the final
        // name already, so the key is stable.
        pendingSpawnActive.set(r.name, msg.chat_id)
        return r
      },
      killSession,
      refreshMenu: refreshTelegramMenu,
      listModels: (agent: AgentKind) => modelCache.get(agent).map((m) => ({ id: m.id, displayName: m.displayName })),
      switchModel: switchSessionModel,
      switchReasoningLevel: switchSessionReasoningLevel,
      listReasoningLevels: (agent: AgentKind, model?: string) =>
        supportedReasoningLevels(agent, lookupModels(agent), model),
      resolveReasoningLevel: (sessionName: string) => {
        const s = registry.get(sessionName)
        return s ? sessionEffort(s) : undefined
      },
      proxyBaseDomain: process.env.MUX_PROXY_BASE_DOMAIN,
      proxyPublicUrl: process.env.MUX_WEB_PUBLIC_URL,
      resumeFromArchive: (id: string) => resumeFromArchive(id),
      spawnPA: async (args: { name: string; agent?: AgentKind; model?: string; focus?: string }) => {
        const workdir = join(home(), ".mux", "workspace", args.name)
        mkdirSync(workdir, { recursive: true })
        if (args.focus != null) {
          try { writeFileSync(join(workdir, "focus.md"), args.focus, "utf8") } catch {}
        }
        const r = await spawnPA({
          registry,
          name: args.name,
          agent: args.agent ?? "claude",
          workdir,
          model: args.model,
          bind: (sid: string) => server.bind(sid),
          spawnTmux: spawnSessionWindow,
          tmuxSession: TMUX_SESSION,
          resolveEffort: (s) => sessionEffort(s),
          registerAdapter: (name, adapter, handle) => {
            const session = registry.resolveName(name)
            const sid = session?.id ?? name
            if (adapter instanceof CodexAdapter) {
              registerCodexRuntime(sid, name, adapter, handle as CodexSpawnHandle)
            } else if (adapter instanceof CursorAdapter) {
              registerCursorRuntime(sid, adapter)
            } else if (adapter instanceof OpenCodeAdapter) {
              registerOpenCodeRuntime(sid, name, adapter, handle as OpenCodeSpawnHandle)
            }
            wireAdapterEvents(adapter, sid)
          },
          onClaudeSessionId: (brokerSessionId, claudeSessionId) => {
            const session = registry.get(brokerSessionId)
            if (session) registry.sessions.setAgentSessionId(session.id, claudeSessionId)
            else pendingClaudeSessionId.set(brokerSessionId, claudeSessionId)
          },
          onCodexSessionId: (brokerSessionId, sessionId) => {
            const session = registry.get(brokerSessionId)
            if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
          },
          onCursorSessionId: (name, sessionId) => {
            const session = registry.resolveName(name)
            if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
          },
          onOpenCodeSessionId: (name, sessionId) => {
            const session = registry.resolveName(name)
            if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
          },
          codexResolveAuth: resolveCodexAuth,
          codexSpawnAppServer: spawnCodexAppServer,
          codexAdapterFactory: (opts) => new CodexAdapter(opts),
          cursorResolveAuth: resolveCursorAuth,
          cursorRunnerFactory: makeRealCursorRunner,
          cursorAdapterFactory: (opts) => new CursorAdapter(opts),
        })
        const entry = registry.get(r.id)
        await refreshTelegramMenu()
        if (entry) {
          webChannel?.broadcastToAll({
            type: "session_added",
            session: {
              id: entry.id,
              name: entry.name,
              workdir: entry.workdir,
              mute: !!entry.mute,
              connected: true,
              agent: entry.agent,
              model: entry.model,
              reasoningLevel: sessionEffort(entry),
              repo_root: entry.repo_root || undefined,
              session_branch: entry.session_branch || undefined,
              finish_job: entry.finish_job,
            },
          })
        }
        return {
          id: entry?.id ?? r.id,
          name: r.name,
          workdir: entry?.workdir ?? workdir,
          agent: entry?.agent ?? args.agent ?? "claude",
          model: entry?.model,
        }
      },
      interrupt: async (name: string) => {
        const s = registry.get(name)
        return s ? interruptSessionById(s.id) : { ok: false, reason: "no such session" }
      },
    }
    const reply = await handleSlash(decision, cmdCtx)
    await ch.send({ op: "reply", chat_id: msg.chat_id, text: reply.text, disable_notification: false })
    return
  }

  if (decision.kind === "error") {
    await ch.send({ op: "reply", chat_id: msg.chat_id, text: `routing error: ${decision.reason}`, disable_notification: false })
    return
  }

  // session route
  const session = registry.get(decision.id)
  if (!session) {
    await ch.send({ op: "reply", chat_id: msg.chat_id, text: `no such session: ${decision.name}`, disable_notification: false })
    return
  }

  // Lazy resume: if the session is suspended, re-spawn it before delivering the message
  if (session.status === "suspended") {
    await ch.send({ op: "reply", chat_id: msg.chat_id, text: `Resuming session "${session.name}"...`, disable_notification: true })
    const resumed = await resumeSuspendedSession(session)
    if (!resumed) {
      await ch.send({ op: "reply", chat_id: msg.chat_id, text: `Failed to resume suspended session "${session.name}". Try /kill and re-spawn.`, disable_notification: false })
      return
    }
  }

  log.debug("send_inbound.before", { session: session.name, text: decision.text.slice(0, 80) })
  // chat_id is namespaced ("telegram:<id>" / "whatsapp:<jid>"), so embedding it in
  // the entry id disambiguates the same message_id arriving in DM vs group.
  try {
    messageLog.append(session.id, {
      id: `in:${msg.chat_id}:${msg.message_id}`,
      ts: msg.ts,
      direction: "inbound",
      channel: ch.name,
      chat_id: msg.chat_id,
      message_id: msg.message_id,
      text: decision.text,
      attachments: msg.attachments,
    })
  } catch (err: any) {
    log.error("messages_append_failed", { session: session.name, err: err?.message ?? String(err) })
  }
  try {
    const meta = {
      chat_id: msg.chat_id,
      message_id: msg.message_id,
      user: msg.user,
      user_id: msg.user_id,
      ts: msg.ts,
      ...(msg.attachments?.[0] ? {
        attachment_kind: msg.attachments[0].kind,
        attachment_file_id: msg.attachments[0].file_id,
        ...(msg.attachments[0].size != null ? { attachment_size: String(msg.attachments[0].size) } : {}),
        ...(msg.attachments[0].mime ? { attachment_mime: msg.attachments[0].mime } : {}),
        ...(msg.attachments[0].name ? { attachment_name: msg.attachments[0].name } : {}),
      } : {}),
    }
    const r = await deliverInbound(session.id, decision.text, meta)
    if (!r.ok) {
      log.warn("send_inbound.adapter_missing", { session: session.name, agent: session.agent ?? "claude" })
      await ch.send({
        op: "reply", chat_id: msg.chat_id,
        text: `⚠ ${session.agent ?? "claude"} session "${session.name}" is not responding (adapter disconnected). Try /kill + re-spawn.`,
        disable_notification: false,
      })
    } else {
      log.debug("send_inbound.after", { session: session.name, ok: true })
    }
  } catch (err: any) {
    log.error("send_inbound.error", { session: session.name, err: err?.message ?? String(err) })
  }
})
}
if (telegram) wireInbound(telegram)
if (whatsapp) wireInbound(whatsapp)

// Fan log activity to web subscribers — listeners receive sessionId (UUID),
// look up session name for display
messageLog.on("append", (sessionId, entry) => {
  webChannel?.broadcastToAll({ type: "message_append", session: sessionId, entry })
  // If a device is actively viewing this session, the new message is already
  // read — advance read status so the unread badge stays clear on every device.
  if (viewingTracker.isAnyExactViewing(sessionId)) advanceRead(sessionId)
})
activityStore.on("append", (sessionId: string, event) => {
  webChannel?.broadcastToAll({ type: "activity_append", session: sessionId, event })
})
bgTaskStore.on("change", (sessionId: string) => {
  webChannel?.broadcastToAll({ type: "bg_tasks", session: sessionId, tasks: bgTaskStore.get(sessionId) })
  // waiting/bgOpen live on agent_state — re-derive whenever tasks move.
  webChannel?.broadcastToAll(toAgentStateFrame(sessionId, agentStateStore.get(sessionId), bgTaskStore.openCount(sessionId)))
})
agentStateStore.on("change", (sessionId: string, state) => {
  webChannel?.broadcastToAll(toAgentStateFrame(sessionId, state, bgTaskStore.openCount(sessionId)))
  if (state.phase === "idle" && pendingReapply.has(sessionId)) {
    const olds = pendingReapply.take(sessionId)!
    const drainSession = registry.get(sessionId)
    void reapplySessionAgentConfig(sessionId, drainSession ? changedSince(olds, drainSession) : undefined).then((r) => {
      if (!r.ok) {
        registry.setModel(sessionId, olds.oldModel)
        registry.setReasoningLevel(sessionId, olds.oldReasoningLevel)
        const s = registry.get(sessionId)
        webChannel?.broadcastToAll({ type: "session_state", session: sessionId, model: s?.model, reasoningLevel: sessionEffort(s ?? ({} as any)) })
        void notifyAgentError(sessionId, s?.name ?? sessionId, "config", `Failed to apply model/effort change: ${r.error}`)
      }
    }).catch((err) => log.warn("drain_reapply_failed", { sessionId, err: String(err) }))
  }
})
agentStateStore.on("change", (sessionId: string, state) => {
  if (state.phase === "idle") gitStatusService.scheduleRecompute(sessionId)
})
agentStateStore.on("thoughtComplete", (sessionId: string, durationMs: number, now: number) => {
  const sec = Math.max(1, Math.round(durationMs / 1000))
  activityStore.append(sessionId, { ts: new Date(now).toISOString(), kind: "thinking", title: `Thought for ${sec}s` })
})
messageLog.on("update", (sessionId, entry_id, patch) => {
  webChannel?.broadcastToAll({ type: "message_update", session: sessionId, entry_id, text: patch.text, edited_at: patch.edited_at })
})
messageLog.on("reaction", (sessionId, entry_id, emoji, ts) => {
  webChannel?.broadcastToAll({ type: "message_reaction", session: sessionId, entry_id, emoji, ts })
})
// "remove" and "rename" listeners removed — we no longer broadcast remove on kill,
// and messages reference UUIDs so renaming doesn't affect them

// Wire web inbound → routing → shim
if (webChannel) {
  webChannel.on("inbound", async (msg) => {
    log.info("web_inbound", { target: msg.target_session_id, chat_id: msg.chat_id, text: (msg.text ?? "").slice(0, 80) })
    // Lazy-resume suspended sessions before routing.
    // target_session_id is the session UUID, so resolve by id — registry.get()
    // is keyed by UUID (getById). Resolving a display name instead needs
    // resolveName(); the rest of the web path (hasSession/adapterSend) also
    // uses getById.
    const targetSession = msg.target_session_id ? registry.get(msg.target_session_id) : undefined
    if (targetSession?.status === "suspended") {
      webChannel!.send({ op: "reply", chat_id: msg.chat_id, text: `Resuming session "${targetSession.name}"...` })
      const resumed = await resumeSuspendedSession(targetSession)
      if (!resumed) {
        webChannel!.send({ op: "reply", chat_id: msg.chat_id, text: `Failed to resume session "${targetSession.name}".` })
        return
      }
    }
    handleWebInbound(msg, {
      messageLog,
      sendInbound: (sid, payload) => server.sendInbound(sid, payload),
      hasSession: (id) => !!registry.get(id),
      replyNoSuchSession: async (chat_id, sessionId) => {
        log.warn("web_inbound_no_session", { chat_id, sessionId })
        await webChannel!.send({ op: "reply", chat_id, text: `no such session: ${sessionId}` })
      },
      adapterSend: async (sid, text, meta) => {
        const sessionEntry = registry.get(sid)
        const sessionId = sessionEntry?.id ?? sid
        log.info("web_inbound_routing", { sid, agent: sessionEntry?.agent ?? "claude" })
        try {
          const r = await deliverInbound(sessionId, text, meta)
          if (!r.ok) log.warn("web_inbound_adapter_missing", { sid, agent: sessionEntry?.agent ?? "claude" })
        } catch (err: any) {
          log.error("web_inbound_adapter_failed", { sid, err: err?.message ?? String(err) })
        }
      },
    })
  })
}

// Wire the agent-rpc registry now that all its broker-side deps exist
// (spawnSession, killSession, deliverInbound). Workers are claude/haiku sessions
// spawned with a strict rpc-only mcp config, marked internal, and run in a
// neutral scratch workdir. The orchestration dispatch (rpc_resolve/rpc_reject,
// above) settles/fails pending calls against this instance.
const RPC_WORKER_IDLE_MS = Number(process.env.RPC_WORKER_IDLE_SEC ?? 600) * 1000
const RPC_WORKERS_DIR = join(STATE_DIR, "rpc-workers")        // neutral scratch workdir
mkdirSync(RPC_WORKERS_DIR, { recursive: true })
agentRpc = createAgentRpc({
  defaultAgent: AgentKind.Claude,
  defaultModel: "haiku",
  defaultTimeoutMs: 30_000,
  newRequestId: () => randomUUID(),
  now: () => Date.now(),
  buildPrompt: buildRpcPrompt,
  isAlive: (sessionId) => !!registry.get(sessionId)?.connected,
  killWorker: async (sessionId) => { await killSession(sessionId); unregisterSession(sessionId) },
  deliver: async (sessionId, text) => { await deliverInbound(sessionId, text, {}) },
  spawnWorker: async ({ key, agent, model }) => {
    const safe = key.replace(/[^a-z0-9_-]/gi, "_")
    mkdirSync(join(STATE_DIR, "agents", "rpc"), { recursive: true })
    const mcpPath = join(STATE_DIR, "agents", "rpc", `${safe}.json`)
    writeRpcWorkerMcpConfig(mcpPath)
    const r = await spawnSession({ workdir: RPC_WORKERS_DIR, requestedName: `rpc-${safe}`, agent, model, internal: true, rpcMcpConfig: mcpPath })
    return { sessionId: r.session_id }
  },
})
const supervisor = createSupervisor({
  registry,
  bindSocket: (sid) => server.bind(sid),
  onClaudeSessionId: (brokerSessionId, claudeSessionId) => {
    pendingClaudeSessionId.set(brokerSessionId, claudeSessionId)
  },
  getPaName: () => settings.getAppConfig(appConfigEnv).paName,
  // Gate purely on `onboarded` (existing installs are seeded onboarded at boot,
  // below). NOT on device count — the wizard's own claim-on-first-connect creates
  // a device mid-onboarding, which must not trip the PA into spawning early
  // (named "assistant" before the user picks a name) on a restart.
  shouldAutoSpawnPA: () => settings.getAppConfig(appConfigEnv).onboarded,
  paWorkdir: appConfig.paWorkdir || undefined,
  resolveEffort: (s) => sessionEffort(s),
  reapInternalWorkers: () => agentRpc.reapIdle(RPC_WORKER_IDLE_MS),
})
// Existing installs (any prior sessions, active/suspended/archived) are implicitly
// onboarded — they keep their auto-PA and skip the wizard. Only a pristine instance
// stays un-onboarded. Uses SESSIONS, not devices: a fresh install mid-onboarding has
// a claimed device but no sessions yet, so it must not get seeded.
if (!settings.getAppConfig(appConfigEnv).onboarded &&
    (registry.list().length > 0 || registry.listArchived().length > 0)) {
  settings.setAppConfig({ onboarded: true })
  log.info("onboarded_seeded_existing_install", {})
}
await reconcileOnStartup({ registry, bindSocket: (sid) => server.bind(sid), supervisor, livePanePid: (wid) => livePanePid(wid) })

// Assign the PA respawn implementation now that supervisor is available.
// Called by setAppConfig when onboarding transitions false → true, so the PA
// picks up newly-saved agent credentials (process.env has been updated by
// applyCredentialEnv before this is invoked).
respawnPAsAfterOnboarding = async () => {
  try {
    const pas = registry.listPAs()
    for (const pa of pas) {
      try {
        const wid = await widOf(pa)
        if (wid) await killWindowById(wid).catch(() => {})
        registry.unregister(pa.name)
        log.info("pa_respawn_killed", { name: pa.name })
      } catch (err: any) {
        log.warn("pa_respawn_kill_failed", { name: pa.name, err: err?.message ?? String(err) })
      }
    }
    await supervisor.ensurePersonalAssistants()
    log.info("pa_respawn_after_onboarding_done")
  } catch (err: any) {
    log.warn("pa_respawn_after_onboarding_failed", { err: err?.message ?? String(err) })
  }
}

async function resumeNonClaudeAdapters(): Promise<void> {
  for (const s of registry.list()) {
    if (s.agent === "codex") {
      if (!s.agent_session_id || !s.agent_home) {
        log.warn("codex_resume_skip", { name: s.name, reason: "missing agent_session_id or agent_home" })
        continue
      }
      try {
        const auth = await resolveCodexAuth({
          apiKey: process.env.OPENAI_API_KEY,
          userCodexHome: `${home()}/.codex`,
          sessionCodexHome: s.agent_home,
        })
        await codexPrepareSessionHome(s.agent_home)
        const effort = sessionEffort(s)
        const handle = spawnCodexAppServer({
          codexHome: s.agent_home,
          workdir: s.workdir,
          authEnv: auth.env,
          model: s.model,
          reasoningLevel: effort,
          pluginConfigArgs: codexSpawnArgs({ sessionName: s.name }).args,
        })
        const adapter = new CodexAdapter({
          sessionName: s.name,
          workdir: s.workdir,
          client: handle.client,
          persistThreadId: async () => {},
          initialThreadId: s.agent_session_id,
          resolveAttachment: resolveAttachmentPath,
        })
        await adapter.resume()
        registerCodexRuntime(s.id, s.name, adapter, handle)
        wireAdapterEvents(adapter, s.id)
        if (s.status === "suspended") registry.sessions.activate(s.id, handle.pid ?? process.pid)
        log.info("codex_resume_ok", { name: s.name, thread: s.agent_session_id })
      } catch (err: any) {
        log.warn("codex_resume_failed", { name: s.name, err: String(err) })
      }
    } else if (s.agent === "cursor") {
      // Cursor sessions are per-turn — no persistent process. The adapter
      // just needs agent_home (config + auth dir). agent_session_id may be
      // absent if the session was spawned but never received a first message
      // yet; that's OK — initialSessionId=undefined means the first turn
      // starts fresh without --resume.
      if (!s.agent_home) {
        log.warn("cursor_resume_skip", { name: s.name, reason: "missing agent_home" })
        continue
      }
      try {
        const auth = await resolveCursorAuth({
          apiKey: process.env.CURSOR_API_KEY,
          userCursorDir: `${home()}/.cursor`,
          sessionHome: s.agent_home,
        })
        const runner = makeRealCursorRunner({ home: s.agent_home, authEnv: auth.env })
        const adapter = new CursorAdapter({
          sessionName: s.name,
          workdir: s.workdir,
          runner,
          persistSessionId: async (id) => {
            registry.sessions.setAgentSessionId(s.id, id)
          },
          initialSessionId: s.agent_session_id,
          resolveAttachment: resolveAttachmentPath,
        })
        registerCursorRuntime(s.id, adapter)
        wireAdapterEvents(adapter, s.id)
        if (s.status === "suspended") registry.sessions.activate(s.id, process.pid)
        log.info("cursor_resume_ready", { name: s.name, session_id: s.agent_session_id ?? "(first turn pending)" })
      } catch (err: any) {
        log.warn("cursor_resume_failed", { name: s.name, err: String(err) })
      }
    } else if (s.agent === "opencode") {
      // opencode's worker (in-process adapter + broker-child `opencode serve`)
      // dies with the broker. Without this respawn the session row survives but
      // adapters.get() is empty → inbound hits web_inbound_adapter_missing and
      // the turn never replies. agent_home holds the private config; the prior
      // opencode session id (if persisted) is resumed so history is preserved.
      if (!s.agent_home) {
        log.warn("opencode_resume_skip", { name: s.name, reason: "missing agent_home" })
        continue
      }
      try {
        const { adapter, handle } = await resumeOpenCodeSession(
          {
            resolveAttachment: resolveAttachmentPath,
            onOpenCodeSessionId: (_name, sid) => { registry.sessions.setAgentSessionId(s.id, sid) },
          },
          { id: s.id, name: s.name, workdir: s.workdir, agent_home: s.agent_home, model: s.model, agent_session_id: s.agent_session_id },
        )
        registerOpenCodeRuntime(s.id, s.name, adapter, handle)
        wireAdapterEvents(adapter, s.id)
        if (s.status === "suspended") registry.sessions.activate(s.id, handle.pid ?? process.pid)
        log.info("opencode_resume_ok", { name: s.name, session_id: s.agent_session_id ?? "(fresh)" })
      } catch (err: any) {
        log.warn("opencode_resume_failed", { name: s.name, err: String(err) })
      }
    }
  }
}

// Regenerate Codex's marketplace.json from the registry BEFORE resuming codex
// sessions — resumeNonClaudeAdapters runs `codex plugin add` per session home,
// which reads this marketplace; a stale/missing one (e.g. right after a rename)
// makes those adds fail until the next boot. Awaited so the file is current
// first. Never throws — logs and continues so plugin config can't block boot.
try {
  if (ensureMuxCoreSkills()) {
    log.info("mux_core_skills_synced")
  }
  // Register + enable mux-core in plugins.json if absent — without this a fresh
  // install spawns sessions with zero plugins (no /mux:soul, no mux skills).
  if (ensureMuxCoreRegistered()) {
    log.info("mux_core_plugin_registered")
  }
  if (ensureOpenCodePluginScopes()) {
    log.info("opencode_plugin_scopes_synced")
  }
} catch (err: any) {
  log.warn("mux_core_soul_skill_sync_failed", { err: err?.message ?? String(err) })
}
await codexPrepareGlobal({ onError: (err) => log.warn("codex_prepare_global_failed", { err }) })
  .catch((err) => log.warn("codex_prepare_global_failed", { err: String(err) }))

await resumeNonClaudeAdapters()
// Housekeeping at boot is intentionally NON-DESTRUCTIVE: collapse every cursor
// home's runtime to a symlink at the shared copy (safe, idempotent) and only
// LOG any orphan-looking homes. Actual deletion lives solely in the explicit
// scripts/reclaim-agent-homes.ts, which reads every session row from the DB.
// `knownHomes` MUST union active+archived — archived sessions are resumable
// (resumeFromArchive) but absent from registry.list()'s in-memory cache.
{
  const knownHomes = new Set(
    [...registry.list(), ...registry.listArchived()]
      .filter((s) => s.agent_home)
      .map((s) => s.agent_home as string),
  )
  const { linked } = reclaimCursorHomes()
  const { candidates } = gcOrphanAgentHomes(knownHomes, { dryRun: true })
  if (linked.length || candidates.length) {
    log.info("agent_home_housekeeping", {
      cursor_linked: linked.length,
      orphan_candidates: candidates.length,
      note: candidates.length ? "run scripts/reclaim-agent-homes.ts to reclaim" : undefined,
    })
  }
}
// Sweep stale runtime-assets directories from previous versions.
try {
  const swept = sweepRuntimeAssets(STATE_DIR, BUILD_VERSION)
  if (swept.length) log.info("runtime_assets_swept", { removed: swept })
} catch (err) { log.warn("runtime_assets_sweep_failed", { err: String(err) }) }
await refreshTelegramMenu()
if (webChannel) await webChannel.start()
// Begin probing exposed proxies (kicks an immediate refresh, then polls). Only
// meaningful with a web channel, but harmless otherwise; onChange no-ops when
// webChannel is undefined.
proxyLivenessMonitor.start()
refreshModels().catch((err) => log.warn("model_cache_init_failed", { err: String(err) }))
const modelRefreshInterval = setInterval(() => {
  refreshModels()
    .catch((err) => log.warn("model_refresh_failed", { err: String(err) }))
}, MODEL_REFRESH_INTERVAL_MS)
// --- Nightly knowledge curator ---------------------------------------------
// At MUX_CURATOR_HOUR:00 local, spawn a short-lived claude session that reads the
// last 24h of sessions and curates ~/.mux (commit + push + digest to the
// chat). Disabled unless MUX_CURATOR_ENABLED=1. MUX_CURATOR_RUN_NOW=1 fires one run
// ~10s after boot for testing.
{
  const curatorDeps: CuratorDeps = {
      // Web is one logical channel: the curator targets the constant "web" chat
      // and its digest/notice fans out to all devices.
      chatId: "web",
      repoPath: MUX_HOME,
      promptPath: curatorPromptPath(STATE_DIR),
      spawn: async ({ workdir, name }) => {
        const r = await spawnSession({ workdir, requestedName: name, agent: "claude" })
        return { name: r.name }
      },
      waitReady: async (name) => {
        // Wait for the session to report CONNECTED (a shim attached), not just
        // registered — delivering before the channel shim is up loses the frame.
        // ~30s budget (claude cold start + shim connect).
        for (let i = 0; i < 600; i++) {
          const s = registry.get(name) ?? registry.resolveName(name)
          if (s?.connected) return s.id
          await new Promise((res) => setTimeout(res, 50))
        }
        const s = registry.get(name) ?? registry.resolveName(name)
        return s?.id // fall back to whatever we have; deliver-retry is the backstop
      },
      sendInbound: async (sid, content, cid) => {
        await server.sendInbound(sid, { content, meta: { chat_id: cid } } as any)
      },
      isIdle: (sid) => (agentStateStore.get(sid)?.phase ?? "idle") === "idle",
      getActive: (cid) => registry.getActive(cid),
      setActive: (cid, name) => {
        const s = registry.get(name) ?? registry.resolveName(name)
        if (s) registry.setActive(cid, s.id)
      },
      archive: (name) => {
        void (async () => {
          const killed = registry.get(name) ?? registry.resolveName(name)
          if (!killed) return
          await killSession(killed.id)
          unregisterSession(killed.id)
          webChannel?.broadcastToAll({ type: "session_removed", id: killed.id })
        })().catch((err) => log.warn("curator_archive_failed", { err: String(err) }))
      },
      postNotice: async (_cid, text) => {
        const payload = { session: "curator", text, ts: new Date().toISOString() }
        for (const s of pushStore.all()) await pushSender.sendToDevice(s.device, payload)
        for (const r of deviceTokenStore.all()) if (r.routing_token) await nativeSender.sendToDevice(r.device, payload)
      },
      reindex: () => { searchStore.rebuildKnowledge() },
  }
  curatorScheduler = new CuratorScheduler(() => runCurator(curatorDeps))
  curatorScheduler.reconfigure(settings.getCurator())
  runCuratorNow = () => runCurator(curatorDeps)
  if (process.env.MUX_CURATOR_RUN_NOW === "1") {
    setTimeout(() => void runCurator(curatorDeps), 10_000)
    log.info("curator_run_now_armed")
  }
}

// Telegram long-polling (grammy bot.start()) does not resolve until shutdown, so it
// MUST be the last awaited call at boot — anything awaited after it never runs.
// WhatsApp's start() is quick (bind webhook + one status probe), so start it BEFORE
// the Telegram gate; otherwise the channel silently never comes up.
if (whatsapp) await whatsapp.start()
if (telegram) await telegram.start()

let shuttingDown = false
async function gracefulShutdown(signal: string) {
  if (shuttingDown) return
  shuttingDown = true
  log.info("graceful_shutdown", { signal })
  try {
    terminalManager.shutdown()
  } catch (err: any) { log.warn("terminal_shutdown_failed", { err: err?.message }) }
  try {
    for (const t of tailers.values()) t.stop()
  } catch (err: any) { log.warn("tailers_shutdown_failed", { err: err?.message }) }
  try {
    await displayManager.stopAll()
  } catch (err: any) { log.warn("display_shutdown_failed", { err: err?.message }) }
  try {
    proxyLivenessMonitor.stop()
  } catch (err: any) { log.warn("proxy_liveness_stop_failed", { err: err?.message }) }
  try {
    if (webChannel) await webChannel.stop()
  } catch (err: any) { log.warn("webChannel_stop_failed", { err: err?.message }) }
  try {
    updateChecker?.stop()
  } catch (err: any) { log.warn("update_checker_stop_failed", { err: err?.message }) }
  try {
    await relayProvider.stop()
  } catch (err: any) { log.warn("relay_provider_stop_failed", { err: err?.message ?? String(err) }) }
  if (telegram) try {
    await telegram.stop()
  } catch (err: any) { log.warn("telegram_stop_failed", { err: err?.message }) }
  if (whatsapp) try {
    await whatsapp.stop()
  } catch (err: any) { log.warn("whatsapp_stop_failed", { err: err?.message ?? String(err) }) }
  try {
    supervisor.stop()
  } catch (err: any) { log.warn("supervisor_stop_failed", { err: err?.message }) }
  try {
    curatorScheduler?.stop()
  } catch (err: any) { log.warn("curator_scheduler_stop_failed", { err: err?.message }) }
  try {
    clearInterval(gcInterval)
    clearInterval(modelRefreshInterval)
  } catch (err: any) { log.warn("gc_interval_clear_failed", { err: err?.message ?? String(err) }) }
  try {
    db.close()
  } catch (err: any) { log.warn("db_close_failed", { err: err?.message ?? String(err) }) }
  process.exit(0)
}
process.on("SIGTERM", () => gracefulShutdown("SIGTERM"))
process.on("SIGINT",  () => gracefulShutdown("SIGINT"))
