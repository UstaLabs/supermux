import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { createNormalizedBridge } from "../core-bridge/normalized-bridge"
import type {
  Completion,
  Core,
  CoreEvent,
  InterruptResult,
  Session,
  SessionConfiguration,
  SessionState,
} from "../../../../packages/supermux-core/src/index.js"

const DEFAULT_STALL_MS = 90_000

export type CoreGrokAdapterOpts = {
  core: Core
  id: string
  sessionName: string
  workdir: string
  initialSessionId?: string
  persistSessionId: (nativeId: string) => Promise<void>
  model?: string
  effort?: string
  resolveAttachment?: (file_id: string) => Promise<string>
  stallTimeoutMs?: number
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

function withCleanupError(original: unknown, cleanup: unknown): Error {
  const primary = asError(original)
  if (cleanup == null) return primary
  const secondary = asError(cleanup)
  return new Error(`${primary.message}; cleanup failed: ${secondary.message}`, { cause: primary })
}

function once<T extends (...args: never[]) => void>(fn: T): T {
  let done = false
  return ((...args: never[]) => {
    if (done) return
    done = true
    fn(...args)
  }) as T
}

/** Broker-facing Grok adapter that talks to a host-owned supermux-core instance.
 * Does not spawn a native child, subclass GrokAdapter, or close the shared Core. */
export class CoreGrokAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "grok"
  readonly sessionName: string
  readonly workdir: string
  readonly id: string

  availableModels: { modelId: string }[] = []
  availableCommands: { name: string; description?: string; _meta?: { scope?: string; path?: string } }[] = []

  private readonly core: Core
  private readonly persistSessionId: (nativeId: string) => Promise<void>
  private readonly resolveAttachment?: (file_id: string) => Promise<string>
  private readonly stallTimeoutMs: number
  private readonly initialSessionId?: string

  private _model?: string
  private _effort?: string
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
  private lastNativeId?: string
  private readonly bridge = createNormalizedBridge({
    agent: "grok",
    emit: (event) => {
      if (event.kind === "error") this.surfaceFailure(event.error, { completeTurn: false })
      else this.emit(event.kind, event)
    },
    onCommands: (commands) => {
      this.availableCommands = commands
      this.emit("commands-update", { kind: "commands-update" })
    },
  })

  constructor(opts: CoreGrokAdapterOpts) {
    super()
    this.core = opts.core
    this.id = opts.id
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.persistSessionId = opts.persistSessionId
    this.initialSessionId = opts.initialSessionId
    this._model = opts.model
    this._effort = opts.effort
    this.resolveAttachment = opts.resolveAttachment
    this.stallTimeoutMs = opts.stallTimeoutMs ?? DEFAULT_STALL_MS
  }

  get model(): string | undefined { return this._model }
  get effort(): string | undefined { return this._effort }

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
      const work = this.openSession(epoch)
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
    const inflight = this.starting
    let startError: unknown
    if (inflight) {
      try {
        await inflight
      } catch (err) {
        startError = err
      }
    }
    try {
      await this.core.sessions.close(this.id, { mode: "shutdown" })
    } catch (err) {
      throw startError != null ? withCleanupError(startError, err) : asError(err)
    }
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
      const error = new Error("grok interrupt is unconfirmed; the turn may still be running")
      this.surfaceFailure(error, { completeTurn: false })
      throw error
    }
    this.continueAfterConfirmedInterrupt = true
    this.bridge.flush()
  }

  private async openSession(epoch: number): Promise<void> {
    this.unsubscribe?.()
    this.unsubscribe = this.core.subscribe((event) => this.onCoreEvent(event, epoch))
    let session: Session | undefined
    try {
      const existing = await this.core.sessions.get(this.id)
      if (this.abandoned(epoch)) {
        this.unsubscribe?.()
        this.unsubscribe = undefined
        return
      }
      const configuration = this.desiredConfiguration()
      if (existing) {
        this.assertCompatibleRecord(existing)
        session = await this.core.sessions.resume(this.id, { configuration })
      } else if (this.initialSessionId) {
        await this.core.sessions.adopt({
          id: this.id,
          agent: "grok",
          agentSessionId: this.initialSessionId,
          cwd: this.workdir,
          configuration,
        })
        if (this.abandoned(epoch)) {
          return
        }
        session = await this.core.sessions.resume(this.id, { configuration })
      } else {
        session = await this.core.sessions.create({ id: this.id, agent: "grok", cwd: this.workdir, configuration })
      }
      if (this.abandoned(epoch)) {
        await this.closeOrRetain(session)
        this.unsubscribe?.()
        this.unsubscribe = undefined
        return
      }
      this.session = session
      if (this.abandoned(epoch)) {
        await this.dropOpened(session)
        return
      }
      const nativeId = session.snapshot().agentSessionId
      this.lastNativeId = nativeId
      try {
        await this.persistSessionId(nativeId)
      } catch (err) {
        try {
          await this.dropOpened(session)
        } catch (cleanup) {
          throw withCleanupError(err, cleanup)
        }
        throw asError(err)
      }
      if (this.abandoned(epoch)) {
        await this.dropOpened(session)
      }
    } catch (err) {
      if (session && this.session === session) {
        try {
          await this.dropOpened(session)
        } catch (cleanup) {
          throw withCleanupError(err, cleanup)
        }
      } else if (!this.session) {
        this.unsubscribe?.()
        this.unsubscribe = undefined
      }
      throw asError(err)
    }
  }

  private async closeOrRetain(session: Session): Promise<void> {
    if (session.snapshot().state === "closed") {
      if (this.session === session) this.session = undefined
      return
    }
    try {
      await session.close({ mode: "shutdown" })
    } catch (err) {
      this.session = session
      throw asError(err)
    }
    if (this.session === session) this.session = undefined
  }

  private async dropOpened(session: Session): Promise<void> {
    this.unsubscribe?.()
    this.unsubscribe = undefined
    await this.closeOrRetain(session)
  }

  private abandoned(epoch: number): boolean {
    return this.stopped || epoch !== this.startEpoch
  }

  private assertCompatibleRecord(record: { agent: string; cwd: string; agentSessionId: string }): void {
    if (record.agent !== "grok") {
      throw new Error(`core session ${this.id} is agent ${record.agent}, expected grok`)
    }
    if (record.cwd !== this.workdir) {
      throw new Error(`core session ${this.id} cwd mismatch`)
    }
    if (this.initialSessionId && record.agentSessionId !== this.initialSessionId) {
      throw new Error(`core session ${this.id} native id mismatch`)
    }
  }

  private desiredConfiguration(): SessionConfiguration {
    return { model: this._model, reasoningEffort: this._effort }
  }

  private async withAttachment(text: string, fileId: string | undefined, epoch: number): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    const path = await this.resolveAttachment(fileId)
    this.assertLive(epoch)
    return text ? `${text}\n\n[Attached file: ${path}]` : `[Attached file: ${path}]`
  }

  private assertLive(epoch: number): void {
    if (this.stopped || epoch !== this.inputEpoch || !this.session) {
      throw new Error("grok adapter is stopped")
    }
  }

  private requireSession(): Session {
    if (!this.session) throw new Error("grok session not initialized")
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
    type Command = CoreGrokAdapter["availableCommands"][number]
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
        `grok produced no response within ${Math.round(this.stallTimeoutMs / 1000)}s — the request appears stalled; please try again`,
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
