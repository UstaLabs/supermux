import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/codex/adapter")

// Codex thread-item types that ARE tools (→ activity tool-cards). Everything
// else (userMessage, agentMessage, reasoning, error, todo, tokenCount, …) is
// NOT a tool. Allowlist (not blocklist) so unknown non-tool item types can
// never leak in as junk cards. Extend this when Codex adds a tool type.
export const CODEX_TOOL_ITEM_TYPES = new Set<string>([
  "command_execution", "commandExecution",
  "fileChange", "file_change",
  "webSearch", "web_search",
  "mcpToolCall", "mcp_tool_call",
  "dynamicToolCall", "dynamic_tool_call",
])
export function isCodexToolItem(type: string | undefined): boolean {
  return typeof type === "string" && CODEX_TOOL_ITEM_TYPES.has(type)
}

type JsonRpcLike = {
  request<T = any>(method: string, params: any): Promise<T>
  onNotification(h: (n: { method: string; params: any }) => void): void
}

export type CodexAdapterOpts = {
  sessionName: string
  workdir: string
  client: JsonRpcLike
  persistThreadId: (id: string) => Promise<void>
  initialThreadId?: string  // when resuming
  /** Resolve an inbound attachment file_id to a local path on disk. Codex runs
   * locally, so we hand it real paths: images go in as `localImage` input
   * items (so the model sees them), other files get their path folded into the
   * prompt text. Without this, codex sessions silently drop attachments. */
  resolveAttachment?: (file_id: string) => Promise<string>
}

type CodexInputItem = { type: "text"; text: string } | { type: "localImage"; path: string }

function turnIdFrom(value: any): string | undefined {
  const id = value?.turn?.id ?? value?.turnId
  return typeof id === "string" && id ? id : undefined
}

export class CodexAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "codex"
  readonly sessionName: string
  readonly workdir: string

  private client: JsonRpcLike
  private threadId?: string
  private currentTurnId?: string
  private persistThreadId: (id: string) => Promise<void>
  private initialThreadId?: string
  private lastChatId?: string
  private resolveAttachment?: (file_id: string) => Promise<string>

  /** The live app-server JSON-RPC client, for read-only queries like skills/list. */
  get rpc(): JsonRpcLike {
    return this.client
  }

  constructor(opts: CodexAdapterOpts) {
    super()
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.client = opts.client
    this.persistThreadId = opts.persistThreadId
    this.initialThreadId = opts.initialThreadId
    this.resolveAttachment = opts.resolveAttachment
    this.wireNotifications()
  }

  private wireNotifications(): void {
    this.client.onNotification(({ method, params }) => {
      switch (method) {
        case "turn/started":
          this.currentTurnId = turnIdFrom(params)
          this.emit("turn-start", { kind: "turn-start" })
          break
        case "turn/completed":
          if (!this.currentTurnId || this.currentTurnId === turnIdFrom(params)) {
            this.currentTurnId = undefined
          }
          this.emit("turn-complete", { kind: "turn-complete" })
          break
        case "item/completed":
          if (params?.item?.type === "agentMessage" && typeof params.item.text === "string") {
            this.emit("assistant-message", { kind: "assistant-message", text: params.item.text, chat_id: this.lastChatId })
          }
          if (isCodexToolItem(params?.item?.type)) {
            const exitCode = params.item.exitCode ?? params.item.exit_code
            const failed = params.item.status === "failed" || params.item.status === "declined"
              || (exitCode != null && exitCode !== 0)
            const tool = params.item.type === "dynamicToolCall" || params.item.type === "dynamic_tool_call"
              ? String(params.item.tool || params.item.type)
              : String(params.item.type)
            this.emit("tool-call", { kind: "tool-call", tool, phase: failed ? "failed" : "completed",
              call_id: String(params.item.id ?? ""), detail: params.item })
          }
          break
        case "item/started":
          if (isCodexToolItem(params?.item?.type)) {
            const tool = params.item.type === "dynamicToolCall" || params.item.type === "dynamic_tool_call"
              ? String(params.item.tool || params.item.type)
              : String(params.item.type)
            this.emit("tool-call", { kind: "tool-call", tool, phase: "started",
              call_id: String(params.item.id ?? ""), detail: params.item })
          }
          break
        case "error":
          this.emit("error", { kind: "error", error: new Error(params?.message ?? "codex error") })
          break
      }
    })
  }

  async start(): Promise<void> {
    // codex 0.133 requires `initialize` before any thread operation. The
    // protocol responds with a userAgent/codexHome capability block; we
    // discard it.
    await this.client.request("initialize", {
      protocolVersion: "2024-11-05",
      clientInfo: { name: "mux", version: "0.0.1" },
    })
    // thread/start returns { thread: { id, sessionId, ... } }; we key on
    // thread.id (the UUID we use for thread/resume + turn/start).
    const r = await this.client.request<{ thread: { id: string; sessionId: string } }>(
      "thread/start", { cwd: this.workdir },
    )
    const threadId = r?.thread?.id
    if (!threadId) throw new Error(`codex thread/start returned no thread.id: ${JSON.stringify(r)}`)
    this.threadId = threadId
    await this.persistThreadId(threadId)
  }

  async resume(): Promise<void> {
    if (!this.initialThreadId) throw new Error("codex adapter: resume() requires initialThreadId")
    await this.client.request("initialize", {
      protocolVersion: "2024-11-05",
      clientInfo: { name: "mux", version: "0.0.1" },
    })
    await this.client.request("thread/resume", { threadId: this.initialThreadId })
    this.threadId = this.initialThreadId
  }

  async stop(): Promise<void> {
    // process supervision lives outside the adapter — see main.ts.
  }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    if (!this.threadId) throw new Error("codex adapter: not started")
    // Codex may emit multiple agentMessage items in one turn (for example,
    // commentary followed by the final answer). Keep the destination for all
    // of them, and replace it explicitly when the next inbound turn starts.
    this.lastChatId = meta?.chat_id
    const input = await this.buildInput(text, meta)
    if (this.currentTurnId) {
      await this.client.request("turn/steer", { threadId: this.threadId, expectedTurnId: this.currentTurnId, input })
    } else {
      const r = await this.client.request<{ turn?: { id?: string }; turnId?: string }>(
        "turn/start", { threadId: this.threadId, input },
      )
      this.currentTurnId = turnIdFrom(r) ?? this.currentTurnId
    }
  }

  /** Build the codex turn input: an optional resolved attachment plus the text.
   * Images become `localImage` items the model can see; other files have their
   * on-disk path folded into the prompt so codex can open them with its tools. */
  private async buildInput(text: string, meta?: InboundMeta): Promise<CodexInputItem[]> {
    const items: CodexInputItem[] = []
    let prompt = text
    if (meta?.attachment_file_id && this.resolveAttachment) {
      try {
        const path = await this.resolveAttachment(meta.attachment_file_id)
        const isImage = !!meta.attachment_mime?.startsWith("image/")
          || meta.attachment_kind === "photo" || meta.attachment_kind === "image"
        if (isImage) {
          items.push({ type: "localImage", path })
        } else {
          const label = meta.attachment_name ? `${meta.attachment_name} (${path})` : path
          prompt = prompt ? `${prompt}\n\n[Attached file: ${label}]` : `[Attached file: ${label}]`
        }
      } catch (err: any) {
        log.warn("codex_attachment_resolve_failed", { session: this.sessionName, file_id: meta.attachment_file_id, err: err?.message ?? String(err) })
      }
    }
    // Always include text when there's a prompt, or when nothing else made it in
    // (so an empty turn never gets sent).
    if (prompt || items.length === 0) items.push({ type: "text", text: prompt })
    return items
  }

  async interrupt(): Promise<void> {
    if (!this.threadId || !this.currentTurnId) return
    await this.client.request("turn/interrupt", {
      threadId: this.threadId,
      turnId: this.currentTurnId,
    })
  }
}
