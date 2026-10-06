import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { makeLogger } from "../../../shared/log"
import type { CodexUsage } from "../../usage/index"
import { codexUsageFromRateLimits } from "../../usage/local"
import {
  createCodexNativeItemState,
  handleCodexItemCompleted,
  handleCodexItemStarted,
} from "./native-items"

export { CODEX_TOOL_ITEM_TYPES, isCodexToolItem } from "./native-items"

const log = makeLogger("agents/codex/adapter")

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
  /** Push-mapped Codex usage from `account/rateLimits/updated`. Injected so
   * tests can assert the mapping without the usage-store singleton. */
  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null
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
  private resolveAttachment?: (file_id: string) => Promise<string>
  onUsageUpdate?: (data: CodexUsage) => void
  getPrevUsage?: () => CodexUsage | null
  private nativeItems = createCodexNativeItemState()

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
    this.onUsageUpdate = opts.onUsageUpdate
    this.getPrevUsage = opts.getPrevUsage
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
            this.nativeItems.deferredWebSearchStarts.clear()
          }
          this.emit("turn-complete", { kind: "turn-complete" })
          break
        case "item/completed":
          handleCodexItemCompleted(params?.item, this.nativeItems, {
            emitAssistant: (text) => this.emit("assistant-message", { kind: "assistant-message", text }),
            emitTool: (event) => this.emit("tool-call", event),
          })
          break
        case "item/started":
          handleCodexItemStarted(params?.item, this.nativeItems, {
            emitTool: (event) => this.emit("tool-call", event),
          })
          break
        case "error":
          this.emit("error", { kind: "error", error: new Error(params?.message ?? "codex error") })
          break
        case "account/rateLimits/updated": {
          const data = codexUsageFromRateLimits(params?.rateLimits ?? params, this.getPrevUsage?.() ?? null)
          if (data) this.onUsageUpdate?.(data)
          break
        }
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
