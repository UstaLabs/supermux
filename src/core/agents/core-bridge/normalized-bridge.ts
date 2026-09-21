import type { AgentEvent, ToolCallEvent } from "../types"
import type { EventEnvelope, NormalizedBody } from "../../../../packages/supermux-core/src/events/normalized.js"
import {
  createCodexNativeItemState,
  handleCodexItemCompleted,
  handleCodexItemStarted,
} from "../codex/native-items"

export type CoreNormalizedEvent = EventEnvelope & NormalizedBody

export type NormalizedBridgeOpts = {
  agent: string
  emit: (event: AgentEvent) => void
  onUsage?: (rateLimits: unknown) => void
  onCommands?: (commands: { name: string; description?: string }[]) => void
}

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

/** Codex `native.payload` is the app-server frame `{ method, params: { item } }`. */
export function extractCodexNativeItem(payload: unknown): Record<string, unknown> | undefined {
  const frame = rec(payload)
  if (!frame) return
  const params = rec(frame.params)
  const item = rec(params?.item)
  if (item) return item
  if (typeof frame.type === "string") return frame
}

/** Grok `native.payload` is either the ACP update object or a vendor JSON-RPC frame. */
export function extractGrokNativeTool(payload: unknown): unknown {
  const frame = rec(payload)
  if (!frame) return payload
  if (typeof frame.sessionUpdate === "string") return frame
  const params = rec(frame.params)
  if (!params) return payload
  const nested = rec(params.update)
  if (nested && typeof nested.sessionUpdate === "string") return nested
  if (typeof params.sessionUpdate === "string") return params
  return payload
}

export function createNormalizedBridge(opts: NormalizedBridgeOpts) {
  const nativeItems = createCodexNativeItemState()
  let pendingAssistant = ""
  let lastAssistant = ""

  const emitTool = (event: ToolCallEvent) => opts.emit(event)

  const emitAssistant = (text: string) => {
    const trimmed = text.trim()
    pendingAssistant = ""
    if (!trimmed || trimmed === lastAssistant) return
    lastAssistant = trimmed
    opts.emit({ kind: "assistant-message", text: trimmed })
  }

  function handle(event: CoreNormalizedEvent): void {
    const kind = event.kind
    const replay = event.replay === true

    if (kind === "commands-update") {
      opts.onCommands?.(event.commands)
      return
    }
    if (replay) return

    if (kind === "assistant-delta") {
      pendingAssistant += event.text
      return
    }
    if (kind === "assistant-message") {
      emitAssistant(event.text)
      return
    }
    if (kind === "tool-call") {
      if (event.phase === "updated") return
      if (opts.agent === "codex") {
        const item = extractCodexNativeItem(event.native.payload)
        if (event.phase === "started") {
          handleCodexItemStarted(item, nativeItems, { emitTool })
        } else {
          handleCodexItemCompleted(item, nativeItems, { emitTool })
        }
        return
      }
      opts.emit({
        kind: "tool-call",
        tool: event.tool,
        phase: event.phase,
        call_id: event.callId,
        detail: extractGrokNativeTool(event.native.payload),
      })
      return
    }
    if (kind === "error") {
      opts.emit({ kind: "error", error: new Error(event.message) })
      return
    }
    if (kind === "usage" && event.rateLimits != null) {
      opts.onUsage?.(event.rateLimits)
    }
  }

  function flush(): void {
    if (pendingAssistant) emitAssistant(pendingAssistant)
  }

  return { handle, flush }
}
