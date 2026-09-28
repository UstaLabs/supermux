import type { AgentEvent, BrokerRequest, RequestSubagent, ToolCallEvent } from "../types"
import type { EventEnvelope, NormalizedBody } from "../../../../packages/supermux-core/src/events/normalized.js"
import { answerLabel, mapPermissionRequest, mapUserQuestion } from "./request-map"
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
  // A permission answer names an option ID; only the request that offered it knows the label,
  // and by the time it resolves the library has already dropped the request. Keep the mapped
  // requests here, from open to resolve, so `request-closed` can state what was chosen.
  const openRequests = new Map<string, BrokerRequest>()
  // What each subagent is called, so a request it raises can say who is asking.
  const subagentLabels = new Map<string, { name?: string; description?: string }>()
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

  function requestSubagent(subagentId: string | undefined): RequestSubagent {
    if (!subagentId) return {}
    const label = subagentLabels.get(subagentId)
    return {
      subagentId,
      ...(label?.name ? { subagentName: label.name } : {}),
      ...(label?.description ? { subagentDescription: label.description } : {}),
    }
  }

  /** Fill in the asking subagent's name/description on a request mapped elsewhere (snapshot list). */
  function decorateRequest(request: BrokerRequest): BrokerRequest {
    return request.subagentId ? { ...request, ...requestSubagent(request.subagentId) } : request
  }

  function emitRequest(mapped: BrokerRequest, subagentId: string | undefined): void {
    const request = { ...mapped, ...requestSubagent(subagentId) }
    openRequests.set(request.requestId, request)
    opts.emit({
      kind: "request-open",
      requestId: request.requestId,
      requestKind: request.kind,
      title: request.title,
      body: request.body,
      options: request.options,
      allowFreeText: request.allowFreeText,
      blocking: request.blocking,
      ...requestSubagent(subagentId),
    })
  }

  function handle(event: CoreNormalizedEvent): void {
    const kind = event.kind
    const replay = event.replay === true

    if (kind === "commands-update") {
      opts.onCommands?.(event.commands)
      return
    }
    if (replay) return

    if (kind === "subagent") {
      const label = subagentLabels.get(event.subagentId) ?? {}
      if (event.name) label.name = event.name
      if (event.description) label.description = event.description
      subagentLabels.set(event.subagentId, label)
      const { sessionId: _s, agent: _a, seq: _q, ts: _t, turnId: _tu, replay: _r, origin: _o, native: _n, ...body } = event
      opts.emit({ kind: "subagent", body })
      return
    }
    if (kind === "task") {
      opts.emit({
        kind: "task",
        taskId: event.taskId,
        taskKind: event.taskKind,
        phase: event.phase,
        ...(event.label ? { label: event.label } : {}),
        ...(event.parentCallId ? { parentCallId: event.parentCallId } : {}),
      })
      return
    }
    // A subagent's own output is the subagent's business: its text is never the parent's reply,
    // its tool calls never the parent's "current tool", its errors never fail the parent's turn.
    // Its requests still need the user, so they go through below, labelled.
    const child = event.subagentId
    if (child && kind !== "permission-request" && kind !== "user-question" && kind !== "request-resolved"
      && kind !== "permission-auto" && kind !== "usage") return

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
    if (kind === "permission-auto") {
      const tool = event.toolCall.tool || event.toolCall.title || "tool"
      opts.emit({
        kind: "activity",
        events: [{
          ts: event.ts,
          kind: "tool",
          tool,
          title: `auto-approved: ${tool}`,
          phase: "completed",
          callId: event.toolCall.callId,
          ...(child ? { subagentId: child } : {}),
        }],
      })
      return
    }
    if (kind === "permission-request") {
      emitRequest(mapPermissionRequest(event), child)
      return
    }
    if (kind === "user-question") {
      emitRequest(mapUserQuestion(event), child)
      return
    }
    if (kind === "request-resolved") {
      const request = openRequests.get(event.requestId)
      openRequests.delete(event.requestId)
      const label = request ? answerLabel(request, event.answer) : undefined
      opts.emit({
        kind: "request-closed",
        requestId: event.requestId,
        outcome: event.outcome,
        ...(label ? { answerLabel: label } : {}),
      })
      return
    }
    if (kind === "usage" && event.rateLimits != null) {
      opts.onUsage?.(event.rateLimits)
    }
  }

  function flush(): void {
    if (pendingAssistant) emitAssistant(pendingAssistant)
  }

  return { handle, flush, decorateRequest }
}
