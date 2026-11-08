import { randomUUID } from "node:crypto"
import type { AgentEvent, BrokerRequest, RequestSubagent, ToolCallEvent } from "../types"
import type { AgentCommandEntry, AgentModelEntry, EventEnvelope, NormalizedBody } from "../../../../packages/supermux-core/src/events/normalized.js"
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
  onCommands?: (commands: AgentCommandEntry[]) => void
  /** The models the agent offers (a session start's ACP options, #102). */
  onModels?: (models: AgentModelEntry[]) => void
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
  // Card #76: one live stream per text block (the core's message / reasoning id), from its first piece of
  // text until its stored form. The id is ours: the core's are not unique across turns (ACP's
  // "assistant:1" comes back every turn).
  const streams = new Map<string, string>()
  const streamKey = (kind: "reply" | "thinking", coreId: string) => `${kind}\u0000${coreId}`
  function openStream(kind: "reply" | "thinking", coreId: string): string {
    const key = streamKey(kind, coreId)
    let id = streams.get(key)
    if (!id) {
      id = randomUUID().replace(/-/g, "").slice(0, 12)
      streams.set(key, id)
    }
    return id
  }
  function takeStream(kind: "reply" | "thinking", coreId: string): string | undefined {
    const key = streamKey(kind, coreId)
    const id = streams.get(key)
    streams.delete(key)
    return id
  }
  function takeOpen(kind: "reply" | "thinking"): string[] {
    const out: string[] = []
    for (const [key, id] of [...streams]) {
      if (!key.startsWith(`${kind}\u0000`)) continue
      streams.delete(key)
      out.push(id)
    }
    return out
  }

  const emitTool = (event: ToolCallEvent) => opts.emit(event)

  const emitAssistant = (text: string, stream?: string) => {
    const trimmed = text.trim()
    pendingAssistant = ""
    if (!trimmed || trimmed === lastAssistant) {
      // Nothing is stored for it, so nothing will replace the live text: the apps drop it.
      if (stream) opts.emit({ kind: "live-end", stream, final: false })
      return
    }
    lastAssistant = trimmed
    // The live text's last piece goes out before the stored message, which then replaces it.
    if (stream) opts.emit({ kind: "live-end", stream, final: true })
    opts.emit({ kind: "assistant-message", text: trimmed, ...(stream ? { stream } : {}) })
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

  /** Returns the live thinking stream a `reasoning` final closed, so its activity row can carry the id. */
  function handle(event: CoreNormalizedEvent): string | undefined {
    const kind = event.kind
    const replay = event.replay === true

    if (kind === "commands-update") {
      opts.onCommands?.(event.commands)
      return
    }
    if (kind === "models-update") {
      opts.onModels?.(event.models)
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
      if (event.text) opts.emit({ kind: "live-delta", stream: openStream("reply", event.messageId), streamKind: "reply", text: event.text })
      return
    }
    if (kind === "assistant-message") {
      emitAssistant(event.text, takeStream("reply", event.messageId))
      return
    }
    if (kind === "reasoning-delta") {
      // Redacted thinking (Claude without --thinking-display) sends empty pieces: those open nothing.
      if (event.text) opts.emit({ kind: "live-delta", stream: openStream("thinking", event.reasoningId), streamKind: "thinking", text: event.text })
      return
    }
    if (kind === "reasoning") {
      const stream = takeStream("thinking", event.reasoningId)
      if (stream) opts.emit({ kind: "live-end", stream, final: true })
      return stream
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
        detail: opts.agent === "antigravity" ? event.input : extractGrokNativeTool(event.native.payload),
      })
      return
    }
    if (kind === "error") {
      opts.emit({
        kind: "error",
        error: new Error(event.message),
        ...(event.errorType ? { errorType: event.errorType } : {}),
        ...(event.recoverable ? { recoverable: true } : {}),
      })
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
    if (kind === "warning" && event.source === "antigravity") {
      // agy auto-denied a tool (headless cannot ask); the turn may end with no text at all, so
      // the denial is the reply the user sees.
      if (pendingAssistant) emitAssistant(pendingAssistant, takeOpen("reply")[0])
      emitAssistant(event.message)
      return
    }
    if (kind === "usage" && event.rateLimits != null) {
      opts.onUsage?.(event.rateLimits)
    }
  }

  function flush(): void {
    const open = takeOpen("reply")
    if (pendingAssistant) emitAssistant(pendingAssistant, open.shift())
    // Whatever still streams has no stored form coming: the apps drop it.
    for (const stream of [...open, ...takeOpen("thinking")]) opts.emit({ kind: "live-end", stream, final: false })
  }

  return { handle, flush, decorateRequest }
}
