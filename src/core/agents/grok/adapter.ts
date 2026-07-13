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
  resolveAttachment?: (file_id: string) => Promise<string>
  mcpServers?: unknown[]
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
  private mcpServers: unknown[]
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
    this.mcpServers = opts.mcpServers ?? []
    this.env = opts.env ?? {}
    this.resolveAttachment = opts.resolveAttachment
    this.client = new AcpClient(() => {})
    this.client.onNotification = (method, params) => this.onNotification(method, params)
    this.client.onServerRequest = (method, params) => this.onServerRequest(method, params)
  }

  set model(m: string | undefined) { this._model = m }
  get model(): string | undefined { return this._model }

  async start(): Promise<void> {
    this.child = this.runner({
      workdir: this.workdir, env: this.env, client: this.client,
      onExit: (code) => this.onExit(code),
    })
    const init: any = await this.client.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: { fs: { readTextFile: true, writeTextFile: true } },
    })
    this.availableModels = init?._meta?.modelState?.availableModels ?? init?.modelState?.availableModels ?? []
    const res: any = await this.client.request("session/new", {
      cwd: this.workdir,
      mcpServers: this.mcpServers,
      ...(this.sessionId ? { loadSessionId: this.sessionId } : {}),
    })
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
      await this.client.request("session/prompt", {
        sessionId: this.sessionId,
        prompt: [{ type: "text", text }],
        ...(this._model ? { model: this._model } : {}),
      })
    } finally {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  private onNotification(method: string, params: unknown): void {
    if (method !== "session/update") return
    for (const ev of parseGrokUpdate(params)) {
      if (ev.kind === "assistant-message") {
        this.emit("assistant-message", { kind: "assistant-message", text: ev.text, chat_id: this.activeChatId })
      } else if (ev.kind === "tool-call") {
        this.emit("tool-call", { kind: "tool-call", tool: ev.tool, phase: ev.phase, call_id: ev.call_id, detail: ev.detail })
      }
    }
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
    if (this.turnActive) { this.turnActive = false; this.emit("turn-complete", { kind: "turn-complete" }) }
    this.child = undefined
  }

  async interrupt(): Promise<void> {
    if (this.sessionId) this.client.notify("session/cancel", { sessionId: this.sessionId })
  }
}
