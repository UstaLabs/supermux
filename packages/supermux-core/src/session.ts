import { requireCloseMode } from "./types.js"
import { randomUUID } from "node:crypto"
import { ACTIVITY_OVERFLOW, copyActivityNotice } from "./activity.js"
import { mergeConfiguration } from "./configuration.js"
import { CoreError, UnsupportedOperation, asError } from "./errors.js"
import type {
  ActivityNotice, AgentRuntime, AgentUpdate, Capabilities, Completion, CoreEvent, InterruptResult,
  Receipt, SendOptions, SessionRecord, SessionState, ForkOptions,
  HistoryOptions, HistoryPage, SessionConfiguration, CloseOptions,
  PendingRequest, PermissionOptionKind, PermissionRequest, PermissionResponse, RequestAnswer,
  QuestionRequest, QuestionResponse, PermissionsSpec, PermissionsApplied,
} from "./types.js"
import { validatePermissionsSpec } from "./permissions.js"
import type { EventEnvelope, NormalizedBody, ToolCategory, TurnCompleteReason } from "./events/normalized.js"

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
  private eventSeq = 0
  private turnSeq = 0
  private currentTurnId?: string
  private completeReason: TurnCompleteReason = "ok"
  private readonly pendingRequests = new Map<string, {
    request: PendingRequest
    resolve: (response: PermissionResponse | QuestionResponse) => void
    signal: AbortSignal
    onAbort: () => void
  }>()
  private readonly nonBlockingQuestions = new Set<string>()

  constructor(
    private readonly record: SessionRecord,
    private readonly runtime: AgentRuntime,
    private readonly emit: (event: CoreEvent) => void,
    private readonly interruptTimeoutMs: number,
    private readonly maxPending: number,
    private readonly outstandingActivity: number,
    private readonly onClosed: () => void,
    private readonly createFork: (options: ForkOptions) => Promise<Session>,
    private readonly persistRecord: (record: SessionRecord) => Promise<void>,
  ) { this.id = record.id }

  snapshot(): SessionRecord & { state: SessionState; pending: number; paused: boolean; pendingRequests: number } {
    return {
      ...structuredClone(this.record),
      state: this.state,
      pending: this.queue.length,
      paused: this.paused,
      pendingRequests: this.pendingRequests.size,
    }
  }

  capabilities(): Capabilities { return { ...this.runtime.capabilities } }

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

  readonly requests = {
    list: (): PendingRequest[] => [...this.pendingRequests.values()].map(slot => structuredClone(slot.request)),
    respond: (requestId: string, answer: RequestAnswer): Promise<void> => {
      try {
        if (typeof requestId !== "string" || !requestId) throw new CoreError("request_not_found", "Unknown permission request")
        const slot = this.pendingRequests.get(requestId)
        if (!slot) {
          if (this.nonBlockingQuestions.has(requestId)) {
            throw new CoreError("request_not_found", "non-blocking question: answer with session.send")
          }
          throw new CoreError("request_not_found", "Unknown permission request")
        }
        if (slot.request.kind === "permission") {
          if (!answer || !("optionId" in answer) || typeof answer.optionId !== "string") {
            throw new CoreError("invalid_input", "optionId is required")
          }
          const allowed = slot.request.body.options.some(option => option.id === answer.optionId)
          if (!allowed) throw new CoreError("invalid_input", "optionId is not among the request options")
          const message = "message" in answer && answer.message !== undefined ? { message: answer.message } : {}
          this.finishRequest(requestId, {
            outcome: { outcome: "selected", optionId: answer.optionId },
            ...message,
          }, "answered", { optionId: answer.optionId, ...message })
          return Promise.resolve()
        }
        if (answer && "decline" in answer && answer.decline === true) {
          this.finishRequest(requestId, { outcome: "declined" }, "answered", { decline: true })
          return Promise.resolve()
        }
        if (!answer || !("answers" in answer) || !answer.answers || typeof answer.answers !== "object" || Array.isArray(answer.answers)) {
          throw new CoreError("invalid_input", "answers are required for a question request")
        }
        const mapped = mapQuestionAnswers(slot.request.body, answer.answers)
        // `mapped` already carries the option LABELS the agent receives, so it is the human-
        // readable form too — no second lookup needed downstream.
        this.finishRequest(requestId, { outcome: "answered", answers: mapped }, "answered", { answers: mapped })
        return Promise.resolve()
      } catch (error) {
        return Promise.reject(error)
      }
    },
  }

  requestAnswers(request: QuestionRequest, signal: AbortSignal): Promise<QuestionResponse> {
    if (this.state === "closed" || this.state === "closing" || this.state === "failed" || signal.aborted) {
      return Promise.resolve({ outcome: "cancelled" })
    }
    if (this.pendingRequests.size >= this.maxPending) {
      this.emitNormalized({ kind: "warning", message: "Pending permission request cap reached", source: "requests" }, undefined, false)
      return Promise.resolve({ outcome: "cancelled" })
    }
    const requestId = randomUUID()
    const body = questionBody(requestId, request)
    const pending: PendingRequest = { requestId, kind: "question", createdAt: new Date().toISOString(), body }
    return new Promise<QuestionResponse>(resolve => {
      const onAbort = () => this.finishRequest(requestId, { outcome: "cancelled" }, "cancelled")
      this.pendingRequests.set(requestId, { request: pending, resolve: resolve as (response: PermissionResponse | QuestionResponse) => void, signal, onAbort })
      if (signal.aborted) {
        onAbort()
        return
      }
      signal.addEventListener("abort", onAbort, { once: true })
      this.emitNormalized(body, undefined, false)
    })
  }

  requestPermission(request: PermissionRequest, signal: AbortSignal): Promise<PermissionResponse> {
    if (this.state === "closed" || this.state === "closing" || this.state === "failed" || signal.aborted) {
      return Promise.resolve({ outcome: { outcome: "cancelled" } })
    }
    if (this.pendingRequests.size >= this.maxPending) {
      this.emitNormalized({ kind: "warning", message: "Pending permission request cap reached", source: "requests" }, undefined, false)
      return Promise.resolve({ outcome: { outcome: "cancelled" } })
    }
    const requestId = randomUUID()
    const body = permissionBody(requestId, request)
    if (!body.options.length) {
      // The agent offered nothing we can classify: never invent choices, never guess "allow".
      this.emitNormalized({ kind: "warning", message: "Permission request had no usable options; cancelled", source: "requests" }, undefined, false)
      return Promise.resolve({ outcome: { outcome: "cancelled" } })
    }
    const pending: PendingRequest = { requestId, kind: "permission", createdAt: new Date().toISOString(), body }
    return new Promise<PermissionResponse>(resolve => {
      const onAbort = () => this.finishRequest(requestId, { outcome: { outcome: "cancelled" } }, "cancelled")
      this.pendingRequests.set(requestId, { request: pending, resolve: resolve as (response: PermissionResponse | QuestionResponse) => void, signal, onAbort })
      if (signal.aborted) {
        onAbort()
        return
      }
      signal.addEventListener("abort", onAbort, { once: true })
      this.emitNormalized(body, undefined, false)
    })
  }

  interrupt(options: { pending: "keep" | "discard" }): Promise<InterruptResult> {
    if (!options || (options.pending !== "keep" && options.pending !== "discard")) {
      return Promise.reject(new TypeError("pending is required and must be keep or discard"))
    }
    try { this.assertReady() } catch (error) { return Promise.reject(error) }
    this.paused = true
    if (options.pending === "discard") this.pending.clear()
    if (this.interrupting) return this.interrupting
    const owned = this.active
    const gates = [...this.activity.values()].map(slot => slot.done)
    if (!owned && !gates.length) return Promise.resolve({ status: "already_idle" })
    this.completeReason = "interrupt"
    this.changeState("interrupting")
    // An interrupted turn cannot keep asking: resolve its pending requests as cancelled before
    // telling the agent to stop, so a prompt blocked on a permission can unwind and the
    // interrupt can be confirmed (request-resolved 'cancelled' reaches the UI).
    this.cancelPendingRequests()
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

  close(options: CloseOptions): Promise<void> {
    const mode = requireCloseMode(options)
    if (mode === "detach" && !this.runtime.capabilities.detach) {
      throw new UnsupportedOperation("detach", this.record.agent)
    }
    if (this.closing) return this.closing
    this.paused = true
    this.changeState("closing")
    this.pending.clear()
    this.cancelPendingRequests()
    // Detach drops THIS process's view only: the agent keeps working behind the keeper and a
    // later resume re-attaches to the same turn. Aborting the prompt here would interrupt it.
    // Shutdown stops the agent, so the owned turn is cancelled for real.
    if (mode === "shutdown") this.abort?.abort()
    this.active?.finish({ status: "cancelled" })
    this.closing = Promise.resolve().then(async () => {
      if (this.configuring) await this.configuring.catch(() => {})
      await this.runtime.close({ mode })
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

  async setPermissions(spec: PermissionsSpec): Promise<{ applied: PermissionsApplied }> {
    this.assertReady()
    if (!this.runtime.capabilities.permissions || !this.runtime.setPermissions) {
      throw new UnsupportedOperation("permissions", this.record.agent)
    }
    const next = validatePermissionsSpec(spec)
    const result = await this.runtime.setPermissions(structuredClone(next))
    const saved: SessionRecord = { ...this.record, permissions: structuredClone(next) }
    await this.persistRecord(saved)
    this.record.permissions = structuredClone(next)
    this.emitNormalized({ kind: "permissions-update", spec: structuredClone(next), applied: result.applied }, undefined, false)
    return result
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

  fork(options: ForkOptions): Promise<Session> {
    try {
      this.assertReady()
      if (!options || typeof options.id !== "string") throw new TypeError("id is required")
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
    const bodies = this.runtime.normalize?.(update) ?? []
    for (const body of bodies) this.emitNormalized(body, update)
  }

  /** Native activity is independent of owned receipts. Duplicate start for an id is a no-op. */
  reportActivity(notice: ActivityNotice): void {
    if (this.state === "closed" || this.state === "closing" || this.state === "failed") return
    const copied = copyActivityNotice(notice)
    if (!copied) return
    if (copied.phase === "started") {
      if (this.activity.has(copied.id)) return
      if (this.activity.size >= this.outstandingActivity) {
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
    this.completeReason = "error"
    this.changeState("failed")
    this.active?.finish({ status: "failed", error })
    for (const entry of this.queue.splice(0)) entry.finish({ status: "failed", error })
    this.cancelPendingRequests()
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
    const previous = this.state
    this.state = state
    this.emit({ type: "session.stateChanged", sessionId: this.id, state })
    if (state === "running" && previous === "idle") {
      this.turnSeq += 1
      this.currentTurnId = `turn:${this.turnSeq}`
      this.completeReason = "ok"
      this.emitNormalized({ kind: "turn-start" }, undefined, false)
    }
    if (state === "idle" && (previous === "running" || previous === "interrupting")) {
      this.flushNormalized(previous === "interrupting" ? "interrupt" : this.completeReason)
      this.currentTurnId = undefined
    }
  }

  private flushNormalized(reason: TurnCompleteReason): void {
    const flushed = this.runtime.flush?.() ?? []
    for (const body of flushed) this.emitNormalized(body, undefined, false)
    this.emitNormalized({ kind: "turn-complete", reason }, undefined, false)
  }

  private emitNormalized(body: NormalizedBody, update?: AgentUpdate, replayFlag?: boolean): void {
    this.eventSeq += 1
    const replay = replayFlag ?? update?.replay === true
    const method = update ? (update.protocol === "acp" ? recSessionUpdate(update.value) : recMethod(update.value)) : undefined
    const native = update
      ? {
          protocol: nativeProtocol(update),
          ...(method ? { method } : {}),
          payload: update.value,
        }
      : { protocol: "core" as const, payload: body }
    const envelope: EventEnvelope & NormalizedBody = {
      sessionId: this.id,
      agent: this.record.agent,
      seq: this.eventSeq,
      ts: new Date().toISOString(),
      replay,
      origin: replay ? "replay" : "live",
      native,
      ...body,
    }
    if (this.currentTurnId) envelope.turnId = this.currentTurnId
    if (body.kind === "user-question" && body.blocking === false) this.nonBlockingQuestions.add(body.requestId)
    this.emit({ type: "session.event", sessionId: this.id, event: envelope })
  }

  /**
   * [answer] is recorded here, at respond() time, because it is the only place that knows what
   * the user picked: the response handed back to the agent is in the agent's own vocabulary and
   * a subscriber watching the event stream cannot recover the choice from it.
   */
  private finishRequest(
    requestId: string,
    response: PermissionResponse | QuestionResponse,
    outcome: "answered" | "expired" | "cancelled",
    answer?: RequestAnswer,
  ): void {
    const slot = this.pendingRequests.get(requestId)
    if (!slot) return
    this.pendingRequests.delete(requestId)
    slot.signal.removeEventListener("abort", slot.onAbort)
    slot.resolve(response)
    this.emitNormalized(
      { kind: "request-resolved", requestId, outcome, ...(answer ? { answer } : {}) },
      undefined,
      false,
    )
  }

  private cancelPendingRequests(): void {
    for (const [id, slot] of [...this.pendingRequests.entries()]) {
      if (slot.request.kind === "question") this.finishRequest(id, { outcome: "cancelled" }, "cancelled")
      else this.finishRequest(id, { outcome: { outcome: "cancelled" } }, "cancelled")
    }
  }

  private trimSeen(): void {
    for (const [key, value] of this.seen) {
      if (this.seen.size <= 200) break
      if (value.entry.settled) this.seen.delete(key)
    }
  }
}

const OPTION_KINDS = new Set<PermissionOptionKind>(["allow_once", "allow_always", "reject_once", "reject_always"])
const TOOL_CATEGORIES = new Set<ToolCategory>([
  "read", "edit", "delete", "move", "search", "execute", "fetch", "mcp", "web-search", "think", "other",
])

function questionBody(requestId: string, request: QuestionRequest): Extract<NormalizedBody, { kind: "user-question" }> {
  const raw = Array.isArray(request.questions) ? request.questions : []
  const used = new Set<string>()
  const questions = raw.map((q, index) => {
    const header = typeof q.header === "string" && q.header ? q.header : undefined
    const candidate = typeof q.id === "string" && q.id ? q.id : header
    const id = candidate && !used.has(candidate) ? candidate : `q${index + 1}`
    used.add(id)
    const prompt = typeof q.question === "string" && q.question
      ? q.question
      : typeof q.prompt === "string" ? q.prompt : ""
    const options = Array.isArray(q.options)
      ? q.options.map((opt, j) => {
          const label = typeof opt.label === "string" ? opt.label : String(opt)
          return {
            id: `o${j + 1}`,
            label,
            ...(typeof opt.description === "string" && opt.description ? { description: opt.description } : {}),
          }
        })
      : []
    return {
      id,
      prompt,
      ...(header ? { header } : {}),
      multiSelect: q.multiSelect === true,
      allowFreeText: q.allowFreeText === true,
      options,
    }
  })
  return { kind: "user-question", requestId, blocking: true, questions }
}

function mapQuestionAnswers(
  body: Extract<NormalizedBody, { kind: "user-question" }>,
  answers: Record<string, string | string[]>,
): Record<string, string | string[]> {
  const byId = new Map(body.questions.map(q => [q.id, q]))
  const mapped: Record<string, string | string[]> = {}
  for (const [qid, value] of Object.entries(answers)) {
    const question = byId.get(qid)
    if (!question) throw new CoreError("invalid_input", "Unknown question id")
    const optionById = new Map(question.options.map(o => [o.id, o.label]))
    if (Array.isArray(value)) {
      mapped[qid] = value.map(item => {
        if (typeof item !== "string") throw new CoreError("invalid_input", "Option ids must be strings")
        return optionById.get(item) ?? item
      })
    } else if (typeof value === "string") {
      mapped[qid] = optionById.get(value) ?? value
    } else {
      throw new CoreError("invalid_input", "Answer must be a string or string array")
    }
  }
  return mapped
}

function permissionBody(requestId: string, request: PermissionRequest): Extract<NormalizedBody, { kind: "permission-request" }> {
  const rawCall = request.toolCall as { toolCallId?: string; title?: string; kind?: string; rawInput?: unknown } | undefined
  const callId = typeof rawCall?.toolCallId === "string" && rawCall.toolCallId ? rawCall.toolCallId : requestId
  const title = typeof rawCall?.title === "string" && rawCall.title ? rawCall.title : callId
  const tool = typeof rawCall?.kind === "string" && rawCall.kind ? rawCall.kind : title
  const category = typeof rawCall?.kind === "string" && TOOL_CATEGORIES.has(rawCall.kind as ToolCategory)
    ? rawCall.kind as ToolCategory
    : undefined
  const options = Array.isArray(request.options) && request.options.length
    ? request.options.map((option, index) => {
        const row = option as { optionId?: string; id?: string; kind?: string; name?: string; label?: string }
        const id = typeof row.optionId === "string" && row.optionId
          ? row.optionId
          : typeof row.id === "string" && row.id ? row.id : `option:${index}`
        // Never guess "allow": an option whose kind we cannot classify is dropped below.
        const hint = `${id} ${typeof row.name === "string" ? row.name : ""}`.toLowerCase()
        const kind: PermissionOptionKind | undefined = OPTION_KINDS.has(row.kind as PermissionOptionKind)
          ? row.kind as PermissionOptionKind
          : OPTION_KINDS.has(id as PermissionOptionKind) ? id as PermissionOptionKind
          : /\b(reject|deny|decline|cancel|no)\b/.test(hint) ? "reject_once"
          : undefined
        const label = typeof row.label === "string" && row.label
          ? row.label
          : typeof row.name === "string" && row.name ? row.name : id
        return kind ? { id, kind, label } : undefined
      }).filter((option): option is { id: string; kind: PermissionOptionKind; label: string } => option !== undefined)
    : []
  const rawInput = rawCall?.rawInput && typeof rawCall.rawInput === "object" ? rawCall.rawInput as Record<string, unknown> : undefined
  const inputCommand = typeof rawInput?.command === "string" ? rawInput.command : undefined
  const detail = (request.detail && typeof request.detail === "object") || inputCommand ? {
    ...(typeof request.detail?.command === "string" ? { command: request.detail.command } : inputCommand ? { command: inputCommand } : {}),
    ...(typeof request.detail?.cwd === "string" ? { cwd: request.detail.cwd } : {}),
    ...(typeof request.detail?.blockedPath === "string" ? { blockedPath: request.detail.blockedPath } : {}),
  } : undefined
  return {
    kind: "permission-request",
    requestId,
    toolCall: {
      callId,
      tool,
      title,
      ...(rawCall?.rawInput !== undefined ? { input: rawCall.rawInput } : {}),
      ...(category ? { category } : {}),
    },
    options,
    ...(detail && Object.keys(detail).length ? { detail } : {}),
  }
}

function recMethod(value: unknown): string | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  const method = (value as { method?: unknown }).method
  return typeof method === "string" ? method : undefined
}

function recSessionUpdate(value: unknown): string | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  const kind = (value as { sessionUpdate?: unknown }).sessionUpdate
  return typeof kind === "string" ? kind : undefined
}

function nativeProtocol(update: AgentUpdate): "acp" | "codex-app-server" {
  if (update.protocol === "acp") return "acp"
  const method = recMethod(update.value)
  if (method?.startsWith("_x.ai/") || method?.startsWith("session/")) return "acp"
  return "codex-app-server"
}


