import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { AcpClient } from "./acp-client"
import { parseGrokUpdate } from "./stream-parser"
import type { GrokRunner } from "./runner"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/adapter")

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
  availableModels: { modelId: string }[] = []

  private queue: { text: string; chat_id?: string; attachmentFileId?: string; resolve: () => void; reject: (e: Error) => void }[] = []
  private draining = false
  private activeChatId?: string
  private turnActive = false

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
      onExit: (code) => this.onExit(code),
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
    try {
      // No `model` here: grok ignores it on session/prompt. The model is set by
      // the --model spawn flag and changed live via session/set_model.
      await this.client.request("session/prompt", {
        sessionId: this.sessionId,
        prompt: [{ type: "text", text }],
      })
    } finally {
      this.turnActive = false
      this.flushAssistant()
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  // grok streams `agent_message_chunk` as token-level DELTAS ("gro", "k", "-", "live"),
  // not cumulative snapshots. Emitting each chunk as its own assistant-message would
  // push one chat message per token, so accumulate and emit once per turn (cursor does
  // the same for a different reason — its chunks are cumulative, so it keeps the last
  // rather than concatenating).
  private pendingAssistantText = ""

  private onNotification(method: string, params: unknown): void {
    if (method !== "session/update") return
    for (const ev of parseGrokUpdate(params)) {
      if (ev.kind === "assistant-message") {
        this.pendingAssistantText += ev.text
      } else if (ev.kind === "tool-call") {
        this.emit("tool-call", { kind: "tool-call", tool: ev.tool, phase: ev.phase, call_id: ev.call_id, detail: ev.detail })
      }
    }
  }

  /** Emit the turn's accumulated text as ONE message. Called on turn end — including
   * an interrupted turn, so a partial answer still reaches the user. */
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

  private onExit(_code: number | null): void {
    this.client.fail(new Error("grok agent exited"))
    if (this.turnActive) {
      this.turnActive = false
      this.flushAssistant()
      this.emit("turn-complete", { kind: "turn-complete" })
    }
    this.child = undefined
  }

  async interrupt(): Promise<void> {
    if (this.sessionId) this.client.notify("session/cancel", { sessionId: this.sessionId })
  }
}
