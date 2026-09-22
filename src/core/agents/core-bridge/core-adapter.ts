import { EventEmitter } from "events"
import { readFile } from "node:fs/promises"
import type { AgentAdapter, AgentKind, BrokerRequest, InboundMeta, RequestAnswerInput } from "../types"
import { mapPendingRequest, toCoreAnswer } from "./request-map"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { makeLogger } from "../../../shared/log"
import type { CodexUsage } from "../../usage/index"
import { codexUsageFromRateLimits } from "../../usage/local"
import { createNormalizedBridge } from "./normalized-bridge"
import { createNormalizedActivity } from "./normalized-activity"
import { detachCodexRuntimeAdapter } from "../codex/core-host"
import type {
  Completion,
  ContentBlock,
  Core,
  CoreEvent,
  HostHandle,
  InterruptResult,
  Session,
  SessionConfiguration,
  SessionState,
} from "../../../../packages/supermux-core/src/index.js"

const DEFAULT_STALL_MS = 90_000
const log = makeLogger("agents/core-adapter")

export type CoreAdapterProfile = {
  kind: AgentKind
  /** How a model/effort change is applied. */
  configuration: { model: "configure" | "restart" | "unsupported"; effort: "configure" | "restart" | "unsupported" }
  /** Whether prompts can be switched on (cursor: unsupported → setPrompts throws). */
  prompts: "restart" | "unsupported"
  /** Attachment rendering. */
  attachments: "image-block" | "path-in-prompt"
  /** Idle-send vs mid-turn: queue through Core, or steer when the session is already running. */
  sendWhenBusy: "queue" | "steer"
  /** What HostHandle.start receives as configuration. */
  startConfiguration: "desired" | "empty"
}

export const GROK_CORE_PROFILE: CoreAdapterProfile = {
  kind: "grok",
  configuration: { model: "configure", effort: "configure" },
  prompts: "restart",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "desired",
}

export const CODEX_CORE_PROFILE: CoreAdapterProfile = {
  kind: "codex",
  configuration: { model: "configure", effort: "configure" },
  prompts: "restart",
  attachments: "image-block",
  sendWhenBusy: "steer",
  startConfiguration: "desired",
}

export const OPENCODE_CORE_PROFILE: CoreAdapterProfile = {
  kind: "opencode",
  configuration: { model: "restart", effort: "unsupported" },
  prompts: "restart",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "empty",
}

export const CURSOR_CORE_PROFILE: CoreAdapterProfile = {
  kind: "cursor",
  configuration: { model: "restart", effort: "unsupported" },
  prompts: "unsupported",
  attachments: "path-in-prompt",
  sendWhenBusy: "queue",
  startConfiguration: "empty",
}

export const CLAUDE_CORE_PROFILE: CoreAdapterProfile = {
  kind: "claude",
  configuration: { model: "restart", effort: "restart" },
  prompts: "restart",
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
} as const

export type CoreAdapterOpts = {
  handle: HostHandle
  reregister: (fields: { model?: string; prompts?: boolean }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  initialSessionId?: string
  persistSessionId: (nativeId: string) => Promise<void>
  model?: string
  effort?: string
  prompts?: boolean
  resolveAttachment?: (file_id: string) => Promise<string>
  stallTimeoutMs?: number
  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null
}

type JsonRpcLike = {
  request<T = unknown>(method: string, params: unknown): Promise<T>
  onNotification(h: (n: { method: string; params: unknown }) => void): void
}

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
  private readonly initialSessionId?: string
  private nativeSessionId?: string

  private _model?: string
  private _effort?: string
  private _prompts: boolean
  private session?: Session
  private unsubscribe?: () => void
  private startEpoch = 0
  private inputEpoch = 0
  private stopped = false
  private starting?: Promise<void>
  private restarting?: Promise<void>
  private stopping?: Promise<void>
  private sendTail: Promise<void> = Promise.resolve()
  private continueAfterConfirmedInterrupt = false
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
    this._prompts = opts.prompts === true
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
  get prompts(): boolean { return this._prompts }

  sessionSnapshotState(): SessionState | undefined {
    return this.session?.snapshot().state
  }

  turnIsRunning(): boolean {
    return this.turnActive
  }

  openRequests(): BrokerRequest[] {
    const session = this.session
    if (!session) return []
    return session.requests.list().map(mapPendingRequest)
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

  async setPrompts(enabled: boolean): Promise<void> {
    if (this.profile.prompts === "unsupported") {
      if (enabled) {
        throw new CoreError("unsupported_operation", `${this.kind} sessions cannot prompt`)
      }
      return
    }
    const session = this.requireSession()
    if (enabled === this._prompts) return
    if (session.snapshot().state === "running") {
      throw new CoreError("session_busy", `${this.kind} session is busy`)
    }
    await this.restartNative({ prompts: enabled })
  }

  async setConfiguration(patch: { model?: string; effort?: string }): Promise<void> {
    if (!("model" in patch) && !("effort" in patch)) return
    const modelMode = this.profile.configuration.model
    const effortMode = this.profile.configuration.effort
    const wantsModel = "model" in patch
    const wantsEffort = "effort" in patch
    if (wantsModel && modelMode === "unsupported") return
    if (wantsEffort && !wantsModel && effortMode === "unsupported") return
    if (wantsEffort && effortMode === "unsupported") {
      patch = wantsModel ? { model: patch.model } : {}
      if (!("model" in patch)) return
    }

    const modelChanged = "model" in patch && patch.model !== this._model
    const effortChanged = "effort" in patch && patch.effort !== this._effort
    if (!modelChanged && !effortChanged) return

    const modelRestart = "model" in patch && modelMode === "restart"
    const effortRestart = "effort" in patch && effortMode === "restart"
    if (modelRestart || effortRestart) {
      const session = this.requireSession()
      if (session.snapshot().state === "running") {
        throw new CoreError("session_busy", `${this.kind} session is busy`)
      }
      await this.restartNative({
        model: "model" in patch ? patch.model : this._model,
        effort: "effort" in patch ? patch.effort : this._effort,
      })
      return
    }

    const session = this.requireSession()
    const previous = { model: this._model, effort: this._effort }
    const requested: SessionConfiguration = {}
    if ("model" in patch) requested.model = patch.model
    if ("effort" in patch) requested.reasoningEffort = patch.effort
    const generation = this.startEpoch
    try {
      await session.configure(requested)
      if (this.stopped || generation !== this.startEpoch) return
      if ("model" in patch) this._model = patch.model
      if ("effort" in patch) this._effort = patch.effort
    } catch (err) {
      if (!this.stopped && generation === this.startEpoch) {
        this._model = previous.model
        this._effort = previous.effort
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

  async resume(): Promise<void> {
    await this.start()
  }

  async stop(): Promise<void> {
    this.stopped = true
    this.startEpoch++
    this.inputEpoch++
    this.disarmStall()
    this.continueAfterConfirmedInterrupt = false
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
    if (this.restarting) await this.restarting.catch(() => {})
    const epoch = this.inputEpoch
    let releaseGate!: () => void
    const gate = new Promise<void>((resolve) => { releaseGate = once(resolve) })
    const previous = this.sendTail
    this.sendTail = this.sendTail.then(() => gate).catch(() => gate)
    await previous.catch(() => {})
    this.failureEmitted = false
    try {
      this.assertLive(epoch)
      const content = await this.buildContent(text, meta, epoch)
      this.assertLive(epoch)
      const session = this.requireSession()
      if (this.continueAfterConfirmedInterrupt) {
        session.pending.continue()
        this.continueAfterConfirmedInterrupt = false
      }
      if (this.profile.sendWhenBusy === "steer") {
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
    const session = this.session
    if (!session) return
    this.disarmStall()
    const result: InterruptResult = await session.interrupt({ pending: "discard" })
    if (this.stopped || generation !== this.startEpoch) return
    if (result.status === "unconfirmed") {
      const error = new Error(`${this.kind} interrupt is unconfirmed; the turn may still be running`)
      this.surfaceFailure(error, { completeTurn: false })
      throw error
    }
    this.continueAfterConfirmedInterrupt = true
    this.bridge.flush()
  }

  private async restartNative(next: { model?: string; effort?: string; prompts?: boolean }): Promise<void> {
    const previous = { model: this._model, effort: this._effort, prompts: this._prompts }
    const generation = this.startEpoch
    const nativeSessionId = this.requireSession().snapshot().agentSessionId
    if ("model" in next) this._model = next.model
    if ("effort" in next) this._effort = next.effort
    if ("prompts" in next) this._prompts = next.prompts === true
    this.nativeSessionId = nativeSessionId
    this.restarting = (async () => {
      try {
        await this.handle.stop({ mode: "shutdown" })
        this.session = undefined
        this.unsubscribe?.()
        this.unsubscribe = undefined
        this.handle = this.reregister({ model: this._model, prompts: this._prompts })
        if (this.stopped || generation !== this.startEpoch) return
        await this.start()
      } catch (err) {
        if (!this.stopped && generation === this.startEpoch) {
          this._model = previous.model
          this._effort = previous.effort
          this._prompts = previous.prompts
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
      const existing = await this.core.sessions.get(this.id)
      if (existing) this.assertCompatibleRecord(existing)
      if (this.abandoned(epoch)) {
        this.unsubscribe?.()
        this.unsubscribe = undefined
        return
      }
      this.session = await this.handle.start({
        cwd: this.workdir,
        configuration: this.desiredConfiguration(),
        nativeSessionId: this.nativeSessionId ?? this.initialSessionId,
        onOpened: async (session) => {
          const nativeId = session.snapshot().agentSessionId
          this.nativeSessionId = nativeId
          await this.persistSessionId(nativeId)
        },
      })
      if (this.abandoned(epoch)) {
        await this.handle.stop({ mode: "shutdown" })
        this.session = undefined
        this.unsubscribe?.()
        this.unsubscribe = undefined
      }
    } catch (err) {
      this.unsubscribe?.()
      this.unsubscribe = undefined
      throw asError(err)
    }
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
    return requested
  }

  private async buildContent(text: string, meta: InboundMeta | undefined, epoch: number): Promise<ContentBlock[]> {
    if (this.profile.attachments === "path-in-prompt") {
      const body = await this.withPathAttachment(text, meta?.attachment_file_id, epoch)
      return [{ type: "text", text: body }]
    }
    const blocks: ContentBlock[] = []
    let prompt = text
    if (meta?.attachment_file_id && this.resolveAttachment) {
      try {
        const path = await this.resolveAttachment(meta.attachment_file_id)
        this.assertLive(epoch)
        const isImage = !!meta.attachment_mime?.startsWith("image/")
          || meta.attachment_kind === "photo" || meta.attachment_kind === "image"
        if (isImage) {
          const data = await readFile(path, { encoding: "base64" })
          this.assertLive(epoch)
          blocks.push({ type: "image", data, mimeType: guessImageMime(path, meta.attachment_mime) })
        } else {
          const label = meta.attachment_name ? `${meta.attachment_name} (${path})` : path
          prompt = prompt ? `${prompt}\n\n[Attached file: ${label}]` : `[Attached file: ${label}]`
        }
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : String(err)
        if (message.includes("stopped")) throw asError(err)
        log.warn(`${this.kind}_attachment_resolve_failed`, { session: this.sessionName, file_id: meta.attachment_file_id, err: message })
      }
    }
    if (prompt || blocks.length === 0) blocks.push({ type: "text", text: prompt })
    return blocks
  }

  private async withPathAttachment(text: string, fileId: string | undefined, epoch: number): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    const path = await this.resolveAttachment(fileId)
    this.assertLive(epoch)
    return text ? `${text}\n\n[Attached file: ${path}]` : `[Attached file: ${path}]`
  }

  private assertLive(epoch: number): void {
    if (this.stopped || epoch !== this.inputEpoch || !this.session) {
      throw new Error(`${this.kind} adapter is stopped`)
    }
  }

  private requireSession(): Session {
    if (!this.session) throw new Error(`${this.kind} session not initialized`)
    return this.session
  }

  private onCoreEvent(event: CoreEvent, epoch: number): void {
    if (event.sessionId !== this.id) return
    if (this.abandoned(epoch)) return
    if (event.type === "session.stateChanged") {
      this.applyCoreState(event.state)
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
      if (this.stallTimer) this.armStall()
      this.bridge.handle(event.event)
      const env = event.event
      const cards = this.activity.handle({ ...env, event: env }, Date.now())
      if (cards.length) this.emit("activity", { kind: "activity", events: cards })
      return
    }
    if (event.type === "session.failed") {
      this.surfaceFailure(event.error)
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
