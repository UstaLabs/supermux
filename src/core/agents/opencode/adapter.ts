import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/opencode/adapter")

// A prompt that opencode never picks up (observed when a request raced the
// server's cold boot after a broker restart: zero log lines, prompt() never
// resolved) produces no events and never settles — without a bound, send() and
// the chat UI hang forever. If no turn activity arrives within this window the
// turn is aborted and an error surfaced so the user can retry; the first activity
// disarms it, so genuinely long turns are never cut short.
const DEFAULT_STALL_MS = 90_000

// ---------------------------------------------------------------------------
// Minimal slice of the @opencode-ai/sdk client surface this adapter needs.
// Declaring it here (instead of importing the SDK's heavily-generic client
// type) keeps the adapter unit-testable with a fake and confines SDK-version
// coupling to the spawn-layer driver that builds the real client — the same
// decoupling CodexAdapter gets from its local `JsonRpcLike` type.
// ---------------------------------------------------------------------------

export type OpenCodePart =
  | { type: "text"; text?: string; sessionID?: string;[k: string]: unknown }
  | { type: "tool"; callID?: string; tool?: string; sessionID?: string; state?: { status?: string;[k: string]: unknown };[k: string]: unknown }
  | { type: string;[k: string]: unknown }

/** opencode bus events (a subset — the union is open so unknown events pass through). */
export type OpenCodeEvent =
  | { type: "message.part.updated"; properties: { part: OpenCodePart; delta?: string } }
  | { type: "session.idle"; properties: { sessionID?: string } }
  | { type: "session.error"; properties: { sessionID?: string; error?: { name?: string; data?: { message?: string } } } }
  | { type: string; properties?: Record<string, unknown> }

type OpenCodeResult<T> = { data?: T; error?: unknown }

export type OpenCodePromptBody = {
  parts: Array<{ type: string;[k: string]: unknown }>
  model?: { providerID: string; modelID: string }
}

export type OpenCodeCommandEntry = {
  name: string
  description?: string
  source?: string
}

export interface OpenCodeClientLike {
  session: {
    create(opts: {
      body?: {
        title?: string
        permission?: Array<{ permission: string; pattern: string; action: string }>
      }
      query?: { directory?: string }
    }): Promise<OpenCodeResult<{ id?: string }>>
    update(opts: {
      sessionID: string
      permission?: Array<{ permission: string; pattern: string; action: string }>
    }): Promise<OpenCodeResult<unknown>>
    prompt(opts: { path: { id: string }; body: OpenCodePromptBody }): Promise<OpenCodeResult<{ parts?: OpenCodePart[] }>>
    abort(opts: { path: { id: string } }): Promise<unknown>
  }
  event: {
    subscribe(): Promise<{ stream: AsyncIterable<OpenCodeEvent> }>
  }
  /** Live command list for slash-menu discovery (skills-only filtering happens in the provider). */
  listCommands(workdir: string): Promise<OpenCodeCommandEntry[]>
}

export type OpenCodeAdapterOpts = {
  sessionName: string
  workdir: string
  client: OpenCodeClientLike
  persistSessionId: (id: string) => Promise<void>
  /** when resuming after a broker restart */
  initialSessionId?: string
  /** broker model string, encoded "<providerID>/<modelID>" (unset → opencode default) */
  model?: string
  /** Resolve an inbound attachment file_id to a local path. opencode runs
   * locally; v1 folds the resolved path into the prompt text (robust, no
   * guessing the file-part URL format) — richer image parts are a follow-up. */
  resolveAttachment?: (file_id: string) => Promise<string>
  /** Override the no-activity stall watchdog (ms). Default 90s; tests use a small value. */
  stallTimeoutMs?: number
}

function asError(e: unknown): Error {
  if (e instanceof Error) return e
  if (typeof e === "string") return new Error(e)
  if (e && typeof e === "object") {
    const obj = e as { message?: unknown; name?: unknown; data?: { message?: unknown } }
    if (typeof obj.data?.message === "string") return new Error(obj.data.message)
    if (typeof obj.message === "string") return new Error(obj.message)
    if (typeof obj.name === "string") return new Error(obj.name)
  }
  try { return new Error(JSON.stringify(e)) } catch { return new Error(String(e)) }
}

export class OpenCodeAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "opencode"
  readonly sessionName: string
  readonly workdir: string

  private client: OpenCodeClientLike
  private sessionId?: string
  private persistSessionId: (id: string) => Promise<void>
  private initialSessionId?: string
  private _model?: string
  private resolveAttachment?: (file_id: string) => Promise<string>
  private stallTimeoutMs: number
  /** Set during an in-flight turn; the event loop calls it on first activity to
   * disarm the stall watchdog. */
  private onTurnActivity?: () => void
  private closed = false
  /** tool callIDs we've already emitted a `started` for (dedupe the running stream). */
  private startedTools = new Set<string>()
  /** Inbound turns run one at a time — see send()/drain(). */
  private queue: Array<{ text: string; meta?: InboundMeta; resolve: () => void; reject: (e: Error) => void }> = []
  private draining = false

  /** Minimal client slice for slash-command discovery. */
  get commandClient(): Pick<OpenCodeClientLike, "listCommands"> {
    return { listCommands: (workdir) => this.client.listCommands(workdir) }
  }

  constructor(opts: OpenCodeAdapterOpts) {
    super()
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.client = opts.client
    this.persistSessionId = opts.persistSessionId
    this.initialSessionId = opts.initialSessionId
    this._model = opts.model
    this.resolveAttachment = opts.resolveAttachment
    this.stallTimeoutMs = opts.stallTimeoutMs ?? DEFAULT_STALL_MS
  }

  /** Live model switch: send() re-parses this on each turn (parseModel), so a
   * field update applies from the next turn — no serve restart. */
  set model(m: string | undefined) { this._model = m }
  get model(): string | undefined { return this._model }

  async start(): Promise<void> {
    const res = await this.client.session.create({
      body: {
        title: this.sessionName,
        permission: [
          { permission: "*", pattern: "*", action: "allow" },
          { permission: "question", pattern: "*", action: "deny" },
        ],
      },
      query: { directory: this.workdir },
    })
    if (res.error) throw asError(res.error)
    const id = res.data?.id
    if (!id) throw new Error(`opencode session.create returned no id: ${JSON.stringify(res.data)}`)
    this.sessionId = id
    await this.persistSessionId(id)
    void this.subscribeEvents()
  }

  async resume(): Promise<void> {
    if (!this.initialSessionId) throw new Error("opencode adapter: resume() requires initialSessionId")
    this.sessionId = this.initialSessionId
    // Patch permissions on resumed sessions so pre-fix sessions get auto-allow too
    try {
      await this.client.session.update({
        sessionID: this.sessionId,
        permission: [
          { permission: "*", pattern: "*", action: "allow" },
          { permission: "question", pattern: "*", action: "deny" },
        ],
      })
    } catch (err) {
      log.warn("opencode_resume_permission_update_failed", { session: this.sessionName, err: asError(err).message })
    }
    void this.subscribeEvents()
  }

  async stop(): Promise<void> {
    // Process supervision (the `opencode serve` child) lives in main.ts, the
    // same as codex. Mark closed so the event loop exits quietly.
    this.closed = true
  }

  // Inbound turns are queued and drained one at a time. opencode serializes
  // prompts per session server-side anyway, and firing a second prompt() while
  // one is in flight makes BOTH HTTP calls resolve to the session's *last*
  // assistant message — so an un-queued second send would clobber the first
  // turn's reply. The FIFO keeps a single prompt() in flight so each turn
  // returns its own reply. Mirrors CursorAdapter's queue/drain.
  async send(text: string, meta?: InboundMeta): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.queue.push({ text, meta, resolve, reject })
      if (!this.draining) void this.drain()
    })
  }

  private async drain(): Promise<void> {
    if (this.draining) return
    this.draining = true
    try {
      while (this.queue.length) {
        const next = this.queue.shift()!
        try {
          await this.runTurn(next.text, next.meta)
          next.resolve()
        } catch (err) {
          next.reject(err instanceof Error ? err : new Error(String(err)))
        }
      }
    } finally {
      this.draining = false
    }
  }

  private async runTurn(text: string, meta?: InboundMeta): Promise<void> {
    if (!this.sessionId) throw new Error("opencode adapter: not started")
    const prompt = await this.buildPrompt(text, meta)
    const body: OpenCodePromptBody = { parts: [{ type: "text", text: prompt }] }
    const model = this.parseModel()
    if (model) body.model = model

    // runTurn brackets the turn: the prompt() call resolves with the completed
    // assistant message, so the authoritative reply text comes from its return
    // value (no fragile streaming reconstruction). The event subscription only
    // adds live tool-call activity cards.
    this.emit("turn-start", { kind: "turn-start" })

    // Stall watchdog: bound the wait for opencode to START the turn. A live turn
    // streams `message.part.updated` (or prompt() resolves) within the cold-start
    // window; a lost prompt produces nothing and never settles. If no activity
    // arrives within stallTimeoutMs, abort the turn and surface an error so the UI
    // unblocks. First activity disarms it, so long turns run to completion.
    let stallTimer: ReturnType<typeof setTimeout> | undefined
    const disarm = () => { if (stallTimer) { clearTimeout(stallTimer); stallTimer = undefined } }
    this.onTurnActivity = disarm
    const watchdog = new Promise<never>((_, reject) => {
      stallTimer = setTimeout(
        () => reject(new Error(`opencode produced no response within ${Math.round(this.stallTimeoutMs / 1000)}s — the request appears stalled; please try again`)),
        this.stallTimeoutMs,
      )
    })

    let res: OpenCodeResult<{ info?: { error?: unknown }; parts?: OpenCodePart[] }>
    try {
      res = await Promise.race([
        this.client.session.prompt({ path: { id: this.sessionId }, body }),
        watchdog,
      ])
    } catch (err) {
      void Promise.resolve(this.interrupt()).catch(() => {})
      this.emit("error", { kind: "error", error: asError(err) })
      this.emit("turn-complete", { kind: "turn-complete" })
      return
    } finally {
      disarm()
      this.onTurnActivity = undefined
    }
    if (res.error) {
      this.emit("error", { kind: "error", error: asError(res.error) })
      this.emit("turn-complete", { kind: "turn-complete" })
      return
    }
    if (res.data?.info?.error) {
      this.emit("error", { kind: "error", error: asError(res.data.info.error) })
      this.emit("turn-complete", { kind: "turn-complete" })
      return
    }
    for (const part of res.data?.parts ?? []) {
      if (part.type === "text") {
        const t = (part as { text?: string }).text
        if (typeof t === "string" && t.trim()) {
          this.emit("assistant-message", { kind: "assistant-message", text: t })
        }
      }
    }
    this.emit("turn-complete", { kind: "turn-complete" })
  }

  async interrupt(): Promise<void> {
    if (!this.sessionId) return
    try {
      await this.client.session.abort({ path: { id: this.sessionId } })
    } catch (err) {
      log.warn("opencode_abort_failed", { session: this.sessionName, err: asError(err).message })
    }
  }

  // --- internals ----------------------------------------------------------

  /** Split the broker's "<providerID>/<modelID>" model string. Unset / unsplittable
   * → undefined, so opencode falls back to its configured default. */
  private parseModel(): { providerID: string; modelID: string } | undefined {
    if (!this.model) return undefined
    const slash = this.model.indexOf("/")
    if (slash <= 0 || slash >= this.model.length - 1) return undefined
    return { providerID: this.model.slice(0, slash), modelID: this.model.slice(slash + 1) }
  }

  private async buildPrompt(text: string, meta?: InboundMeta): Promise<string> {
    let prompt = text
    if (meta?.attachment_file_id && this.resolveAttachment) {
      try {
        const path = await this.resolveAttachment(meta.attachment_file_id)
        const label = meta.attachment_name ? `${meta.attachment_name} (${path})` : path
        prompt = prompt ? `${prompt}\n\n[Attached file: ${label}]` : `[Attached file: ${label}]`
      } catch (err) {
        log.warn("opencode_attachment_resolve_failed", { session: this.sessionName, file_id: meta.attachment_file_id, err: asError(err).message })
      }
    }
    return prompt
  }

  /** Background loop: map opencode tool-part state transitions to tool-call
   * activity events. Fail-safe — if the stream errors, the chat loop (driven
   * by send()/prompt()) keeps working. */
  private async subscribeEvents(): Promise<void> {
    let sub: { stream: AsyncIterable<OpenCodeEvent> }
    try {
      sub = await this.client.event.subscribe()
    } catch (err) {
      log.warn("opencode_event_subscribe_failed", { session: this.sessionName, err: asError(err).message })
      return
    }
    try {
      for await (const ev of sub.stream) {
        if (this.closed) break
        if (ev.type === "message.part.updated") this.onTurnActivity?.()
        this.handleEvent(ev)
      }
    } catch (err) {
      log.warn("opencode_event_stream_ended", { session: this.sessionName, err: asError(err).message })
    }
  }

  /** True once the SSE part carries enough to render a useful started card
   * (command/path/title). opencode streams incremental updates — the first
   * `running` event often has status only; input arrives on a later delta. */
  private hasToolSummary(part: OpenCodePart): boolean {
    const state = (part as { state?: Record<string, unknown> }).state
    if (!state || typeof state !== "object") return false
    const input = state.input
    if (input && typeof input === "object" && !Array.isArray(input) && Object.keys(input as object).length > 0) return true
    if (typeof state.title === "string" && state.title.trim()) return true
    if (typeof state.raw === "string" && state.raw.trim()) return true
    return false
  }

  private emitToolStarted(part: OpenCodePart, callId: string, tool: string): void {
    if (callId && this.startedTools.has(callId)) return
    if (callId) this.startedTools.add(callId)
    this.emit("tool-call", { kind: "tool-call", tool, phase: "started", call_id: callId, detail: part })
  }

  private handleEvent(ev: OpenCodeEvent): void {
    if (ev.type !== "message.part.updated") return
    const part = (ev.properties as { part?: OpenCodePart } | undefined)?.part
    if (!part || part.type !== "tool") return
    if (part.sessionID && this.sessionId && part.sessionID !== this.sessionId) return
    const callId = String((part as { callID?: string }).callID ?? "")
    const tool = String((part as { tool?: string }).tool ?? "tool")
    const status = (part as { state?: { status?: string } }).state?.status
    if (status === "pending" || status === "running") {
      if (callId && this.startedTools.has(callId)) return
      if (!this.hasToolSummary(part)) return
      this.emitToolStarted(part, callId, tool)
    } else if (status === "completed") {
      if (callId && !this.startedTools.has(callId)) this.emitToolStarted(part, callId, tool)
      this.emit("tool-call", { kind: "tool-call", tool, phase: "completed", call_id: callId, detail: part })
    } else if (status === "error") {
      if (callId && !this.startedTools.has(callId)) this.emitToolStarted(part, callId, tool)
      this.emit("tool-call", { kind: "tool-call", tool, phase: "failed", call_id: callId, detail: part })
    }
  }
}
