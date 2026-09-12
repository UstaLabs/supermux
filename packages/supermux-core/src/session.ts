import { randomUUID } from "node:crypto"
import { ACTIVITY_OVERFLOW, MAX_OUTSTANDING_ACTIVITY, copyActivityNotice } from "./activity.js"
import { mergeConfiguration } from "./configuration.js"
import { CoreError, UnsupportedOperation, asError } from "./errors.js"
import type {
  ActivityNotice, AgentRuntime, AgentUpdate, Capabilities, Completion, CoreEvent, InterruptResult,
  Receipt, SendOptions, SessionRecord, SessionState, ForkOptions,
  HistoryOptions, HistoryPage, SessionConfiguration,
} from "./types.js"

type ActivitySlot = {
  done: Promise<void>
  settle: (error?: Error) => void
}

type Entry = {
  receipt: Receipt
  input: SendOptions
  finish(result: Completion): void
  settled: boolean
}

export class Session {
  readonly id: string
  private state: SessionState = "idle"
  private queue: Entry[] = []
  private active?: Entry
  private abort?: AbortController
  private paused = false
  private forking?: Promise<Session>
  private closing?: Promise<void>
  private interrupting?: Promise<InterruptResult>
  private configuring?: Promise<void>
  private seen = new Map<string, { fingerprint: string; entry: Entry }>()
  private activity = new Map<string, ActivitySlot>()

  constructor(
    private readonly record: SessionRecord,
    private readonly runtime: AgentRuntime,
    private readonly emit: (event: CoreEvent) => void,
    private readonly interruptTimeoutMs: number,
    private readonly maxPending: number,
    private readonly onClosed: () => void,
    private readonly createFork: (options: ForkOptions) => Promise<Session>,
    private readonly persistRecord: (record: SessionRecord) => Promise<void>,
  ) { this.id = record.id }

  snapshot(): SessionRecord & { state: SessionState; pending: number; paused: boolean } {
    return { ...structuredClone(this.record), state: this.state, pending: this.queue.length, paused: this.paused }
  }

  capabilities(): Capabilities { return { ...this.runtime.capabilities, detach: false } }

  async send(options: SendOptions): Promise<Receipt> {
    this.assertReady()
    if (options.whenBusy !== "queue" && options.whenBusy !== "reject") throw new CoreError("invalid_input", "whenBusy must be queue or reject")
    if (!Array.isArray(options.content) || !options.content.length) throw new CoreError("invalid_input", "At least one content block is required")
    const input = structuredClone(options)
    const fingerprint = JSON.stringify(input.content)
    if (input.idempotencyKey !== undefined) {
      if (!input.idempotencyKey) throw new CoreError("invalid_input", "idempotencyKey cannot be empty")
      const prior = this.seen.get(input.idempotencyKey)
      if (prior) {
        if (prior.fingerprint !== fingerprint) throw new CoreError("idempotency_conflict", "This idempotency key was used for different input")
        return prior.entry.receipt
      }
    }
    if (input.whenBusy === "reject" && (this.active || this.queue.length || this.paused || this.activity.size)) throw new CoreError("session_busy", "Session is busy or its queue is paused")
    if (this.queue.length >= this.maxPending) throw new CoreError("queue_full", "Session input queue is full")
    let resolve!: (result: Completion) => void
    const receipt = { messageId: randomUUID(), completed: new Promise<Completion>(done => { resolve = done }) }
    const entry: Entry = { receipt, input, settled: false, finish: result => {
      if (entry.settled) return
      entry.settled = true
      resolve(result)
      this.emit({ type: "message.completed", sessionId: this.id, messageId: receipt.messageId, result })
      this.trimSeen()
    } }
    if (input.idempotencyKey) this.seen.set(input.idempotencyKey, { fingerprint, entry })
    this.queue.push(entry)
    this.emit({ type: "message.accepted", sessionId: this.id, messageId: receipt.messageId })
    this.drain()
    return receipt
  }

  readonly pending = {
    list: (): { messageId: string }[] => this.queue.map(e => ({ messageId: e.receipt.messageId })),
    cancel: (messageId: string): boolean => {
      const index = this.queue.findIndex(e => e.receipt.messageId === messageId)
      if (index < 0) return false
      this.queue.splice(index, 1)[0]!.finish({ status: "cancelled" })
      return true
    },
    clear: (): void => { for (const entry of this.queue.splice(0)) entry.finish({ status: "cancelled" }) },
    continue: (): void => {
      this.assertReady()
      if (this.interrupting || this.state === "interrupting") throw new CoreError("session_busy", "Interrupt has not been confirmed")
      this.paused = false
      this.drain()
    },
  }

  interrupt(options: { pending: "keep" | "discard" } = { pending: "discard" }): Promise<InterruptResult> {
    try { this.assertReady() } catch (error) { return Promise.reject(error) }
    if (options.pending !== "keep" && options.pending !== "discard") return Promise.reject(new CoreError("invalid_input", "pending must be keep or discard"))
    this.paused = true
    if (options.pending === "discard") this.pending.clear()
    if (this.interrupting) return this.interrupting
    const owned = this.active
    const gates = [...this.activity.values()].map(slot => slot.done)
    if (!owned && !gates.length) return Promise.resolve({ status: "already_idle" })
    this.changeState("interrupting")
    const operation = this.interruptActive(owned, gates)
    this.interrupting = operation
    void operation.catch(() => {})
    void operation.finally(() => { if (this.interrupting === operation) this.interrupting = undefined }).catch(() => {})
    return operation
  }

  private async interruptActive(owned: Entry | undefined, gates: Promise<void>[]): Promise<InterruptResult> {
    const interrupted = Promise.resolve().then(() => this.runtime.interrupt())
    const ownedDone = owned ? owned.receipt.completed.then(() => {}) : Promise.resolve()
    const acknowledged = interrupted
      .then(() => Promise.all([ownedDone, ...gates]))
      .then(() => this.stoppedOrTerminal())
    void acknowledged.catch(() => {})
    let timer: ReturnType<typeof setTimeout> | undefined
    try {
      const result = await Promise.race([
        acknowledged,
        new Promise<InterruptResult>(resolve => { timer = setTimeout(() => resolve({ status: "unconfirmed" }), this.interruptTimeoutMs) }),
      ])
      if (result.status === "stopped") this.settleAfterInterrupt()
      return result
    } finally { if (timer) clearTimeout(timer) }
  }

  /** Close/fail are not native completion. Cancelled owned receipts are not confirmation either. */
  private async stoppedOrTerminal(): Promise<InterruptResult> {
    if (this.closing) {
      try { await this.closing }
      catch (error) { throw new CoreError("session_failed", "Session runtime failed; close and resume it", { cause: error }) }
      throw new CoreError("session_closed", "Session is closed")
    }
    if (this.state === "failed") throw new CoreError("session_failed", "Session runtime failed; close and resume it")
    if (this.state === "closed" || this.state === "closing") throw new CoreError("session_closed", "Session is closed")
    return { status: "stopped" }
  }

  close(): Promise<void> {
    if (this.closing) return this.closing
    this.paused = true
    this.changeState("closing")
    this.pending.clear()
    this.abort?.abort()
    this.active?.finish({ status: "cancelled" })
    this.closing = Promise.resolve().then(async () => {
      if (this.configuring) await this.configuring.catch(() => {})
      await this.runtime.close()
    }).then(() => {
      this.seen.clear()
      this.endActivityWaiters(new CoreError("session_closed", "Session is closed"))
      this.changeState("closed")
      this.onClosed()
    }, error => {
      this.closing = undefined
      this.changeState("failed")
      this.endActivityWaiters(new CoreError("session_failed", "Session runtime failed; close and resume it", { cause: asError(error) }))
      throw error
    })
    return this.closing
  }

  async steer(input: Pick<SendOptions, "content">): Promise<void> {
    this.assertReady()
    if (!this.runtime.capabilities.steer || !this.runtime.steer) throw new UnsupportedOperation("steer", this.record.agent)
    if (this.interrupting || this.state === "interrupting") throw new CoreError("session_busy", "Interrupt has not been confirmed")
    if (this.state !== "running") throw new CoreError("session_not_running", "No active work to steer")
    if (this.activity.size > 1) throw new CoreError("session_busy", "Native work is ambiguous")
    if (!this.active && this.activity.size !== 1) throw new CoreError("session_not_running", "No active work to steer")
    await this.runtime.steer(structuredClone(input.content))
  }

  /** Requested persisted configuration. An empty object means driver/factory defaults, not a live native snapshot. */
  configuration(): SessionConfiguration {
    if (this.record.configuration) return structuredClone(this.record.configuration)
    return {}
  }

  configure(patch: SessionConfiguration): Promise<void> {
    try {
      this.assertReady()
      if (!this.runtime.capabilities.configure || !this.runtime.configure) throw new UnsupportedOperation("configure", this.record.agent)
      if (this.active || this.queue.length || this.activity.size || this.state !== "idle") throw new CoreError("session_busy", "Configure requires an idle session with no pending input")
      const next = mergeConfiguration(this.record.configuration ?? {}, patch)
      const operation = this.applyConfiguration(next)
      this.configuring = operation
      void operation.finally(() => { if (this.configuring === operation) this.configuring = undefined }).catch(() => {})
      return operation
    } catch (error) { return Promise.reject(error) }
  }

  async history(options: HistoryOptions = {}): Promise<HistoryPage> {
    this.assertReady()
    if (!this.runtime.capabilities.history || !this.runtime.history) throw new UnsupportedOperation("history", this.record.agent)
    if (options.cursor !== undefined && typeof options.cursor !== "string") throw new CoreError("invalid_input", "History cursor must be a string")
    if (options.limit !== undefined && (!Number.isInteger(options.limit) || options.limit <= 0)) throw new CoreError("invalid_input", "History limit must be a positive integer")
    return this.runtime.history({ ...options })
  }

  fork(options: ForkOptions = {}): Promise<Session> {
    try {
      this.assertReady()
      if (!this.runtime.capabilities.fork) throw new UnsupportedOperation("fork", this.record.agent)
      if (this.active || this.queue.length || this.activity.size || this.state !== "idle") throw new CoreError("session_busy", "Fork requires an idle session with no pending input")
      if (options.at && (typeof options.at.nativeTurnId !== "string" || !options.at.nativeTurnId)) throw new CoreError("invalid_input", "Fork point must identify a native turn")
      const input = structuredClone(options)
      this.forking = Promise.resolve().then(() => this.createFork(input))
      void this.forking.finally(() => { this.forking = undefined }).catch(() => {})
      return this.forking
    } catch (error) { return Promise.reject(error) }
  }
  async detach(): Promise<void> { throw new UnsupportedOperation("detach", this.record.agent) }

  /** Driver callbacks target this handle, never a mutable global session lookup. */
  update(update: AgentUpdate): void {
    if (this.state === "closed") return
    this.emit({ type: "session.update", sessionId: this.id, update })
  }

  /** Native activity is independent of owned receipts. Duplicate start for an id is a no-op. */
  reportActivity(notice: ActivityNotice): void {
    if (this.state === "closed" || this.state === "closing" || this.state === "failed") return
    const copied = copyActivityNotice(notice)
    if (!copied) return
    if (copied.phase === "started") {
      if (this.activity.has(copied.id)) return
      if (this.activity.size >= MAX_OUTSTANDING_ACTIVITY) {
        this.fail(new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message))
        return
      }
      let settle!: (error?: Error) => void
      const done = new Promise<void>((resolve, reject) => {
        settle = error => { error ? reject(error) : resolve() }
      })
      void done.catch(() => {})
      this.activity.set(copied.id, { done, settle })
      if (this.state === "idle") this.changeState("running")
      return
    }
    const slot = this.activity.get(copied.id)
    if (!slot) return
    this.activity.delete(copied.id)
    slot.settle()
    this.afterWorkSettled()
  }

  fail(error: Error): void {
    if (this.state === "closed" || this.state === "closing" || this.state === "failed") return
    this.paused = true
    this.changeState("failed")
    this.active?.finish({ status: "failed", error })
    for (const entry of this.queue.splice(0)) entry.finish({ status: "failed", error })
    this.abort?.abort()
    this.endActivityWaiters(new CoreError("session_failed", "Session runtime failed; close and resume it", { cause: error }))
    this.emit({ type: "session.failed", sessionId: this.id, error })
  }

  private assertReady(): void {
    if (this.forking) throw new CoreError("session_busy", "Native conversation fork is in progress")
    if (this.closing || this.state === "closed" || this.state === "closing") throw new CoreError("session_closed", "Session is closed")
    if (this.configuring) throw new CoreError("session_busy", "Session configuration is in progress")
    if (this.state === "failed") throw new CoreError("session_failed", "Session runtime failed; close and resume it")
  }

  private async applyConfiguration(next: SessionConfiguration): Promise<void> {
    const requested = structuredClone(next)
    const previousNative = this.runtime.configuration
      ? structuredClone(this.runtime.configuration())
      : structuredClone(this.record.configuration ?? {})
    try {
      await this.runtime.configure!(structuredClone(requested))
    } catch (error) {
      await this.rollbackConfiguration(previousNative, error)
      throw asError(error)
    }
    const saved: SessionRecord = { ...this.record }
    if (Object.keys(requested).length) saved.configuration = structuredClone(requested)
    else delete saved.configuration
    try {
      await this.persistRecord(saved)
    } catch (error) {
      await this.rollbackConfiguration(previousNative, error)
      throw asError(error)
    }
    if (Object.keys(requested).length) this.record.configuration = structuredClone(requested)
    else delete this.record.configuration
  }

  private async rollbackConfiguration(previousNative: SessionConfiguration, cause: unknown): Promise<void> {
    try {
      await this.runtime.configure!(structuredClone(previousNative))
    } catch (rollbackError) {
      const failed = new AggregateError(
        [asError(cause), asError(rollbackError)],
        "Session configuration could not be saved or restored",
      )
      this.fail(failed)
      throw failed
    }
  }

  private drain(): void {
    if (this.active || this.paused || this.closing || this.configuring || this.state === "failed" || this.activity.size) return
    const entry = this.queue.shift()
    if (!entry) return
    this.active = entry
    const abort = new AbortController()
    this.abort = abort
    this.changeState("running")
    this.emit({ type: "message.started", sessionId: this.id, messageId: entry.receipt.messageId })
    void this.run(entry, abort)
  }

  private async run(entry: Entry, abort: AbortController): Promise<void> {
    try {
      const result = await this.runtime.prompt(entry.input.content, abort.signal)
      entry.finish(result.stopReason === "cancelled" ? { status: "cancelled" } : { status: "completed", stopReason: result.stopReason })
    } catch (error) {
      entry.finish(abort.signal.aborted ? { status: "cancelled" } : { status: "failed", error: asError(error) })
    } finally {
      if (this.active === entry) {
        this.active = undefined
        this.abort = undefined
        this.afterWorkSettled()
        this.drain()
      }
    }
  }

  private afterWorkSettled(): void {
    if (this.closing || this.state === "failed" || this.state === "closed" || this.state === "closing") return
    if (this.state === "interrupting") return
    if (this.active || this.activity.size) {
      if (this.state === "idle") this.changeState("running")
      return
    }
    this.changeState("idle")
    this.drain()
  }

  private settleAfterInterrupt(): void {
    if (this.closing || this.state === "failed" || this.state === "closed" || this.state === "closing") return
    if (this.active || this.activity.size) this.changeState("running")
    else this.changeState("idle")
  }

  private endActivityWaiters(error: Error): void {
    for (const slot of this.activity.values()) slot.settle(error)
    this.activity.clear()
  }

  private changeState(state: SessionState): void {
    if (this.state === state) return
    this.state = state
    this.emit({ type: "session.stateChanged", sessionId: this.id, state })
  }

  private trimSeen(): void {
    for (const [key, value] of this.seen) {
      if (this.seen.size <= 200) break
      if (value.entry.settled) this.seen.delete(key)
    }
  }
}


