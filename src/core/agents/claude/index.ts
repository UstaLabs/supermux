import { EventEmitter } from "events"
import type { AgentAdapter, AssistantMessageEvent, InboundMeta, AgentKind } from "../types"

export type ClaudeAdapterOpts = {
  sessionName: string
  workdir: string
  // Functions that drive the existing socket-server. Adapter doesn't own them.
  sendInboundSocket: (payload: { content: string; meta: Record<string, string> }) => Promise<void>
  interruptSocket: () => Promise<void>
}

export class ClaudeCodeAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "claude"
  readonly sessionName: string
  readonly workdir: string
  private opts: ClaudeAdapterOpts

  constructor(opts: ClaudeAdapterOpts) {
    super()
    this.opts = opts
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
  }

  async start(): Promise<void> {
    // Spawn is owned by spawn-helper in this phase; adapter is a passive
    // event surface. start() is a no-op for Claude — the supervisor/spawn-helper
    // does the work. We keep the method to satisfy the interface.
  }

  async resume(): Promise<void> {
    // Reconnect logic lives in socket-server today; adapter is passive.
  }

  async stop(): Promise<void> {
    // Tmux teardown is driven by main.ts's killSession; adapter doesn't kill.
  }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    await this.opts.sendInboundSocket({
      content: text,
      meta: (meta as Record<string, string>) ?? {},
    })
  }

  async interrupt(): Promise<void> {
    await this.opts.interruptSocket()
  }

  // Called by main.ts when the shim's reply() lands.
  emitAssistantMessage(text: string, extras?: Omit<AssistantMessageEvent, "kind" | "text">): void {
    this.emit("assistant-message", { kind: "assistant-message", text, ...(extras ?? {}) })
  }
}
