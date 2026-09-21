/** Cursor stream-json mapper. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, ToolCallPhase } from "../events/normalized.js"

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined
}

function assistantText(message: Record<string, unknown> | undefined): string {
  const content = Array.isArray(message?.content) ? message.content : []
  return content.map(part => {
    if (typeof part === "string") return part
    const row = rec(part)
    return row && typeof row.text === "string" ? row.text : ""
  }).join("")
}

function toolName(toolCall: Record<string, unknown> | undefined): string {
  if (!toolCall) return "unknown"
  const keys = Object.keys(toolCall)
  return keys[0] ?? "unknown"
}

function suffix(prev: string, next: string): string {
  if (next.startsWith(prev)) return next.slice(prev.length)
  return next
}

export type CursorNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & { flush: () => NormalizedBody[] }

export function createCursorNormalizer(): CursorNormalizer {
  let run = 1
  let buffer = ""
  let messageId = `assistant:${run}`

  function takeAssistant(): NormalizedBody[] {
    if (!buffer) return []
    const out: NormalizedBody[] = [{ kind: "assistant-message", messageId, text: buffer }]
    buffer = ""
    return out
  }

  function splitRun(): NormalizedBody[] {
    const out = takeAssistant()
    if (out.length) {
      run += 1
      messageId = `assistant:${run}`
    }
    return out
  }

  // Real cursor-agent emits {type:'thinking', subtype:'delta', text} frames and a
  // {type:'thinking', subtype:'completed'} marker (seen on the live wire 2026-09-21).
  let reasoningRun = 1
  let reasoningBuffer = ""
  let reasoningOpen = false
  function takeReasoning(): NormalizedBody[] {
    if (!reasoningOpen) return []
    const text = reasoningBuffer
    const out: NormalizedBody[] = [{ kind: "reasoning", reasoningId: `reasoning:${reasoningRun}`, redacted: !text, ...(text ? { text } : {}) }]
    reasoningBuffer = ""
    reasoningOpen = false
    reasoningRun += 1
    return out
  }

  function mapFrame(frame: Record<string, unknown>): NormalizedBody[] {
    const type = frame.type
    if (type === "thinking") {
      if (frame.subtype === "completed") return takeReasoning()
      const text = typeof frame.text === "string" ? frame.text : ""
      const prefix = reasoningOpen ? [] : splitRun()
      reasoningOpen = true
      if (!text) return prefix
      reasoningBuffer += text
      return [...prefix, { kind: "reasoning-delta", reasoningId: `reasoning:${reasoningRun}`, text }]
    }
    if (type === "system" && frame.subtype === "init") {
      return [{
        kind: "session-info",
        ...(typeof frame.cwd === "string" ? { cwd: frame.cwd } : {}),
        ...(typeof frame.model === "string" ? { model: frame.model } : {}),
      }]
    }
    if (type === "assistant") {
      const closed = takeReasoning()
      if (closed.length) return [...closed, ...mapFrame(frame)]
      const next = assistantText(rec(frame.message))
      const delta = suffix(buffer, next)
      if (next.startsWith(buffer)) buffer = next
      else buffer += delta
      if (!delta) return []
      return [{ kind: "assistant-delta", messageId, text: delta }]
    }
    if (type === "tool_call") {
      const prefix = [...takeReasoning(), ...splitRun()]
      const callId = str(frame.call_id) ?? "tool"
      const toolObj = rec(frame.tool_call)
      const name = toolName(toolObj)
      const body = toolObj ? rec(toolObj[name]) ?? toolObj : undefined
      const subtype = frame.subtype
      const phase: ToolCallPhase = subtype === "completed" || subtype === "complete"
        ? "completed"
        : subtype === "failed" || subtype === "error"
          ? "failed"
          : "started"
      const input = body?.args ?? body?.arguments ?? body?.params
      const output = body?.result ?? frame.result ?? body?.output
      return [...prefix, {
        kind: "tool-call",
        callId,
        tool: name,
        phase,
        ...(input !== undefined ? { input } : {}),
        ...(output !== undefined && phase !== "started" ? { output } : {}),
      }]
    }
    if (type === "result") {
      const out = takeAssistant()
      if (frame.is_error === true) {
        out.push({
          kind: "error",
          message: typeof frame.result === "string" && frame.result ? frame.result : "Cursor turn failed",
        })
      }
      return out
    }
    return []
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    if (update.protocol !== "native") return []
    const frame = rec(update.value)
    if (!frame) return []
    return mapFrame(frame)
  }) as CursorNormalizer

  normalize.flush = () => [...takeReasoning(), ...takeAssistant()]
  return normalize
}
