import type { Channel, ChannelCapabilities, InboundAttachment, InboundMessage, OutboundAction, OutboundResult } from "../channel"
import { DeviceStore } from "./device-store"
import { watchRowExtras } from "./watch-session-row"
import { serveStatic } from "./static-serve"
import { makeLogger } from "../../shared/log"
import { home } from "../../shared/home"
import { existsSync, writeFileSync, mkdirSync } from "fs"
import { join, sep } from "path"
import { kindFromMime, type AttachmentKind } from "../../core/files/kinds"
import { PayloadTooLargeError, EmptyUploadError, OffsetConflictError, UploadOverflowError, UploadNotFoundError } from "../../core/files/store"
import { extractSubdomain, handleProxyRequest, matchProxyPath, parseCookie } from "./proxy"
import { authToken, authedViaBearer, buildAuthCookie, buildClearCookie, sameOriginOk } from "./cookies"
import { FsService } from "../../core/editor/fs-service"
import { computeWorkdirDiff, listRepoRefs } from "../../core/editor/workdir-diff"
import { reanchor } from "../../core/review/anchor"
import { FsWatcher } from "../../core/editor/fs-watcher"
import { LspConnection } from "../../core/lsp/bridge"
import { encodeTouch, encodeKey, encodeText, TouchAction } from "../../core/display/scrcpy/control"
import { redactAppConfig } from "../../core/settings/app-config"
import { pairJsonResponse } from "./pair-json"
import { buildHostBody } from "./host-route"
import { normalizeExistingWorkdir, uniqueKnownWorkdirs } from "../../core/session-manager/workdir-paths"
import { worktreesRoot } from "../../core/worktree/manager"
import { hooksFileUsesHookSecret } from "../../core/agents/claude/hooks-settings"
import { getRepoInfo } from "../../core/git/repo-info"
import { remoteStatus, fetchRemote, publishBranch, pushBranch, pullBranch } from "../../core/git/remote"
import { listBranches, switchBranch } from "../../core/git/branches"
import type { AgentKind } from "../../core/agents/types"
import { AGENT_KINDS, isAgentKind } from "../../shared/agents"
import type { SlashCommand } from "../../core/slash-commands/types"
import type { UpdateChecker } from "../../core/update/checker"
import { detectUpdateMode } from "../../core/update/mode"
import { resolveAndApply, restartService } from "../../core/update/apply"
import { BUILD_COMMIT, BUILD_VERSION } from "../../shared/build-info"

const VALID_KINDS: AttachmentKind[] = ["photo", "document", "voice", "audio", "video", "video_note"]

// Browser KeyboardEvent.key -> Android keycode (subset relevant for control).
const SCRCPY_KEYCODES: Record<string, number> = {
  Enter: 66,
  Backspace: 67,
  Tab: 61,
  ArrowUp: 19,
  ArrowDown: 20,
  ArrowLeft: 21,
  ArrowRight: 22,
  Escape: 111,
}

const log = makeLogger("channels/web")

const RATE_LIMIT_WINDOW_MS = 5 * 60_000
const RATE_LIMIT_MAX = 16

// Only a proxy we actually sit behind may name the peer for us. frpc (relay) and the nginx
// exposure recipe both forward from loopback and set X-Forwarded-For; anyone else reaching
// the port directly is quoting a header they invented. Trusting it from every caller let a
// brute-forcer mint a fresh rate-limit bucket per guess just by varying one header.
//
// Loopback is the default trust set because that is where frpc/nginx forward from. It does
// mean a LOCAL process can still spoof the header — which is acceptable: anything running
// as this user can simply read the device token out of the state dir, so the limiter was
// never a boundary against it. The boundary that matters is remote traffic, which arrives
// either directly (peer IP, header ignored) or via the proxy (bucketed per real client).
export const DEFAULT_TRUSTED_PROXY_PEERS = ["127.0.0.1", "::1", "::ffff:127.0.0.1"]

// Resolved once per request at the single entry point, where Bun's `server` — and so the
// real socket peer — is in scope. Without a peer fallback every direct client (the native
// macOS app included) shared one "unknown" bucket, so any one client's auth failures
// throttled every other client on the host.
const rateLimitBucket = new WeakMap<Request, string>()

function clientIp(req: Request): string {
  return rateLimitBucket.get(req) ?? "unknown"
}

// Auth-failure rate-limit state is now per-WebChannel-instance (see the
// `authFailures` field on the class), so there is no process-global bucket to
// clear. Kept as an exported no-op for tests that build a fresh channel per
// case — each new instance already starts with an empty bucket.
export function __resetAuthFailures(): void {}

const API_PREFIXES = ["/api", "/sessions", "/archived-sessions", "/projects", "/paths", "/commands", "/devices", "/pair.json", "/pair", "/me", "/logout", "/ws", "/files", "/upload", "/push", "/usage", "/proxies", "/fs", "/displays", "/settings", "/config", "/agents", "/opencode", "/client-logs", "/debug", "/models", "/reasoning-levels", "/system", "/repos", "/forge", "/host"]
const MAX_CLIENT_LOG_RING = 800

export type StoredClientLogEntry = {
  ts: number
  category: string
  event: string
  data?: Record<string, unknown>
  device: string
  serverTs: number
  meta?: Record<string, unknown>
}
const MUTATING_METHODS = new Set(["POST", "PUT", "DELETE", "PATCH"])

// Pause feeding a terminal viewer's output once this many bytes are queued on
// its socket; resume on the websocket `drain`. Bounds the broker's per-viewer
// send-buffer memory and how far a slow client falls behind. NB: getBufferedAmount
// reports POST-compression (permessage-deflate) wire bytes — terminal streams
// deflate ~5-10x, so 256KB here is several MB of logical redraws: a generous bound
// that won't trip on burst repaints. Tune via the perf measurement.
const TERMINAL_BP_HIGH_WATER = 256 * 1024

function makeDeferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void
  const promise = new Promise<void>((r) => { resolve = r })
  return { promise, resolve }
}

type WSData = { deviceName: string; openedAt: number; lastPongAt?: number; terminal?: true; terminalKind?: "scratch" | "agent"; terminalSession?: string; terminalId?: string; terminalAgentTarget?: string; display?: true; scrcpy?: true; displayStreamId?: string; _termDrain?: { promise: Promise<void>; resolve: () => void } }

export interface SessionSnapshot {
  id?: string
  name: string
  workdir: string
  mute: boolean
  connected: boolean
  agent: AgentKind
  role?: "personal_assistant" | "worker"
  isDefault?: boolean
  model?: string
  session_branch?: string
  repo_root?: string
  git?: import("../../core/worktree/lite-status").GitLiteStatus
  finish_job?: import("../../core/worktree/finish-job").FinishJob
}

export interface ArchivedSessionSnapshot {
  id: string
  name: string
  workdir: string
  agent: AgentKind
  model?: string
  killed_at?: string
  repo_root?: string
}

export interface WebChannelOpts {
  port: number
  devicesFile: string
  /** Peers allowed to declare the real client via X-Forwarded-For / CF-Connecting-IP.
   *  Defaults to loopback (where frpc/nginx forward from). See DEFAULT_TRUSTED_PROXY_PEERS. */
  trustedProxyPeers?: string[]
  publicUrl: string
  staticDir?: string
  staticEmbedded?: Record<string, string>
  getSessionsSnapshot: () => SessionSnapshot[]
  getSessionLog: (name: string) => unknown[]
  getSessionActivity?: (name: string) => unknown[]
  getSessionBgTasks?: (name: string) => unknown[]
  setMute: (name: string, muted: boolean) => void
  onSendFromWeb: (msg: InboundMessage) => void
  fileStore?: import("../../core/files/store").FileStore
  pushStore?: import("../../core/push/subscriptions").PushSubscriptionStore
  deviceTokenStore?: import("../../core/push/device-tokens").DevicePushTokenStore
  vapidPublicKey?: string
  viewingTracker?: import("../../core/push/viewing-tracker").ViewingTracker
  getReads?: () => Record<string, string>          // sessionId -> last_read_at (snapshot)
  getDrafts?: () => Record<string, string>         // sessionId -> draft text (snapshot)
  setDraft?: (sessionId: string, text: string | null) => void
  markRead?: (sessionId: string) => void           // advance read + broadcast session_read
  getModels?: (agent: AgentKind) => { id: string; displayName: string }[]
  refreshModels?: (agent: AgentKind) => Promise<void>
  switchModel?: (sessionName: string, model: string, applyNow?: boolean) => Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }>
  getSessionReasoningLevels?: (id: string) => { agent: string; current?: string; levels: { id: string; description?: string }[]; visible: boolean } | undefined
  // Session-less reasoning levels for the New Session launcher (no session id yet):
  // resolves the levels an agent+model offers before spawn. Codex's are per-model.
  getReasoningLevels?: (agent: AgentKind, model?: string) => { agent: string; levels: { id: string; description?: string }[]; visible: boolean }
  switchReasoningLevel?: (id: string, level: string, applyNow?: boolean) => Promise<{ ok: true; status: "applied" | "queued" } | { ok: false; error: string }>
  getSessionAgent?: (name: string) => { agent: AgentKind; model?: string; reasoningLevel?: string } | undefined
  interruptSession?: (id: string) => Promise<{ ok: boolean; reason?: string }>
  finishSession?: (id: string, req: { action: "merge"|"pr"|"keep"|"discard"; skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string; draft?: boolean; prRequiresGreen?: boolean; prTitle?: string; prBody?: string }) => Promise<import("../../core/worktree/finish-job").FinishJob | { error: string }>
  finishReadiness?: (id: string) => import("../../core/worktree/readiness").FinishReadiness | { error: string }
  reviewList?: (id: string) => import("../../core/review/store").Comment[]
  reviewAdd?: (id: string, c: Omit<import("../../core/review/store").NewComment, "sessionId">) => import("../../core/review/store").Comment
  reviewUpdate?: (commentId: string, patch: { status?: "open" | "submitted" | "resolved"; body?: string; resolvedBy?: string }) => void
  reviewDelete?: (commentId: string) => void
  reviewSubmit?: (id: string) => Promise<{ ok: boolean; delivered: number; reason?: string }>
  sendUserMessage?: (id: string, text: string) => Promise<{ ok: boolean; reason?: string }>
  reviewSession?: (id: string) => { workdir: string; repoRoot?: string; baseCommits?: Record<string, string> } | undefined
  verifySuggest?: (id: string) => { content: string; source: string } | undefined
  verifySave?: (id: string, content: string) => { ok: boolean; reason?: string }
  spawnSession?: (args: { name?: string; workdir: string; agent?: AgentKind; model?: string; reasoningLevel?: string; worktree?: boolean; baseBranch?: string; inheritFrom?: string }) => Promise<{ id?: string; name: string; workdir: string; agent: AgentKind; model?: string; reasoningLevel?: string; repo_root?: string; session_branch?: string }>
  createDraft?: (args: { name?: string; workdir: string; agent?: AgentKind; model?: string; reasoningLevel?: string; draftPayload?: { text?: string; attachments?: unknown[] } }) => Promise<{ id: string; name: string; workdir: string; agent: AgentKind }>
  killSession?: (name: string) => Promise<void>
  renameSession?: (oldName: string, newName: string) => Promise<void>
  reorderSessions?: (orderedIds: string[]) => void
  transcribe?: (sessionId: string | undefined, input: { draft?: string; audioPath?: string }) => Promise<{ text: string; degraded?: boolean }>
  /**
   * Server-side TTS (codex). Returns either a soft platform error, or an async
   * iterable of audio chunks (streamed NDJSON: play first while later synth).
   */
  speak?: (input: { text: string; engine?: string; lang?: string }) => Promise<
    | { error: string; status: 400 }
    | {
        engine: string
        chunks: AsyncIterable<{
          audio: Uint8Array
          mime: string
          index: number
          total: number
        }>
      }
  >
  spawnPA?: (args: { name: string; workdir: string; agent?: AgentKind; model?: string; reasoningLevel?: string }) => Promise<{ id?: string; name: string; workdir: string; agent: AgentKind; model?: string; reasoningLevel?: string }>
  listPAs?: () => SessionSnapshot[]
  updatePA?: (name: string, patch: { model?: string; reasoningLevel?: string }) => Promise<{ ok: boolean; error?: string }>
  proxyBaseDomain?: string
  proxyMainHost?: string
  proxyLookup?: (domain: string) => { port: number; sessionName: string; isPublic: boolean } | undefined
  proxyAuth?: (token: string) => boolean
  internalSecret?: string
  getCuratorSettings?: () => { config: unknown; nextRun: string | null }
  setCuratorSettings?: (cfg: unknown) => { config: unknown; nextRun: string | null }
  runCuratorNow?: () => Promise<void>
  getEditorConfig?: () => import("../../core/settings/editor-config").EditorConfig
  getEditorSettings?: () => { lsp: { servers: import("../../core/lsp/editor-settings").LspServerSettingsRow[] } }
  setEditorSettings?: (patch: unknown) => { lsp: { servers: import("../../core/lsp/editor-settings").LspServerSettingsRow[] } }
  installEditorLspServer?: (serverId: string) => Promise<{ ok: boolean; lines: string[] }>
  addCustomEditorLspServer?: (body: unknown) => { ok: boolean; error?: string; lsp?: { servers: import("../../core/lsp/editor-settings").LspServerSettingsRow[] } }
  removeCustomEditorLspServer?: (serverId: string) => { ok: boolean; error?: string; lsp?: { servers: import("../../core/lsp/editor-settings").LspServerSettingsRow[] } }
  listChatIds?: () => string[]
  createProxy?: (args: { sessionName: string; port: number; domain?: string }) => { url: string; domain: string; port: number }
  removeProxy?: (domain: string) => void
  listProxies?: () => { domain: string; sessionName: string; port: number; createdAt: string; isPublic: boolean; url: string }[]
  updateProxy?: (domain: string, isPublic: boolean) => { domain: string; sessionName: string; port: number; createdAt: string; isPublic: boolean }
  terminalManager?: import("../../core/terminal/manager").TerminalManager
  fsWatcher?: FsWatcher
  getSessionWorkdir?: (name: string) => string | undefined
  /** Resolve a claude session's tmux "session:window" target (for kind=agent
   * terminals). Returns undefined for non-claude/unknown sessions. When this opt
   * is absent, all kind=agent requests return 404 (the feature is opt-in). */
  getSessionTmuxTarget?: (name: string) => Promise<string | undefined>
  getSessionBaseCommits?: (name: string) => Record<string, string> | undefined
  getSessionCreatedAt?: (name: string) => string | undefined
  listArchivedSessions?: () => ArchivedSessionSnapshot[]
  resumeFromArchive?: (id: string) => Promise<{ ok: boolean; name?: string; error?: string }>
  getDisplayPort?: (id: string) => number | undefined
  getScrcpy?: (id: string) => import("../../core/display/scrcpy/backend").ScrcpyInstance | undefined
  listDisplays?: () => import("../../core/display/types").DisplayStreamInfo[]
  startDisplay?: (args: { sessionName: string; provider?: string; device?: string; width?: number; height?: number }) => Promise<import("../../core/display/types").DisplayStreamInfo>
  stopDisplay?: (id: string) => Promise<void>
  onAgentHook?: (event: string, body: any) => void
  getSessionAgentState?: (name: string) => unknown
  getSessionCommands?: (name: string) => unknown[]
  getSessionCommandsResolved?: (name: string) => boolean
  previewAgentCommands?: (args: { agent: AgentKind; workdir: string }) => Promise<{ commands: SlashCommand[]; resolved: boolean }>
  getAppConfig?: () => import("../../core/settings/app-config").AppConfig
  setAppConfig?: (patch: Partial<import("../../core/settings/app-config").AppConfig>) => import("../../core/settings/app-config").AppConfig
  getAgentStatuses?: () => import("../../core/agents/detect").AgentStatus[]
  startAgentLogin?: (kind: string) => import("../../core/agents/login/session").LoginState
  getAgentLogin?: (kind: string) => import("../../core/agents/login/session").LoginState | undefined
  cancelAgentLogin?: (kind: string) => void
  sendAgentLoginCode?: (kind: string, code: string) => void
  startAgentInstall?: (kind: string) => { job: import("../../core/agents/install").InstallJob; alreadyRunning: boolean }
  getAgentInstall?: (kind: string) => import("../../core/agents/install").InstallJob | undefined
  listOpenCodeProviders?: () => Promise<import("../../core/agents/opencode/auth-ops").OpenCodeProviderInfo[]>
  setOpenCodeApiKey?: (providerId: string, key: string) => Promise<void>
  startOpenCodeOAuth?: (providerId: string, method: number) => Promise<{
    url: string
    instructions?: string
    method: "auto" | "code"
  }>
  finishOpenCodeOAuth?: (providerId: string, method: number, code: string) => Promise<void>
  getSoul?: () => Promise<string> | string
  setSoul?: (content: string) => Promise<void> | void
  getExposure?: () => { exposureMode: string; publicUrl: string; snippets: import("../../core/settings/exposure").ExposureSnippets }
  validateExposure?: () => Promise<{ reachable: boolean; status?: number; error?: string }>
  getForgeConnections?: () => import("../../core/forge/types").ForgeConnection[]
  getForgeCliStatus?: () => Promise<{ github: { available: boolean; login?: string }; gitlab: { available: boolean; login?: string } }> | { github: { available: boolean; login?: string }; gitlab: { available: boolean; login?: string } }
  addForgeConnection?: (o: { kind: string; host?: string; apiBase?: string; token: string; source: "pat" | "cli"; transport?: "https" | "ssh" }) => Promise<import("../../core/forge/types").ForgeConnection>
  importForgeCli?: (kind: string, transport?: "https" | "ssh") => Promise<import("../../core/forge/types").ForgeConnection>
  removeForgeConnection?: (id: string) => void
  searchForgeRepos?: (query: string) => Promise<{ repos: unknown[]; errors: unknown[] }>
  cloneForgeRepo?: (connectionId: string, owner: string, name: string) => Promise<{ localPath: string }>
  createForgeRepo?: (input: { connectionId: string; name: string; owner?: string; private: boolean }) => Promise<{ repo: unknown; localPath: string }>
  createLocalRepo?: (name: string) => Promise<{ localPath: string }>
  listClonedRepos?: () => unknown[]
  removeClonedRepo?: (path: string) => void
  pullClonedRepo?: (path: string) => unknown
  // null = update checks disabled (MUX_UPDATE_CHECK=0); undefined = same as null.
  updateChecker?: UpdateChecker | null
  relayUrl?: string
  getHostInfo?: () => import("./host-route").HostInfo
  claimStore?: import("./pair-claim").ClaimStore
  mintDeviceToken?: (name: string) => { token: string; name: string }
  getRelayUrl?: () => string | undefined
}

export class WebChannel implements Channel {
  readonly name = "web"
  readonly capabilities: ChannelCapabilities = {
    multiplexesSessions: false,
    supportsReactions: false,
    supportsEdit: false,
    supportsAttachments: true,
  }

  private server?: ReturnType<typeof Bun.serve>
  private heartbeatTimer?: NodeJS.Timeout
  private readonly store: DeviceStore
  private readonly fileStore?: import("../../core/files/store").FileStore
  private readonly pushStore?: import("../../core/push/subscriptions").PushSubscriptionStore
  private readonly deviceTokenStore?: import("../../core/push/device-tokens").DevicePushTokenStore
  private readonly vapidPublicKey?: string
  private readonly relayUrl?: string
  private readonly getHostInfo?: () => import("./host-route").HostInfo
  private readonly claimStore?: import("./pair-claim").ClaimStore
  private readonly mintDeviceToken?: (name: string) => { token: string; name: string }
  private readonly getRelayUrl?: () => string | undefined
  private inboundHandlers: Array<(m: InboundMessage) => void> = []
  private wsConnections = new Set<{ ws: import("bun").ServerWebSocket<WSData>; deviceName: string }>()
  private displaySockets = new WeakMap<object, import("bun").Socket>()
  private readonly fsWatcher?: FsWatcher
  private readonly clientLogRing: StoredClientLogEntry[] = []
  // Per-instance auth-failure rate-limit buckets, keyed by client IP. Instance
  // (not module) scope keeps concurrent channels — e.g. the many WebChannels a
  // single `bun test` process spins up, all seeing clientIp() === "unknown" —
  // from sharing one bucket and spuriously rate-limiting each other's requests.
  private readonly authFailures = new Map<string, { count: number; firstAt: number }>()

  constructor(private readonly opts: WebChannelOpts) {
    this.store = new DeviceStore(opts.devicesFile)
    this.fileStore = opts.fileStore
    this.pushStore = opts.pushStore
    this.deviceTokenStore = opts.deviceTokenStore
    this.vapidPublicKey = opts.vapidPublicKey
    this.relayUrl = opts.relayUrl
    this.getHostInfo = opts.getHostInfo
    this.claimStore = opts.claimStore
    this.mintDeviceToken = opts.mintDeviceToken
    this.getRelayUrl = opts.getRelayUrl
    this.fsWatcher = opts.fsWatcher
  }

  get boundPort(): number {
    return this.server?.port ?? this.opts.port
  }

  on(event: "inbound", handler: (m: InboundMessage) => void): void {
    if (event === "inbound") this.inboundHandlers.push(handler)
  }

  /** Bucket this request by its real origin: the proxy-declared client when we trust the
   *  peer to declare one, otherwise the socket peer itself. */
  private resolveRateLimitBucket(req: Request, server: import("bun").Server<WSData>): void {
    const peer = server.requestIP(req)?.address ?? ""
    const trusted = this.opts.trustedProxyPeers ?? DEFAULT_TRUSTED_PROXY_PEERS
    const forwarded = trusted.includes(peer)
      ? (req.headers.get("cf-connecting-ip") ?? req.headers.get("x-forwarded-for")?.split(",")[0]?.trim())
      : undefined
    rateLimitBucket.set(req, forwarded || peer || "unknown")
  }

  private checkRateLimit(req: Request): boolean {
    const ip = clientIp(req)
    const now = Date.now()
    const rec = this.authFailures.get(ip)
    if (!rec || now - rec.firstAt > RATE_LIMIT_WINDOW_MS) {
      this.authFailures.set(ip, { count: 0, firstAt: now })
      return true
    }
    return rec.count < RATE_LIMIT_MAX
  }

  private recordAuthFailure(req: Request): void {
    const ip = clientIp(req)
    const rec = this.authFailures.get(ip) ?? { count: 0, firstAt: Date.now() }
    rec.count++
    this.authFailures.set(ip, rec)
  }

  /**
   * Verify the credential FIRST, and throttle only failures.
   *
   * The limiter exists to stop credential GUESSING, so it must gate wrong answers, never
   * right ones. Checking it before `verify` meant a burst of 401s — e.g. the Mac app
   * retrying config() 20x with a token left stale by a state reset — rejected every later
   * request from that bucket, including ones bearing a freshly minted, valid token. The
   * whole onboarding wizard then reported the host as unreachable while the broker was
   * healthy and the credential was good.
   *
   * Verifying first is also why a success must NOT clear the failure counter: a valid token
   * is already immune to throttling, so clearing would buy legitimate clients nothing while
   * handing anyone sharing their bucket a fresh budget on every success. The Mac app polls
   * about once a second, which would have reset a co-bucketed brute-forcer's count forever.
   */
  private authenticate(req: Request): { ok: true; device: { name: string; token: string } } | { ok: false; throttled: boolean } {
    const token = authToken(req)
    if (token) {
      const dev = this.store.verify(token)
      if (dev) {
        this.store.touch(token)
        return { ok: true, device: { name: dev.name, token } }
      }
    }
    // Presenting NO credential is not a guess, so it must not spend the brute-force budget.
    // /host is public and merely asks whether the caller is authed before adding its
    // authed-only fields — and MacBrokerSidecar.pollForHost probes it anonymously up to 60x
    // at 500ms on every app start. Counting those meant the sidecar's own health check blew
    // the 16-failure budget and throttled the broker it had just spawned.
    if (!token) return { ok: false, throttled: false }
    const throttled = !this.checkRateLimit(req)
    if (!throttled) this.recordAuthFailure(req)
    return { ok: false, throttled }
  }

  async start(): Promise<void> {
    if (this.pushStore) {
      this.store.addRevokeListener((name) => { this.pushStore!.remove(name) })
    }
    if (this.deviceTokenStore) {
      this.store.addRevokeListener((name) => { this.deviceTokenStore!.remove(name) })
    }
    this.server = Bun.serve<WSData>({
      port: this.opts.port,
      fetch: (req, server) => this.routeRequestOrUpgrade(req, server),
      websocket: {
        // Negotiated per-connection (clients that don't support it are unaffected).
        // Terminal + control-channel JSON are highly compressible; the heavy win
        // is on slow links. NOTE: this is server-wide, so it also applies to the
        // /ws/display + /ws/scrcpy binary streams, whose payloads are already
        // compressed — deflate achieves a near-1.0 ratio on them (small header
        // overhead) but still costs CPU. If that CPU shows up on this shared box,
        // the fallback is terminal-only app-level deflate (see the perf plan).
        perMessageDeflate: true,
        open: (ws) => {
          if ((ws.data as any)?.proxyUpstream) {
            const { proxyUpstream, proxyPath, proxyWsProtocol } = ws.data as any
            const wsUrl = `ws://127.0.0.1:${proxyUpstream.port}${proxyPath}`
            // Forward the client's requested subprotocol(s) upstream. Dev-server
            // HMR sockets (e.g. Vite's `vite-hmr`) are only handled when the
            // upgrade carries the matching Sec-WebSocket-Protocol; without it the
            // upstream accepts the socket but never speaks, so HMR silently dies.
            const protocols: string[] | undefined = proxyWsProtocol
              ? String(proxyWsProtocol).split(",").map((s: string) => s.trim()).filter(Boolean)
              : undefined
            const upstream = protocols && protocols.length
              ? new WebSocket(wsUrl, protocols)
              : new WebSocket(wsUrl)
            ;(ws.data as any)._proxyWs = upstream
            upstream.addEventListener("message", (e) => { try { ws.send(e.data) } catch {} })
            upstream.addEventListener("close", () => { try { ws.close() } catch {} })
            upstream.addEventListener("error", () => { try { ws.close() } catch {} })
            return
          }
          if (ws.data.scrcpy) { this.onScrcpyWsOpen(ws); return }
          if (ws.data.display) { this.onDisplayWsOpen(ws); return }
          if (ws.data.terminal) {
            void this.onTerminalWsOpen(ws)
            return
          }
          this.onWsOpen(ws)
        },
        message: (ws, msg) => {
          if ((ws.data as any)?._proxyWs) {
            const upstream = (ws.data as any)._proxyWs as WebSocket
            try { upstream.send(msg) } catch {}
            return
          }
          if (ws.data.scrcpy) { this.onScrcpyWsMessage(ws, msg); return }
          if (ws.data.display) { this.onDisplayWsMessage(ws, msg); return }
          if (ws.data.terminal) {
            this.onTerminalWsMessage(ws, msg)
            return
          }
          this.onWsMessage(ws, String(msg))
        },
        close: (ws) => {
          if ((ws.data as any)?._proxyWs) {
            const upstream = (ws.data as any)._proxyWs as WebSocket
            try { upstream.close() } catch {}
            return
          }
          if (ws.data.scrcpy) { this.onScrcpyWsClose(ws); return }
          if (ws.data.display) { this.onDisplayWsClose(ws); return }
          if (ws.data.terminal) {
            this.onTerminalWsClose(ws)
            return
          }
          this.onWsClose(ws)
        },
        drain: (ws) => {
          // Socket buffer emptied — release any terminal viewer we paused.
          const d = ws.data._termDrain
          if (d) { ws.data._termDrain = undefined; d.resolve() }
        },
      },
    })
    this.heartbeatTimer = setInterval(() => this.pingAll(), 30_000)
    log.info("web channel listening", { port: this.boundPort })
  }

  async stop(): Promise<void> {
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    this.heartbeatTimer = undefined
    // Force-close active/keep-alive connections (the `true`). A graceful stop
    // leaves idle keep-alive sockets open, so a stopped channel keeps serving on
    // them — which on an in-process restart (and across reused ports under
    // `bun test`) lets a client's pooled connection hit the dead-but-not-closed
    // old server and read its now-removed state, yielding stale/401 responses.
    this.server?.stop(true)
  }

  async send(action: OutboundAction): Promise<OutboundResult> {
    if (action.op !== "reply") return { ok: true, value: { dropped: true, reason: "v1 only delivers reply on web" } }
    if (action.chat_id !== "web" && !action.chat_id.startsWith("web:")) return { ok: false, error: `unexpected chat_id for web channel: ${action.chat_id}` }
    // We return a message_id but DO NOT emit a message_append frame from here.
    // Bug I1: previously this method broadcast a frame with no `session` field,
    // and the web-app dispatcher pushed the entry into bySession[undefined].
    // The authoritative broadcast happens via main.ts's messageLog.on("append")
    // listener, which has the session name in hand. Letting that listener be
    // the single source of message_append frames removes the orphan entry AND
    // the duplicate the client otherwise saw.
    const messageId = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    return { ok: true, value: { message_id: messageId } }
  }

  broadcastToAll(frame: object): void {
    const json = JSON.stringify(frame)
    for (const c of this.wsConnections) c.ws.send(json)
  }

  broadcastToOthers(frame: object, except: import("bun").ServerWebSocket<WSData>): void {
    const json = JSON.stringify(frame)
    for (const c of this.wsConnections) if (c.ws !== except) c.ws.send(json)
  }

  private async routeRequestOrUpgrade(req: Request, server: import("bun").Server<WSData>): Promise<Response | undefined> {
    this.resolveRateLimitBucket(req, server)
    const url = new URL(req.url)
    if (this.opts.proxyBaseDomain) {
      const host = req.headers.get("host") ?? ""
      const sub = extractSubdomain(host, this.opts.proxyBaseDomain, this.opts.proxyMainHost)
      if (sub) {
        return this.handleProxyRoute(req, sub, server)
      }
    } else if (this.opts.proxyLookup) {
      // Path-based proxy (active only when no base domain is configured).
      const pm = matchProxyPath(url.pathname)
      if (pm) {
        if (pm.rest === null) {
          // bare /p/<slug> → 301 so the browser anchors relative URLs correctly
          return new Response(null, { status: 301, headers: { location: `/p/${pm.slug}/${url.search}` } })
        }
        return this.handleProxyRoute(req, pm.slug, server, {
          prefix: `/p/${pm.slug}`,
          upstreamPath: pm.rest + url.search,
        })
      }
    }
    if (url.pathname === "/ws") {
      const auth = this.authenticate(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const dev = auth.device
      const upgraded = server.upgrade(req, { data: { deviceName: dev.name, openedAt: Date.now() } as WSData })
      if (upgraded) return undefined  // 101 returned by Bun automatically
      return new Response("upgrade failed", { status: 500 })
    }
    if (url.pathname === "/ws/term") {
      const sessionName = url.searchParams.get("session") ?? ""
      const kind = url.searchParams.get("kind") === "agent" ? "agent" : "scratch"
      // Per-terminal id (multiple scratch terminals per session). Agent terminals
      // are singular → fixed id "agent". Sanitized: it becomes a tmux name.
      const terminalId = kind === "agent"
        ? "agent"
        : ((url.searchParams.get("terminal") ?? "").replace(/[^A-Za-z0-9]/g, "").slice(0, 64) || "main")
      const auth = this.authenticate(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const dev = auth.device
      if (!sessionName || !this.opts.getSessionWorkdir?.(sessionName)) {
        return new Response("session not found", { status: 404 })
      }
      // Agent terminals are claude-only: getSessionTmuxTarget returns undefined
      // for non-claude / unknown sessions (see main.ts).
      let agentTarget: string | undefined
      if (kind === "agent") {
        agentTarget = await this.opts.getSessionTmuxTarget?.(sessionName)
        if (!agentTarget) return new Response("agent terminal unsupported", { status: 404 })
      }
      const upgraded = server.upgrade(req, {
        data: { deviceName: dev.name, openedAt: Date.now(), terminal: true, terminalKind: kind, terminalSession: sessionName, terminalId, terminalAgentTarget: agentTarget } as WSData,
      })
      if (upgraded) return undefined
      return new Response("upgrade failed", { status: 500 })
    }
    if (url.pathname === "/ws/display") {
      const id = url.searchParams.get("id") ?? ""
      const auth = this.authenticate(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const dev = auth.device
      if (!id || this.opts.getDisplayPort?.(id) === undefined) {
        return new Response("display stream not found", { status: 404 })
      }
      const upgraded = server.upgrade(req, {
        data: { deviceName: dev.name, openedAt: Date.now(), display: true, displayStreamId: id } as WSData,
      })
      if (upgraded) return undefined
      return new Response("upgrade failed", { status: 500 })
    }
    if (url.pathname === "/ws/scrcpy") {
      const id = url.searchParams.get("id") ?? ""
      const auth = this.authenticate(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const dev = auth.device
      if (!id || this.opts.getScrcpy?.(id) === undefined) {
        return new Response("scrcpy stream not found", { status: 404 })
      }
      const upgraded = server.upgrade(req, {
        data: { deviceName: dev.name, openedAt: Date.now(), scrcpy: true, displayStreamId: id } as WSData,
      })
      if (upgraded) return undefined
      return new Response("upgrade failed", { status: 500 })
    }
    return this.routeRequest(req)
  }

  private onWsOpen(ws: import("bun").ServerWebSocket<WSData>): void {
    this.wsConnections.add({ ws, deviceName: ws.data.deviceName })
  }

  // Lazily create the per-connection LSP bridge, stored on ws.data so its
  // language-server child processes live and die with this socket.
  private lspFor(ws: import("bun").ServerWebSocket<WSData>): LspConnection {
    let conn = (ws.data as any)._lsp as LspConnection | undefined
    if (!conn) {
      conn = new LspConnection({
        send: (frame) => { try { ws.send(JSON.stringify(frame)) } catch {} },
        getWorkdir: (session) => this.opts.getSessionWorkdir?.(session),
        getEditorConfig: () => this.opts.getEditorConfig?.() ?? { lsp: { servers: {} } },
        log: (event, data) => log.info(event, data),
      })
      ;(ws.data as any)._lsp = conn
    }
    return conn
  }

  private onWsClose(ws: import("bun").ServerWebSocket<WSData>): void {
    if (this.fsWatcher && (ws.data as any)?._editorCb) {
      this.fsWatcher.unsubscribe((ws.data as any)._editorSession, (ws.data as any)._editorCb)
    }
    ;((ws.data as any)?._lsp as LspConnection | undefined)?.dispose()
    for (const c of this.wsConnections) {
      if (c.ws === ws) { this.wsConnections.delete(c); break }
    }
    this.opts.viewingTracker?.clear(ws.data.deviceName)
  }

  private async onTerminalWsOpen(ws: import("bun").ServerWebSocket<WSData>): Promise<void> {
    const tm = this.opts.terminalManager
    if (!tm) { ws.close(1011, "terminal not configured"); return }
    const sessionName = ws.data.terminalSession!
    const terminalId = ws.data.terminalId!
    const workdir = this.opts.getSessionWorkdir?.(sessionName)
    if (!workdir) { ws.close(1011, "session not found"); return }
    try {
      ws.send(JSON.stringify({ type: "reset" }))
    } catch {
      try { ws.close(1011, "terminal reset failed") } catch {}
      return
    }
    let result: Awaited<ReturnType<typeof tm.attach>>
    try {
      result = await tm.attach({
        deviceName: ws.data.deviceName,
        sessionName,
        terminalId,
        workdir,
        cols: 80,
        rows: 24,
        kind: ws.data.terminalKind ?? "scratch",
        agentTarget: ws.data.terminalAgentTarget,
        onData: (data) => {
          try { ws.sendBinary(data) } catch {}
          // Past the high-water mark: hand pumpOutput a promise that resolves on
          // the socket's `drain`, so we stop pulling pty-helper output (→ tmux
          // sees a slow client and redraws current state instead of replaying).
          if (ws.getBufferedAmount() > TERMINAL_BP_HIGH_WATER) {
            const d = ws.data._termDrain ?? makeDeferred()
            ws.data._termDrain = d
            return d.promise
          }
        },
        onExit: (code) => { try { ws.send(JSON.stringify({ type: "exit", code })); ws.close() } catch {} },
        onFailure: (reason) => { try { ws.close(1011, reason.slice(0, 120)) } catch {} },
      })
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error)
      try { ws.send(JSON.stringify({ type: "error", reason })) } catch {}
      try { ws.close(1011, reason.slice(0, 120)) } catch {}
      return
    }
    if (!result.ok) {
      try { ws.send(JSON.stringify({ type: "error", reason: result.error })) } catch {}
      try { ws.close(1011, result.error.slice(0, 120)) } catch {}
    }
  }

  private onTerminalWsMessage(ws: import("bun").ServerWebSocket<WSData>, msg: string | Buffer | ArrayBuffer): void {
    const tm = this.opts.terminalManager
    if (!tm) return
    const sessionName = ws.data.terminalSession!
    const terminalId = ws.data.terminalId!
    if (typeof msg === "string") {
      try {
        const frame = JSON.parse(msg)
        if (frame.type === "resize" && typeof frame.cols === "number" && typeof frame.rows === "number") {
          tm.resize(ws.data.deviceName, sessionName, terminalId, frame.cols, frame.rows)
        } else if (frame.type === "close") {
          // Explicit close: destroy the tmux session, then drop the socket.
          void tm.close(sessionName, terminalId)
          try { ws.close() } catch {}
        }
      } catch {}
      return
    }
    const data = msg instanceof ArrayBuffer ? new Uint8Array(msg) : new Uint8Array(msg.buffer, msg.byteOffset, msg.byteLength)
    tm.write(ws.data.deviceName, sessionName, terminalId, data)
  }

  private onTerminalWsClose(ws: import("bun").ServerWebSocket<WSData>): void {
    // Wake a paused pumpOutput so it can observe the (about-to-be-killed) stream
    // ending — otherwise it would await a drain that never comes.
    const d = ws.data._termDrain
    if (d) { ws.data._termDrain = undefined; d.resolve() }
    // Socket dropped (reload / nav / network): DETACH — the tmux session lives on.
    this.opts.terminalManager?.detach(ws.data.deviceName, ws.data.terminalSession!, ws.data.terminalId!)
  }

  private onDisplayWsOpen(ws: import("bun").ServerWebSocket<WSData>): void {
    const id = ws.data.displayStreamId!
    const port = this.opts.getDisplayPort?.(id)
    if (port === undefined) { ws.close(1011, "display stream not found"); return }
    Bun.connect({
      hostname: "127.0.0.1",
      port,
      socket: {
        data: (_sock, data) => { try { ws.sendBinary(data) } catch {} },
        close: () => { try { ws.close() } catch {} },
        error: () => { try { ws.close() } catch {} },
      },
    }).then((sock) => {
      this.displaySockets.set(ws, sock)
    }).catch(() => { try { ws.close(1011, "upstream connect failed") } catch {} })
  }

  private onDisplayWsMessage(ws: import("bun").ServerWebSocket<WSData>, msg: string | Buffer | ArrayBuffer): void {
    const sock = this.displaySockets.get(ws)
    if (!sock) return
    const data = typeof msg === "string"
      ? new TextEncoder().encode(msg)
      : msg instanceof ArrayBuffer ? new Uint8Array(msg) : new Uint8Array(msg.buffer, msg.byteOffset, msg.byteLength)
    try { sock.write(data) } catch {}
  }

  private onDisplayWsClose(ws: import("bun").ServerWebSocket<WSData>): void {
    const sock = this.displaySockets.get(ws)
    if (sock) { try { sock.end() } catch {} this.displaySockets.delete(ws) }
  }

  private onScrcpyWsOpen(ws: import("bun").ServerWebSocket<WSData>): void {
    const id = ws.data.displayStreamId!
    const inst = this.opts.getScrcpy?.(id)
    if (!inst) { ws.close(1011, "scrcpy stream not found"); return }
    ws.send(JSON.stringify({ type: "init", codec: "avc", width: inst.width, height: inst.height }))
    // scrcpy only emits frames on screen change; replay the cached config + last
    // keyframe immediately so an idle/static screen paints instantly on connect.
    if (inst.configData && inst.lastKeyFrame) {
      const payload = new Uint8Array(inst.configData.length + inst.lastKeyFrame.length)
      payload.set(inst.configData, 0)
      payload.set(inst.lastKeyFrame, inst.configData.length)
      const msg = new Uint8Array(1 + payload.length)
      msg[0] = 0x01 // keyframe flag
      msg.set(payload, 1)
      try { ws.sendBinary(msg) } catch {}
    }
    inst.onAccessUnit((au) => {
      if ((ws.data as any)._scrcpyClosed) return
      // WebCodecs annexB: skip standalone config AUs; the decoder gets SPS/PPS
      // prepended on each keyframe instead.
      if (au.config) return
      const config = inst.configData
      const payload = au.keyFrame && config
        ? (() => { const m = new Uint8Array(config.length + au.data.length); m.set(config); m.set(au.data, config.length); return m })()
        : au.data
      const msg = new Uint8Array(1 + payload.length)
      msg[0] = au.keyFrame ? 0x01 : 0x00
      msg.set(payload, 1)
      try { ws.sendBinary(msg) } catch {}
    })
  }

  private onScrcpyWsMessage(ws: import("bun").ServerWebSocket<WSData>, msg: string | Buffer | ArrayBuffer): void {
    if (typeof msg !== "string") return
    const inst = this.opts.getScrcpy?.(ws.data.displayStreamId!)
    if (!inst) return
    let frame: any
    try { frame = JSON.parse(msg) } catch { return }
    if (frame.type === "touch") {
      const bytes = encodeTouch({
        action: frame.action as TouchAction,
        x: frame.x,
        y: frame.y,
        width: frame.width,
        height: frame.height,
      })
      inst.sendControl(bytes)
      return
    }
    if (frame.type === "text" && typeof frame.text === "string") {
      inst.sendControl(encodeText(frame.text))
      return
    }
    if (frame.type === "key" && typeof frame.key === "string") {
      const code = SCRCPY_KEYCODES[frame.key]
      if (code === undefined) return
      inst.sendControl(encodeKey(code, frame.action ?? 0))
      return
    }
  }

  private onScrcpyWsClose(ws: import("bun").ServerWebSocket<WSData>): void {
    // Teardown of the stream is owned by the DisplayManager, not the ws.
    // Just stop forwarding access units to this (now-dead) socket.
    ;(ws.data as any)._scrcpyClosed = true
  }

  private pingAll(): void {
    const now = Date.now()
    for (const c of [...this.wsConnections]) {
      const lastPong = c.ws.data.lastPongAt ?? c.ws.data.openedAt
      if (now - lastPong > 60_000) {
        try { c.ws.close() } catch {}
        continue
      }
      try { c.ws.send(JSON.stringify({ type: "ping" })) } catch {}
    }
  }

  private async onWsMessage(ws: import("bun").ServerWebSocket<WSData>, raw: string): Promise<void> {
    let frame: any
    try { frame = JSON.parse(raw) } catch { ws.send(JSON.stringify({ type: "error", reason: "bad json" })); return }
    if (frame.type === "pong") {
      ws.data.lastPongAt = Date.now()
      return
    }
    if (frame.type === "subscribe") {
      const sessions = this.opts.getSessionsSnapshot()
      const logs: Record<string, unknown[]> = {}
      const activity: Record<string, unknown[]> = {}
      const bgTasks: Record<string, unknown[]> = {}
      const agentState: Record<string, unknown> = {}
      const commands: Record<string, unknown[]> = {}
      const commandsResolved: Record<string, boolean> = {}
      for (const s of sessions) {
        const sessionKey = s.id ?? s.name
        logs[sessionKey] = this.opts.getSessionLog(sessionKey)
        activity[sessionKey] = this.opts.getSessionActivity?.(sessionKey) ?? []
        bgTasks[sessionKey] = this.opts.getSessionBgTasks?.(sessionKey) ?? []
        agentState[sessionKey] = this.opts.getSessionAgentState?.(sessionKey)
        commands[sessionKey] = this.opts.getSessionCommands?.(sessionKey) ?? []
        commandsResolved[sessionKey] = this.opts.getSessionCommandsResolved?.(sessionKey) ?? false
      }
      const proxies = this.opts.listProxies?.() ?? []
      const displays = this.opts.listDisplays?.() ?? []
      const onboarded = this.opts.getAppConfig?.()?.onboarded ?? false
      const reads = this.opts.getReads?.() ?? {}
      const drafts = this.opts.getDrafts?.() ?? {}
      ws.send(JSON.stringify({ type: "snapshot", sessions, logs, activity, bgTasks, agentState, proxies, displays, commands, commandsResolved, homeDir: home(), onboarded, reads, drafts }))
      return
    }
    if (frame.type === "ping") {
      ws.send(JSON.stringify({ type: "pong" }))
      return
    }
    if (frame.type === "viewing") {
      const session = typeof frame.session === "string" || frame.session === null ? frame.session : undefined
      const visible = typeof frame.visible === "boolean" ? frame.visible : undefined
      if (session === undefined || visible === undefined) {
        log.warn("ws.viewing.bad_frame", { device: ws.data.deviceName })
        return
      }
      this.opts.viewingTracker?.update(ws.data.deviceName, { session, visible })
      // A visible device sitting on an exact session has read it up to its
      // newest message. (Sitting on the list, session===null, does not count.)
      if (visible && typeof session === "string") this.opts.markRead?.(session)
      return
    }
    if (frame.type === "draft_set" && typeof frame.session === "string" && typeof frame.text === "string") {
      // Empty text is a clear — normalize so an empty draft never lingers.
      if (frame.text.length === 0) {
        this.opts.setDraft?.(frame.session, null)
        this.broadcastToOthers({ type: "draft_clear", session: frame.session }, ws)
      } else {
        this.opts.setDraft?.(frame.session, frame.text)
        this.broadcastToOthers({ type: "draft_set", session: frame.session, text: frame.text }, ws)
      }
      return
    }
    if (frame.type === "draft_clear" && typeof frame.session === "string") {
      this.opts.setDraft?.(frame.session, null)
      this.broadcastToOthers({ type: "draft_clear", session: frame.session }, ws)
      return
    }
    if (frame.type === "send" && frame.session && frame.op === "reply") {
      const requestedIds: string[] = Array.isArray(frame.args?.attachments) ? frame.args.attachments : []
      let attachments: InboundAttachment[] | undefined = undefined
      if (requestedIds.length > 0) {
        if (!this.fileStore) {
          ws.send(JSON.stringify({ type: "error", reason: "file store not mounted" }))
          return
        }
        attachments = []
        for (const id of requestedIds) {
          const meta = await this.fileStore.resolveOwnedWebUpload(id, ws.data.deviceName)
          if (!meta) {
            ws.send(JSON.stringify({ type: "error", reason: "invalid attachment reference" }))
            return
          }
          attachments.push({
            kind: meta.kind,
            file_id: meta.file_id,
            mime: meta.mime,
            size: meta.size,
            name: meta.name,
          })
        }
      }

      if (!frame.args?.text && !(attachments && attachments.length > 0)) {
        ws.send(JSON.stringify({ type: "error", reason: "send must include text or attachments" }))
        return
      }

      const clientMessageId = typeof frame.client_message_id === "string"
        && /^web-[A-Za-z0-9._:-]{1,120}$/.test(frame.client_message_id)
        ? frame.client_message_id
        : undefined

      const msg: InboundMessage = {
        channel: "web",
        // Web is one logical channel — a constant chat_id. The device identity
        // lives in user/user_id (+ presence/push), not the routing key.
        chat_id: "web",
        message_id: clientMessageId ?? `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        user: ws.data.deviceName,
        user_id: ws.data.deviceName,
        ts: new Date().toISOString(),
        text: frame.args?.text,
        target_session_id: frame.session,
        ...(attachments && attachments.length > 0 ? { attachments } : {}),
      }
      this.opts.onSendFromWeb(msg)
      for (const h of this.inboundHandlers) h(msg)
      // The draft just became a real message — clear it for this session on the
      // user's other devices too (the sender's composer already cleared locally).
      this.opts.setDraft?.(frame.session, null)
      this.broadcastToOthers({ type: "draft_clear", session: frame.session }, ws)
      return
    }
    if (frame.type === "editor_open" && frame.session) {
      if (this.fsWatcher && this.opts.getSessionWorkdir) {
        const fsWorkdir = this.opts.getSessionWorkdir(frame.session)
        if (fsWorkdir) {
          const cb = (paths: string[]) => {
            try { ws.send(JSON.stringify({ type: "fs_changed", session: frame.session, paths })) } catch {}
          }
          ;(ws.data as any)._editorCb = cb
          ;(ws.data as any)._editorSession = frame.session
          this.fsWatcher.subscribe(frame.session, fsWorkdir, cb)
        }
      }
      return
    }
    if (frame.type === "editor_close" && frame.session) {
      if (this.fsWatcher && (ws.data as any)?._editorCb) {
        this.fsWatcher.unsubscribe(frame.session, (ws.data as any)._editorCb)
        ;(ws.data as any)._editorCb = null
        ;(ws.data as any)._editorSession = null
      }
      return
    }
    if (typeof frame.type === "string" && frame.type.startsWith("lsp_")) {
      this.lspFor(ws).handle(frame)
      return
    }
    if (frame.type === "client_error") {
      log.warn("client_error", {
        kind: frame.kind,
        message: String(frame.message ?? "").slice(0, 500),
        url: frame.url,
        stack: String(frame.stack ?? "").slice(0, 1500),
        device: ws.data.deviceName,
      })
      return
    }
    if (frame.type === "client_logs") {
      this.ingestClientLogs(ws.data.deviceName, frame.entries ?? [], frame.meta)
      return
    }
    ws.send(JSON.stringify({ type: "error", reason: "unknown frame type" }))
  }

  private requireAuth(req: Request): { ok: true; device: { name: string; token: string } } | { ok: false; throttled: boolean } {
    return this.authenticate(req)
  }

  /**
   * 429 for throttling, 401 for a genuinely bad credential — never conflate the two.
   * Reporting throttling as 401 told correctly-paired clients their credential was
   * rejected, which is what sent the previous fix hunting through the client.
   */
  private authFailureStatus(a: { ok: false; throttled: boolean }): number {
    return a.throttled ? 429 : 401
  }

  private authFailureResponse(a: { ok: false; throttled: boolean }): Response {
    return a.throttled
      ? new Response("rate limited", { status: 429 })
      : new Response("unauthorized", { status: 401 })
  }

  private json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
  }

  // Mode-specific instruction text shown when self-update isn't possible
  // (source/docker installs, or checks disabled). Mirrors the CLI's wording.
  private updateInstruction(mode: import("../../core/update/checker").UpdateMode): string {
    if (mode === "docker") return "Docker install — update with: docker compose pull && docker compose up -d"
    return "Source install — update via git (git pull && restart)."
  }

  /**
   * POST /api/update/run handler. The checker's STATE is the in-flight guard:
   * if it's already checking/downloading/swapping we 409 rather than starting a
   * second apply. Only `binary` mode self-updates; source/docker (and the
   * checks-disabled / no-checker case) return 400 with the instruction text.
   *
   * On a green light we kick off resolveAndApply ASYNC (never awaited in the
   * handler) and return 202 immediately. Progress is pushed into the checker via
   * onState so the PWA's status poll reflects downloading→swapping; on success we
   * either restart via systemd (the process dies — the PWA reconnects to the new
   * version) or land in restart-required; on failure we record state "failed".
   */
  private handleUpdateRun(): Response {
    const checker = this.opts.updateChecker
    // No checker (MUX_UPDATE_CHECK=0) → can't self-update; tell the caller how.
    if (!checker) {
      return this.json(
        { error: "update checks disabled", instruction: this.updateInstruction(detectUpdateMode()) },
        400,
      )
    }
    const status = checker.status()
    if (status.mode !== "binary") {
      return this.json(
        { error: `self-update not available in ${status.mode} mode`, instruction: this.updateInstruction(status.mode) },
        400,
      )
    }
    // Busy guard: the checker's own state IS the in-flight flag.
    if (status.state === "checking" || status.state === "downloading" || status.state === "swapping") {
      return this.json({ error: "busy" }, 409)
    }
    // Claim the slot synchronously so a second concurrent POST sees "checking" → 409.
    checker.setState("checking")

    // Green light. Fire-and-forget the apply; surface progress via the checker.
    void (async () => {
      try {
        const result = await resolveAndApply({
          url: process.env.MUX_UPDATE_URL ?? "https://supermux.dev/versions.json",
          currentVersion: BUILD_VERSION,
          onState: (s) => checker.setState(s),
        })
        if (result.ok) {
          if (restartService()) {
            // Process is about to die; systemd/launchd restarts → PWA reconnects
            // to the new version. Nothing more to do here.
          } else {
            checker.setState("restart-required")
          }
        } else {
          checker.setState("failed", result.error.kind)
        }
      } catch (err) {
        checker.setState("failed", err instanceof Error ? err.message : String(err))
      }
    })()

    return this.json({ started: true }, 202)
  }

  ingestClientLogs(device: string, entries: unknown[], meta?: Record<string, unknown>): void {
    const serverTs = Date.now()
    for (const raw of entries) {
      if (!raw || typeof raw !== "object") continue
      const e = raw as Record<string, unknown>
      const category = String(e.category ?? "client")
      const event = String(e.event ?? "log")
      const stored: StoredClientLogEntry = {
        ts: Number(e.ts) || serverTs,
        category,
        event,
        data: e.data && typeof e.data === "object" ? (e.data as Record<string, unknown>) : undefined,
        device,
        serverTs,
        meta,
      }
      this.clientLogRing.push(stored)
      log.info("client_log", {
        device,
        category,
        event,
        data: stored.data ? JSON.stringify(stored.data).slice(0, 800) : undefined,
        meta: meta ? JSON.stringify(meta).slice(0, 300) : undefined,
      })
    }
    while (this.clientLogRing.length > MAX_CLIENT_LOG_RING) this.clientLogRing.shift()
  }

  private async handleProxyRoute(
    req: Request,
    slug: string,
    server?: import("bun").Server<WSData>,
    pathOpts?: { prefix: string; upstreamPath: string },
  ): Promise<Response | undefined> {
    if (!this.opts.proxyLookup) return new Response("proxy not configured", { status: 500 })
    const upstream = this.opts.proxyLookup(slug)
    if (!upstream) return new Response("not found", { status: 404 })

    if (!upstream.isPublic) {
      // The cmux_token cookie is host-only (path mode) or Domain=.<base>
      // (subdomain mode), so a paired device already sends it here. A static
      // 401 (no reflected input) replaces the old /proxy-auth redirect dance.
      const token = authToken(req)
      if (!token || !this.opts.proxyAuth?.(token)) {
        const mainUrl = this.opts.publicUrl.replace(/\/$/, "")
        return new Response(
          `<!DOCTYPE html><meta charset="utf-8"><title>Not paired</title><body style="font-family:system-ui;background:#0a0a0a;color:#e5e5e5;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0"><p>Pair this device in the <a style="color:#6ea8fe" href="${mainUrl}/">main app</a> first, then reload.</p></body>`,
          { status: 401, headers: { "content-type": "text/html" } },
        )
      }
    }

    // WebSocket upgrade
    const upgradeHeader = req.headers.get("upgrade")?.toLowerCase()
    if (upgradeHeader === "websocket" && server) {
      const url = new URL(req.url)
      const wsProtocol = req.headers.get("sec-websocket-protocol")
      // A server selects at most one subprotocol; echo the first offered token
      // back in the 101 so strict clients accept the handshake. The full list is
      // forwarded to the upstream dial in the websocket `open` handler.
      const selectedProtocol = wsProtocol?.split(",")[0]?.trim()
      const wsPath = pathOpts ? pathOpts.upstreamPath : url.pathname + url.search
      const upgraded = server.upgrade(req, {
        data: { proxyUpstream: upstream, proxyPath: wsPath, proxyWsProtocol: wsProtocol } as any,
        ...(selectedProtocol ? { headers: { "Sec-WebSocket-Protocol": selectedProtocol } } : {}),
      })
      return upgraded ? undefined : new Response("ws upgrade failed", { status: 500 })
    }

    return handleProxyRequest(req, upstream, pathOpts)
  }

  private async routeRequest(req: Request): Promise<Response> {
    const url = new URL(req.url)
    const path = url.pathname
    const method = req.method.toUpperCase()

    // CSRF guard: state-changing API requests must come from the app's own
    // origin. SameSite=Lax already blocks cross-site POST cookies; this also
    // rejects a missing Origin. /internal/* carries its own secret (not in
    // API_PREFIXES); GET/WS are exempt.
    if (MUTATING_METHODS.has(method) && API_PREFIXES.some((p) => path === p || path.startsWith(p + "/"))) {
      // Bearer-authed native clients carry no ambient cookie, so CSRF doesn't
      // apply; the same-origin guard is only meaningful for cookie browsers.
      if (!authedViaBearer(req) && !sameOriginOk(
        req,
        this.opts.publicUrl,
        this.getRelayUrl?.(),
      )) {
        return new Response("bad origin", { status: 403 })
      }
    }

    // static-serve: any GET that isn't an API path tries the static directory first.
    // Cache-Control matters here because the broker is typically fronted by Cloudflare,
    // which caches asset-shaped responses by default — without explicit headers, CF can
    // serve a stale index.html or sw.js that points to old hashed bundles, and PWAs see
    // no update. Hashed files in /assets/ are content-addressed (e.g. index-DaYj1-bp.js)
    // and safe to cache forever; everything else (HTML shell, SW, manifest, registerSW)
    // is an entry point and must revalidate every request. Resolution (disk-first, then
    // embedded PWA for compiled binaries, then SPA fallback) lives in serveStatic.
    if (method === "GET" && !API_PREFIXES.some((p) => path === p || path.startsWith(p + "/"))) {
      const res = serveStatic({ staticDir: this.opts.staticDir, embedded: this.opts.staticEmbedded ?? {}, path, acceptEncoding: req.headers.get("accept-encoding") ?? undefined })
      if (res) return res
    }

    if (method === "POST" && path.startsWith("/internal/agent-hook/")) {
      // Machine-to-machine (Claude hooks curl this from localhost). Gated by a
      // persistent secret embedded in the hook URL so a reachable web port can't
      // forge agent-state/error/push events. Legacy hooks files (written before
      // secret support, or clobbered by tests) omit ?s= — accept those until the
      // file is regenerated with a secret on the next broker restart. A mismatch
      // is worth a log line: hooks drive agent status, the sending curl discards
      // its own failures (`|| true`), and a silent 403 here once froze every
      // pre-restart session at "idle" for days before anyone could see why.
      if (this.opts.internalSecret) {
        const provided = url.searchParams.get("s")
        const requiresSecret = hooksFileUsesHookSecret()
        const mismatch = requiresSecret ? provided !== this.opts.internalSecret : Boolean(provided) && provided !== this.opts.internalSecret
        if (mismatch) {
          log.warn("agent_hook_rejected", { path, hasSecret: provided !== null })
          return new Response("forbidden", { status: 403 })
        }
      }
      const event = path.slice("/internal/agent-hook/".length)
      let body: any = {}
      try { body = await req.json() } catch {}
      try { this.opts.onAgentHook?.(event, body) } catch {}
      return new Response("ok", { status: 200 })
    }

    if (method === "POST" && path === "/upload") {
      log.info("upload.start", {
        ip: clientIp(req),
        contentType: req.headers.get("content-type"),
        contentLength: req.headers.get("content-length"),
      })
      const authResult = this.requireAuth(req)
      if (!authResult.ok) {
        log.warn("upload.unauth", { ip: clientIp(req) })
        return new Response("unauthorized", { status: 401 })
      }
      if (!this.fileStore) {
        log.error("upload.no_store")
        return new Response("file store not mounted", { status: 500 })
      }

      // Streaming path gets the full cap (it never buffers). The legacy buffered
      // multipart path keeps a smaller in-RAM cap so an old/hostile client can't
      // OOM the broker with a huge multipart body.
      const MAX_UPLOAD_BYTES = Number(process.env.MUX_WEB_UPLOAD_MAX_MB ?? 500) * 1024 * 1024
      const MAX_MULTIPART_BYTES = Number(process.env.MUX_WEB_UPLOAD_MULTIPART_MAX_MB ?? 25) * 1024 * 1024
      const contentType = req.headers.get("content-type") ?? ""

      // ── Legacy buffered path: multipart/form-data (old app-store builds) ──
      // RFC media types are case-insensitive, so normalize before matching.
      if (contentType.toLowerCase().includes("multipart/form-data")) {
        const contentLength = Number(req.headers.get("content-length") ?? 0)
        if (contentLength > MAX_MULTIPART_BYTES) {
          log.warn("upload.too_large_header", { contentLength, cap: MAX_MULTIPART_BYTES, device: authResult.device.name })
          return new Response("payload too large", { status: 413 })
        }

        let form: Awaited<ReturnType<Request["formData"]>>
        try {
          form = await req.formData()
        } catch (err: any) {
          log.warn("upload.bad_multipart", { err: err?.message ?? String(err), device: authResult.device.name })
          return new Response("bad multipart", { status: 400 })
        }

        const file = form.get("file")
        const session = form.get("session")
        const kindHint = form.get("kind")
        if (!(file instanceof Blob)) {
          log.warn("upload.no_file_field", { device: authResult.device.name })
          return new Response("file field required", { status: 400 })
        }
        if (typeof session !== "string" || session.length === 0) {
          log.warn("upload.no_session_field", { device: authResult.device.name })
          return new Response("session field required", { status: 400 })
        }
        if (file.size > MAX_MULTIPART_BYTES) {
          log.warn("upload.too_large_body", { size: file.size, cap: MAX_MULTIPART_BYTES, device: authResult.device.name })
          return new Response("payload too large", { status: 413 })
        }
        if (file.size === 0) {
          log.warn("upload.empty_file", { device: authResult.device.name })
          return new Response("empty file", { status: 400 })
        }

        const bytes = new Uint8Array(await file.arrayBuffer())
        const mime = file.type || undefined
        const name = (file as any).name as string | undefined
        const kind: AttachmentKind = (typeof kindHint === "string" && VALID_KINDS.includes(kindHint as AttachmentKind))
          ? (kindHint as AttachmentKind)
          : kindFromMime(mime)

        try {
          const { file_id, size } = await this.fileStore.put({
            kind, mime, name, session, origin: "web-upload",
            device: authResult.device.name, bytes,
          })
          log.info("upload.ok", { file_id, kind, mime, size, name, session, device: authResult.device.name, via: "multipart" })
          return this.json({ file_id, size, mime, name })
        } catch (err: any) {
          log.error("upload.store_failed", { err: err?.message ?? String(err), device: authResult.device.name, session, mime, size: bytes.length })
          return new Response("file store error", { status: 500 })
        }
      }

      // ── Streaming path: raw request body (updated clients) ───────────────
      const session = req.headers.get("x-mux-session") ?? ""
      if (session.length === 0) {
        log.warn("upload.no_session_header", { device: authResult.device.name })
        return new Response("session header required", { status: 400 })
      }
      const contentLength = Number(req.headers.get("content-length") ?? 0)
      if (contentLength > MAX_UPLOAD_BYTES) {
        log.warn("upload.too_large_header", { contentLength, cap: MAX_UPLOAD_BYTES, device: authResult.device.name })
        return new Response("payload too large", { status: 413 })
      }
      if (!req.body) {
        log.warn("upload.no_body", { device: authResult.device.name })
        return new Response("empty body", { status: 400 })
      }

      const mime = req.headers.get("x-mux-mime") || undefined
      const filenameHeader = req.headers.get("x-mux-filename")
      let name: string | undefined
      if (filenameHeader) {
        try { name = decodeURIComponent(filenameHeader) } catch { name = filenameHeader }
      }
      const kindHint = req.headers.get("x-mux-kind")
      const kind: AttachmentKind = (kindHint && VALID_KINDS.includes(kindHint as AttachmentKind))
        ? (kindHint as AttachmentKind)
        : kindFromMime(mime)

      try {
        const { file_id, size } = await this.fileStore.putStream(
          { kind, mime, name, session, origin: "web-upload", device: authResult.device.name, maxBytes: MAX_UPLOAD_BYTES },
          req.body,
        )
        log.info("upload.ok", { file_id, kind, mime, size, name, session, device: authResult.device.name, via: "stream" })
        return this.json({ file_id, size, mime, name })
      } catch (err: any) {
        if (err instanceof PayloadTooLargeError) {
          log.warn("upload.too_large_stream", { device: authResult.device.name, cap: MAX_UPLOAD_BYTES })
          return new Response("payload too large", { status: 413 })
        }
        if (err instanceof EmptyUploadError) {
          log.warn("upload.empty_body", { device: authResult.device.name })
          return new Response("empty body", { status: 400 })
        }
        log.error("upload.store_failed", { err: err?.message ?? String(err), device: authResult.device.name, session, mime, via: "stream" })
        return new Response("file store error", { status: 500 })
      }
    }

    // ── Resumable/chunked upload: init → PATCH chunks → HEAD probe ────────
    if (method === "POST" && path === "/upload/init") {
      const authResult = this.requireAuth(req)
      if (!authResult.ok) return this.authFailureResponse(authResult)
      if (!this.fileStore) return new Response("file store not mounted", { status: 500 })
      const MAX_UPLOAD_BYTES = Number(process.env.MUX_WEB_UPLOAD_MAX_MB ?? 500) * 1024 * 1024

      let body: any
      try { body = await req.json() } catch { return new Response("bad json", { status: 400 }) }
      const session = typeof body?.session === "string" ? body.session : ""
      if (session.length === 0) return new Response("session required", { status: 400 })
      const totalSize = Number(body?.total_size)
      if (!Number.isFinite(totalSize) || totalSize <= 0) return new Response("total_size required", { status: 400 })
      if (totalSize > MAX_UPLOAD_BYTES) return new Response("payload too large", { status: 413 })

      const mime = typeof body?.mime === "string" ? body.mime : undefined
      const name = typeof body?.name === "string" ? body.name : undefined
      const kindHint = typeof body?.kind === "string" ? body.kind : undefined
      const kind: AttachmentKind = (kindHint && VALID_KINDS.includes(kindHint as AttachmentKind))
        ? (kindHint as AttachmentKind)
        : kindFromMime(mime)

      try {
        const { upload_id, chunk_size } = await this.fileStore.createPending({
          session, kind, mime, name, totalSize, device: authResult.device.name, origin: "web-upload",
        })
        log.info("upload.init", { upload_id, kind, mime, name, session, totalSize, device: authResult.device.name })
        return this.json({ upload_id, offset: 0, chunk_size })
      } catch (err: any) {
        log.error("upload.init_failed", { err: err?.message ?? String(err), device: authResult.device.name, session })
        return new Response("file store error", { status: 500 })
      }
    }

    if (method === "PATCH" && path.startsWith("/upload/")) {
      const authResult = this.requireAuth(req)
      if (!authResult.ok) return this.authFailureResponse(authResult)
      if (!this.fileStore) return new Response("file store not mounted", { status: 500 })
      const upload_id = decodeURIComponent(path.slice("/upload/".length))

      const offset = Number(req.headers.get("upload-offset"))
      if (!Number.isInteger(offset) || offset < 0) return new Response("Upload-Offset required", { status: 400 })
      if (!req.body) return new Response("empty body", { status: 400 })

      const chunk = new Uint8Array(await req.arrayBuffer())
      try {
        const { received, done } = await this.fileStore.appendChunk(upload_id, offset, chunk)
        if (!done) return this.json({ offset: received })
        const fin = await this.fileStore.finalizePending(upload_id)
        log.info("upload.finalized", { upload_id, size: fin.size, device: authResult.device.name })
        return this.json({ file_id: fin.file_id, size: fin.size, mime: fin.mime, name: fin.name })
      } catch (err: any) {
        if (err instanceof UploadNotFoundError) return new Response("upload not found", { status: 404 })
        if (err instanceof OffsetConflictError) {
          return new Response("offset conflict", { status: 409, headers: { "upload-offset": String(err.offset) } })
        }
        if (err instanceof UploadOverflowError) return new Response("chunk exceeds total size", { status: 400 })
        log.error("upload.patch_failed", { err: err?.message ?? String(err), upload_id, device: authResult.device.name })
        return new Response("file store error", { status: 500 })
      }
    }

    if (method === "HEAD" && path.startsWith("/upload/")) {
      const authResult = this.requireAuth(req)
      if (!authResult.ok) return this.authFailureResponse(authResult)
      if (!this.fileStore) return new Response(null, { status: 500 })
      const upload_id = decodeURIComponent(path.slice("/upload/".length))
      const offset = await this.fileStore.pendingOffset(upload_id)
      if (offset === null) return new Response(null, { status: 404 })
      return new Response(null, { status: 200, headers: { "upload-offset": String(offset) } })
    }

    if (method === "GET" && path === "/push/vapid-public-key") {
      if (!this.vapidPublicKey) return new Response("not configured", { status: 503 })
      return this.json({ publicKey: this.vapidPublicKey })
    }

    if (method === "POST" && path === "/push/subscribe") {
      const auth = this.requireAuth(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      if (!this.pushStore) return new Response("push not configured", { status: 503 })
      let body: any
      try { body = await req.json() } catch { return new Response("bad json", { status: 400 }) }
      const endpoint: unknown = body?.endpoint
      const p256dh: unknown = body?.keys?.p256dh
      const authKey: unknown = body?.keys?.auth
      if (typeof endpoint !== "string" || typeof p256dh !== "string" || typeof authKey !== "string") {
        return new Response("endpoint + keys.p256dh + keys.auth required", { status: 400 })
      }
      this.pushStore.upsert({
        device: auth.device.name,
        endpoint,
        keys: { p256dh, auth: authKey },
        userAgent: req.headers.get("user-agent") ?? undefined,
      })
      return this.json({ ok: true })
    }

    if (method === "DELETE" && path === "/push/subscribe") {
      const auth = this.requireAuth(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      if (!this.pushStore) return new Response("push not configured", { status: 503 })
      this.pushStore.remove(auth.device.name)
      return this.json({ ok: true })
    }

    if (method === "POST" && path === "/push/device") {
      const auth = this.requireAuth(req); if (!auth.ok) return this.authFailureResponse(auth)
      if (!this.deviceTokenStore) return new Response("push not configured", { status: 503 })
      let body: any; try { body = await req.json() } catch { return new Response("bad json", { status: 400 }) }
      const platform = body?.platform, rt = body?.routingToken, pubkey = body?.pubkey
      if ((platform !== "ios" && platform !== "android") || typeof rt !== "string" || typeof pubkey !== "string")
        return new Response("platform + routingToken + pubkey required", { status: 400 })
      this.deviceTokenStore.putNative(auth.device.name, platform, rt, pubkey)
      return this.json({ ok: true })
    }

    if (method === "DELETE" && path === "/push/device") {
      const auth = this.requireAuth(req); if (!auth.ok) return this.authFailureResponse(auth)
      if (!this.deviceTokenStore) return new Response("push not configured", { status: 503 })
      this.deviceTokenStore.remove(auth.device.name)
      return this.json({ ok: true })
    }

    if (method === "GET" && path.startsWith("/files/")) {
      const auth = this.requireAuth(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      if (!this.fileStore) return new Response("file store not mounted", { status: 500 })
      const file_id = decodeURIComponent(path.slice("/files/".length))
      const meta = await this.fileStore.get(file_id)
      if (!meta) return new Response("not found", { status: 404 })
      // Bun.file() is lazy — ENOENT only surfaces when the body streams, after
      // the handler has returned. Stat eagerly so the row-without-file case
      // produces a real HTTP 404 instead of a TCP-level error.
      if (!existsSync(meta.path)) {
        log.error("file_missing_on_disk", { file_id, path: meta.path })
        return new Response("not found", { status: 404 })
      }
      try {
        return await serveFile(req, meta)
      } catch (err: any) {
        log.error("/files served fail", { file_id, err: err?.message ?? String(err) })
        return new Response("file gone", { status: 404 })
      }
    }

    // ── Public auth routes (before the auth gate) ───────────────────────────
    // Pairing: a valid token in this one-time navigation becomes an HttpOnly
    // cookie; the token never reaches JS.
    if (method === "GET" && path === "/pair") {
      const t = url.searchParams.get("t") ?? ""
      if (t && this.store.verify(t)) {
        this.store.touch(t)
        return new Response(null, {
          status: 302,
          headers: { location: "/", "set-cookie": buildAuthCookie(t, { publicUrl: this.opts.publicUrl, proxyBaseDomain: this.opts.proxyBaseDomain }) },
        })
      }
      return new Response(
        `<!DOCTYPE html><meta charset="utf-8"><title>Pairing failed</title><body style="font-family:system-ui;background:#0a0a0a;color:#e5e5e5;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0"><p>Pairing link is invalid or expired. Generate a new one.</p></body>`,
        { status: 401, headers: { "content-type": "text/html" } },
      )
    }
    if (method === "GET" && path === "/pair.json") {
      const t = url.searchParams.get("t") ?? ""
      this.store.touch(t)
      return pairJsonResponse(t, this.store)
    }
    // Logout: clear the cookie (Origin-checked as a mutating route above).
    if (method === "POST" && path === "/logout") {
      return new Response(null, {
        status: 200,
        headers: { "set-cookie": buildClearCookie({ publicUrl: this.opts.publicUrl, proxyBaseDomain: this.opts.proxyBaseDomain }) },
      })
    }
    // Public host identity (pairing verification + adoption probe). Identity-only
    // without auth; platform/version added when authed (spec §3.3).
    if (method === "GET" && path === "/host") {
      const info = this.getHostInfo?.()
      if (!info) return this.json({ error: "host identity unavailable" }, 503)
      return this.json(buildHostBody(info, this.requireAuth(req).ok))
    }
    // Paired-status probe for the PWA (200 when the cookie is valid, else 401/429).
    // Under throttling this must NOT claim `paired: false` — the device may well be paired
    // and simply throttled, and answering "unpaired" sends clients into a re-pair flow.
    if (method === "GET" && path === "/me") {
      const a = this.requireAuth(req)
      const relayUrl = this.getRelayUrl?.() ?? this.relayUrl
      if (a.ok) return this.json({ paired: true, device: a.device.name, relayUrl }, 200)
      return this.json(a.throttled ? { error: "rate limited" } : { paired: false }, this.authFailureStatus(a))
    }

    // Native pairing claim (spec §3.4). Two paths, independent of whether a
    // claimStore exists (it always does in production):
    //   • a claimSecret is supplied → consume it → mint (add a device to any host).
    //   • no secret → trust-on-first-connect, permitted ONLY on a brand-new broker
    //     (no devices, not onboarded). This is how the FIRST device bootstraps —
    //     it must work with the claimStore present, or fresh onboarding deadlocks.
    if (method === "POST" && path === "/pair/claim") {
      const info = this.getHostInfo?.()
      const body = (await req.json().catch(() => ({}))) as Record<string, unknown>
      const name = (((body.deviceName as string | undefined) ?? (body.name as string | undefined))?.trim()) || undefined
      const mint = this.mintDeviceToken ?? ((n: string) => this.store.mint(n))
      const secret = typeof body.claimSecret === "string" ? body.claimSecret : ""

      if (secret) {
        if (!this.claimStore || !this.claimStore.consume(secret)) return new Response("unauthorized", { status: 401 })
        const minted = mint(name ?? "device")
        this.store.touch(minted.token)
        const host = info ? { hostId: info.hostId, name: info.name, platform: info.platform, version: info.version } : undefined
        return this.json({ host, deviceToken: minted.token, name: minted.name })
      }

      // Secretless: trust-on-first-connect, brand-new broker only.
      const onboarded = this.opts.getAppConfig?.()?.onboarded ?? false
      if (this.store.list().length > 0 || onboarded) {
        return this.json({ error: "already set up — use normal pairing" }, 403)
      }
      const minted = mint(name ?? "setup")
      this.store.touch(minted.token)
      return new Response(JSON.stringify({ paired: true, name: minted.name }), {
        status: 200,
        headers: {
          "content-type": "application/json",
          "set-cookie": buildAuthCookie(minted.token, { publicUrl: this.opts.publicUrl, proxyBaseDomain: this.opts.proxyBaseDomain }),
        },
      })
    }

    const auth = this.requireAuth(req)
    if (!auth.ok) return this.authFailureResponse(auth)

    // Mint a one-time pairing claim so an already-paired client (or the local
    // desktop wizard) can add ANOTHER device (spec §3.4). Authed-only.
    if (method === "POST" && path === "/pair/mint-claim") {
      if (!this.claimStore) return this.json({ error: "pairing claims unavailable" }, 503)
      const claim = this.claimStore.mintWithExpiry()
      return this.json({ claimSecret: claim.secret, expiresAt: new Date(claim.expiresAt).toISOString() })
    }

    // ── System: broker restart ──────────────────────────────────────────
    if (method === "POST" && path === "/system/restart") {
      const cp = await import("child_process")
      cp.spawn("systemctl", ["--user", "restart", "mux.service"], {
        detached: true,
        stdio: "ignore",
      })
      return this.json({ ok: true })
    }

    // ── Settings: nightly curator ───────────────────────────────────────────
    if (method === "GET" && path === "/settings/curator") {
      const cur = this.opts.getCuratorSettings?.()
      if (!cur) return this.json({ error: "curator unavailable" }, 503)
      return this.json({ ...cur, chats: this.opts.listChatIds?.() ?? [] })
    }
    if (method === "PUT" && path === "/settings/curator") {
      if (!this.opts.setCuratorSettings) return this.json({ error: "curator unavailable" }, 503)
      const body = await req.json().catch(() => ({}))
      const result = this.opts.setCuratorSettings(body)
      return this.json({ ...result, chats: this.opts.listChatIds?.() ?? [] })
    }
    if (method === "POST" && path === "/settings/curator/run-now") {
      if (!this.opts.runCuratorNow) return this.json({ error: "curator unavailable" }, 503)
      void this.opts.runCuratorNow() // fire-and-forget; run.ts guards re-entrancy
      return this.json({ ok: true })
    }

    // ── Forge: git connections + repo management ───────────────────────────
    if (path === "/forge/connections" && method === "GET") {
      const conns = this.opts.getForgeConnections?.(); if (!conns) return this.json({ error: "forge unavailable" }, 503)
      let cli = null; try { cli = await this.opts.getForgeCliStatus?.() ?? null } catch { cli = null }
      return this.json({ connections: conns, cli })
    }
    if (path === "/forge/connections" && method === "POST") {
      if (!this.opts.addForgeConnection) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try {
        return this.json(await this.opts.addForgeConnection({
          kind: String(b.kind ?? ""), host: b.host ? String(b.host) : undefined, apiBase: b.apiBase ? String(b.apiBase) : undefined,
          token: String(b.token ?? ""), source: b.source === "cli" ? "cli" : "pat", transport: b.transport === "ssh" ? "ssh" : "https",
        }))
      } catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/connections/import" && method === "POST") {
      if (!this.opts.importForgeCli) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(await this.opts.importForgeCli(String(b.kind ?? ""), b.transport === "ssh" ? "ssh" : "https")) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    {
      const fm = path.match(/^\/forge\/connections\/(.+)$/)
      if (fm && method === "DELETE") {
        if (!this.opts.removeForgeConnection) return this.json({ error: "forge unavailable" }, 503)
        this.opts.removeForgeConnection(decodeURIComponent(fm[1]!)); return this.json({ ok: true })
      }
    }
    if (path === "/forge/search" && method === "POST") {
      if (!this.opts.searchForgeRepos) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(await this.opts.searchForgeRepos(String(b.query ?? ""))) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/clone" && method === "POST") {
      if (!this.opts.cloneForgeRepo) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(await this.opts.cloneForgeRepo(String(b.connectionId ?? ""), String(b.owner ?? ""), String(b.name ?? ""))) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/create" && method === "POST") {
      if (!this.opts.createForgeRepo) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(await this.opts.createForgeRepo({ connectionId: String(b.connectionId ?? ""), name: String(b.name ?? ""), owner: b.owner ? String(b.owner) : undefined, private: b.private !== false })) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/create-local" && method === "POST") {
      if (!this.opts.createLocalRepo) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(await this.opts.createLocalRepo(String(b.name ?? ""))) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/cloned" && method === "GET") {
      const list = this.opts.listClonedRepos?.(); if (!list) return this.json({ error: "forge unavailable" }, 503)
      return this.json({ repos: list })
    }
    if (path === "/forge/cloned" && method === "DELETE") {
      if (!this.opts.removeClonedRepo) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { this.opts.removeClonedRepo(String(b.path ?? "")); return this.json({ ok: true }) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }
    if (path === "/forge/cloned/pull" && method === "POST") {
      if (!this.opts.pullClonedRepo) return this.json({ error: "forge unavailable" }, 503)
      const b = await req.json().catch(() => ({})) as any
      try { return this.json(this.opts.pullClonedRepo(String(b.path ?? ""))) }
      catch (e: any) { return this.json({ error: e?.message ?? String(e) }, 400) }
    }

    // ── Model discovery ────────────────────────────────────────────────────
    if (method === "GET" && path === "/models") {
      const agent = url.searchParams.get("agent")
      if (!agent || !isAgentKind(agent)) return this.json({ error: "invalid agent" }, 400)
      let models = this.opts.getModels?.(agent) ?? []
      if (models.length === 0 && this.opts.refreshModels) {
        await this.opts.refreshModels(agent)
        models = this.opts.getModels?.(agent) ?? []
      }
      return this.json({ models })
    }

    // ── Settings: app config ────────────────────────────────────────────────
    if (method === "GET" && path === "/settings/config") {
      const cfg = this.opts.getAppConfig?.()
      if (!cfg) return this.json({ error: "config unavailable" }, 503)
      return this.json(redactAppConfig(cfg))
    }
    if (method === "PUT" && path === "/settings/config") {
      if (!this.opts.setAppConfig) return this.json({ error: "config unavailable" }, 503)
      const body = await req.json().catch(() => ({}))
      const updated = this.opts.setAppConfig(body as Partial<import("../../core/settings/app-config").AppConfig>)
      return this.json(redactAppConfig(updated))
    }

    // ── Voice cleanup glossary ──────────────────────────────────────────────
    // The app reads/edits the project-term glossary fed to the voice-cleanup
    // prompt (and, later, the on-device recognizer). Persisted via app-config;
    // GET always returns a list (default-seeded by resolveAppConfig).
    if (method === "GET" && path === "/config/voice-glossary") {
      const cfg = this.opts.getAppConfig?.()
      if (!cfg) return this.json({ error: "config unavailable" }, 503)
      return this.json({ glossary: cfg.voiceCleanupGlossary ?? [] })
    }
    if (method === "PUT" && path === "/config/voice-glossary") {
      if (!this.opts.setAppConfig) return this.json({ error: "config unavailable" }, 503)
      const body = await req.json().catch(() => ({})) as { glossary?: unknown }
      const updated = this.opts.setAppConfig({ voiceCleanupGlossary: body.glossary as string[] | undefined })
      return this.json({ glossary: updated.voiceCleanupGlossary ?? [] })
    }

    // ── Settings: exposure ─────────────────────────────────────────────────
    if (method === "GET" && path === "/settings/exposure") {
      const e = this.opts.getExposure?.()
      if (!e) return this.json({ error: "exposure unavailable" }, 503)
      return this.json(e)
    }
    if (method === "POST" && path === "/settings/exposure/validate") {
      if (!this.opts.validateExposure) return this.json({ error: "validate unavailable" }, 503)
      return this.json(await this.opts.validateExposure())
    }

    // ── Settings: soul (identity) ───────────────────────────────────────────
    if (method === "GET" && path === "/settings/soul") {
      if (!this.opts.getSoul) return this.json({ error: "soul unavailable" }, 503)
      const content = await this.opts.getSoul()
      return new Response(content, { headers: { "content-type": "text/plain; charset=utf-8" } })
    }
    if (method === "PUT" && path === "/settings/soul") {
      if (!this.opts.setSoul) return this.json({ error: "soul unavailable" }, 503)
      const content = await req.text()
      await this.opts.setSoul(content)
      return this.json({ ok: true })
    }
    // ── Settings: web editor (LSP servers, etc.) ─────────────────────────────
    if (method === "GET" && path === "/settings/editor") {
      const view = this.opts.getEditorSettings?.()
      if (!view) return this.json({ error: "editor settings unavailable" }, 503)
      return this.json(view)
    }
    if (method === "PUT" && path === "/settings/editor") {
      if (!this.opts.setEditorSettings) return this.json({ error: "editor settings unavailable" }, 503)
      const body = await req.json().catch(() => ({}))
      return this.json(this.opts.setEditorSettings(body))
    }
    if (method === "POST" && path.match(/^\/settings\/editor\/lsp\/[^/]+\/install$/)) {
      if (!this.opts.installEditorLspServer) return this.json({ error: "editor settings unavailable" }, 503)
      const serverId = decodeURIComponent(path.split("/")[4]!)
      const result = await this.opts.installEditorLspServer(serverId)
      return this.json(result)
    }
    if (method === "POST" && path === "/settings/editor/lsp/custom") {
      if (!this.opts.addCustomEditorLspServer) return this.json({ error: "editor settings unavailable" }, 503)
      const body = await req.json().catch(() => ({}))
      const result = this.opts.addCustomEditorLspServer(body)
      return this.json(result, result.ok ? 200 : 400)
    }
    if (method === "DELETE" && path.match(/^\/settings\/editor\/lsp\/custom\/[^/]+$/)) {
      if (!this.opts.removeCustomEditorLspServer) return this.json({ error: "editor settings unavailable" }, 503)
      const serverId = decodeURIComponent(path.split("/")[5]!)
      const result = this.opts.removeCustomEditorLspServer(serverId)
      return this.json(result, result.ok ? 200 : 400)
    }

    if (method === "GET" && path === "/agents/status") {
      const statuses = this.opts.getAgentStatuses?.()
      if (!statuses) return this.json({ error: "agent detection unavailable" }, 503)
      return this.json(statuses)
    }

    if (method === "POST" && path.match(/^\/agents\/[^/]+\/login$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.startAgentLogin) return this.json({ error: "login unavailable" }, 503)
      return this.json(this.opts.startAgentLogin(kind))
    }
    if (method === "GET" && path.match(/^\/agents\/[^/]+\/login$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      const st = this.opts.getAgentLogin?.(kind)
      return st ? this.json(st) : this.json({ error: "no login in progress" }, 404)
    }
    if (method === "POST" && path.match(/^\/agents\/[^/]+\/login\/cancel$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      this.opts.cancelAgentLogin?.(kind)
      return this.json({ ok: true })
    }
    if (method === "POST" && path.match(/^\/agents\/[^/]+\/login\/code$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      const body = (await req.json().catch(() => ({}))) as { code?: string }
      if (!body.code) return this.json({ error: "code required" }, 400)
      this.opts.sendAgentLoginCode?.(kind, body.code)
      return this.json({ ok: true })
    }

    // ── agent install (broker shells out to the agent's official installer) ──
    if (method === "POST" && path.match(/^\/agents\/[^/]+\/install$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.startAgentInstall) return this.json({ error: "install unavailable" }, 503)
      if (!isAgentKind(kind)) return this.json({ error: `unknown agent: ${kind}` }, 400)
      const { job, alreadyRunning } = this.opts.startAgentInstall(kind)
      // 409 (with the live job) when one is already running, so the client can
      // just resume polling instead of starting a duplicate.
      return this.json(job, alreadyRunning ? 409 : 200)
    }
    if (method === "GET" && path.match(/^\/agents\/[^/]+\/install$/)) {
      const kind = decodeURIComponent(path.split("/")[2]!)
      const job = this.opts.getAgentInstall?.(kind)
      if (!job) return this.json({ error: "no install in progress" }, 404)
      return this.json(job)
    }

    // ── opencode provider auth (multi-provider: OAuth browser login + API key) ──
    if (method === "GET" && path === "/opencode/providers") {
      if (!this.opts.listOpenCodeProviders) return this.json({ error: "unavailable" }, 503)
      try { return this.json(await this.opts.listOpenCodeProviders()) }
      catch (e: any) { return this.json({ error: e?.message ?? "failed to list providers" }, 500) }
    }
    if (method === "POST" && path === "/opencode/auth/key") {
      const body = (await req.json().catch(() => ({}))) as { providerId?: string; key?: string }
      if (!body.providerId || !body.key) return this.json({ error: "providerId and key required" }, 400)
      try {
        await this.opts.setOpenCodeApiKey?.(body.providerId, body.key)
        const pairedOpenCodeProvider = body.providerId === "opencode"
          ? "opencode-go"
          : body.providerId === "opencode-go"
            ? "opencode"
            : undefined
        if (pairedOpenCodeProvider) {
          await this.opts.setOpenCodeApiKey?.(pairedOpenCodeProvider, body.key)
        }
        return this.json({ ok: true })
      }
      catch (e: any) { return this.json({ error: e?.message ?? "failed to save key" }, 500) }
    }
    if (method === "POST" && path === "/opencode/auth/oauth/start") {
      const body = (await req.json().catch(() => ({}))) as { providerId?: string; method?: number }
      if (!body.providerId || body.method == null) return this.json({ error: "providerId and method required" }, 400)
      try { return this.json((await this.opts.startOpenCodeOAuth?.(body.providerId, body.method)) ?? {}) }
      catch (e: any) { return this.json({ error: e?.message ?? "failed to start oauth" }, 500) }
    }
    if (method === "POST" && path === "/opencode/auth/oauth/finish") {
      const body = (await req.json().catch(() => ({}))) as { providerId?: string; method?: number; code?: string }
      if (!body.providerId || body.method == null || !body.code) return this.json({ error: "providerId, method, code required" }, 400)
      try { await this.opts.finishOpenCodeOAuth?.(body.providerId, body.method, body.code); return this.json({ ok: true }) }
      catch (e: any) { return this.json({ error: e?.message ?? "failed to finish oauth" }, 500) }
    }

    // ── Editor filesystem routes ────────────────────────────────────────────
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const fs = new FsService(workdir)
      const relPath = url.searchParams.get("path") ?? "."
      const entries = await fs.listDir(relPath)
      return this.json(entries)
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs\/read$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const fs = new FsService(workdir)
      const filePath = url.searchParams.get("path") ?? ""
      try {
        const content = await fs.readFile(filePath)
        return new Response(content, { headers: { "content-type": "text/plain; charset=utf-8" } })
      } catch (err: any) {
        const msg = err?.message ?? String(err)
        if (msg.includes("too large")) return new Response(msg, { status: 413 })
        if (msg.includes("binary")) return new Response(msg, { status: 415 })
        return new Response(msg, { status: 400 })
      }
    }
    if (method === "PUT" && path.match(/^\/sessions\/[^/]+\/fs\/write$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const fs = new FsService(workdir)
      const filePath = url.searchParams.get("path") ?? ""
      const content = await req.text()
      try {
        const result = await fs.writeFile(filePath, content)
        return this.json(result)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs\/search$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const fs = new FsService(workdir)
      const query = url.searchParams.get("q") ?? ""
      const results = await fs.searchFiles(query)
      return this.json(results)
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs\/diff$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const baseCommits = this.opts.getSessionBaseCommits?.(id) ?? {}
      const createdAt = this.opts.getSessionCreatedAt?.(id)
      const baseSpec = url.searchParams.get("base") ?? undefined
      const repos = await computeWorkdirDiff(workdir, baseCommits, createdAt, baseSpec)
      const comments = (this.opts.reviewList?.(id) ?? []).map((c) => {
        const sess = this.opts.reviewSession?.(id)
        const repoAbs = c.repo ? join(sess?.workdir ?? workdir, c.repo) : (sess?.workdir ?? workdir)
        const { currentLine, outdated } = reanchor(repoAbs, c)
        return { ...c, currentLine, outdated }
      })
      return this.json({ repos, comments })
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs\/refs$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json({ repos: listRepoRefs(workdir) })
    }

    if (method === "GET" && path === "/sessions") {
      // Watch-only enrichment: fold in agent phase, a last-message preview, and unread —
      // the signals the watch can't get over WebSocket. Reuses opts the route already has,
      // keyed exactly like the WS `subscribe` snapshot above (s.id ?? s.name).
      const reads = this.opts.getReads?.() ?? {}
      const enriched = this.opts.getSessionsSnapshot().map((s) => {
        const key = s.id ?? s.name
        const log = this.opts.getSessionLog(key)
        const extras = watchRowExtras(
          this.opts.getSessionAgentState?.(key) as { phase?: string; tool?: string } | undefined,
          log[log.length - 1] as { ts?: string; direction?: string; text?: string } | undefined,
          reads[key],
        )
        return { ...s, ...extras }
      })
      return this.json(enriched)
    }
    if (method === "GET" && path.startsWith("/sessions/") && path.endsWith("/messages")) {
      const id = decodeURIComponent(path.split("/")[2]!)
      return this.json(this.opts.getSessionLog(id))
    }
    if (method === "POST" && path.startsWith("/sessions/") && path.endsWith("/mute")) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      this.opts.setMute(id, !!body.muted)
      this.broadcastToAll({ type: "session_state", session: id, mute: !!body.muted })
      return this.json({ ok: true })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/read$/)) {
      // The watch (no WS, so no `viewing` frame) clears unread on open via this route.
      const id = decodeURIComponent(path.split("/")[2]!)
      this.opts.markRead?.(id)   // advances last_read_at + broadcasts session_read (main.ts:1121)
      return this.json({ ok: true })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/interrupt$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.interruptSession) return this.json({ error: "not configured" }, 503)
      const result = await this.opts.interruptSession(id)
      if (!result.ok) return this.json({ error: result.reason ?? "interrupt failed" }, 400)
      return this.json({ ok: true })
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/finish\/readiness$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.finishReadiness) return this.json({ error: "not configured" }, 503)
      const r = this.opts.finishReadiness(id)
      if (r && typeof r === "object" && "error" in r) return this.json(r, 404)
      return this.json(r)
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/finish$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.finishSession) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const action = body.action === "pr" || body.action === "keep" || body.action === "discard" ? body.action : "merge"
      const result = await this.opts.finishSession(id, {
        action,
        skipVerify: body.skipVerify === true,
        commitFirst: body.commitFirst === true,
        commitMessage: typeof body.commitMessage === "string" ? body.commitMessage : undefined,
        draft: body.draft === true,
        prRequiresGreen: body.prRequiresGreen === true,
        prTitle: typeof body.prTitle === "string" ? body.prTitle : undefined,
        prBody: typeof body.prBody === "string" ? body.prBody : undefined,
      })
      if (result && typeof result === "object" && "error" in result) return this.json(result, 400)
      return this.json(result)
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/git\/status$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json(remoteStatus(workdir))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/git\/fetch$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json(fetchRemote(workdir))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/git\/publish$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json(publishBranch(workdir))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/git\/push$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json(pushBranch(workdir))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/git\/pull$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json(pullBranch(workdir))
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/git\/branches$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      const snap = this.opts.getSessionsSnapshot().find((s) => s.id === id || s.name === id)
      if (snap?.session_branch) {
        // Worktree sessions are pinned — no list, and no git calls needed.
        return this.json({ inPlace: false, repoRoot: null, current: null, detachedSha: null, local: [], remote: [] })
      }
      return this.json({ inPlace: true, ...listBranches(workdir) })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/git\/switch$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      // getSessionWorkdir is UUID-only, so name-addressed requests 404 above;
      // the name arm of this find keeps the 409 guard intact if that changes.
      const snap = this.opts.getSessionsSnapshot().find((s) => s.id === id || s.name === id)
      if (snap?.session_branch) {
        return this.json({ error: "worktree sessions are pinned to their session branch" }, 409)
      }
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const name = String(body.name ?? "").trim()
      if (!name) return this.json({ error: "name required" }, 400)
      return this.json(switchBranch(workdir, name, { create: body.create === true }))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/message$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.sendUserMessage) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const text = String(body.text ?? "").trim()
      if (!text) return this.json({ error: "text required" }, 400)
      return this.json(await this.opts.sendUserMessage(id, text))
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/verify\/suggest$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const s = this.opts.verifySuggest?.(id)
      return s ? this.json(s) : this.json({ error: "not worktree-backed" }, 400)
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/verify\/save$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.verifySave) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const content = String(body.content ?? "")
      if (!content.trim()) return this.json({ error: "content required" }, 400)
      return this.json(this.opts.verifySave(id, content))
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/review\/comments$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      return this.json({ comments: this.opts.reviewList?.(id) ?? [] })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/review\/comments$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.reviewAdd) return this.json({ error: "not configured" }, 503)
      const b = await req.json().catch(() => ({})) as Record<string, unknown>
      const commentPath = String(b.path ?? "").trim()
      const anchorLine = Number(b.anchorLine)
      if (!commentPath || !Number.isFinite(anchorLine)) return this.json({ error: "path and numeric anchorLine required" }, 400)
      const sess = this.opts.reviewSession?.(id)
      // capture the blob sha for the file at comment time (best-effort)
      // use the same tree the re-anchor uses: workdir-relative sub-repo if b.repo is set
      let headBlobSha: string | undefined
      try {
        const repoAbs = b.repo ? join(String(sess?.workdir), String(b.repo)) : sess?.workdir
        if (repoAbs) headBlobSha = (await import("child_process")).execFileSync("git", ["-C", repoAbs, "hash-object", "--", commentPath], { encoding: "utf-8" }).trim()
      } catch { /* untracked/new file → no blob */ }
      const c = this.opts.reviewAdd(id, {
        repo: String(b.repo ?? ""), path: commentPath, side: (b.side === "LEFT" ? "LEFT" : "RIGHT"),
        anchorLine, anchorContext: String(b.anchorContext ?? ""),
        rangeStart: b.rangeStart != null ? Number(b.rangeStart) : undefined,
        rangeEnd: b.rangeEnd != null ? Number(b.rangeEnd) : undefined,
        diffHunkHeader: b.diffHunkHeader != null ? String(b.diffHunkHeader) : undefined,
        body: String(b.body ?? ""), author: "user", createdAt: new Date().toISOString(), headBlobSha,
      })
      return this.json(c)
    }
    if (method === "PATCH" && path.match(/^\/sessions\/[^/]+\/review\/comments\/[^/]+$/)) {
      const commentId = decodeURIComponent(path.split("/")[5]!)
      const b = await req.json().catch(() => ({})) as Record<string, unknown>
      this.opts.reviewUpdate?.(commentId, { status: b.status as never, body: b.body as never, resolvedBy: b.resolvedBy as never })
      return this.json({ ok: true })
    }
    if (method === "DELETE" && path.match(/^\/sessions\/[^/]+\/review\/comments\/[^/]+$/)) {
      const commentId = decodeURIComponent(path.split("/")[5]!)
      this.opts.reviewDelete?.(commentId)
      return this.json({ ok: true })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/review\/submit$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.reviewSubmit) return this.json({ error: "not configured" }, 503)
      return this.json(await this.opts.reviewSubmit(id))
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/models$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const info = this.opts.getSessionAgent?.(id)
      if (!info) return this.json({ error: "session not found" }, 404)
      let models = this.opts.getModels?.(info.agent) ?? []
      if (models.length === 0 && this.opts.refreshModels) {
        await this.opts.refreshModels(info.agent)
        models = this.opts.getModels?.(info.agent) ?? []
      }
      return this.json({ agent: info.agent, current: info.model, models })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/model$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const model = body.model as string | undefined
      const applyNow = body.applyNow === true
      if (!model) return this.json({ error: "model required" }, 400)
      if (!this.opts.switchModel) return this.json({ error: "not configured" }, 503)
      const result = await this.opts.switchModel(id, model, applyNow)
      if (!result.ok) return this.json({ error: result.error }, 400)
      return this.json({ ok: true, status: result.status }, result.status === "queued" ? 202 : 200)
    }
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/reasoning-levels$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const info = this.opts.getSessionReasoningLevels?.(id)
      if (!info) return this.json({ error: "session not found" }, 404)
      return this.json(info)
    }
    if (method === "GET" && path === "/reasoning-levels") {
      const url = new URL(req.url)
      const agent = url.searchParams.get("agent")
      const model = url.searchParams.get("model") || undefined
      if (!isAgentKind(agent)) {
        return this.json({ error: `agent required (${AGENT_KINDS.join("|")})` }, 400)
      }
      const info = this.opts.getReasoningLevels?.(agent, model)
      return this.json(info ?? { agent, levels: [], visible: false })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/reasoning-level$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const reasoningLevel = body.reasoningLevel as string | undefined
      const applyNow = body.applyNow === true
      if (!reasoningLevel) return this.json({ error: "reasoningLevel required" }, 400)
      if (!this.opts.switchReasoningLevel) return this.json({ error: "not configured" }, 503)
      const result = await this.opts.switchReasoningLevel(id, reasoningLevel, applyNow)
      if (!result.ok) return this.json({ error: result.error }, 400)
      return this.json({ ok: true, status: result.status }, result.status === "queued" ? 202 : 200)
    }
    if (method === "GET" && path === "/projects") {
      // A worktree-backed session lives under ~/.mux/worktrees; surface its real
      // checkout (repo_root) instead, and drop any leftover worktree paths so the
      // project picker only ever shows real repos.
      const wtRoot = worktreesRoot()
      const active = this.opts.getSessionsSnapshot().map((s) => s.repo_root ?? s.workdir)
      const archived = this.opts.listArchivedSessions?.().map((s) => s.workdir) ?? []
      const projects = uniqueKnownWorkdirs([...active, ...archived], home())
        .filter((p) => p !== wtRoot && !p.startsWith(wtRoot + sep))
        .map((p) => ({ path: p }))
      return this.json({ projects })
    }
    if (method === "POST" && path === "/paths/validate") {
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const input = body.path as string | undefined
      if (!input?.trim()) return this.json({ ok: false, error: "path required" })
      try {
        return this.json({ ok: true, path: normalizeExistingWorkdir(input, home()) })
      } catch (err: any) {
        return this.json({ ok: false, error: err?.message ?? String(err) })
      }
    }
    if (method === "GET" && path === "/repos/info") {
      const url = new URL(req.url)
      const p = url.searchParams.get("path")
      if (!p?.trim()) return this.json({ error: "path required" }, 400)
      const doFetch = url.searchParams.get("fetch") === "1"
      try {
        return this.json(getRepoInfo(normalizeExistingWorkdir(p, home()), { fetch: doFetch }))
      } catch (err: any) {
        return this.json({ isGitRepo: false, eligible: false, error: err?.message ?? String(err) })
      }
    }
    if (method === "GET" && path === "/commands/preview") {
      if (!this.opts.previewAgentCommands) return this.json({ error: "not configured" }, 503)
      const url = new URL(req.url)
      const agent = url.searchParams.get("agent")
      const workdirInput = url.searchParams.get("workdir")
      if (!isAgentKind(agent)) {
        return this.json({ error: `agent required (${AGENT_KINDS.join("|")})` }, 400)
      }
      if (!workdirInput?.trim()) return this.json({ error: "workdir required" }, 400)
      let workdir: string
      try {
        workdir = normalizeExistingWorkdir(workdirInput, home())
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
      try {
        return this.json(await this.opts.previewAgentCommands({ agent, workdir }))
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "POST" && path === "/sessions") {
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const workdir = body.workdir as string | undefined
      if (!workdir) return this.json({ error: "workdir required" }, 400)
      if (!this.opts.spawnSession) return this.json({ error: "not configured" }, 503)
      let normalizedWorkdir: string
      try {
        normalizedWorkdir = normalizeExistingWorkdir(workdir, home())
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
      try {
        const requestedAgent = body.agent
        if (requestedAgent != null && !isAgentKind(requestedAgent)) {
          return this.json({ error: `unknown agent: ${String(requestedAgent)}` }, 400)
        }
        const agent = requestedAgent == null ? undefined : requestedAgent
        const userStatus = body.userStatus as string | undefined
        if (userStatus === "draft") {
          if (!this.opts.createDraft) return this.json({ error: "not configured" }, 503)
          const draft = await this.opts.createDraft({
            name: body.name as string | undefined,
            workdir: normalizedWorkdir,
            agent,
            model: body.model as string | undefined,
            reasoningLevel: body.reasoningLevel as string | undefined,
            draftPayload: body.draftPayload as { text?: string; attachments?: unknown[] } | undefined,
          })
          return this.json(draft)
        }
        const inheritFrom = typeof body.inheritFrom === "string" && body.inheritFrom.trim()
          ? body.inheritFrom.trim()
          : undefined
        const result = await this.opts.spawnSession({
          name: body.name as string | undefined,
          workdir: normalizedWorkdir,
          agent,
          model: body.model as string | undefined,
          reasoningLevel: body.reasoningLevel as string | undefined,
          worktree: body.worktree as boolean | undefined,
          baseBranch: body.baseBranch as string | undefined,
          inheritFrom,
        })
        return this.json(result)
      } catch (err: any) {
        log.warn("session_create_failed", {
          workdir: normalizedWorkdir,
          agent: body.agent as string | undefined,
          model: body.model as string | undefined,
          err: err?.message ?? String(err),
        })
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "DELETE" && path.match(/^\/sessions\/[^/]+$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.killSession) return this.json({ error: "not configured" }, 503)
      try {
        await this.opts.killSession(id)
        return new Response(null, { status: 204 })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "PATCH" && path === "/sessions/reorder") {
      const body = await req.json().catch(() => ({})) as { orderedIds?: unknown }
      const ids = Array.isArray(body.orderedIds) ? body.orderedIds.filter((x): x is string => typeof x === "string") : []
      if (!this.opts.reorderSessions) return this.json({ error: "not configured" }, 503)
      this.opts.reorderSessions(ids)
      return this.json({ ok: true })
    }
    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/rename$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const newName = body.name as string | undefined
      if (!newName) return this.json({ error: "name required" }, 400)
      if (!this.opts.renameSession) return this.json({ error: "not configured" }, 503)
      try {
        await this.opts.renameSession(id, newName)
        return this.json({ ok: true })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    // ── Read-aloud TTS (server engines; platform is client-native) ──────────
    // Streams NDJSON audio chunks so the client can start playback on the first
    // piece while later pieces are still being synthesized.
    // Line shape: {"i":0,"n":3,"mime":"audio/mpeg","audio":"<base64>","engine":"codex"}
    if (method === "POST" && path === "/speak") {
      if (!this.opts.speak) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as { text?: string; engine?: string; lang?: string }
      if (typeof body.text !== "string" || !body.text.trim()) {
        return this.json({ error: "text required" }, 400)
      }
      try {
        const result = await this.opts.speak({
          text: body.text,
          engine: typeof body.engine === "string" ? body.engine : undefined,
          lang: typeof body.lang === "string" ? body.lang : undefined,
        })
        if ("error" in result) {
          return this.json({ error: result.error }, result.status)
        }
        const enc = new TextEncoder()
        const stream = new ReadableStream<Uint8Array>({
          async start(controller) {
            try {
              for await (const c of result.chunks) {
                const b64 = Buffer.from(c.audio).toString("base64")
                const line = JSON.stringify({
                  i: c.index,
                  n: c.total,
                  mime: c.mime || "audio/mpeg",
                  audio: b64,
                  engine: result.engine,
                }) + "\n"
                controller.enqueue(enc.encode(line))
              }
              controller.close()
            } catch (e) {
              try {
                const msg = e instanceof Error ? e.message : String(e)
                controller.enqueue(enc.encode(JSON.stringify({ error: msg }) + "\n"))
              } catch { /* ignore */ }
              controller.close()
            }
          },
        })
        return new Response(stream, {
          status: 200,
          headers: {
            "content-type": "application/x-ndjson; charset=utf-8",
            "x-tts-engine": result.engine,
            "x-tts-stream": "1",
            "cache-control": "no-store",
          },
        })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 502)
      }
    }
    if (method === "POST" && (path === "/transcribe" || path.match(/^\/sessions\/[^/]+\/transcribe$/))) {
      // The session id is OPTIONAL. `/transcribe` (id-less — e.g. the pre-spawn launcher)
      // or `/transcribe?session=<id>` or the legacy `/sessions/<id>/transcribe`. When present
      // it only enriches cleanup context (recent messages + skills); the cleanup engine/model/
      // glossary always come from global config, so a live session is never required.
      const id = path === "/transcribe"
        ? (url.searchParams.get("session")?.trim() || undefined)
        : decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.transcribe) return this.json({ error: "not configured" }, 503)
      const ctype = req.headers.get("content-type") ?? ""
      let input: { draft?: string; audioPath?: string }
      if (ctype.includes("multipart/form-data")) {
        if (!this.fileStore) return this.json({ error: "file store not mounted" }, 500)
        const form = await req.formData().catch(() => null)
        const file = form?.get("audio")
        if (!(file instanceof Blob)) return this.json({ error: "audio field required" }, 400)
        const bytes = new Uint8Array(await file.arrayBuffer())
        const { file_id } = await this.fileStore.put({ kind: "voice", mime: file.type || undefined, name: (file as any).name, session: id, origin: "web-upload", device: "web", bytes })
        const meta = await this.fileStore.get(file_id)
        input = { audioPath: meta!.path }
      } else {
        const body = await req.json().catch(() => ({})) as { draft?: string }
        if (typeof body.draft !== "string") return this.json({ error: "draft required" }, 400)
        input = { draft: body.draft }
      }
      const result = await this.opts.transcribe(id, input)
      return this.json(result)
    }
    if (method === "GET" && path === "/devices") {
      return this.json(this.store.list().map((d) => ({ name: d.name, created_at: d.created_at, last_seen_at: d.last_seen_at })))
    }
    if (method === "POST" && path === "/devices") {
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const requested = ((body.name as string | undefined) ?? "").trim()
      if (!requested) return this.json({ error: "name required" }, 400)
      const { token, name: finalName } = this.store.mint(requested)
      // A desktop host normally listens on localhost and publishes through the built-in
      // relay. Pairing links leave the host, so prefer the relay's LIVE URL; using the
      // configured localhost publicUrl here produces a perfectly valid but unusable QR.
      const pairBase = this.getRelayUrl?.() ?? this.opts.publicUrl
      const url = `${pairBase.replace(/\/$/, "")}/pair?t=${token}`
      return this.json({ url, name: finalName })
    }
    if (method === "DELETE" && path.startsWith("/devices/")) {
      const name = decodeURIComponent(path.slice("/devices/".length))
      const ok = this.store.revoke(name)
      return new Response(null, { status: ok ? 204 : 404 })
    }

    if (method === "GET" && path === "/usage") {
      const { fetchAllUsage } = await import("../../core/usage/index")
      const data = await fetchAllUsage()
      return this.json(data)
    }

    if (method === "POST" && path === "/usage/codex/reset") {
      const { redeemCodexReset, fetchCodexUsage } = await import("../../core/usage/index")
      try {
        const result = await redeemCodexReset()
        const codex = await fetchCodexUsage().catch(() => null) // best-effort refresh
        return this.json({ ...result, codex })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 502)
      }
    }

    if (method === "GET" && path === "/proxies") {
      const proxies = this.opts.listProxies?.() ?? []
      return this.json(proxies)
    }
    if (method === "POST" && path === "/proxies") {
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const sessionName = body.sessionName as string | undefined
      const port = body.port as number | undefined
      const domain = body.domain as string | undefined
      if (!sessionName || !port) return this.json({ error: "sessionName and port required" }, 400)
      if (!this.opts.createProxy) return this.json({ error: "not configured" }, 503)
      try {
        const result = this.opts.createProxy({ sessionName, port, domain: domain || undefined })
        return this.json(result)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "PATCH" && path.match(/^\/proxies\/[^/]+$/)) {
      const domain = decodeURIComponent(path.slice("/proxies/".length))
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      if (typeof body.isPublic !== "boolean") return this.json({ error: "isPublic (boolean) required" }, 400)
      if (!this.opts.updateProxy) return this.json({ error: "not configured" }, 503)
      try {
        const updated = this.opts.updateProxy(domain, body.isPublic)
        return this.json(updated)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "DELETE" && path.match(/^\/proxies\/[^/]+$/)) {
      const domain = decodeURIComponent(path.slice("/proxies/".length))
      if (!this.opts.removeProxy) return this.json({ error: "not configured" }, 503)
      try {
        this.opts.removeProxy(domain)
        return new Response(null, { status: 204 })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "GET" && path === "/displays") {
      const a = this.requireAuth(req); if (!a.ok) return this.authFailureResponse(a)
      return this.json(this.opts.listDisplays?.() ?? [])
    }
    if (method === "POST" && path === "/displays") {
      const a = this.requireAuth(req); if (!a.ok) return this.authFailureResponse(a)
      if (!this.opts.startDisplay) return this.json({ error: "not configured" }, 503)
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      try {
        const info = await this.opts.startDisplay({
          sessionName: (body.sessionName as string) || "web",
          provider: body.provider as string | undefined,
          device: body.device as string | undefined,
          width: body.width as number | undefined,
          height: body.height as number | undefined,
        })
        return this.json(info)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 400)
      }
    }
    if (method === "DELETE" && path.match(/^\/displays\/[^/]+$/)) {
      const a = this.requireAuth(req); if (!a.ok) return this.authFailureResponse(a)
      if (!this.opts.stopDisplay) return this.json({ error: "not configured" }, 503)
      const id = decodeURIComponent(path.slice("/displays/".length))
      try { await this.opts.stopDisplay(id); return new Response(null, { status: 204 }) }
      catch (err: any) { return this.json({ error: err?.message ?? String(err) }, 400) }
    }

    if (method === "GET" && path === "/archived-sessions") {
      const sessions = this.opts.listArchivedSessions?.() ?? []
      return this.json(sessions)
    }

    if (method === "POST" && path.match(/^\/sessions\/[^/]+\/resume$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      if (!this.opts.resumeFromArchive) return this.json({ error: "not configured" }, 503)
      try {
        const result = await this.opts.resumeFromArchive(id)
        if (!result.ok) return this.json({ error: result.error }, 400)
        return this.json({ ok: true, name: result.name })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }

    // ── PA CRUD routes ──────────────────────────────────────────────────────
    if (method === "POST" && path === "/api/pas") {
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const name = body.name as string | undefined
      if (!name || !name.trim()) return this.json({ error: "name required" }, 400)
      if (!this.opts.spawnPA) return this.json({ error: "not configured" }, 503)
      const agent = body.agent as AgentKind | undefined
      if (agent != null && !isAgentKind(agent)) {
        return this.json({ error: `unknown agent: ${String(agent)}` }, 400)
      }
      const workdir = join(home(), ".mux", "workspace", name.trim())
      try {
        mkdirSync(workdir, { recursive: true })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
      const focusText = body.focusText as string | undefined
      if (focusText != null) {
        try { writeFileSync(join(workdir, "focus.md"), focusText, "utf8") } catch {}
      }
      try {
        const result = await this.opts.spawnPA({
          name: name.trim(),
          workdir,
          agent,
          model: body.model as string | undefined,
          reasoningLevel: body.reasoningLevel as string | undefined,
        })
        return this.json(result)
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }
    if (method === "GET" && path === "/api/pas") {
      const pas = this.opts.listPAs?.() ?? this.opts.getSessionsSnapshot?.().filter((s) => s.role === "personal_assistant") ?? []
      return this.json({ pas })
    }
    if (method === "PATCH" && path.match(/^\/api\/pas\/[^/]+$/)) {
      const name = decodeURIComponent(path.slice("/api/pas/".length))
      const body = await req.json().catch(() => ({})) as Record<string, unknown>
      const patch: { model?: string; reasoningLevel?: string } = {}
      if (typeof body.model === "string") patch.model = body.model
      if (typeof body.reasoningLevel === "string") patch.reasoningLevel = body.reasoningLevel
      if (!this.opts.updatePA) return this.json({ error: "not configured" }, 503)
      try {
        const result = await this.opts.updatePA(name, patch)
        if (!result.ok) return this.json({ error: result.error ?? "update failed" }, 400)
        return this.json({ ok: true })
      } catch (err: any) {
        return this.json({ error: err?.message ?? String(err) }, 500)
      }
    }

    // ── Web terminals ────────────────────────────────────────────────────────
    // List a session's persisted terminals (source of truth: the muxterm tmux
    // server) so the PWA can rebuild its tab strip across reloads.
    if (method === "GET" && path === "/api/term/list") {
      const session = url.searchParams.get("session") ?? ""
      if (!session || !this.opts.getSessionWorkdir?.(session)) return this.json({ error: "session not found" }, 404)
      const terminals = (await this.opts.terminalManager?.listForSession(session)) ?? []
      return this.json({ terminals })
    }
    // Explicitly destroy one terminal (its tmux session + any viewers).
    if (method === "POST" && path === "/api/term/close") {
      const body = (await req.json().catch(() => ({}))) as Record<string, unknown>
      const session = typeof body.session === "string" ? body.session : ""
      const terminal = typeof body.terminal === "string" ? body.terminal : ""
      if (!session || !terminal) return this.json({ error: "session and terminal required" }, 400)
      await this.opts.terminalManager?.close(session, terminal)
      return this.json({ ok: true })
    }

    // ── In-app updater ──────────────────────────────────────────────────────
    // (auth already enforced by the gate above; mirrors the /api/pas routes).
    // When MUX_UPDATE_CHECK=0 the broker passes updateChecker=null: status still
    // returns a renderable shape (disabled:true) rather than 404, and run reports
    // it can't self-update with the mode-specific instruction text.
    if (method === "GET" && path === "/api/update/status") {
      const checker = this.opts.updateChecker
      if (!checker) {
        return this.json({
          current: BUILD_VERSION,
          commit: BUILD_COMMIT,
          mode: detectUpdateMode(),
          updateAvailable: false,
          latest: null,
          notesUrl: null,
          state: "idle",
          lastChecked: null,
          lastError: null,
          disabled: true,
        })
      }
      return this.json(checker.status())
    }
    if (method === "POST" && path === "/api/update/run") {
      return this.handleUpdateRun()
    }

    if (method === "POST" && path === "/client-logs") {
      const auth = this.requireAuth(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      let body: any
      try { body = await req.json() } catch { return this.json({ error: "invalid json" }, 400) }
      this.ingestClientLogs(auth.device.name, body?.entries ?? [], body?.meta)
      return this.json({ ok: true, count: (body?.entries ?? []).length })
    }

    if (method === "GET" && path === "/debug/client-logs") {
      const auth = this.requireAuth(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const category = url.searchParams.get("category") ?? undefined
      const limit = Math.min(500, Math.max(1, Number(url.searchParams.get("limit") ?? "200") || 200))
      let entries = [...this.clientLogRing]
      if (category) entries = entries.filter((e) => e.category === category)
      return this.json({ entries: entries.slice(-limit) })
    }

    return new Response("not found", { status: 404 })
  }
}

async function serveFile(req: Request, meta: { path: string; mime?: string; name?: string; size: number }): Promise<Response> {
  const file = Bun.file(meta.path)
  const headers: Record<string, string> = {
    "content-type": meta.mime ?? "application/octet-stream",
    "cache-control": "private, max-age=31536000, immutable",
    "accept-ranges": "bytes",
  }
  if (meta.name) headers["content-disposition"] = `inline; filename="${meta.name.replace(/"/g, "")}"`

  const rangeHeader = req.headers.get("range")
  if (rangeHeader) {
    const m = /^bytes=(\d+)-(\d+)?$/.exec(rangeHeader)
    if (!m) return new Response("invalid range", { status: 416 })
    const start = parseInt(m[1]!, 10)
    const end = m[2] != null ? parseInt(m[2], 10) : meta.size - 1
    if (isNaN(start) || isNaN(end) || start > end || end >= meta.size) {
      return new Response("range not satisfiable", { status: 416, headers: { "content-range": `bytes */${meta.size}` } })
    }
    headers["content-range"] = `bytes ${start}-${end}/${meta.size}`
    headers["content-length"] = String(end - start + 1)
    return new Response(file.slice(start, end + 1), { status: 206, headers })
  }

  headers["content-length"] = String(meta.size)
  return new Response(file, { headers })
}
