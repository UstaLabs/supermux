import { EventEmitter } from "events"
import { readFile } from "node:fs/promises"
import type { AgentAdapter, AgentKind, BrokerRequest, InboundMeta, RequestAnswerInput } from "../types"
import { mapPendingRequest, toCoreAnswer } from "./request-map"
import { inboundAttachments, isImageAttachment } from "../attachment-meta"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { makeLogger } from "../../../shared/log"
import type { CodexUsage } from "../../usage/index"
import { codexUsageFromRateLimits } from "../../usage/local"
import { createNormalizedBridge } from "./normalized-bridge"
import { createNormalizedActivity } from "./normalized-activity"
import { CODEX_FAST_SERVICE_TIER } from "../../models/fast-mode"
import { isPermissionMode, permissionsFor } from "../permission-modes"
import { detachCodexRuntimeAdapter } from "../codex/core-host"
import type { SubagentMessageDelivery } from "../../../../packages/supermux-core/src/types.js"
import type { ActivityEvent } from "../claude/activity-event"
import type {
  Completion,
  ContentBlock,
  Core,
  CoreEvent,
  HostHandle,
  InterruptResult,
  PermissionsSpec,
  Session,
  SessionConfiguration,
  SessionRecord,
  SessionState,
} from "../../../../packages/supermux-core/src/index.js"

const DEFAULT_STALL_MS = 90_000
/** How long input waits for a core-side reopen (reload / account switch) before it is tried anyway. */
const REOPEN_WAIT_MS = 5 * 60_000
const log = makeLogger("agents/core-adapter")

type ConfigMode = "configure" | "restart" | "unsupported"

/** A live configuration change; a missing key is left alone. */
export type ConfigurationPatch = { model?: string; effort?: string; fast?: boolean }

export type CoreAdapterProfile = {
  kind: AgentKind
  /** How a model/effort/fast-mode change is applied. */
  configuration: { model: ConfigMode; effort: ConfigMode; fast: ConfigMode }
  /** Whether permission mode can be switched live (`live` uses Session.setPermissions). */
  permissionMode: "live" | "unsupported"
  /** Attachment rendering. */
  attachments: "image-block" | "path-in-prompt"
  /** Idle-send vs mid-turn: queue through Core, or steer when the session is already running. */
  sendWhenBusy: "queue" | "steer"
  /** What HostHandle.start receives as configuration. */
  startConfiguration: "desired" | "empty"
}

export const GROK_CORE_PROFILE: CoreAdapterProfile = {
  kind: "grok",
  configuration: { model: "configure", effort: "configure", fast: "unsupported" },
  permissionMode: "live",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "desired",
}

export const ANTIGRAVITY_CORE_PROFILE: CoreAdapterProfile = {
  kind: "antigravity",
  configuration: { model: "configure", effort: "unsupported", fast: "unsupported" }, // effort is baked into the model id; configure restarts agy when idle
  permissionMode: "live",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "desired",
}

export const CODEX_CORE_PROFILE: CoreAdapterProfile = {
  kind: "codex",
  configuration: { model: "configure", effort: "configure", fast: "configure" },
  permissionMode: "live",
  attachments: "image-block",
  sendWhenBusy: "steer",
  startConfiguration: "desired",
}

export const OPENCODE_CORE_PROFILE: CoreAdapterProfile = {
  kind: "opencode",
  configuration: { model: "restart", effort: "unsupported", fast: "unsupported" },
  permissionMode: "live",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "empty",
}

export const CURSOR_CORE_PROFILE: CoreAdapterProfile = {
  kind: "cursor",
  configuration: { model: "restart", effort: "unsupported", fast: "unsupported" },
  permissionMode: "live",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "empty",
}

export const CLAUDE_CORE_PROFILE: CoreAdapterProfile = {
  kind: "claude",
  configuration: { model: "restart", effort: "restart", fast: "restart" },
  permissionMode: "live",
  attachments: "image-block",
  sendWhenBusy: "queue",
  startConfiguration: "empty",
}

export const CORE_ADAPTER_PROFILES = {
  grok: GROK_CORE_PROFILE,
  codex: CODEX_CORE_PROFILE,
  opencode: OPENCODE_CORE_PROFILE,
  cursor: CURSOR_CORE_PROFILE,
  claude: CLAUDE_CORE_PROFILE,
  antigravity: ANTIGRAVITY_CORE_PROFILE,
} as const

export type CoreAdapterOpts = {
  handle: HostHandle
  /** Re-register the host entry for a relaunch with the adapter's current configuration. */
  reregister: (fields: { model?: string; effort?: string; fast: boolean; permissionMode?: string }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  initialSessionId?: string
  persistSessionId: (nativeId: string) => Promise<void>
  model?: string
  effort?: string
  fast?: boolean
  permissionMode?: string
  resolveAttachment?: (file_id: string) => Promise<string>
  stallTimeoutMs?: number
  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null
}

type JsonRpcLike = {
  request<T = unknown>(method: string, params: unknown): Promise<T>
  onNotification(h: (n: { method: string; params: unknown }) => void): void
}

/** A running turn: core turn ids (`snapshot().turnId`) are per Session, so both name it. */
type RunningTurn = { session: Session; turnId: string }

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function once<T extends (...args: never[]) => void>(fn: T): T {
  let done = false
  return ((...args: never[]) => {
    if (done) return
    done = true
    fn(...args)
  }) as T
}

function isSessionBusy(err: unknown): boolean {
  return typeof err === "object" && err !== null && "code" in err && (err as { code: unknown }).code === "session_busy"
}

/**
 * The agent's answer when a resume names a conversation it never saved (killed before its first
 * turn, say): the reason, or undefined for any other failure. Only these exact signals count, so an
 * auth, network or timeout failure never drops a conversation:
 * - Claude Code: "No conversation found with session ID: <id>";
 * - Codex app-server, thread/resume: "no rollout found for thread id <id>";
 * - ACP session/load (Cursor): JSON-RPC -32602 "Invalid params" whose data.message is
 *   `Session "<id>" not found` (the SDK's RequestError keeps code and data).
 * The core or the host may wrap the open's error, so `cause` and AggregateError `errors` are searched too.
 */
export function missingConversation(err: unknown): string | undefined {
  const pending: unknown[] = [err]
  const seen = new Set<unknown>()
  while (pending.length) {
    const e = pending.pop()
    if (typeof e !== "object" || e === null || seen.has(e)) continue
    seen.add(e)
    const message = e instanceof Error ? e.message : ""
    const known = /No conversation found with session ID: \S+/.exec(message) ?? /no rollout found for thread id \S+/.exec(message)
    if (known) return known[0]
    const { code, data } = e as { code?: unknown; data?: { message?: unknown } | null }
    const detail = typeof data?.message === "string" ? data.message : undefined
    if (code === -32602 && detail && /^Session "[^"]+" not found$/.test(detail)) return detail
    if ("cause" in e) pending.push((e as { cause?: unknown }).cause)
    if (e instanceof AggregateError) pending.push(...e.errors)
  }
  return undefined
}

function withAttachedFile(prompt: string, label: string): string {
  return prompt ? `${prompt}\n\n[Attached file: ${label}]` : `[Attached file: ${label}]`
}

function guessImageMime(path: string, mime?: string): string {
  if (mime && mime.startsWith("image/")) return mime
  const lower = path.toLowerCase()
  if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg"
  if (lower.endsWith(".gif")) return "image/gif"
  if (lower.endsWith(".webp")) return "image/webp"
  return "image/png"
}

/** One broker-facing adapter for every Core-backed agent. Differences live in CoreAdapterProfile. */
export class CoreAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind
  readonly sessionName: string
  readonly workdir: string
  readonly id: string
  readonly profile: CoreAdapterProfile

  availableModels: { modelId: string }[] = []
  availableCommands: { name: string; description?: string; _meta?: { scope?: string; path?: string } }[] = []

  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null

  private handle: HostHandle
  private readonly reregister: CoreAdapterOpts["reregister"]
  private readonly core: Core
  private readonly persistSessionId: (nativeId: string) => Promise<void>
  private readonly resolveAttachment?: (file_id: string) => Promise<string>
  private readonly stallTimeoutMs: number
  /** The native conversation to resume at first open; cleared when it turns out never saved. */
  private initialSessionId?: string
  private nativeSessionId?: string

  private _model?: string
  private _effort?: string
  private _fast: boolean
  private _permissionMode?: string
  private session?: Session
  private unsubscribe?: () => void
  private startEpoch = 0
  private inputEpoch = 0
  private stopped = false
  private starting?: Promise<void>
  private restarting?: Promise<void>
  private stopping?: Promise<void>
  private sendTail: Promise<void> = Promise.resolve()
  /** An interrupt paused the session's queue: the next input continues it (refused while the turn still runs). */
  private continueAfterInterrupt = false
  /** Inbox interrupts waiting for the turn to end (interruptKeepingQueue): no restart meanwhile. */
  private inboxInterruptWaits = 0
  /** The input epoch of the latest such wait: a Stop since then drops the waiting input, so it no longer holds a restart back. */
  private inboxInterruptEpoch = 0
  private turnActive = false
  private stallTimer?: ReturnType<typeof setTimeout>
  private failureEmitted = false
  private runtimeRequest?: (method: string, params: unknown) => Promise<unknown>
  private readonly activity
  private readonly bridge

  constructor(profile: CoreAdapterProfile, opts: CoreAdapterOpts) {
    super()
    this.profile = profile
    this.kind = profile.kind
    this.handle = opts.handle
    this.reregister = opts.reregister
    this.core = opts.core
    this.id = opts.id
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.persistSessionId = opts.persistSessionId
    this.initialSessionId = opts.initialSessionId
    this.nativeSessionId = opts.initialSessionId
    this._model = opts.model
    this._effort = opts.effort
    this._fast = opts.fast === true
    this._permissionMode = opts.permissionMode
    this.resolveAttachment = opts.resolveAttachment
    this.stallTimeoutMs = opts.stallTimeoutMs ?? DEFAULT_STALL_MS
    this.onUsageUpdate = opts.onUsageUpdate
    this.getPrevUsage = opts.getPrevUsage
    this.activity = createNormalizedActivity({ workdir: opts.workdir })
    this.bridge = createNormalizedBridge({
      agent: profile.kind,
      emit: (event) => {
        if (event.kind === "error") this.surfaceFailure(event.error, { completeTurn: false })
        else this.emit(event.kind, event)
      },
      onCommands: (commands) => {
        this.availableCommands = commands
        this.emit("commands-update", { kind: "commands-update" })
      },
      onUsage: (rateLimits) => {
        const data = codexUsageFromRateLimits(rateLimits, this.getPrevUsage?.() ?? null)
        if (data) this.onUsageUpdate?.(data)
      },
    })
  }

  get model(): string | undefined { return this._model }
  get effort(): string | undefined { return this._effort }
  get fast(): boolean { return this._fast }
  get permissionMode(): string | undefined { return this._permissionMode }

  /**
   * Switch this session to another account of its agent (manual). Core shuts the live process
   * down and reopens the same native session with the account's credentials — a real restart, so
   * a keeper-reattached process never keeps its old environment. Refused mid-turn.
   */
  async setAccount(account: string): Promise<void> {
    if (this.restarting) await this.restarting.catch(() => {})
    const session = this.requireSession()
    const state = session.snapshot().state
    if (state === "running" || state === "interrupting") {
      throw new CoreError("session_busy", `${this.kind} session is busy`)
    }
    const generation = this.startEpoch
    this.restarting = (async () => {
      const permissions = this.launchPermissions()
      const next = await this.core.sessions.resume(this.id, { account, ...(permissions ? { permissions } : {}) })
      if (this.stopped || generation !== this.startEpoch) return
      this.session = next
    })()
    try { await this.restarting } finally { this.restarting = undefined }
  }

  /** True while the Core session is open, a config restart is in flight, or the core is
   *  replacing the session's process itself (a context / host-tool reload, an account switch):
   *  the native process changing under a restart is not the agent dying. */
  isAlive(): boolean {
    if (this.restarting || this.core.sessions.reopening(this.id)) return true
    const state = this.current()?.snapshot().state
    return state !== undefined && state !== "closed" && state !== "failed"
  }

  sessionSnapshotState(): SessionState | undefined {
    return this.current()?.snapshot().state
  }

  /**
   * The Session this adapter drives. When the core replaced it on its own (a context or host-tool
   * reload, an account switch: the old Session is closed and core.sessions.live(id) is the new
   * one), switch to the new one. The adapter's own start / restart / stop own `session` meanwhile.
   */
  private current(): Session | undefined {
    const session = this.session
    if (!session || this.stopped || this.restarting || this.starting) return session
    const state = session.snapshot().state
    if (state !== "closed" && state !== "failed" && state !== "closing") return session
    const live = this.core.sessions.live(this.id)
    if (live && live !== session) this.session = live
    return this.session
  }

  /** Waits while the core is replacing the session's process (bounded), then follows it. */
  private async settleReopen(): Promise<void> {
    for (let waited = 0; this.core.sessions.reopening(this.id) && waited < REOPEN_WAIT_MS; waited += 25) {
      await new Promise((resolve) => setTimeout(resolve, 25))
    }
    this.current()
  }

  turnIsRunning(): boolean {
    return this.turnActive
  }

  openRequests(): BrokerRequest[] {
    const session = this.current()
    if (!session) return []
    return session.requests.list().map((pending) => this.bridge.decorateRequest(mapPendingRequest(pending)))
  }

  /**
   * Send the user's text to one subagent. Direct runtimes (Codex, Grok, OpenCode) deliver it to
   * the child now; relay runtimes (Claude, Cursor) queue a parent turn that forwards it, whose
   * completion is tracked like any other send. Throws the library's `unsupported_operation`.
   */
  async messageSubagent(subagentId: string, text: string): Promise<{ via: "direct" | "relay"; delivery: Promise<SubagentMessageDelivery> }> {
    const session = this.requireSession()
    const epoch = this.inputEpoch
    if (this.continueAfterInterrupt) {
      session.pending.continue()
      this.continueAfterInterrupt = false
    }
    // A relayed message shows in the subagent's thread once the parent actually forwarded it
    // (its resumed prompt, or the forwarding tool's success) — never when the agent refused it.
    const relay = { subagentId, text: text.trim(), logged: false }
    this.pendingRelays.push(relay)
    let result: Awaited<ReturnType<Session["messageSubagent"]>>
    try {
      result = await session.messageSubagent(subagentId, [{ type: "text", text }], { whenBusy: "queue" })
    } catch (err) {
      this.dropRelay(relay)
      throw err
    }
    if (result.via === "direct") {
      this.dropRelay(relay)
      this.emitMessageRow(subagentId, "to", text, "user")
      return { via: "direct", delivery: result.delivery }
    }
    void result.receipt.completed
      .then((completion) => {
        if (!this.stopped && epoch === this.inputEpoch) this.handleCompletion(completion)
      })
      .catch(() => {})
    const delivery = result.delivery.then((outcome) => {
      if (outcome.status === "delivered" && !relay.logged) {
        relay.logged = true
        this.emitMessageRow(subagentId, "to", text, "user")
        // Keep it a little: the resume that carries this text may still be on its way.
        setTimeout(() => this.dropRelay(relay), 60_000).unref?.()
      } else this.dropRelay(relay)
      return outcome
    })
    return { via: "relay", delivery }
  }

  private readonly pendingRelays: { subagentId: string; text: string; logged: boolean }[] = []

  private dropRelay(relay: { subagentId: string; text: string; logged: boolean }): void {
    const index = this.pendingRelays.indexOf(relay)
    if (index >= 0) this.pendingRelays.splice(index, 1)
  }

  /** One `subagent_message` row of a subagent's conversation. */
  private emitMessageRow(subagentId: string, direction: "to" | "from", text: string, sender?: "user" | "parent"): void {
    const body = text.trim()
    if (!body) return
    const first = body.split("\n").find((line) => line.trim()) ?? body
    const row: ActivityEvent = {
      ts: new Date().toISOString(),
      kind: "subagent_message",
      title: first.length > 120 ? `${first.slice(0, 119)}…` : first,
      text: body,
      direction,
      ...(sender ? { sender } : {}),
      subagentId,
    }
    this.emit("activity", { kind: "activity", events: [row] })
  }

  /** Subagents whose current run already produced a reply row (else its result is the reply). */
  private readonly repliedThisRun = new Set<string>()

  /** The subagent's conversation as it happens: prompts in, its own messages out. */
  private threadRows(env: { kind: string; subagentId?: string; replay?: boolean; [key: string]: unknown }): void {
    if (env.replay === true || !env.subagentId) return
    const id = env.subagentId
    if (env.kind === "assistant-message") {
      if (String(env.text ?? "").trim()) this.repliedThisRun.add(id)
      this.emitMessageRow(id, "from", String(env.text ?? ""))
      return
    }
    if (env.kind !== "subagent") return
    const phase = env.phase
    if (phase === "completed" || phase === "failed" || phase === "cancelled") {
      // Agents that stream no child text (Claude foreground, OpenCode, Cursor without the
      // extension) answer only in the result: that IS the reply of this run.
      if (!this.repliedThisRun.has(id) && typeof env.result === "string" && env.result.trim()) this.emitMessageRow(id, "from", env.result)
      this.repliedThisRun.delete(id)
      return
    }
    if (phase !== "started" && phase !== "resumed") return
    this.repliedThisRun.delete(id)
    if (typeof env.prompt !== "string" || !env.prompt.trim()) return
    if (phase === "started") {
      this.emitMessageRow(id, "to", env.prompt, "parent")
      return
    }
    // A resume that carries the user's relayed text is the user's message, not the parent's
    // (logged once: here, or already when the parent's forwarding tool reported success).
    const relay = this.pendingRelays.find((r) => r.subagentId === id && r.text === env.prompt!.toString().trim())
    if (relay?.logged) { this.dropRelay(relay); return }
    if (relay) relay.logged = true
    this.emitMessageRow(id, "to", env.prompt, relay ? "user" : "parent")
  }

  async stopSubagent(subagentId: string): Promise<void> {
    await this.requireSession().stopSubagent(subagentId)
  }

  async respondRequest(requestId: string, answer: RequestAnswerInput): Promise<void> {
    await this.requireSession().requests.respond(requestId, toCoreAnswer(answer))
  }

  attachRuntimeRequest(request: (method: string, params: unknown) => Promise<unknown>): void {
    this.runtimeRequest = request
  }

  get rpc(): JsonRpcLike {
    return {
      request: async <T = unknown>(method: string, params: unknown): Promise<T> => {
        if (!this.runtimeRequest) throw new Error(`${this.kind} rpc unavailable`)
        return this.runtimeRequest(method, params) as Promise<T>
      },
      onNotification: () => {},
    }
  }

  async setPermissionMode(id: string): Promise<{ applied: "now" | "next-turn" }> {
    if (this.profile.permissionMode === "unsupported") {
      throw new CoreError("unsupported_operation", `${this.kind} sessions cannot set permission mode`)
    }
    const session = this.requireSession()
    if (id === this._permissionMode) return { applied: "now" }
    const spec = permissionsFor(this.kind, id)
    const result = await session.setPermissions(spec)
    this._permissionMode = id
    return result
  }

  async setConfiguration(patch: ConfigurationPatch): Promise<void> {
    const modes = this.profile.configuration
    const wanted: ConfigurationPatch = {}
    if ("model" in patch && modes.model !== "unsupported") wanted.model = patch.model
    if ("effort" in patch && modes.effort !== "unsupported") wanted.effort = patch.effort
    if ("fast" in patch && modes.fast !== "unsupported") wanted.fast = patch.fast === true

    const changed = ("model" in wanted && wanted.model !== this._model)
      || ("effort" in wanted && wanted.effort !== this._effort)
      || ("fast" in wanted && wanted.fast !== this._fast)
    if (!changed) return

    const keys = Object.keys(wanted) as (keyof ConfigurationPatch)[]
    if (keys.some((key) => modes[key] === "restart")) {
      // Refused mid-turn. After an unconfirmed interrupt ("interrupting": the turn still runs) a
      // restart is how the turn ends (after an unconfirmed Stop), except while inbox input waits for
      // that turn to end (interruptKeepingQueue): the restart would close the session under it. A
      // Stop since then drops that input (send's assertLive), so it no longer holds the restart back.
      const session = this.requireSession()
      const state = session.snapshot().state
      const inboxWaiting = this.inboxInterruptWaits > 0 && this.inboxInterruptEpoch === this.inputEpoch
      if (state === "running" || (state === "interrupting" && inboxWaiting)) {
        throw new CoreError("session_busy", `${this.kind} session is busy`)
      }
      await this.restartNative(wanted)
      return
    }

    const session = this.requireSession()
    const previous = { model: this._model, effort: this._effort, fast: this._fast }
    const requested: SessionConfiguration = {}
    if ("model" in wanted) requested.model = wanted.model
    if ("effort" in wanted) requested.reasoningEffort = wanted.effort
    if ("fast" in wanted) requested.serviceTier = wanted.fast ? CODEX_FAST_SERVICE_TIER : undefined
    const generation = this.startEpoch
    try {
      await session.configure(requested)
      if (this.stopped || generation !== this.startEpoch) return
      if ("model" in wanted) this._model = wanted.model
      if ("effort" in wanted) this._effort = wanted.effort
      if ("fast" in wanted) this._fast = wanted.fast === true
    } catch (err) {
      if (!this.stopped && generation === this.startEpoch) {
        this._model = previous.model
        this._effort = previous.effort
        this._fast = previous.fast
      }
      throw asError(err)
    }
  }

  async setEffort(effort: string | undefined): Promise<void> {
    if (effort === this._effort && this.session) return
    await this.setConfiguration({ effort })
  }

  async start(): Promise<void> {
    for (;;) {
      if (this.stopping) {
        await this.stopping.catch(() => {})
        continue
      }
      if (this.starting) {
        if (this.stopped) {
          await this.starting.catch(() => {})
          continue
        }
        return this.starting
      }
      if (this.session && this.session.snapshot().state !== "closed" && this.session.snapshot().state !== "failed") {
        return
      }
      this.stopped = false
      const epoch = ++this.startEpoch
      const work = this.openViaHandle(epoch)
      this.starting = work
      try {
        await work
        return
      } finally {
        if (this.starting === work) this.starting = undefined
      }
    }
  }

  /**
   * Opens the session: the first time (as start()), or again after its agent crashed. The host keeps
   * a failed or closed session's slot until that session is closed, and refuses a new start
   * meanwhile (`already_live`), so revive() closes it first and reopens the native conversation. A
   * live session is left alone, and so is an adapter the user stopped (start() refuses it).
   */
  async resume(): Promise<void> {
    for (;;) {
      if (this.restarting) {
        await this.restarting.catch(() => {})
        continue
      }
      const session = this.stopped || this.starting || this.stopping ? undefined : this.current()
      const state = session?.snapshot().state
      if (!session || (state !== "failed" && state !== "closed") || this.core.sessions.reopening(this.id)) return this.start()
      return this.revive(session)
    }
  }

  /**
   * Closes a failed or closed session for good, then opens its native conversation again under a
   * new host registration, like restartNative. A close that fails leaves the host slot in
   * failed-cleanup, which the new registration's start retries first. A stop() meanwhile wins.
   */
  private async revive(session: Session): Promise<void> {
    const generation = this.startEpoch
    this.nativeSessionId = session.snapshot().agentSessionId
    this.restarting = (async () => {
      await this.handle.stop({ mode: "shutdown" }).catch(() => {})
      this.session = undefined
      this.unsubscribe?.()
      this.unsubscribe = undefined
      if (this.stopped || generation !== this.startEpoch) return
      this.handle = this.reregister({ model: this._model, effort: this._effort, fast: this._fast, permissionMode: this._permissionMode })
      await this.start()
    })()
    try { await this.restarting } finally { this.restarting = undefined }
  }

  async stop(): Promise<void> {
    this.stopped = true
    this.startEpoch++
    this.inputEpoch++
    this.disarmStall()
    this.continueAfterInterrupt = false
    if (this.stopping) {
      try {
        await this.stopping
        if (!this.session || this.session.snapshot().state === "closed") return
      } catch {
        // Previous stop rejected (failed close). Retry close while retaining ownership.
      }
    }
    const work = this.performStop()
    this.stopping = work
    try {
      await work
    } finally {
      if (this.stopping === work) this.stopping = undefined
    }
  }

  private async performStop(): Promise<void> {
    await this.handle.stop({ mode: "shutdown" })
    this.session = undefined
    this.unsubscribe?.()
    this.unsubscribe = undefined
    this.runtimeRequest = undefined
    if (this.kind === "codex") detachCodexRuntimeAdapter(this.id)
    this.completeStoppedTurn()
  }

  private completeStoppedTurn(): void {
    this.bridge.flush()
    if (this.turnActive) {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    // Before any await: an inbox interrupt ends the turn running at hand-over (deliverInbound calls
    // this synchronously), never one that starts later, such as the turn of a message handed over
    // just before it.
    const handedOverDuring = meta?.busy === "interrupt" ? this.runningTurn() : undefined
    if (this.restarting) await this.restarting.catch(() => {})
    const epoch = this.inputEpoch
    let releaseGate!: () => void
    const gate = new Promise<void>((resolve) => { releaseGate = once(resolve) })
    const previous = this.sendTail
    this.sendTail = this.sendTail.then(() => gate).catch(() => gate)
    await previous.catch(() => {})
    this.failureEmitted = false
    try {
      await this.settleReopen()
      this.assertLive(epoch)
      const content = await this.buildContent(text, meta, epoch)
      this.assertLive(epoch)
      let session = this.requireSession()
      if (meta?.busy === "interrupt") {
        await this.interruptKeepingQueue(session, handedOverDuring)
        // The idle it waited for also wakes a core-side reopen that was waiting for the turn to end:
        // a context or tool reload takes the queue (still paused) to a new Session; an account switch
        // closes the old Session and cancels it. Either way, follow the new Session. No await from
        // this check to the send below, so the input lands in the queue before a reload takes it, or
        // in the new Session.
        if (this.core.sessions.reopening(this.id)) await this.settleReopen()
        // A Stop during the wait drops this input like the rest of the pending input.
        this.assertLive(epoch)
        session = this.requireSession()
      }
      if (this.continueAfterInterrupt) {
        session.pending.continue()
        this.continueAfterInterrupt = false
      }
      if (this.profile.sendWhenBusy === "steer" && !meta?.busy) {
        const snap = session.snapshot()
        if (snap.state === "running") {
          try {
            await session.steer({ content })
          } catch (err) {
            throw asError(err)
          }
          releaseGate()
          return
        }
        let receipt
        try {
          receipt = await session.send({ content, whenBusy: "reject" })
        } catch (err) {
          if (!isSessionBusy(err) || this.stopped || epoch !== this.inputEpoch) throw asError(err)
          if (session.snapshot().state !== "running") throw asError(err)
          try {
            await session.steer({ content })
          } catch (steerErr) {
            throw asError(steerErr)
          }
          releaseGate()
          return
        }
        releaseGate()
        const result = await receipt.completed
        if (this.stopped || epoch !== this.inputEpoch) return
        this.handleCompletion(result)
        return
      }
      const receipt = await session.send({
        content,
        whenBusy: "queue",
      })
      releaseGate()
      const result = await receipt.completed
      if (this.stopped || epoch !== this.inputEpoch) return
      this.handleCompletion(result)
    } catch (err) {
      releaseGate()
      if (this.stopped || epoch !== this.inputEpoch) {
        throw asError(err)
      }
      const error = asError(err)
      this.surfaceFailure(error, { completeTurn: false })
    } finally {
      releaseGate()
    }
  }

  async interrupt(): Promise<void> {
    this.inputEpoch++
    this.failureEmitted = false
    const generation = this.startEpoch
    const session = this.current()
    if (!session) return
    this.disarmStall()
    let result: InterruptResult
    try {
      result = await session.interrupt({ pending: "discard" })
    } finally {
      // Core paused the queue whatever came back: confirmed, unconfirmed or thrown by the driver's
      // interrupt (the session goes idle, still paused, once the turn ends after all).
      if (!this.stopped && generation === this.startEpoch) this.continueAfterInterrupt = true
    }
    if (this.stopped || generation !== this.startEpoch) return
    if (result.status === "unconfirmed") {
      const error = new Error(`${this.kind} interrupt is unconfirmed; the turn may still be running`)
      this.surfaceFailure(error, { completeTurn: false })
      throw error
    }
    this.bridge.flush()
  }

  /** The turn running now: its Session and core turn id (in step with the session's state, unlike its events). */
  private runningTurn(): RunningTurn | undefined {
    const session = this.current()
    const turnId = session?.snapshot().turnId
    return session && turnId ? { session, turnId } : undefined
  }

  /**
   * Inbox delivery with interrupt=true (spec §1.3): end the turn that ran when the message was
   * handed over (`handedOverDuring`), if it still runs, but KEEP the other waiting input (the Stop
   * button discards it). A turn that started since then (an earlier message's) is left alone: the
   * message just queues behind it. Core interrupt() pauses the queue whatever the outcome and
   * refuses to continue while unconfirmed, so wait until the session is idle, then let send()
   * continue the queue before this message is queued. The send holds the input gate all along:
   * later input to this session waits behind it until the session is idle. An unconfirmed (or
   * failed) interrupt only makes that wait last until the turn ends on its own; it is logged. No
   * restart meanwhile (setConfiguration): it would close the session under the waiting input.
   */
  private async interruptKeepingQueue(session: Session, handedOverDuring: RunningTurn | undefined): Promise<void> {
    if (!handedOverDuring || handedOverDuring.session !== session || session.snapshot().turnId !== handedOverDuring.turnId) return
    const generation = this.startEpoch
    this.inboxInterruptWaits++
    this.inboxInterruptEpoch = this.inputEpoch
    try {
      let outcome: InterruptResult["status"] | "failed"
      let err: string | undefined
      try {
        outcome = (await session.interrupt({ pending: "keep" })).status
      } catch (error) {
        // A driver whose interrupt throws still ends the turn on its own; whenIdle() waits for that.
        outcome = "failed"
        err = asError(error).message
      } finally {
        // Paused whatever came back (as after Stop): the next input continues it, even if this one fails.
        if (!this.stopped && generation === this.startEpoch) this.continueAfterInterrupt = true
      }
      if (outcome === "unconfirmed" || outcome === "failed") {
        log.warn("inbox_interrupt_unconfirmed", {
          session: this.sessionName, session_id: this.id, agent: this.kind, outcome, ...(err ? { err } : {}),
        })
      }
      try {
        await session.whenIdle()
      } catch {
        // Failed or closed meanwhile: the send reports it (or follows the Session that replaced this one).
      }
    } finally {
      this.inboxInterruptWaits--
    }
  }

  private async restartNative(next: ConfigurationPatch & { permissionMode?: string }): Promise<void> {
    const previous = { model: this._model, effort: this._effort, fast: this._fast, permissionMode: this._permissionMode }
    const generation = this.startEpoch
    const nativeSessionId = this.requireSession().snapshot().agentSessionId
    if ("model" in next) this._model = next.model
    if ("effort" in next) this._effort = next.effort
    if ("fast" in next) this._fast = next.fast === true
    if ("permissionMode" in next) this._permissionMode = next.permissionMode
    this.nativeSessionId = nativeSessionId
    this.restarting = (async () => {
      try {
        await this.handle.stop({ mode: "shutdown" })
        this.session = undefined
        this.unsubscribe?.()
        this.unsubscribe = undefined
        this.handle = this.reregister({ model: this._model, effort: this._effort, fast: this._fast, permissionMode: this._permissionMode })
        if (this.stopped || generation !== this.startEpoch) return
        await this.start()
      } catch (err) {
        if (!this.stopped && generation === this.startEpoch) {
          this._model = previous.model
          this._effort = previous.effort
          this._fast = previous.fast
          this._permissionMode = previous.permissionMode
        }
        throw asError(err)
      }
    })()
    try { await this.restarting } finally { this.restarting = undefined }
  }

  private async openViaHandle(epoch: number): Promise<void> {
    this.unsubscribe?.()
    this.unsubscribe = this.core.subscribe((event) => this.onCoreEvent(event, epoch))
    try {
      let existing = await this.core.sessions.get(this.id)
      if (existing) this.assertCompatibleRecord(existing)
      if (this.abandoned(epoch)) {
        this.unsubscribe?.()
        this.unsubscribe = undefined
        return
      }
      const permissions = this.launchPermissions()
      const open = () => this.handle.start({
        cwd: this.workdir,
        configuration: this.desiredConfiguration(),
        ...(permissions ? { permissions } : {}),
        nativeSessionId: this.nativeSessionId ?? this.initialSessionId,
        onOpened: async (session) => {
          const nativeId = session.snapshot().agentSessionId
          this.nativeSessionId = nativeId
          await this.persistSessionId(nativeId)
        },
      })
      const resuming = existing !== undefined || (this.nativeSessionId ?? this.initialSessionId) !== undefined
      try {
        this.session = await open()
      } catch (err) {
        const reason = resuming && !this.abandoned(epoch) ? missingConversation(err) : undefined
        if (!reason) throw err
        // The agent never saved that conversation (it died before its first turn): nothing to lose,
        // so open a fresh one instead of resuming the missing one again and again.
        log.warn("core_resume_fresh_fallback", { session: this.sessionName, session_id: this.id, agent: this.kind, reason })
        // Its record names the missing conversation: without one, the host creates a new conversation.
        await this.core.sessions.forget(this.id)
        if (this.abandoned(epoch)) throw err
        this.initialSessionId = undefined
        this.nativeSessionId = undefined
        existing = undefined
        // The failed start left its handle terminal: a new registration opens the fresh one.
        this.handle = this.reregister({ model: this._model, effort: this._effort, fast: this._fast, permissionMode: this._permissionMode })
        this.session = await open()
      }
      try {
        await this.reconcilePermissions(this.session, existing)
      } catch (err) {
        // A failed switch must not leave the session open under a mode the user did not choose
        // (an adapter stopped meanwhile is closed by the abandoned branch below instead).
        if (!this.abandoned(epoch)) {
          log.warn(`${this.kind}_permissions_reconcile_failed`, {
            session: this.sessionName, session_id: this.id, agent: this.kind, err: asError(err).message, action: "shutting the agent down",
          })
          await this.handle.stop({ mode: "shutdown" }).catch(() => {})
          this.session = undefined
          throw err
        }
      }
      if (this.abandoned(epoch)) {
        await this.handle.stop({ mode: "shutdown" })
        this.session = undefined
        this.unsubscribe?.()
        this.unsubscribe = undefined
        return
      }
      // What the library remembers about this session's subagents (resume): truthful flags/names.
      const subagents = this.session.subagents?.() ?? []
      if (subagents.length) this.emit("subagent-snapshot", { kind: "subagent-snapshot", subagents })
    } catch (err) {
      this.unsubscribe?.()
      this.unsubscribe = undefined
      throw asError(err)
    }
  }

  /**
   * The broker's mode as a spec, for the host to launch or resume the session under. Absent when no
   * mode is known (or not a catalog mode), or the agent has no permission modes.
   */
  private launchPermissions(): PermissionsSpec | undefined {
    const mode = this._permissionMode
    if (this.profile.permissionMode === "unsupported" || !mode || !isPermissionMode(this.kind, mode)) return undefined
    return permissionsFor(this.kind, mode)
  }

  /**
   * The safety net behind the spec handed to the host at open. That spec makes the launch itself use
   * the broker's mode (Codex's process-wide policy, which a live switch cannot change) and the core
   * stores it in the record. A keeper-reattached agent is the exception: it keeps the spec it is
   * running, whatever the resume said. So judge the record as it was BEFORE this open (afterwards it
   * always holds the broker's spec): when it held something else (an older spec, or none), tell the
   * live session too. Setting the spec a driver already runs is a no-op for it. A session that was
   * just created (no record before this open) was created with that spec, as `start({ permissions })`
   * hands it to the host's `create`: it launched under it and its record stores it. A failure here
   * shuts the agent down on purpose (the caller logs a warning and closes the session): safety over
   * the running turn, it must not keep running under a mode the user did not choose. An agent that
   * cannot switch permissions live is left exactly as it is.
   */
  private async reconcilePermissions(session: Session, before: SessionRecord | undefined): Promise<void> {
    const desired = this.launchPermissions()
    if (!desired || before === undefined || !session.capabilities().permissions) return
    if (before.permissions !== undefined && JSON.stringify(before.permissions) === JSON.stringify(desired)) return
    await session.setPermissions(desired)
  }

  private abandoned(epoch: number): boolean {
    return this.stopped || epoch !== this.startEpoch
  }

  private assertCompatibleRecord(record: { agent: string; cwd: string; agentSessionId: string }): void {
    if (record.agent !== this.kind) {
      throw new Error(`core session ${this.id} is agent ${record.agent}, expected ${this.kind}`)
    }
    if (record.cwd !== this.workdir) {
      throw new Error(`core session ${this.id} cwd mismatch`)
    }
    if (this.initialSessionId && record.agentSessionId !== this.initialSessionId) {
      throw new Error(`core session ${this.id} native id mismatch`)
    }
  }

  private desiredConfiguration(): SessionConfiguration {
    if (this.profile.startConfiguration === "empty") return {}
    const requested: SessionConfiguration = {}
    if (this._model) requested.model = this._model
    if (this._effort) requested.reasoningEffort = this._effort
    if (this._fast && this.profile.configuration.fast === "configure") requested.serviceTier = CODEX_FAST_SERVICE_TIER
    return requested
  }

  private async buildContent(text: string, meta: InboundMeta | undefined, epoch: number): Promise<ContentBlock[]> {
    const attachments = this.resolveAttachment ? inboundAttachments(meta) : []
    if (this.profile.attachments === "path-in-prompt") {
      let body = text
      for (const a of attachments) body = withAttachedFile(body, await this.resolveAttachment!(a.file_id))
      this.assertLive(epoch)
      return [{ type: "text", text: body }]
    }
    const blocks: ContentBlock[] = []
    let prompt = text
    for (const a of attachments) {
      try {
        const path = await this.resolveAttachment!(a.file_id)
        this.assertLive(epoch)
        if (isImageAttachment(a)) {
          const data = await readFile(path, { encoding: "base64" })
          this.assertLive(epoch)
          blocks.push({ type: "image", data, mimeType: guessImageMime(path, a.mime) })
        } else {
          prompt = withAttachedFile(prompt, a.name ? `${a.name} (${path})` : path)
        }
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err)
        if (message.includes("stopped")) throw asError(err)
        log.warn(`${this.kind}_attachment_resolve_failed`, { session: this.sessionName, file_id: a.file_id, err: message })
      }
    }
    if (prompt || blocks.length === 0) blocks.push({ type: "text", text: prompt })
    return blocks
  }

  private assertLive(epoch: number): void {
    if (this.stopped || epoch !== this.inputEpoch || !this.session) {
      throw new Error(`${this.kind} adapter is stopped`)
    }
  }

  private requireSession(): Session {
    const session = this.current()
    if (!session) throw new Error(`${this.kind} session not initialized`)
    return session
  }

  private onCoreEvent(event: CoreEvent, epoch: number): void {
    if (event.sessionId !== this.id) return
    if (this.abandoned(epoch)) return
    if (event.type === "session.stateChanged") {
      // The old Session closing under a core-side reopen is not the turn or the agent ending.
      if (event.state === "closed" && this.core.sessions.reopening(this.id)) return
      this.applyCoreState(event.state)
      return
    }
    if (event.type === "session.resumed") {
      // A core-side reopen (context / host-tool reload) opened the replacement Session.
      if (!this.restarting && !this.starting) this.current()
      return
    }
    if (event.type === "session.update") {
      if (event.update.protocol === "native") {
        const frame = event.update.value as { method?: string; params?: unknown }
        if (frame?.method === "initialize") this.ingestInitialize(frame.params)
        else if (this.isTurnCompletedUpdate(frame)) this.bridge.flush()
      } else if ((event.update.value as { sessionUpdate?: string } | undefined)?.sessionUpdate === "turn_completed") {
        this.bridge.flush()
      }
      return
    }
    if (event.type === "session.event") {
      // The watchdog only guards a prompt the agent never picks up (armed at
      // message.started): the FIRST event of the turn proves the agent is on
      // it, so disarm for good. Re-arming per event cut every quiet stretch —
      // a subagent working for minutes, a long tool run — at 90 s.
      if (this.stallTimer) this.disarmStall()
      this.bridge.handle(event.event)
      const env = event.event
      const cards = this.activity.handle({ ...env, event: env }, Date.now())
      if (cards.length) this.emit("activity", { kind: "activity", events: cards })
      this.threadRows(env as unknown as { kind: string; subagentId?: string; replay?: boolean })
      if (env.subagentId && env.kind === "tool-call" && env.phase === "started" && env.replay !== true) {
        // The subagent's own "what it is doing now", for agents that report no progress line.
        const card = cards.find((c) => c.kind === "tool")
        const activity = card?.description || card?.title || env.title || env.tool
        if (activity) this.emit("subagent-activity", { kind: "subagent-activity", subagentId: env.subagentId, activity })
      }
      return
    }
    if (event.type === "session.failed") {
      this.surfaceFailure(event.error)
      return
    }
    if (event.type === "account.switched" || event.type === "account.refreshed") {
      // A Core-internal reopen (limit switch, token refresh) replaced the Session object.
      const live = this.core.sessions.live(this.id)
      if (live && !this.restarting) this.session = live
      if (event.type === "account.switched") this.emit("account", { kind: "account", event: "switched", from: event.from, to: event.to, reason: event.reason })
      else this.emit("account", { kind: "account", event: "refreshed", account: event.account })
      return
    }
    if (event.type === "account.exhausted") {
      this.emit("account", { kind: "account", event: "exhausted", account: event.account, reason: event.reason })
      return
    }
    if (event.type === "account.unavailable") {
      this.emit("account", { kind: "account", event: "unavailable", account: event.account, reason: event.reason })
      return
    }
    if (event.type === "account.available") {
      this.emit("account", { kind: "account", event: "available", account: event.account })
      return
    }
    if (event.type === "message.started") {
      this.armStall()
      return
    }
    if (event.type === "message.completed") {
      this.handleCompletion(event.result)
    }
  }

  private applyCoreState(state: SessionState): void {
    if (state === "running") this.openTurn()
    else if (state === "idle") this.closeTurn()
    else if (state === "closed") this.completeStoppedTurn()
  }

  private isTurnCompletedUpdate(frame: { method?: string; params?: unknown }): boolean {
    if (frame?.method !== "_x.ai/session_notification" && frame?.method !== "_x.ai/session/update") return false
    const params = frame.params
    if (!params || typeof params !== "object") return false
    const rec = params as { update?: { sessionUpdate?: string }; sessionUpdate?: string }
    const kind = rec.update?.sessionUpdate ?? rec.sessionUpdate
    return kind === "turn_completed"
  }

  private ingestInitialize(params: unknown): void {
    type Command = CoreAdapter["availableCommands"][number]
    const init = params as {
      _meta?: { modelState?: { availableModels?: { modelId: string }[] }; availableCommands?: Command[] }
      modelState?: { availableModels?: { modelId: string }[] }
      models?: { availableModels?: { modelId: string }[] }
    } | undefined
    const models =
      init?._meta?.modelState?.availableModels ??
      init?.modelState?.availableModels ??
      init?.models?.availableModels
    if (models) this.availableModels = models
    if (Array.isArray(init?._meta?.availableCommands)) this.availableCommands = init._meta.availableCommands
  }

  private openTurn(): void {
    if (!this.turnActive) {
      this.turnActive = true
      this.failureEmitted = false
      this.emit("turn-start", { kind: "turn-start" })
    }
  }

  private closeTurn(): void {
    this.disarmStall()
    this.bridge.flush()
    if (this.turnActive) {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  private handleCompletion(result: Completion): void {
    if (result.status === "failed") {
      this.surfaceFailure(result.error, { completeTurn: false })
    }
  }

  private surfaceFailure(error: Error, opts: { completeTurn?: boolean } = {}): void {
    if (!this.failureEmitted) {
      this.failureEmitted = true
      this.emit("error", { kind: "error", error })
    }
    this.disarmStall()
    this.bridge.flush()
    if (opts.completeTurn === false) return
    if (this.turnActive) {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  private armStall(): void {
    this.disarmStall()
    this.stallTimer = setTimeout(() => {
      this.stallTimer = undefined
      const error = new Error(
        `${this.kind} produced no response within ${Math.round(this.stallTimeoutMs / 1000)}s — the request appears stalled; please try again`,
      )
      this.surfaceFailure(error, { completeTurn: false })
      void this.interrupt().catch(() => {})
    }, this.stallTimeoutMs)
  }

  private disarmStall(): void {
    if (this.stallTimer) {
      clearTimeout(this.stallTimer)
      this.stallTimer = undefined
    }
  }
}

export type CoreGrokAdapter = CoreAdapter
export type CoreCodexAdapter = CoreAdapter
export type CoreOpenCodeAdapter = CoreAdapter
export type CoreCursorAdapter = CoreAdapter
export type CoreClaudeAdapter = CoreAdapter
export type CoreAntigravityAdapter = CoreAdapter
