/** Claude headless stream-json mapper. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, PlanEntryStatus } from "../events/normalized.js"

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined
}

function planStatus(status: unknown): PlanEntryStatus {
  if (status === "in_progress" || status === "inProgress") return "in_progress"
  if (status === "completed") return "completed"
  return "pending"
}

function outputText(value: unknown): unknown {
  if (typeof value === "string") return value
  if (Array.isArray(value)) {
    return value.map(part => {
      if (typeof part === "string") return part
      const row = rec(part)
      if (row && typeof row.text === "string") return row.text
      return JSON.stringify(part)
    }).join("")
  }
  return value
}

function bashOutput(input: Record<string, unknown> | undefined, result: unknown): string | undefined {
  const fromResult = outputText(result)
  if (typeof fromResult === "string" && fromResult) return fromResult
  const cmd = str(input?.command)
  return cmd
}

function fileDiffFrom(input: Record<string, unknown> | undefined, result: unknown): { path: string; diff: string; changeKind?: string } | undefined {
  const path = str(input?.file_path) ?? str(input?.path)
  if (!path) return
  const oldText = typeof input?.old_string === "string" ? input.old_string : undefined
  const newText = typeof input?.new_string === "string" ? input.new_string : typeof input?.content === "string" ? input.content : undefined
  if (typeof input?.diff === "string") return { path, diff: input.diff }
  const row = rec(result)
  if (row && typeof row.diff === "string") return { path, diff: row.diff, ...(typeof row.kind === "string" ? { changeKind: row.kind } : {}) }
  if (newText != null) {
    const diff = oldText != null ? `--- a/${path}\n+++ b/${path}\n@@\n-${oldText}\n+${newText}` : newText
    return { path, diff, changeKind: oldText != null ? "update" : "add" }
  }
  return
}

function questionsFrom(input: Record<string, unknown> | undefined, requestId: string): NormalizedBody {
  const list = Array.isArray(input?.questions) ? input.questions : []
  return {
    kind: "user-question",
    requestId,
    blocking: true,
    questions: list.map((q, i) => {
      const row = rec(q) ?? {}
      const qid = str(row.header) ?? str(row.id) ?? `${requestId}:${i}`
      const options = Array.isArray(row.options)
        ? row.options.map((opt, j) => {
            const o = rec(opt) ?? {}
            return { id: str(o.label) ?? `${qid}:${j}`, label: str(o.label) ?? str(o.text) ?? String(opt) }
          })
        : undefined
      return {
        id: qid,
        prompt: str(row.question) ?? str(row.prompt) ?? "",
        ...(options ? { options } : {}),
        ...(row.multiSelect === true ? { allowFreeText: false } : {}),
      }
    }),
  }
}

export type ClaudeNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & { flush: () => NormalizedBody[] }

export function createClaudeNormalizer(): ClaudeNormalizer {
  let streamMessageId = "message"
  const assistant = new Map<string, string>()
  const reasoning = new Map<string, string>()
  const tools = new Map<string, { name: string; input?: Record<string, unknown> }>()

  function take(kind: "assistant" | "reasoning"): NormalizedBody[] {
    const map = kind === "assistant" ? assistant : reasoning
    if (!map.size) return []
    const out: NormalizedBody[] = []
    for (const [id, text] of map) {
      if (kind === "assistant") out.push({ kind: "assistant-message", messageId: id, text })
      else out.push({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) })
    }
    map.clear()
    return out
  }

  function extrasForTool(name: string, callId: string, input: Record<string, unknown> | undefined, result: unknown, phase: "started" | "completed" | "failed"): NormalizedBody[] {
    const extra: NormalizedBody[] = []
    if (name === "Bash" || name === "BashOutput") {
      const delta = bashOutput(input, result)
      if (delta) extra.push({ kind: "command-output", callId, stream: "merged", delta })
    }
    if (name === "Write" || name === "Edit") {
      const diff = fileDiffFrom(input, result)
      if (diff) extra.push({ kind: "file-diff", callId, path: diff.path, diff: diff.diff, ...(diff.changeKind ? { changeKind: diff.changeKind } : {}) })
    }
    if (name === "TodoWrite") {
      const todos = Array.isArray(input?.todos) ? input.todos : []
      extra.push({
        kind: "plan",
        entries: todos.map(todo => {
          const row = rec(todo) ?? {}
          return { content: str(row.content) ?? "", status: planStatus(row.status) }
        }),
      })
    }
    if (name === "Task" || name === "Agent") {
      extra.push({
        kind: "task",
        taskId: callId,
        taskKind: "agent",
        phase: phase === "started" ? "started" : phase === "failed" ? "failed" : "completed",
        label: str(input?.description) ?? str(input?.subagent_type) ?? name,
      })
    }
    if (name === "AskUserQuestion" && phase === "started") extra.push(questionsFrom(input, callId))
    return extra
  }

  function mapAssistantContent(message: Record<string, unknown>): NormalizedBody[] {
    const messageId = str(message.id) ?? "message"
    const content = Array.isArray(message.content) ? message.content : []
    // The full assistant frame supersedes whatever the partial stream accumulated for the SAME
    // blocks: drop those before flushing, otherwise each block gets two finals (one from the
    // partial accumulation, one from this frame). Only unrelated open runs are flushed here.
    content.forEach((_block, index) => { const own = `${messageId}:${index}`; assistant.delete(own); reasoning.delete(own) })
    const prefix = [...take("reasoning"), ...take("assistant")]
    const out: NormalizedBody[] = [...prefix]
    content.forEach((block, index) => {
      const row = rec(block)
      if (!row) return
      const type = row.type
      const id = `${messageId}:${index}`
      if (type === "text") {
        const text = typeof row.text === "string" ? row.text : ""
        assistant.delete(id)
        out.push({ kind: "assistant-message", messageId: id, text })
        return
      }
      if (type === "thinking") {
        const text = typeof row.thinking === "string" ? row.thinking : typeof row.text === "string" ? row.text : ""
        reasoning.delete(id)
        out.push({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) })
        return
      }
      if (type === "tool_use") {
        const callId = str(row.id) ?? id
        const name = str(row.name) ?? "tool"
        const input = rec(row.input) ?? (row.input as Record<string, unknown> | undefined)
        tools.set(callId, { name, input })
        out.push({
          kind: "tool-call",
          callId,
          tool: name,
          phase: "started",
          input: row.input,
        })
        out.push(...extrasForTool(name, callId, input, undefined, "started"))
      }
    })
    return out
  }

  function mapUserContent(message: Record<string, unknown>): NormalizedBody[] {
    const content = Array.isArray(message.content) ? message.content : []
    const prefix = [...take("reasoning"), ...take("assistant")]
    const out: NormalizedBody[] = [...prefix]
    for (const block of content) {
      const row = rec(block)
      if (!row || row.type !== "tool_result") continue
      const callId = str(row.tool_use_id) ?? "tool"
      const failed = row.is_error === true
      const prior = tools.get(callId)
      const name = str(row.name) ?? prior?.name ?? "tool"
      const input = rec(row.input) ?? prior?.input
      out.push({
        kind: "tool-call",
        callId,
        tool: name,
        phase: failed ? "failed" : "completed",
        output: outputText(row.content ?? row.output),
      })
      out.push(...extrasForTool(name, callId, input, row.content ?? row.output, failed ? "failed" : "completed"))
      tools.delete(callId)
    }
    return out
  }

  function mapStreamEvent(event: Record<string, unknown>): NormalizedBody[] {
    const type = event.type
    if (type === "message_start") {
      const message = rec(event.message)
      streamMessageId = str(message?.id) ?? "message"
      return []
    }
    if (type !== "content_block_delta") return []
    const index = typeof event.index === "number" ? event.index : 0
    const delta = rec(event.delta) ?? {}
    const id = `${streamMessageId}:${index}`
    if (delta.type === "text_delta") {
      const text = typeof delta.text === "string" ? delta.text : ""
      assistant.set(id, (assistant.get(id) ?? "") + text)
      return [{ kind: "assistant-delta", messageId: id, text }]
    }
    if (delta.type === "thinking_delta") {
      const text = typeof delta.thinking === "string" ? delta.thinking : typeof delta.text === "string" ? delta.text : ""
      reasoning.set(id, (reasoning.get(id) ?? "") + text)
      return [{ kind: "reasoning-delta", reasoningId: id, text }]
    }
    return []
  }

  function mapFrame(frame: Record<string, unknown>): NormalizedBody[] {
    const type = frame.type
    if (type === "system") {
      const subtype = str(frame.subtype)
      if (subtype === "init") {
        return [{
          kind: "session-info",
          ...(typeof frame.cwd === "string" ? { cwd: frame.cwd } : {}),
          ...(typeof frame.model === "string" ? { model: frame.model } : {}),
        }]
      }
      if (subtype && (subtype.startsWith("hook_") || subtype === "hook")) return []
      if (subtype === "thinking_tokens" || subtype === "thinking") {
        const text = typeof frame.text === "string" ? frame.text : typeof frame.thinking === "string" ? frame.thinking : ""
        return [{ kind: "reasoning", reasoningId: str(frame.uuid) ?? "thinking", redacted: !text, ...(text ? { text } : {}) }]
      }
      return []
    }
    if (type === "thinking" || type === "thinking_tokens") {
      const text = typeof frame.text === "string" ? frame.text : typeof frame.thinking === "string" ? frame.thinking : ""
      return [{ kind: "reasoning", reasoningId: str(frame.uuid) ?? "thinking", redacted: !text, ...(text ? { text } : {}) }]
    }
    if (type === "command_lifecycle") return []
    if (type === "stream_event") {
      const event = rec(frame.event)
      if (!event) return []
      return mapStreamEvent(event)
    }
    if (type === "assistant") {
      const message = rec(frame.message)
      if (!message) return []
      return mapAssistantContent(message)
    }
    if (type === "user") {
      const message = rec(frame.message)
      if (!message) return []
      return mapUserContent(message)
    }
    if (type === "rate_limit_event") {
      return [{ kind: "usage", rateLimits: frame.rate_limit_info ?? frame }]
    }
    if (type === "result") {
      const usage = rec(frame.usage)
      const tokens = usage
        ? {
            input: num(usage.input_tokens) ?? 0,
            output: num(usage.output_tokens) ?? 0,
            total: (num(usage.input_tokens) ?? 0) + (num(usage.output_tokens) ?? 0)
              + (num(usage.cache_creation_input_tokens) ?? 0) + (num(usage.cache_read_input_tokens) ?? 0),
          }
        : undefined
      const cost = typeof frame.total_cost_usd === "number"
        ? { amount: frame.total_cost_usd, currency: "USD" }
        : undefined
      const out: NormalizedBody[] = [...take("reasoning"), ...take("assistant"), {
        kind: "usage",
        ...(tokens ? { tokens } : {}),
        ...(cost ? { cost } : {}),
      }]
      if (frame.is_error === true) {
        const message = Array.isArray(frame.errors) ? frame.errors.find(x => typeof x === "string") as string | undefined : undefined
        out.push({ kind: "error", message: message ?? (typeof frame.result === "string" ? frame.result : "Claude turn failed") })
      }
      return out
    }
    if (type === "control_request") {
      const request = rec(frame.request)
      if (request?.subtype !== "can_use_tool") return []
      return [{
        kind: "permission-request",
        requestId: str(frame.request_id) ?? "permission",
        toolCall: request,
        options: [
          { optionId: "allow", kind: "allow_once", label: "Allow" },
          { optionId: "deny", kind: "reject_once", label: "Deny" },
        ],
      }]
    }
    return []
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    if (update.protocol !== "native") return []
    const frame = rec(update.value)
    if (!frame) return []
    return mapFrame(frame)
  }) as ClaudeNormalizer

  normalize.flush = () => {
    const out = [...take("reasoning"), ...take("assistant")]
    return out
  }
  return normalize
}
