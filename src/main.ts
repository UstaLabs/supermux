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
import { WorkspaceService } from "./core/workspace/service"
import { workspaceDto, viewDto } from "./core/workspace/dto"
import { propagateSessionRename } from "./core/workspace/name"
import { makeReadAdvancer } from "./core/session-manager/read-status"
import { ProxyLivenessMonitor, type ProxyStatus } from "./core/proxy/liveness"
import { exposedLinksPublicUrl, hostRelayUrl } from "./core/relay/public-url"

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
      publicUrl: exposedProxyLinksBaseUrl(),
    }),
  }
}

import { startSocketServer } from "./core/session-manager/socket-server"
import { createSupervisor, reconcileOnStartup } from "./core/session-manager/supervisor"
import { acquirePidFile, releasePidFile } from "./core/session-manager/pid-file"
import { ensureWindowId } from "./core/session-manager/window-id"
import { resumedSessionPid } from "./core/session-manager/resume-pid"
import { spawnSession as spawnSessionHelper, spawnPA } from "./core/session-manager/spawn-helper"
import { SessionManager } from "./core/session-manager/manager"
import { buildClaudeSpawnSpec } from "./core/session-manager/spawn-command"
import { getSessionBackend } from "./core/runtime"
import { createAgentRpc } from "./core/agent-rpc"
import { buildRpcPrompt } from "./core/agent-rpc/prompts"
import { runStt, VOICE_STT_ENGINE } from "./core/transcription/stt"
import { buildVoicePayload } from "./core/transcription/voice-context"
import { cleanupDraft, VOICE_CLEANUP_MODEL } from "./core/transcription/voice-cleanup"
import { runTtsStream, VOICE_TTS_ENGINE } from "./core/tts/tts"
import { cursorSpawnArgs, codexSpawnArgs, claudeSpawnArgs, codexPrepareGlobal, codexPrepareSessionHome, opencodeConfigEntries, ensureOpenCodePluginScopes, ensureGrokPluginScopes } from "./core/plugins"
import { ensureMuxCoreSkills, ensureMuxCoreRegistered } from "./core/plugins/mux-core"
import { CommandRegistry, ClaudeCommandProvider, CodexCommandProvider, CursorCommandProvider, OpenCodeCommandProvider } from "./core/slash-commands"
import { AgentKind } from "./shared/agents"
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
import { usesFilesystemEndpoint } from "./core/local-endpoint"
import {
  MUX_HOME, STATE_DIR, PID_FILE, SOCKETS_DIR, ENV_FILE, INBOX_DIR, DEVICES_FILE, HOST_KEY_FILE,
} from "./shared/paths"
import { validateWebEnv } from "./shared/web-env"
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync, cpSync, chmodSync, unlinkSync, watch as fsWatch } from "fs"
import { randomBytes, randomUUID } from "crypto"
import { spawn as nodeSpawn, execFileSync } from "child_process"
import { makeLogger } from "./shared/log"
import { resolveCommand, spawnCommand } from "./core/process/launcher"
import { checkPreflight, hasBinary } from "./shared/preflight"
import { detectAllAgents, detectAgent } from "./core/agents/detect"
import { createInstallManager } from "./core/agents/install"
import { withAgentBinDirs } from "./core/agents/bin-dirs"
import { homedir, hostname } from "os"
import { home } from "./shared/home"
import { join, dirname, resolve, isAbsolute, sep } from "path"
import { fileURLToPath } from "url"
import { ClaudeCodeAdapter } from "./core/agents/claude/index"
import { applyClaudeLiveSwitch } from "./core/agents/claude/live-switch"
import { writeClaudeHooksSettings, resolveInternalHookSecret, CLAUDE_HOOKS_SETTINGS_PATH } from "./core/agents/claude/hooks-settings"
import type { AgentAdapter } from "./core/agents/types"
import { resolveCodexAuth } from "./core/agents/codex/auth"
import { spawnCodexAppServer, type CodexSpawnHandle } from "./core/agents/codex/spawn"
import { CodexAdapter } from "./core/agents/codex/adapter"
import type { CursorAdapter } from "./core/agents/cursor/adapter"
import { ModelCache } from "./core/models/cache"
import { discoverClaudeModels, discoverCodexModels, discoverCursorModels, discoverOpenCodeModels } from "./core/models/discovery"
import { discoverGrokModels } from "./core/agents/grok/model-discovery"
import { refreshModelCache, type ModelDiscoverers } from "./core/models/refresh"
import { listOpenCodeProviders, setOpenCodeApiKey, startOpenCodeOAuth, finishOpenCodeOAuth } from "./core/agents/opencode/auth-ops"
import { OpenCodeAdapter } from "./core/agents/opencode/adapter"
import type { OpenCodeSpawnHandle } from "./core/agents/opencode/spawn"
import { GrokAdapter } from "./core/agents/grok/adapter"
import { resolveSessionEffort, clampSessionReasoningLevel } from "./core/models/session-agent-settings"
import { supportedReasoningLevels, shouldShowReasoningControl } from "./core/models/reasoning-levels"
import { DeviceStore } from "./channels/web/device-store"
import { TerminalManager } from "./core/terminal/manager"
import { DisplayManager } from "./core/display/manager"
import { LinuxXvfbProvider } from "./core/display/providers/linux-xvfb"
import { MacosScreenProvider } from "./core/display/providers/macos-screen"
import { FsWatcher } from "./core/editor/fs-watcher"
import { ActivityStore } from "./core/session-manager/activity-store"
import { AgentStateStore } from "./core/session-manager/agent-state-store"
import { toAgentStateFrame } from "./core/session-manager/agent-state-frame"
import { BackgroundTaskStore } from "./core/session-manager/background-task-store"
import { TranscriptTailer } from "./core/agents/claude/transcript-tailer"
import { BgTaskDetector } from "./core/agents/claude/bg-task-detector"
import { claudeTranscriptPath } from "./core/agents/claude/transcript-path"
import { normalizeToolName } from "./core/agents/tool-normalize"
import { gcOrphanAgentHomes, reclaimCursorHomes } from "./core/agents/shared-runtime"
import { CuratorScheduler } from "./core/curator/scheduler"
import { runCurator, type CuratorDeps } from "./core/curator/run"
import { curatorPromptPath, frpcPath } from "./core/runtime-assets"
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
import { claudeCliIsAuthenticated } from "./core/agents/claude-auth-status"
import { getRepoInfo } from "./core/git/repo-info"
import { createWorktree, ensureWorktreeAt, type WorktreeHandle } from "./core/worktree/manager"
import { startFinishJob, getFinishJob, clearFinishJob, type FinishJob, type FinishJobOpts, type FinishAction } from "./core/worktree/finish-job"
import { computeReadiness, type FinishReadiness } from "./core/worktree/readiness"
import { suggestVerify } from "./core/worktree/verify-suggest"
import { loadFinishConfig } from "./core/worktree/finish-config"
import { computeLiteStatus } from "./core/worktree/lite-status"
import { GitStatusService, type ServiceSession } from "./core/worktree/git-status-service"
import { deriveName, ensureUnique } from "./core/session-manager/naming"

const log = makeLogger("main")
const relayLog = makeLogger("core/relay/frp-provider")

const __dirname = dirname(fileURLToPath(import.meta.url))
const STATIC_DIR = join(__dirname, "channels/web/static")
const SOUL_PATH = join(MUX_HOME, "soul.md")
const IS_TEST_BROKER = process.env.MUX_TEST_BROKER === "1"

// The browser-journey fixture boots this real entrypoint, but it must never
// mutate a developer's ~/.agents / agent runtimes or bind the live broker port.
// Require explicit, nested throwaway state before honoring the side-effect-free
// boot path so a stray MUX_TEST_BROKER=1 fails closed.
if (IS_TEST_BROKER) {
  const configuredHome = process.env.MUX_HOME
  const configuredState = process.env.MUX_STATE_DIR
  const configuredPort = Number(process.env.MUX_WEB_PORT)
  const nestedState = configuredHome && configuredState
    ? resolve(configuredState).startsWith(resolve(configuredHome) + sep)
    : false
  if (!configuredHome || !configuredState || !nestedState || !Number.isInteger(configuredPort) || configuredPort <= 0 || configuredPort === 9898) {
    log.error("test_broker_isolation_invalid", {
      has_home: !!configuredHome,
      has_state: !!configuredState,
      state_nested_under_home: nestedState,
      port: process.env.MUX_WEB_PORT,
    })
    process.exit(1)
  }
}

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
if (usesFilesystemEndpoint()) mkdirSync(SOCKETS_DIR, { recursive: true, mode: 0o700 })

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
// Repair any session that has no workspace before anything reads the tables.
// A heal logs at warn — it means a crash between the session insert and the
// workspace insert, not a normal path. Called once at startup, never from the
// Registry constructor.
registry.healWorkspaces()
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
    workdir,
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
  [AgentKind.Grok]: discoverGrokModels,
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

const agentModelRefreshes = new Map<AgentKind, Promise<void>>()

function refreshAgentModels(agent: AgentKind): Promise<void> {
  const running = agentModelRefreshes.get(agent)
  if (running) return running
  const discover = modelDiscoverers[agent]
  if (!discover) return Promise.resolve()
  const refresh = refreshModels({ [agent]: discover })
    .finally(() => agentModelRefreshes.delete(agent))
  agentModelRefreshes.set(agent, refresh)
  return refresh
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
    const adapter = current ? runtimes.get(current.id)?.adapter : undefined
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
const sessionBackend = getSessionBackend()
// The addressable persistent runtime target ID for a session, healing a missing ID once via a
// name->id resolve (then persisted), so every kill/interrupt/liveness/consent
// path routes by id — never by window name. Returns null when no live window
// can be found, so callers no-op + log instead of routing by a stale name.
const runtimeTargetIdOf = (s: { id: string; name: string; tmux_window_id?: string }) =>
  ensureWindowId(s, {
    tmuxSession: TMUX_SESSION,
    resolve: (group, name) => sessionBackend.resolve(group, name),
    persist: (id, wid) => registry.sessions.setTmuxWindowId(id, wid),
  })
const replyOwner = new Map<string, string>()              // key: `${chat_id}:${message_id}`
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

// The SessionManager component owns per-session runtime state (Move 2). The
// thin aliases below keep existing call sites unchanged while the handlers
// migrate into the component stage by stage.
// Collaborators enter as narrow ports, once, here. Everything declared later in
// this file (terminalManager, displayManager, fsWatcher, commandRegistry, the
// socket server, …) is deref'd lazily inside a closure — and webChannel/agentRpc
// are `let`-assigned much later, so their thunks must never capture the value.
const sessionManager = new SessionManager(registry, {
  getWebChannel: () => webChannel,
  getAgentRpc: () => agentRpc,
  socket: { sendInbound: (session_id, payload) => server.sendInbound(session_id, payload) },
  backend: {
    runtimeTargetIdOf,
    kill: (targetId) => sessionBackend.kill(targetId),
  },
  cleanup: {
    terminals: { killAllForSession: (name) => terminalManager.killAllForSession(name) },
    fsWatcher: { killSession: (name) => fsWatcher.killSession(name) },
    stopClaudeTailer,
    releaseDraftAttachments: (payload) => releaseDraftAttachmentRefs(payload),
    recentInbound: { clear: (id) => recentInboundIds.clear(id) },
    pendingReapply: { clear: (id) => pendingReapply.clear(id) },
    syncGitStatus: () => gitStatusService.sync(gitServiceSessions()),
  },
  displays: {
    killAllForSession: (name) => displayManager.killAllForSession(name),
    start: (args) => displayManager.start(args),
    get: (id) => displayManager.get(id),
    stop: (id) => displayManager.stop(id),
  },
  agentState: agentStateStore,
  bgTasks: bgTaskStore,
  commands: {
    remove: (name) => commandRegistry.remove(name),
    refresh: (name) => commandRegistry.refresh(name),
  },
  register: {
    interruptClaudePane,
    notifyAgentError,
    ensureClaudeTailer,
    maybeAutoSendSoulSetup,
  },
  outbound: {
    onAssistantMessage,
    getChannel: (name) => channels[name],
    telegramApi: telegram ? { token: TG_TOKEN!, getFile: (id: string) => telegram.getFile(id) } : undefined,
  },
  orchestration: {
    spawnSession: (args) => spawnSession(args),
    refreshTelegramMenu,
    wsDto: (id) => wsDto(id),
    exposedProxyLinksBaseUrl,
    proxyWsPayload,
    proxyLiveness: {
      getStatus: (domain) => proxyLivenessMonitor.getStatus(domain),
      refresh: () => proxyLivenessMonitor.refresh(),
    },
  },
  stores: { fileStore, messageLog, searchStore, db },
  resume: {
    bind: (sid) => server.bind(sid),
    ensureSessionWorktree: (s) => ensureSessionWorktree(s),
    sessionEffort: (s) => sessionEffort(s as any),
    resolveAttachment: (file_id) => resolveAttachmentPath(file_id),
    wireAdapterEvents: (adapter, sid) => wireAdapterEvents(adapter, sid),
    sessionBackend,
    tmuxSession: TMUX_SESSION,
  },
})
const runtimes = sessionManager.runtimes
const soulSetupQueued = new Set<string>()

const deleteRuntime = (sessionId: string) => sessionManager.deleteRuntime(sessionId)
const registerClaudeRuntime = (sessionId: string, adapter: ClaudeCodeAdapter) => sessionManager.registerClaudeRuntime(sessionId, adapter)
const registerCodexRuntime = (sessionId: string, name: string, adapter: CodexAdapter, handle: CodexSpawnHandle) => sessionManager.registerCodexRuntime(sessionId, name, adapter, handle)
const registerCursorRuntime = (sessionId: string, adapter: CursorAdapter) => sessionManager.registerCursorRuntime(sessionId, adapter)
const registerGrokRuntime = (sessionId: string, adapter: GrokAdapter) => sessionManager.registerGrokRuntime(sessionId, adapter)
const registerOpenCodeRuntime = (sessionId: string, name: string, adapter: OpenCodeAdapter, handle: OpenCodeSpawnHandle) => sessionManager.registerOpenCodeRuntime(sessionId, name, adapter, handle)

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
    const adapter = runtimes.get(s.id)?.adapter as { rpc?: import("./core/slash-commands/types").CodexRpc; commandClient?: import("./core/slash-commands/types").OpenCodeCommandClient } | undefined
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
    const adapter = runtimes.get(s.id)?.adapter as { rpc?: import("./core/slash-commands/types").CodexRpc } | undefined
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

const unregisterSession = (id: string) => sessionManager.unregister(id)

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

// Soft-interrupt a Claude session by sending a single Esc to its persistent terminal —
// Claude's native "stop generating" key. The pane runs Claude as the foreground
// process, so send-keys to its window reaches the REPL. Addressed strictly by
// window id (healed from the registry) so a rename can't aim us at a stale name.
async function interruptClaudePane(sessionId: string): Promise<void> {
  const s = registry.get(sessionId)
  if (!s || s.agent !== AgentKind.Claude) return
  const wid = await runtimeTargetIdOf(s)
  if (!wid) { log.warn("claude_interrupt_no_runtime_target", { sessionId }); return }
  await sessionBackend.sendKeys(wid, ["Escape"])
}

// The one funnel every Stop surface (web button, /stop command) routes through:
// dispatch to the agent's own interrupt(). The broker does NOT flip the live
// status itself — idle is reflected from the session: Claude's interrupt marker
// in the transcript, or codex/cursor turn-complete.
async function interruptSessionById(sessionId: string): Promise<{ ok: boolean; reason?: string }> {
  return runInterrupt({ adapter: runtimes.get(sessionId)?.adapter })
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
    const session = registry.get(sessionId)
    const workdir = session?.workdir
    try {
      for (const a of toActivityEvents(adapter.kind, ev, now, workdir)) activityStore.append(sessionId, a)
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
const terminalManager = new TerminalManager()
const displayManager = new DisplayManager({
  providers: [new LinuxXvfbProvider(), new MacosScreenProvider()],
  onAdded: (info) => webChannel?.broadcastToAll({ type: "display_added", display: info }),
  onRemoved: (id) => webChannel?.broadcastToAll({ type: "display_removed", id }),
})
// Workspace behaviour layer: close-a-view side effects and create-for-session.
// archiveSession closes over killSession/unregisterSession/webChannel the same
// way the killSession opt does; those are only invoked after startup wiring.
const workspaceService = new WorkspaceService(
  registry.workspaces,
  {
    archiveSession: async (id) => {
      const s = registry.get(id)
      if (!s) return
      await killSession(s.id)
      unregisterSession(s.id)
      webChannel?.broadcastToAll({ type: "session_removed", id: s.id })
    },
    closeTerminal: async (scope, terminalId) => {
      await terminalManager.close(scope, terminalId)
    },
    stopDisplay: async (id) => {
      await displayManager.stop(id)
    },
  },
  registry.db,
)

/**
 * Archive a workspace once nothing live remains in it.
 *
 * "Live" means a chat view whose session is still active/suspended, or any
 * non-chat view (terminal / editor / display). Spec §9.3 is explicit that
 * closing the last CHAT does not close the workspace — a workspace holding a
 * terminal is still a workspace. This only retires the empty shells that a
 * session archive would otherwise strand in the sidebar.
 */
function archiveWorkspaceIfEmpty(workspaceId: string): void {
  const ws = registry.workspaces.getById(workspaceId)
  if (!ws || ws.status !== "active") return
  const views = registry.workspaces.listViews(workspaceId)
  if (views.some((v) => v.kind !== "chat")) return
  const liveChat = registry.workspaces.chatSessionIds(workspaceId).some((sid) => {
    const row = registry.db
      .query("SELECT status FROM sessions WHERE id = ?")
      .get(sid) as { status?: string } | null
    return row?.status === "active" || row?.status === "suspended"
  })
  if (liveChat) return
  registry.workspaces.archive(workspaceId)
  webChannel?.broadcastToAll({ type: "workspace_removed", id: workspaceId })
}

const wsDto = (id: string) => {
  const w = registry.workspaces.getById(id)
  return w ? workspaceDto(w, registry.workspaces.listViews(id)) : undefined
}
const fsWatcher = new FsWatcher()

function spawnLoginProc(kind: string) {
  const h = homedir()
  let cmd: string, args: string[]
  let detached = false
  const env = { ...process.env } as Record<string, string>
  if (kind === "codex") { cmd = resolveCommand(["codex"], env, process.platform) ?? "codex"; args = ["login", "--device-auth"]; env.CODEX_HOME = join(h, ".codex") }
  else if (kind === "cursor") { cmd = resolveCommand(["cursor-agent", "agent"], env, process.platform) ?? "cursor-agent"; args = ["login"]; env.NO_OPEN_BROWSER = "1" }
  else if (kind === "claude") {
    const spec = claudeLoginSpawnCommand()
    ;({ cmd, args } = spec)
    detached = spec.detached ?? false
  }
  // grok prints the device URL + code on plain stdout (no PTY needed, same as codex).
  else if (kind === "grok") { cmd = resolveCommand(["grok"], env, process.platform) ?? "grok"; args = ["login", "--device-auth"] }
  else { cmd = ""; args = [] } // unknown kind handled below
  let outCb: (c: string) => void = () => {}
  let exitCb: (code: number | null) => void = () => {}
  if (!cmd) {
    queueMicrotask(() => { outCb(`no device login for ${kind}`); exitCb(127) })
    return { onStdout: (cb: (c: string) => void) => { outCb = cb }, onExit: (cb: (code: number | null) => void) => { exitCb = cb }, kill: () => {}, write: () => {} }
  }
  let child: ReturnType<typeof nodeSpawn>
  try {
    child = (kind === "claude")
      ? nodeSpawn(cmd, args, { env, detached })
      : spawnCommand(cmd, args, { env, detached })
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

// Relay data plane (frp). Fresh native CLI + desktop setups enable the hosted
// relay by default; an empty/absent MUX_RELAY_DOMAIN keeps the broker LAN-only.
// The provider boundary still permits custom/self-hosted relay implementations.
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
      activationGated: process.platform !== "win32",
      spawn: (argv) => {
        const command = parentBoundFrpcCommand([frpcPath(STATE_DIR), ...argv.slice(1)])
        if (process.platform === "win32") {
          const proc = Bun.spawn(command, { stdin: "ignore", stdout: "ignore", stderr: "ignore" })
          return { kill: () => proc.kill(), activate: () => {}, exited: proc.exited }
        }
        const proc = Bun.spawn(command, { stdin: "pipe", stdout: "ignore", stderr: "ignore" })
        let activated = false
        return {
          kill: () => proc.kill(),
          activate: async () => {
            if (activated) return
            activated = true
            await proc.stdin.write("activate\n")
            await proc.stdin.end()
          },
          exited: proc.exited,
        }
      },
      writeConfig: (toml) => { const p = join(STATE_DIR, "frpc.toml"); writeFileSync(p, toml, { mode: 0o600 }); return p },
      log: relayLog,
    })
  : new NullRelayProvider()
void relayProvider.start()

function exposedProxyLinksBaseUrl(): string | undefined {
  return exposedLinksPublicUrl({
    hostId: hostIdentity.hostId,
    relayDomain: process.env.MUX_RELAY_DOMAIN,
    relayUrl: relayProvider.status().relayUrl,
    publicUrl: process.env.MUX_WEB_PUBLIC_URL,
  })
}

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
    // CSRF trusts this as a second allowed Origin for cookie browsers on the
    // hosted relay. Prefer the live online URL, but fall back to the
    // deterministic host URL whenever MUX_RELAY_DOMAIN is set — frpc can be
    // mid-reconnect (status.state !== "online", relayUrl cleared) while a
    // browser still POSTs from https://h-<hostId>.relay… and must not get
    // "bad origin". Same fallback as exposedLinksPublicUrl.
    getRelayUrl: () =>
      relayProvider.status().relayUrl
      ?? (process.env.MUX_RELAY_DOMAIN
        ? hostRelayUrl(hostIdentity.hostId, process.env.MUX_RELAY_DOMAIN)
        : undefined),
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
        user_status: s.user_status,
        sort_order: s.sort_order,
        draft_payload: s.draft_payload,
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
      const adapter = runtimes.get(s.id)?.adapter
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
    refreshModels: (agent) => refreshAgentModels(agent),
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
        inheritFromSessionId: args.inheritFrom,
      })
      const entry = registry.get(r.session_id)
      // Spec §9.1: a session without a workspaceId gets a fresh workspace.
      // With one, it joins that workspace as a second chat.
      // Broadcast workspace_added/changed before session_added (spec §9.1 step 6).
      if (entry) {
        if (args.workspaceId) {
          workspaceService.addChatSession(args.workspaceId, entry.id)
          const dto = wsDto(args.workspaceId)
          if (dto) webChannel?.broadcastToAll({ type: "workspace_changed", workspace: dto })
        } else {
          const ws = workspaceService.createForSession({
            sessionId: entry.id,
            name: entry.name,
            workdir: entry.workdir,
            repo_root: entry.repo_root || undefined,
            base_branch: entry.base_branch || undefined,
            branch: entry.session_branch || undefined,
            sort_order: entry.sort_order,
          })
          webChannel?.broadcastToAll({
            type: "workspace_added",
            workspace: workspaceDto(ws, registry.workspaces.listViews(ws.id)),
          })
        }
      }
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
            user_status: entry.user_status,
            sort_order: entry.sort_order,
            draft_payload: entry.draft_payload,
          },
        })
      }
      return {
        id: entry?.id ?? r.session_id,
        name: entry?.name ?? r.name,
        workdir: entry?.workdir ?? args.workdir,
        agent: entry?.agent ?? "claude",
        model: entry?.model,
        reasoningLevel: entry ? sessionEffort(entry) : undefined,
        repo_root: entry?.repo_root || undefined,
        session_branch: entry?.session_branch || undefined,
      }
    },
    createDraft: async (args) => {
      // Drafts have no process, so they never get a tmux window; a unique
      // DISPLAY name is all we need. Derive one exactly like the spawn path
      // (requestedName ?? deriveName(workdir)) and uniquify against every
      // taken name plus outstanding reservations so a draft can't collide
      // with a live session or an in-flight spawn.
      const base = args.name ?? deriveName(args.workdir)
      const name = ensureUnique(base, registry.takenNames())
      const draftPayload = args.draftPayload as import("./core/session-manager/types").DraftPayload | undefined
      const s = registry.sessions.register({
        name,
        agent: (args.agent ?? "claude"),
        workdir: args.workdir,
        pid: 0,
        model: args.model,
        reasoningLevel: args.reasoningLevel,
        user_status: "draft",
        draft_payload: draftPayload,
      })
      // Hold attachment refs while the draft exists (released on kill/start).
      bumpDraftAttachmentRefs(draftPayload)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({
        type: "session_added",
        session: {
          id: s.id,
          name: s.name,
          workdir: s.workdir,
          mute: !!s.mute,
          connected: false,
          agent: s.agent,
          model: s.model,
          reasoningLevel: sessionEffort(s),
          repo_root: s.repo_root || undefined,
          session_branch: s.session_branch || undefined,
          finish_job: s.finish_job,
          user_status: s.user_status,
          sort_order: s.sort_order,
          draft_payload: s.draft_payload,
        },
      })
      return { id: s.id, name: s.name, workdir: s.workdir, agent: s.agent }
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
        tmuxSession: TMUX_SESSION,
        resolveEffort: (s) => sessionEffort(s),
        registerAdapter: (name, adapter, handle) => sessionManager.registerSpawnedAdapter(name, adapter, handle),
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
        onGrokSessionId: (name, sessionId) => {
          const session = registry.resolveName(name)
          if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
        },
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
            user_status: entry.user_status,
            sort_order: entry.sort_order,
            draft_payload: entry.draft_payload,
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
      user_status: s.user_status,
      sort_order: s.sort_order,
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
      // SessionRecord carries no workspace_id (the column exists, the type does
      // not) — read it straight from the row before the session goes.
      const workspaceId = (registry.db
        .query("SELECT workspace_id FROM sessions WHERE id = ?")
        .get(s.id) as { workspace_id?: string } | null)?.workspace_id
      await killSession(s.id)
      unregisterSession(s.id)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({ type: "session_removed", id: s.id })
      // Archiving a session used to leave its workspace behind forever: the row
      // stayed in the sidebar with a dead chat, and "archive" did nothing because
      // there was no live session left to kill. Retire the workspace once nothing
      // live remains in it.
      //
      // A workspace with non-chat views (terminal / editor / display) SURVIVES —
      // spec §9.3: closing the last chat does not close the workspace.
      if (workspaceId) archiveWorkspaceIfEmpty(workspaceId)
    },
    renameSession: async (id, newName) => {
      const s = registry.get(id)
      if (!s) throw new Error("session not found")
      const oldName = s.name
      registry.rename(s.id, newName)
      await refreshTelegramMenu()
      webChannel?.broadcastToAll({ type: "session_renamed", id: s.id, old: oldName, new: newName })
      // Spec §9.5: the workspace name follows its primary session. Both frames
      // go out — an old client only knows the first one. propagateSessionRename
      // returns undefined when the name did not change (loop guard).
      const wsId = propagateSessionRename(registry.workspaces, s.id, newName)
      if (wsId) {
        const dto = wsDto(wsId)
        if (dto) webChannel?.broadcastToAll({ type: "workspace_changed", workspace: dto })
      }
    },
    reorderSessions: (orderedIds) => {
      registry.sessions.reorder(orderedIds)
      // Fan out so every connected client (web / native) re-sorts live —
      // the drag origin already applied optimistically; peers need this frame.
      webChannel?.broadcastToAll({ type: "sessions_reordered", orderedIds })
    },
    listWorkspaces: () => {
      // Hide workspaces owned by an INTERNAL session, exactly as the session list
      // does via registry.listVisible() (`filter(s => !s.internal)`). Without this
      // the rpc-worker sessions — invisible in the session sidebar since forever —
      // reappear as workspace rows, which is what the user hit.
      const internal = new Set(
        (registry.db.query("SELECT id FROM sessions WHERE internal = 1").all() as Array<{ id: string }>)
          .map((r) => r.id),
      )
      return registry.workspaces
        .list()
        .filter((w) => !w.primary_session_id || !internal.has(w.primary_session_id))
        .map((w) => workspaceDto(w, registry.workspaces.listViews(w.id)))
    },
    getWorkspace: (id) => wsDto(id),
    createWorkspace: async (args) => {
      const ws = registry.workspaces.create({ name: args.name ?? "Workspace", workdir: args.workdir })
      return workspaceDto(ws, [])
    },
    patchWorkspace: (id, patch) => {
      // byUser: true is deliberate — a rename on this HTTP route came from a
      // human, and spec §9.5 rule 5 locks the name against agent propagation.
      if (patch.name !== undefined) registry.workspaces.rename(id, patch.name, { byUser: true })
      if (patch.layout !== undefined) registry.workspaces.setLayout(id, patch.layout as any)
      if (patch.activeViewId !== undefined) registry.workspaces.setActiveView(id, patch.activeViewId)
      const dto = wsDto(id)
      if (!dto) throw new Error("workspace not found")
      return dto
    },
    archiveWorkspace: async (id) => { await workspaceService.archiveWorkspace(id) },
    reorderWorkspaces: (orderedIds) => registry.workspaces.reorder(orderedIds),
    addWorkspaceView: (workspaceId, args) => {
      const v = registry.workspaces.addView(workspaceId, {
        kind: args.kind as any, state: args.state as any, title: args.title, groupId: args.groupId,
      })
      return viewDto(v)
    },
    patchWorkspaceView: (viewId, patch) => {
      if (patch.title !== undefined) registry.workspaces.setViewTitle(viewId, patch.title)
      if (patch.state !== undefined) registry.workspaces.setViewState(viewId, patch.state as any)
      const v = registry.workspaces.getView(viewId)
      if (!v) throw new Error("view not found")
      return viewDto(v)
    },
    closeWorkspaceView: async (viewId) => { await workspaceService.closeView(viewId) },
    moveWorkspaceView: (viewId, toWorkspaceId, toGroupId) =>
      registry.workspaces.moveView(viewId, toWorkspaceId, toGroupId),
    getWorkspaceWorkdir: (id) => registry.workspaces.getById(id)?.workdir,
    // sessions.workspace_id lives on disk; SessionRecord does not expose it.
    getSessionWorkspaceId: (id) => {
      const row = db.query("SELECT workspace_id FROM sessions WHERE id = ?").get(id) as
        | { workspace_id: string | null }
        | null
      return row?.workspace_id ?? undefined
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
          publicUrl: exposedProxyLinksBaseUrl(),
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
      // For sessions that already have tmux_window_id, runtimeTargetIdOf short-circuits with
      // no tmux call. Legacy/unhealed sessions resolve by name once, then persist,
      // so the agent terminal no longer 404s on first attach.
      return (await runtimeTargetIdOf(s)) ?? undefined
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
      settings.setAppConfig(patch)
      const next = settings.getAppConfig(appConfigEnv)
      // Explicit PUT is authoritative — overwrite so a corrected/rotated key
      // takes effect for new spawns. Already-running sessions keep their old
      // env until respawn (acceptable for v1).
      applyCredentialEnv(next, process.env)
      if ("claudeOauthToken" in patch || "anthropicApiKey" in patch) {
        void refreshAgentModels(AgentKind.Claude)
      }
      return next
    },
    getAgentStatuses: () => {
      const c = settings.getAppConfig(appConfigEnv)
      const hasCredential = (kind: AgentKind) =>
        kind === "claude" ? !!(c.claudeOauthToken || c.anthropicApiKey) || claudeCliIsAuthenticated()
        : kind === "codex" ? !!c.codexApiKey
        : kind === "cursor" ? !!c.cursorApiKey
        : false
      return detectAllAgents(
        { hasBinary, fileExists: existsSync, hasCredential },
        {
          home: homedir(), xdgConfigHome: process.env.XDG_CONFIG_HOME, xdgDataHome: process.env.XDG_DATA_HOME,
          appData: process.env.APPDATA, localAppData: process.env.LOCALAPPDATA, platform: process.platform,
        },
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
    // Voice: pluggable STT engine (when audio) → optional agent cleanup → composer-ready text.
    // STT engines live under src/core/transcription/ (see stt.ts). Closes over `agentRpc`
    // at request time (same pattern as the login closures below).
    transcribe: async (sessionId, input) => {
      // The session id is OPTIONAL (id-less /transcribe is used by the pre-spawn launcher). When
      // present it enriches the cleanup with prior messages + the session's agent skills; when
      // absent the cleanup still runs off the draft + global glossary/engine/model.
      const s = sessionId ? registry.get(sessionId) : undefined
      const cfg = settings.getAppConfig(appConfigEnv)
      let draft = input.draft ?? ""
      let sttMs = 0
      let sttEngine = "client"
      let prefersCleanup = true
      if (input.audioPath) {
        const t0 = Date.now()
        const r = await runStt(input.audioPath, {
          engine: cfg.voiceSttEngine ?? VOICE_STT_ENGINE,
          // whisper-specific knobs stay on app-config until engines grow their own model fields
          model: cfg.whisperModel,
          lang: cfg.whisperLang,
          // claude-voice biases recognition with the voice glossary (x-config-keyterms)
          keyterms: cfg.voiceCleanupGlossary,
        })
        sttMs = Date.now() - t0
        draft = r.text
        sttEngine = r.fellBack ? `${r.engine}(fallback)` : r.engine
        prefersCleanup = r.prefersCleanup
      }
      if (!draft.trim()) { log.info("voice_transcribe_empty", { sessionId: sessionId ?? null, sttEngine, sttMs }); return { text: "" } }
      const skills = s ? commandRegistry.get(s.name).filter((c) => c.family === "agent").map((c) => c.name) : []
      const messages = sessionId ? messageLog.get(s?.id ?? sessionId, 10) : []
      const payload = buildVoicePayload(draft, messages, skills)
      // Client-side STT drafts (no audioPath) always go through cleanup; engine-produced
      // drafts honor prefersCleanup (whisper: true; codex-realtime / claude-voice / cursor-stt: false).
      if (!prefersCleanup) {
        log.info("voice_transcribe_out", { sessionId: sessionId ?? null, draft, text: draft, sttMs, cleanupMs: 0, sttEngine, cleanupEngine: "skipped" })
        return { text: draft }
      }
      log.info("voice_transcribe_in", { sessionId: sessionId ?? null, sttEngine, draft, sttMs, ctxMsgs: messages.length, skills, model: cfg.voiceCleanupModel ?? VOICE_CLEANUP_MODEL })
      try {
        const t1 = Date.now()
        const out = await cleanupDraft(
          { draft, recentMessages: payload.context.recentMessages, skills, glossary: cfg.voiceCleanupGlossary ?? [] },
          { engine: cfg.voiceCleanupEngine, model: cfg.voiceCleanupModel },
        )
        const cleanupMs = Date.now() - t1
        const text = out.text || draft
        log.info("voice_transcribe_out", { sessionId: sessionId ?? null, draft, text, sttMs, cleanupMs, sttEngine, cleanupEngine: out.engine, model: cfg.voiceCleanupModel ?? VOICE_CLEANUP_MODEL })
        return { text }
      } catch (e) {
        log.warn("voice_cleanup_failed", { sessionId: sessionId ?? null, draft, sttMs, sttEngine, err: String(e) })
        return { text: draft, degraded: true }
      }
    },
    // Read-aloud: server-side TTS stream (codex). `platform` is client-native.
    // Yields audio chunks as soon as each is synthesized (one-ahead pipeline).
    speak: async (input) => {
      const cfg = settings.getAppConfig(appConfigEnv)
      const engine = input.engine ?? cfg.voiceTtsEngine ?? VOICE_TTS_ENGINE
      if (engine === "platform") {
        return { error: "platform", status: 400 as const }
      }
      const t0 = Date.now()
      // Validate engine/auth before opening the stream body (so clients get 502 JSON
      // instead of a half-open NDJSON error mid-stream when possible).
      try {
        const stream = runTtsStream(input.text, { engine, lang: input.lang })
        // Peek first chunk so auth/empty failures surface as HTTP error responses.
        const iter = stream[Symbol.asyncIterator]()
        const first = await iter.next()
        if (first.done) throw new Error("tts: empty stream")
        // Bind the narrowed chunk to a const: inside rest() the `first.done` check
        // above no longer narrows (closure boundary), so `first.value` would widen
        // back to `void | TtsStreamChunk` and the generator's element type with it.
        const firstChunk = first.value
        log.info("voice_speak_stream_start", {
          engine: firstChunk.engine,
          chars: input.text.length,
          total: firstChunk.total,
          firstBytes: firstChunk.audio.byteLength,
          ms: Date.now() - t0,
        })
        async function* rest(): AsyncGenerator<typeof firstChunk, void, unknown> {
          yield firstChunk
          let n = 1
          try {
            while (true) {
              const nres = await iter.next()
              if (nres.done) break
              n++
              yield nres.value
            }
            log.info("voice_speak_stream_done", {
              engine: firstChunk.engine,
              chunks: n,
              ms: Date.now() - t0,
            })
          } catch (e) {
            log.warn("voice_speak_stream_failed", { engine, err: String(e), after: n, ms: Date.now() - t0 })
            throw e
          }
        }
        return {
          engine: firstChunk.engine,
          chunks: rest(),
        }
      } catch (e) {
        log.warn("voice_speak_failed", { engine, err: String(e), ms: Date.now() - t0 })
        throw e
      }
    },
  })
  // loginManager constructed AFTER webChannel so its onChange can reference webChannel.
  // The startAgentLogin/getAgentLogin/cancelAgentLogin closures above close over `loginManager`
  // (the `let` binding) — they run at request time, by which point loginManager is assigned.
  loginManager = new LoginManager({
    paths: {
      home: homedir(), xdgConfigHome: process.env.XDG_CONFIG_HOME, xdgDataHome: process.env.XDG_DATA_HOME,
      appData: process.env.APPDATA, localAppData: process.env.LOCALAPPDATA, platform: process.platform,
    },
    fileExists: existsSync,
    hasCredential: (kind) => kind === AgentKind.Claude && claudeCliIsAuthenticated(),
    spawnLogin: spawnLoginProc,
    onChange: (kind, st) => {
      webChannel?.broadcastToAll({ type: "agent_login_state", kind, state: st })
      if (st.phase === "success") void refreshAgentModels(kind)
    },
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


/** Keep FileStore ref_counts in sync with draft_payload.attachments so hourly GC
 *  doesn't reap files that are still staged on a draft. Mirrors MessageStore's
 *  bump/release on message append/remove. */
function bumpDraftAttachmentRefs(payload: { attachments?: Array<{ file_id?: string }> } | null | undefined) {
  const atts = payload?.attachments
  if (!atts?.length) return
  for (const a of atts) {
    if (!a?.file_id) continue
    fileStore.bumpRef(a.file_id).catch((err) =>
      log.error("draft_bumpref_failed", { file_id: a.file_id, err: err?.message ?? String(err) }),
    )
  }
}
function releaseDraftAttachmentRefs(payload: { attachments?: Array<{ file_id?: string }> } | null | undefined) {
  const atts = payload?.attachments
  if (!atts?.length) return
  for (const a of atts) {
    if (!a?.file_id) continue
    fileStore.release(a.file_id).catch((err) =>
      log.error("draft_release_failed", { file_id: a.file_id, err: err?.message ?? String(err) }),
    )
  }
}

const killSession = (id: string) => sessionManager.kill(id)


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

const resumeSuspendedSession = (session: Parameters<SessionManager["resumeSuspended"]>[0]) => sessionManager.resumeSuspended(session)

const resumeFromArchive = (sessionId: string) => sessionManager.resumeFromArchive(sessionId)

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
    onRegister: (m) => sessionManager.handleRegister(m),
    onOutbound: (m) => sessionManager.handleOutbound(m),
    onOrchestration: (m) => sessionManager.handleOrchestration(m),
  },
})

const recentInboundIds = new RecentInboundIds()
const pendingReapply = new PendingReapply()
function deliverInbound(sessionId: string, text: string, meta: any): Promise<InboundDeliveryResult> {
  return deliverInboundCore({
    getAdapter: (id) => runtimes.get(id)?.adapter,
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

async function spawnSession(args: {
  workdir: string
  requestedName?: string
  agent?: AgentKind
  model?: string
  reasoningLevel?: string
  worktree?: boolean
  baseBranch?: string
  /** When set (e.g. "continue in new conversation"), reuse that session's display-name base and worktree metadata instead of deriving a name from the workdir basename (often a uuid under ~/.mux/worktrees). */
  inheritFromSessionId?: string
  internal?: boolean
  rpcMcpConfig?: string
}) {
  const agent = args.agent ?? AgentKind.Claude
  const inheritSrc = args.inheritFromSessionId
    ? registry.sessions.getById(args.inheritFromSessionId)
    : undefined
  const workdir = normalizeExistingWorkdir(args.workdir)
  // Prefer an explicit name, then the inherited session's name, then (for new worktrees) the project basename.
  const requestedName = args.requestedName?.trim() || inheritSrc?.name || undefined
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
          sessionName: requestedName || deriveName(workdir),
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
      tmuxSession: TMUX_SESSION,
      resolveAttachment: resolveAttachmentPath,
      registerAdapter: (name, adapter, handle) => sessionManager.registerSpawnedAdapter(name, adapter, handle),
      onThreadId: (name: string, threadId: string) => {
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, threadId)
      },
      onCursorSessionId: (name: string, sessionId: string) => {
        const session = registry.resolveName(name)
        if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
      },
      onGrokSessionId: (name: string, sessionId: string) => {
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
    },
    // Worktree-backed: derive the session name from the ORIGINAL repo, not the
    // worktree dir (whose basename is a uuid) — otherwise the session is named after the uuid.
    { workdir: effectiveWorkdir, requestedName: requestedName ?? (wt ? deriveName(workdir) : undefined), agent: args.agent, model: args.model, reasoningLevel: args.reasoningLevel, effort, internal: args.internal, rpcMcpConfig: args.rpcMcpConfig },
  )
  // Claude's row now exists synchronously (born in the spawn path). Wait for
  // the shim to CONNECT — proof the window survived and the agent came up —
  // while polling window liveness so an instant death fast-fails instead of
  // waiting out the full timeout.
  let registered = registry.get(r.session_id)
  if ((args.agent ?? "claude") === "claude") {
    registered = await waitForRegisteredSession({
      id: r.session_id,
      name: r.name,
      lookup: (id) => {
        const s = registry.get(id)
        return s?.connected ? s : undefined
      },
      stillAlive: async () => {
        const wid = registry.get(r.session_id)?.tmux_window_id
        return wid ? (await sessionBackend.livePid(wid)) !== null : false
      },
    }).catch((err) => {
      log.warn("spawn_post_check_failed", { name: r.name, workdir })
      throw err
    })
    // onRegister no longer broadcasts (it only attaches) — announce the born
    // session from the spawn path. Duplicate session_added frames are safe:
    // clients upsert by id (see web sessions.add).
    const bornRow = registry.get(r.session_id)
    if (bornRow && !bornRow.internal) {
      webChannel?.broadcastToAll({
        type: "session_added",
        session: { id: bornRow.id, name: bornRow.name, workdir: bornRow.workdir, mute: false, connected: bornRow.connected, agent: bornRow.agent, user_status: bornRow.user_status, sort_order: bornRow.sort_order, draft_payload: bornRow.draft_payload },
      })
      await refreshTelegramMenu()
    }
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
  } else if (registry.get(r.session_id)) {
    // Same-workdir continue (worktree: false): copy project/worktree metadata so
    // the new session groups under the real project and Finish stays available.
    // Prefer explicit inheritFrom, else any peer already bound to this workdir.
    const peer =
      (inheritSrc?.repo_root && inheritSrc.session_branch
        ? inheritSrc
        : undefined)
      ?? [...registry.list(), ...registry.listArchived()].find(
        (s) => s.id !== r.session_id && s.workdir === effectiveWorkdir && !!s.repo_root && !!s.session_branch,
      )
    if (peer?.repo_root && peer.session_branch) {
      registry.sessions.setWorktree(r.session_id, {
        repo_root: peer.repo_root,
        base_branch: peer.base_branch || "HEAD",
        session_branch: peer.session_branch,
      })
    }
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
    const wid = await runtimeTargetIdOf(session)
    if (!wid) return { ok: false, error: "session window not found" }
    const result = await applyClaudeLiveSwitch(wid, {
      model: changed?.model === false ? undefined : session.model,
      effort: changed?.effort === false ? undefined : effort,
    }, { backend: sessionBackend })
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
        userCodexHome: join(home(), ".codex"),
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

  if (session.agent === AgentKind.Grok) {
    try {
      const runtime = runtimes.get(session.id)
      if (runtime?.kind !== AgentKind.Grok) return { ok: false, error: "grok session has no live adapter" }
      // Model applies live over ACP (session/set_model); effort is a spawn flag
      // with no ACP setter, so setEffort() relaunches the stdio child and reloads
      // the same grok session id — history is preserved across the respawn.
      if (changed?.model !== false && session.model) runtime.adapter.model = session.model
      if (changed?.effort !== false) await runtime.adapter.setEffort(effort)
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

  // cursor, opencode and grok read their adapter's `model` field fresh on each
  // turn (opencode re-parses it in send() via parseModel(); grok folds it into
  // each session/prompt), so a model switch is a live in-process field update —
  // no process/serve restart, no config reapply.
  if (session.agent === "cursor" || session.agent === "opencode" || session.agent === AgentKind.Grok) {
    const adapter = runtimes.get(session.id)?.adapter as any
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
        // The row exists when spawnSession resolves (all agents) — flip the
        // chat's active session directly.
        registry.setActive(msg.chat_id, r.session_id)
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
      proxyPublicUrl: exposedProxyLinksBaseUrl(),
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
          tmuxSession: TMUX_SESSION,
          resolveEffort: (s) => sessionEffort(s),
          registerAdapter: (name, adapter, handle) => sessionManager.registerSpawnedAdapter(name, adapter, handle),
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
          onGrokSessionId: (name, sessionId) => {
            const session = registry.resolveName(name)
            if (session) registry.sessions.setAgentSessionId(session.id, sessionId)
          },
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
              user_status: entry.user_status,
              sort_order: entry.sort_order,
              draft_payload: entry.draft_payload,
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
    const text = decision.reason === "no_active_session"
      ? "No active session. Create one in the app, or use /spawn <workdir> [as name]."
      : `routing error: ${decision.reason}`
    await ch.send({ op: "reply", chat_id: msg.chat_id, text, disable_notification: false })
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
    const msgText = err?.message ?? String(err)
    log.error("send_inbound.error", { session: session.name, err: msgText })
    // Safety net: adapters should emit `error` themselves, but if send() throws
    // without that (or the event is missed), still surface the real reason so the
    // user isn't left on a silent stuck/idle session (seen with grok handoff).
    void notifyAgentError(session.id, session.name, "error", msgText)
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
    // First message to a draft: spawn its agent, then deliver to the now-live
    // session. spawnSession/spawnClaudeSession always mint a NEW row (for claude
    // the row is created async by the shim's onRegister with a fresh uuid), so
    // there is no way to adopt the draft's id here. Instead we HARD-DELETE the
    // vestigial draft row FIRST — that frees its display name in the registry so
    // the spawned agent claims the SAME name (otherwise ensureUnique would
    // uniquify it to "<name>-2") and leaves no archived "settled" ghost — then
    // spawn a fresh in_progress session and route the message to it. On spawn
    // failure the draft is restored verbatim (same id) so nothing is lost. The
    // spawn's own onRegister emits session_added for the new live row; we only
    // add the draft's session_removed here (and session_added on the restore
    // path). Net result: exactly one session, no orphan/ghost draft.
    let inbound = msg
    if (targetSession && targetSession.user_status === "draft") {
      const draftSnapshot = {
        id: targetSession.id,
        name: targetSession.name,
        agent: targetSession.agent,
        workdir: targetSession.workdir,
        model: targetSession.model,
        reasoningLevel: targetSession.reasoningLevel,
        pid: 0,
        user_status: "draft" as const,
        sort_order: targetSession.sort_order,
        draft_payload: targetSession.draft_payload,
      }
      releaseDraftAttachmentRefs(draftSnapshot.draft_payload)
      registry.sessions.deleteById(targetSession.id)   // frees the name, no ghost
      webChannel?.broadcastToAll({ type: "session_removed", id: draftSnapshot.id })
      let started: ReturnType<typeof registry.resolveName>
      try {
        const spawned = await spawnSession({
          workdir: draftSnapshot.workdir,
          requestedName: draftSnapshot.name,
          agent: draftSnapshot.agent,
          model: draftSnapshot.model,
          reasoningLevel: draftSnapshot.reasoningLevel,
        })
        started = registry.resolveName(spawned.name)
      } catch (err: any) {
        log.error("draft_spawn_failed", { id: draftSnapshot.id, name: draftSnapshot.name, err: err?.message ?? String(err) })
      }
      if (!started) {
        // Restore the draft (same id, still a draft) and re-add it client-side.
        registry.sessions.register(draftSnapshot)
        bumpDraftAttachmentRefs(draftSnapshot.draft_payload)
        webChannel?.broadcastToAll({
          type: "session_added",
          session: {
            id: draftSnapshot.id,
            name: draftSnapshot.name,
            workdir: draftSnapshot.workdir,
            mute: false,
            connected: false,
            agent: draftSnapshot.agent,
            model: draftSnapshot.model,
            reasoningLevel: draftSnapshot.reasoningLevel,
            status: "active",
            user_status: "draft",
            sort_order: draftSnapshot.sort_order,
            draft_payload: draftSnapshot.draft_payload,
          },
        })
        webChannel!.send({ op: "reply", chat_id: msg.chat_id, text: `Failed to start session "${draftSnapshot.name}".` })
        return
      }
      inbound = { ...msg, target_session_id: started.id }
    }
    handleWebInbound(inbound, {
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
          const msgText = err?.message ?? String(err)
          log.error("web_inbound_adapter_failed", { sid, err: msgText })
          // Safety net for agents that throw from send() without emitting `error`
          // (or when that event is missed) — show the real message to the user.
          void notifyAgentError(sessionId, sessionEntry?.name ?? sid, "error", msgText)
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
  paWorkdir: appConfig.paWorkdir || undefined,
  resolveEffort: (s) => sessionEffort(s),
  // Adapter registration + session-id persistence for supervisor-spawned
  // non-claude PAs derive from the component — the half-filled-bag bug
  // (adapters built then dropped) is structurally closed.
  sessionManager,
  reapInternalWorkers: () => agentRpc.reapIdle(RPC_WORKER_IDLE_MS),
})
// Existing installs (any prior sessions, active/suspended/archived) are implicitly
// onboarded and skip the wizard. Only a pristine instance
// stays un-onboarded. Uses SESSIONS, not devices: a fresh install mid-onboarding has
// a claimed device but no sessions yet, so it must not get seeded.
if (!settings.getAppConfig(appConfigEnv).onboarded &&
    (registry.list().length > 0 || registry.listArchived().length > 0)) {
  settings.setAppConfig({ onboarded: true })
  log.info("onboarded_seeded_existing_install", {})
}
await reconcileOnStartup({ registry, bindSocket: (sid) => server.bind(sid), supervisor, sessionBackend })


// Regenerate Codex's marketplace.json from the registry BEFORE resuming codex
// sessions — resumeNonClaudeAdapters runs `codex plugin add` per session home,
// which reads this marketplace; a stale/missing one (e.g. right after a rename)
// makes those adds fail until the next boot. Awaited so the file is current
// first. Never throws — logs and continues so plugin config can't block boot.
if (!IS_TEST_BROKER) {
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
    if (ensureGrokPluginScopes()) {
      log.info("grok_plugin_scopes_synced")
    }
  } catch (err: any) {
    log.warn("mux_core_soul_skill_sync_failed", { err: err?.message ?? String(err) })
  }
  await codexPrepareGlobal({ onError: (err) => log.warn("codex_prepare_global_failed", { err }) })
    .catch((err) => log.warn("codex_prepare_global_failed", { err: String(err) }))
}

await sessionManager.resumeAtBoot()
// Housekeeping at boot is intentionally NON-DESTRUCTIVE: collapse every cursor
// home's runtime to a symlink at the shared copy (safe, idempotent) and only
// LOG any orphan-looking homes. Actual deletion lives solely in the explicit
// scripts/reclaim-agent-homes.ts, which reads every session row from the DB.
// `knownHomes` MUST union active+archived — archived sessions are resumable
// (resumeFromArchive) but absent from registry.list()'s in-memory cache.
if (!IS_TEST_BROKER) {
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
if (!IS_TEST_BROKER) {
  refreshModels().catch((err) => log.warn("model_cache_init_failed", { err: String(err) }))
}
const modelRefreshInterval = IS_TEST_BROKER ? undefined : setInterval(() => {
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
        // Agent/model/effort come from curator settings (Settings → Curator),
        // same knobs as session launch / PA create. Read live so a Save mid-day
        // applies to the next run without restarting the broker.
        const c = settings.getCurator()
        const r = await spawnSession({
          workdir,
          requestedName: name,
          agent: c.agent,
          model: c.model,
          reasoningLevel: c.reasoningLevel,
        })
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
    if (modelRefreshInterval) clearInterval(modelRefreshInterval)
  } catch (err: any) { log.warn("gc_interval_clear_failed", { err: err?.message ?? String(err) }) }
  try {
    db.close()
  } catch (err: any) { log.warn("db_close_failed", { err: err?.message ?? String(err) }) }
  process.exit(0)
}
process.on("SIGTERM", () => gracefulShutdown("SIGTERM"))
process.on("SIGINT",  () => gracefulShutdown("SIGINT"))
