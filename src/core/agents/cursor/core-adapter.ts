import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, BrokerRequest, InboundMeta, RequestAnswerInput } from "../types"
import { mapPendingRequest, toCoreAnswer } from "../core-bridge/request-map"
import { createNormalizedBridge } from "../core-bridge/normalized-bridge"
import { createNormalizedActivity } from "../core-bridge/normalized-activity"
import type {
  Completion,
  Core,
  CoreEvent,
  HostHandle,
  InterruptResult,
  Session,
  SessionConfiguration,
  SessionState,
} from "../../../../packages/supermux-core/src/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"

const DEFAULT_STALL_MS = 90_000

export type CoreCursorAdapterOpts = {
  handle: HostHandle
  /** Host.stop() terminals the handle; model restart re-registers then starts. */
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

/** Broker-facing Cursor adapter that talks to a host-owned supermux-core instance.
 * Does not spawn a native child, subclass CursorAdapter, or close the shared Core. */
export class CoreCursorAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "cursor"
  readonly sessionName: string
  readonly workdir: string
  readonly id: string

  availableModels: { modelId: string }[] = []
  availableCommands: { name: string; description?: string; _meta?: { scope?: string; path?: string } }[] = []

  private handle: HostHandle
  private readonly reregister: CoreCursorAdapterOpts["reregister"]
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
  private stopping?: Promise<void>
  private sendTail: Promise<void> = Promise.resolve()
  private continueAfterConfirmedInterrupt = false
  private turnActive = false
  private stallTimer?: ReturnType<typeof setTimeout>
  private failureEmitted = false
  private readonly activity
  private readonly bridge = createNormalizedBridge({
    agent: "cursor",
    emit: (event) => {
      if (event.kind === "error") this.surfaceFailure(event.error, { completeTurn: false })
      else this.emit(event.kind, event)
    },
    onCommands: (commands) => {
      this.availableCommands = commands
      this.emit("commands-update", { kind: "commands-update" })
    },
  })

  constructor(opts: CoreCursorAdapterOpts) {
    super()
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
    if (enabled) {
      throw new CoreError("unsupported_operation", "cursor sessions cannot prompt")
    }
  }

  async setConfiguration(patch: { model?: string; effort?: string }): Promise<void> {
    if ("effort" in patch && !("model" in patch)) return
    if (!("model" in patch)) return
    const session = this.requireSession()
    if (session.snapshot().state === "running") {
      throw new CoreError("session_busy", "cursor session is busy")
    }
    const nativeSessionId = session.snapshot().agentSessionId
    const previous = this._model
    const generation = this.startEpoch
    this._model = patch.model
    this.nativeSessionId = nativeSessionId
    try {
      await this.handle.stop({ mode: "shutdown" })
      this.session = undefined
      this.unsubscribe?.()
      this.unsubscribe = undefined
      this.handle = this.reregister({ model: this._model, prompts: this._prompts })
      if (this.stopped || generation !== this.startEpoch) return
      await this.start()
    } catch (err) {
      if (!this.stopped && generation === this.startEpoch) this._model = previous
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
    const epoch = this.inputEpoch
    let releaseGate!: () => void
    const gate = new Promise<void>((resolve) => { releaseGate = once(resolve) })
    const previous = this.sendTail
    this.sendTail = this.sendTail.then(() => gate).catch(() => gate)
    await previous.catch(() => {})
    try {
      this.assertLive(epoch)
      const body = await this.withAttachment(text, meta?.attachment_file_id, epoch)
      this.assertLive(epoch)
      const session = this.requireSession()
      if (this.continueAfterConfirmedInterrupt) {
        session.pending.continue()
        this.continueAfterConfirmedInterrupt = false
      }
      const receipt = await session.send({
        content: [{ type: "text", text: body }],
        whenBusy: "queue",
      })
      releaseGate()
      const result = await receipt.completed
      // Cancelled after interrupt is intentional idle: broker send() is void.
      // Do not treat discarded queued receipts as errors (avoids duplicate toasts).
      // Stop invalidates the turn: finish only after confirmed native cleanup.
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
    const generation = this.startEpoch
    const session = this.session
    if (!session) return
    this.disarmStall()
    const result: InterruptResult = await session.interrupt({ pending: "discard" })
    if (this.stopped || generation !== this.startEpoch) return
    if (result.status === "unconfirmed") {
      const error = new Error("cursor interrupt is unconfirmed; the turn may still be running")
      this.surfaceFailure(error, { completeTurn: false })
      throw error
    }
    this.continueAfterConfirmedInterrupt = true
    this.bridge.flush()
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
    if (record.agent !== "cursor") {
      throw new Error(`core session ${this.id} is agent ${record.agent}, expected cursor`)
    }
    if (record.cwd !== this.workdir) {
      throw new Error(`core session ${this.id} cwd mismatch`)
    }
    const expectedNative = this.nativeSessionId ?? this.initialSessionId
    if (expectedNative && record.agentSessionId !== expectedNative) {
      throw new Error(`core session ${this.id} native id mismatch`)
    }
  }

  private desiredConfiguration(): SessionConfiguration {
    // Model is a driver option (ACP set_config_option after open), not Session.configure.
    return {}
  }

  private async withAttachment(text: string, fileId: string | undefined, epoch: number): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    const path = await this.resolveAttachment(fileId)
    this.assertLive(epoch)
    return text ? `${text}\n\n[Attached file: ${path}]` : `[Attached file: ${path}]`
  }

  private assertLive(epoch: number): void {
    if (this.stopped || epoch !== this.inputEpoch || !this.session) {
      throw new Error("cursor adapter is stopped")
    }
  }

  private requireSession(): Session {
    if (!this.session) throw new Error("cursor session not initialized")
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
    type Command = CoreCursorAdapter["availableCommands"][number]
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
        `cursor produced no response within ${Math.round(this.stallTimeoutMs / 1000)}s — the request appears stalled; please try again`,
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
