import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { AcpClient } from "./acp-client"
import { parseGrokUpdate } from "./stream-parser"
import type { GrokRunner } from "./runner"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/adapter")

/** No-activity stall watchdog (ms). First `session/update` disarms it so long
 * turns that stream thoughts/tools run to completion. Mirrors opencode. */
const DEFAULT_STALL_MS = 90_000

export type GrokAdapterOpts = {
  sessionName: string
  workdir: string
  runner: GrokRunner
  persistSessionId: (id: string) => Promise<void>
  initialSessionId?: string
  model?: string
  /** Reasoning effort (`high` | `medium` | `low` for grok-4.5). Spawn-time only. */
  effort?: string
  resolveAttachment?: (file_id: string) => Promise<string>
  env?: Record<string, string>
  /** Override the no-activity stall watchdog (ms). Default 90s; tests use a small value. */
  stallTimeoutMs?: number
}

function asError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err))
}

/** Build a user-facing exit message from the process code + recent stderr. */
export function formatGrokExitError(code: number | null, stderr?: string): Error {
  const tail = stderr?.trim()
  if (tail) {
    // Prefer the last non-empty line of stderr — usually the actual diagnostic.
    const lines = tail.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
    const last = lines[lines.length - 1] ?? tail
    const codeBit = code != null ? ` (exit ${code})` : ""
    return new Error(`grok agent exited${codeBit}: ${last}`)
  }
  if (code != null && code !== 0) return new Error(`grok agent exited with code ${code}`)
  return new Error("grok agent exited")
}

export class GrokAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "grok"
  readonly sessionName: string
  readonly workdir: string

  private client: AcpClient
  private child?: { kill: () => void }
  private sessionId?: string
  private persistSessionId: (id: string) => Promise<void>
  private runner: GrokRunner
  private _model?: string
  private _effort?: string
  private env: Record<string, string>
  private resolveAttachment?: (file_id: string) => Promise<string>
  private stallTimeoutMs: number
  availableModels: { modelId: string }[] = []

  private queue: { text: string; chat_id?: string; attachmentFileId?: string; resolve: () => void; reject: (e: Error) => void }[] = []
  private draining = false
  private activeChatId?: string
  private turnActive = false
  /** Disarm callback for the in-flight stall watchdog; set while a turn waits
   * for first activity. */
  private onTurnActivity?: () => void

  constructor(opts: GrokAdapterOpts) {
    super()
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.runner = opts.runner
    this.persistSessionId = opts.persistSessionId
    this.sessionId = opts.initialSessionId
    this._model = opts.model
    this._effort = opts.effort
    this.env = opts.env ?? {}
    this.resolveAttachment = opts.resolveAttachment
    this.stallTimeoutMs = opts.stallTimeoutMs ?? DEFAULT_STALL_MS
    this.client = new AcpClient(() => {})
    this.client.onNotification = (method, params) => this.onNotification(method, params)
    this.client.onServerRequest = (method, params) => this.onServerRequest(method, params)
  }

  // A model switch is applied live over ACP (session/set_model). The broker treats
  // grok as a live-switch agent, so this setter must not need a respawn.
  set model(m: string | undefined) {
    this._model = m
    if (m && this.sessionId) {
      this.client.request("session/set_model", { sessionId: this.sessionId, modelId: m })
        .catch((err) => log.warn("grok_set_model_failed", { session: this.sessionName, model: m, err: String(err) }))
    }
  }
  get model(): string | undefined { return this._model }

  /** Reasoning effort is a spawn-time flag with no ACP setter, so changing it
   * relaunches the stdio child. The grok session id is already persisted, so
   * start() reloads the same conversation via loadSessionId — history survives. */
  async setEffort(effort: string | undefined): Promise<void> {
    if (effort === this._effort) return
    this._effort = effort
    if (!this.child) return
    this.child.kill()
    this.child = undefined
    this.client = new AcpClient(() => {})
    this.client.onNotification = (method, params) => this.onNotification(method, params)
    this.client.onServerRequest = (method, params) => this.onServerRequest(method, params)
    await this.start()
  }

  get effort(): string | undefined { return this._effort }

  async start(): Promise<void> {
    // model/effort are spawn-time flags: `grok agent --model M --reasoning-effort E stdio`.
    // session/prompt ignores a `model` param, so this is the only way effort takes hold.
    this.child = this.runner({
      workdir: this.workdir, env: this.env, client: this.client,
      model: this._model, effort: this._effort,
      onExit: (code, stderr) => this.onExit(code, stderr),
    })
    const init: any = await this.client.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: { fs: { readTextFile: true, writeTextFile: true } },
    })
    this.availableModels = init?._meta?.modelState?.availableModels ?? init?.modelState?.availableModels ?? []
    // No mcpServers here: grok ignores the param on session/new. mux-shim is
    // registered in the session-private ~/.grok/config.toml instead.
    const res: any = await this.client.request("session/new", {
      cwd: this.workdir,
      mcpServers: [],
      ...(this.sessionId ? { loadSessionId: this.sessionId } : {}),
    })
    // session/new echoes modelState too; prefer it as the freshest view.
    if (res?.models?.availableModels) this.availableModels = res.models.availableModels
    if (res?.sessionId) {
      this.sessionId = res.sessionId
      this.persistSessionId(res.sessionId).catch(() => {})
    }
  }

  async resume(): Promise<void> { if (!this.child) await this.start() }

  async stop(): Promise<void> { this.child?.kill(); this.child = undefined }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.queue.push({ text, chat_id: meta?.chat_id, attachmentFileId: meta?.attachment_file_id, resolve, reject })
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
          this.activeChatId = next.chat_id
          const text = await this.withAttachment(next.text, next.attachmentFileId)
          await this.runOne(text)
          next.resolve()
        } catch (err: any) {
          // runOne emits `error` for turn failures and does not rethrow them.
          // Remaining throws (e.g. session not initialized) still reject send().
          next.reject(err instanceof Error ? err : new Error(String(err)))
        } finally { this.activeChatId = undefined }
      }
    } finally { this.draining = false }
  }

  private async withAttachment(text: string, fileId?: string): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    try {
      const path = await this.resolveAttachment(fileId)
      return text ? `${text}\n\n[Attached file: ${path}]` : `[Attached file: ${path}]`
    } catch { return text }
  }

  private async runOne(text: string): Promise<void> {
    if (!this.sessionId) throw new Error("grok session not initialized")
    this.turnActive = true
    this.emit("turn-start", { kind: "turn-start" })

    // Stall watchdog: bound the wait for grok to START producing activity. A
    // live turn streams session/update frames; a lost/hung prompt produces
    // nothing and never settles — e.g. the handoff session stuck on "Continue".
    // First activity disarms it, so long turns run to completion.
    let stallTimer: ReturnType<typeof setTimeout> | undefined
    const disarm = () => { if (stallTimer) { clearTimeout(stallTimer); stallTimer = undefined } }
    this.onTurnActivity = disarm
    const watchdog = new Promise<never>((_, reject) => {
      stallTimer = setTimeout(
        () => reject(new Error(
          `grok produced no response within ${Math.round(this.stallTimeoutMs / 1000)}s — the request appears stalled; please try again`,
        )),
        this.stallTimeoutMs,
      )
    })

    try {
      // No `model` here: grok ignores it on session/prompt. The model is set by
      // the --model spawn flag and changed live via session/set_model.
      await Promise.race([
        this.client.request("session/prompt", {
          sessionId: this.sessionId,
          prompt: [{ type: "text", text }],
        }),
        watchdog,
      ])
    } catch (err) {
      const error = asError(err)
      // Faithful surface: wireAdapterEvents → notifyAgentError (toast + push).
      // Without this, JSON-RPC / exit / stall failures only hit broker logs and
      // the user sees an idle (or forever-working) session with no explanation.
      // Do not rethrow: matching opencode, the error event is the user-facing
      // path and send() resolves so the broker safety net doesn't double-toast.
      this.emit("error", { kind: "error", error })
      // On stall, cancel the in-flight turn so the child doesn't keep working
      // after we've already told the user it failed.
      if (/stalled/i.test(error.message)) {
        void this.interrupt().catch(() => {})
      }
    } finally {
      disarm()
      this.onTurnActivity = undefined
      this.turnActive = false
      this.flushAssistant()
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  // grok streams `agent_message_chunk` as token-level DELTAS ("gro", "k", "-", "live"),
  // not cumulative snapshots. Emitting each chunk as its own assistant-message would
  // push one chat message per token. Accumulate deltas, then flush at natural speech
  // boundaries: (1) a new tool_call (commentary before tools) and (2) turn end.
  // Live-verified (grok 0.2.101): multi-step turns interleave
  //   agent_message_chunk* → tool_call → … → agent_message_chunk* → end_turn
  // ACP's optional messageId on chunks is NOT emitted by grok today.
  private pendingAssistantText = ""

  private onNotification(method: string, params: unknown): void {
    if (method !== "session/update") return
    // Any session/update counts as turn activity for the stall watchdog
    // (thoughts, tool calls, message chunks).
    this.onTurnActivity?.()
    for (const ev of parseGrokUpdate(params)) {
      if (ev.kind === "assistant-message") {
        this.pendingAssistantText += ev.text
      } else if (ev.kind === "tool-call") {
        // Flush any pre-tool narration so the user sees it while tools run,
        // instead of waiting for the whole turn to finish.
        if (ev.phase === "started") this.flushAssistant()
        this.emit("tool-call", { kind: "tool-call", tool: ev.tool, phase: ev.phase, call_id: ev.call_id, detail: ev.detail })
      }
    }
  }

  /** Emit buffered assistant text as one chat message. Safe to call with an empty
   * buffer (no-op). Used on tool boundaries and turn end (incl. interrupt/crash). */
  private flushAssistant(): void {
    const text = this.pendingAssistantText.trim()
    this.pendingAssistantText = ""
    if (text) this.emit("assistant-message", { kind: "assistant-message", text, chat_id: this.activeChatId })
  }

  private async onServerRequest(method: string, params: unknown): Promise<unknown> {
    if (method === "session/request_permission") {
      const opts = (params as any)?.options as { optionId: string; kind?: string }[] | undefined
      const allow = opts?.find((o) => /allow|approve|accept|yes/i.test(o.optionId) || o.kind === "allow_once" || o.kind === "allow_always")
      const optionId = allow?.optionId ?? opts?.[0]?.optionId
      return optionId ? { outcome: { outcome: "selected", optionId } } : { outcome: { outcome: "cancelled" } }
    }
    return {}
  }

  private onExit(code: number | null, stderr?: string): void {
    // Reject any in-flight JSON-RPC (session/prompt, initialize, …) with a
    // faithful message; runOne's catch emits the user-facing `error` event and
    // its finally emits turn-complete. Do NOT emit turn-complete here — that
    // double-fired Stop on the agent-state store.
    this.client.fail(formatGrokExitError(code, stderr))
    this.child = undefined
  }

  async interrupt(): Promise<void> {
    if (this.sessionId) this.client.notify("session/cancel", { sessionId: this.sessionId })
  }
}
