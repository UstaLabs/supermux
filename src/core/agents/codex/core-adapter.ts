import { EventEmitter } from "events"
import { readFile } from "node:fs/promises"
import type { AgentAdapter, AgentKind, BrokerRequest, InboundMeta, RequestAnswerInput } from "../types"
import { mapPendingRequest, toCoreAnswer } from "../core-bridge/request-map"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { makeLogger } from "../../../shared/log"
import type { CodexUsage } from "../../usage/index"
import { codexUsageFromRateLimits } from "../../usage/local"
import { createNormalizedBridge } from "../core-bridge/normalized-bridge"
import { createNormalizedActivity } from "../core-bridge/normalized-activity"
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
import { detachCodexRuntimeAdapter } from "./core-host"

const log = makeLogger("agents/codex/core-adapter")

export type CoreCodexAdapterOpts = {
  handle: HostHandle
  reregister: (fields: { model?: string; prompts?: boolean }) => HostHandle
  core: Core
  id: string
  sessionName: string
  workdir: string
  initialThreadId?: string
  persistThreadId: (nativeId: string) => Promise<void>
  model?: string
  effort?: string
  prompts?: boolean
  resolveAttachment?: (file_id: string) => Promise<string>
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

/** Broker-facing Codex adapter that talks to a host-owned supermux-core instance.
 * Does not spawn a native child or close the shared Core. */
export class CoreCodexAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "codex"
  readonly sessionName: string
  readonly workdir: string
  readonly id: string

  availableModels: { modelId: string }[] = []
  availableCommands: { name: string; description?: string; _meta?: { scope?: string; path?: string } }[] = []

  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null

  private handle: HostHandle
  private readonly reregister: CoreCodexAdapterOpts["reregister"]
  private readonly core: Core
  private readonly persistThreadId: (nativeId: string) => Promise<void>
  private readonly resolveAttachment?: (file_id: string) => Promise<string>
  private readonly initialThreadId?: string

  private _model?: string
  private _effort?: string
  private _prompts: boolean
  private session?: Session
  private unsubscribe?: () => void
  private startEpoch = 0
  private inputEpoch = 0
  private stopped = false
  private starting?: Promise<void>
  private stopping?: Promise<void>
  private sendTail: Promise<void> = Promise.resolve()
  private continueAfterConfirmedInterrupt = false
  private turnActive = false
  private failureEmitted = false
  private lastNativeId?: string
  private runtimeRequest?: (method: string, params: unknown) => Promise<unknown>
  private readonly activity
  private readonly bridge = createNormalizedBridge({
    agent: "codex",
    emit: (event) => {
      if (event.kind === "error") this.surfaceFailure(event.error, { completeTurn: false })
      else this.emit(event.kind, event)
    },
    onUsage: (rateLimits) => {
      const data = codexUsageFromRateLimits(rateLimits, this.getPrevUsage?.() ?? null)
      if (data) this.onUsageUpdate?.(data)
    },
  })

  constructor(opts: CoreCodexAdapterOpts) {
    super()
    this.handle = opts.handle
    this.reregister = opts.reregister
    this.core = opts.core
    this.id = opts.id
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.persistThreadId = opts.persistThreadId
    this.initialThreadId = opts.initialThreadId
    this._model = opts.model
    this._effort = opts.effort
    this._prompts = opts.prompts === true
    this.resolveAttachment = opts.resolveAttachment
    this.onUsageUpdate = opts.onUsageUpdate
    this.getPrevUsage = opts.getPrevUsage
    this.activity = createNormalizedActivity({ workdir: opts.workdir })
  }

  get model(): string | undefined { return this._model }
  get effort(): string | undefined { return this._effort }
  get prompts(): boolean { return this._prompts }

  openRequests(): BrokerRequest[] {
    const session = this.session
    if (!session) return []
    return session.requests.list().map(mapPendingRequest)
  }

  async respondRequest(requestId: string, answer: RequestAnswerInput): Promise<void> {
    await this.requireSession().requests.respond(requestId, toCoreAnswer(answer))
  }

  async setPrompts(enabled: boolean): Promise<void> {
    const session = this.requireSession()
    if (session.snapshot().state === "running") {
      throw new CoreError("session_busy", "codex session is busy")
    }
    const nativeSessionId = session.snapshot().agentSessionId
    const previous = this._prompts
    const generation = this.startEpoch
    this._prompts = enabled
    this.lastNativeId = nativeSessionId
    try {
      await this.handle.stop({ mode: "shutdown" })
      this.session = undefined
      this.unsubscribe?.()
      this.unsubscribe = undefined
      this.handle = this.reregister({ model: this._model, prompts: this._prompts })
      if (this.stopped || generation !== this.startEpoch) return
      await this.start()
    } catch (err) {
      if (!this.stopped && generation === this.startEpoch) this._prompts = previous
      throw asError(err)
    }
  }

  attachRuntimeRequest(request: (method: string, params: unknown) => Promise<unknown>): void {
    this.runtimeRequest = request
  }

  get rpc(): JsonRpcLike {
    return {
      request: async <T = unknown>(method: string, params: unknown): Promise<T> => {
        if (!this.runtimeRequest) throw new Error("codex rpc unavailable")
        return this.runtimeRequest(method, params) as Promise<T>
      },
      onNotification: () => {},
    }
  }

  async setConfiguration(patch: { model?: string; effort?: string }): Promise<void> {
    const session = this.requireSession()
    const previous = { model: this._model, effort: this._effort }
    const requested: SessionConfiguration = {}
    if ("model" in patch) requested.model = patch.model
    if ("effort" in patch) requested.reasoningEffort = patch.effort
    if (!("model" in patch) && !("effort" in patch)) return
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
    detachCodexRuntimeAdapter(this.id)
    this.completeStoppedTurn()
  }

  private completeStoppedTurn(): void {
    if (this.turnActive) {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  async send(text: string, meta?: InboundMeta): Promise<void> {
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
      const snap = session.snapshot()
      if (snap.state === "running") {
        try {
          await session.steer({ content })
        } catch (err) {
          if (this.stopped || epoch !== this.inputEpoch) throw asError(err)
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
    const result: InterruptResult = await session.interrupt({ pending: "discard" })
    if (this.stopped || generation !== this.startEpoch) return
    if (result.status === "unconfirmed") {
      const error = new Error("codex interrupt is unconfirmed; the turn may still be running")
      this.surfaceFailure(error, { completeTurn: false })
      throw error
    }
    this.continueAfterConfirmedInterrupt = true
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
        nativeSessionId: this.lastNativeId ?? this.initialThreadId,
        onOpened: async (session) => {
          const nativeId = session.snapshot().agentSessionId
          this.lastNativeId = nativeId
          await this.persistThreadId(nativeId)
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
    if (record.agent !== "codex") {
      throw new Error(`core session ${this.id} is agent ${record.agent}, expected codex`)
    }
    if (record.cwd !== this.workdir) {
      throw new Error(`core session ${this.id} cwd mismatch`)
    }
    if (this.initialThreadId && record.agentSessionId !== this.initialThreadId) {
      throw new Error(`core session ${this.id} native id mismatch`)
    }
  }

  private desiredConfiguration(): SessionConfiguration {
    const requested: SessionConfiguration = {}
    if (this._model) requested.model = this._model
    if (this._effort) requested.reasoningEffort = this._effort
    return requested
  }

  private async buildContent(text: string, meta: InboundMeta | undefined, epoch: number): Promise<ContentBlock[]> {
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
        log.warn("codex_attachment_resolve_failed", { session: this.sessionName, file_id: meta.attachment_file_id, err: message })
      }
    }
    if (prompt || blocks.length === 0) blocks.push({ type: "text", text: prompt })
    return blocks
  }

  private assertLive(epoch: number): void {
    if (this.stopped || epoch !== this.inputEpoch || !this.session) {
      throw new Error("codex adapter is stopped")
    }
  }

  private requireSession(): Session {
    if (!this.session) throw new Error("codex session not initialized")
    return this.session
  }

  private onCoreEvent(event: CoreEvent, epoch: number): void {
    if (event.sessionId !== this.id) return
    if (this.abandoned(epoch)) return
    if (event.type === "session.stateChanged") {
      this.applyCoreState(event.state)
      return
    }
    if (event.type === "session.event") {
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
    if (event.type === "message.completed") {
      this.handleCompletion(event.result)
    }
  }

  private applyCoreState(state: SessionState): void {
    if (state === "running") this.openTurn()
    else if (state === "idle") this.closeTurn()
    else if (state === "closed") this.completeStoppedTurn()
  }

  private openTurn(): void {
    if (!this.turnActive) {
      this.turnActive = true
      this.failureEmitted = false
      this.emit("turn-start", { kind: "turn-start" })
    }
  }

  private closeTurn(): void {
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
    if (opts.completeTurn === false) return
    if (this.turnActive) {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }
}
