/** ACP SessionUpdate mapper. Schema pin: @agentclientprotocol/sdk 1.4.0 types.gen.d.ts. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, PlanEntryPriority, PlanEntryStatus, ToolCallPhase, ToolCategory } from "../events/normalized.js"

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function str(value: unknown): string | undefined {
  return typeof value === "string" && value ? value : undefined
}

function contentText(content: unknown): string {
  const block = rec(content)
  if (!block) return typeof content === "string" ? content : ""
  if (typeof block.text === "string") return block.text
  if (block.type === "text" && typeof block.text === "string") return block.text
  return ""
}

function category(kind: unknown): ToolCategory | undefined {
  if (kind === "read" || kind === "edit" || kind === "delete" || kind === "move" || kind === "search" || kind === "execute" || kind === "think" || kind === "fetch" || kind === "other") return kind
  if (kind === "switch_mode") return "other"
  return
}

function toolPhase(status: unknown, isUpdate: boolean): ToolCallPhase {
  if (status === "failed") return "failed"
  if (status === "completed") return "completed"
  if (isUpdate) return "updated"
  return "started"
}

function planStatus(status: unknown): PlanEntryStatus {
  if (status === "in_progress" || status === "inProgress") return "in_progress"
  if (status === "completed") return "completed"
  return "pending"
}

function planPriority(value: unknown): PlanEntryPriority | undefined {
  if (value === "high" || value === "medium" || value === "low") return value
  return
}

function compactionStatus(status: unknown): "in_progress" | "completed" | "failed" | "cancelled" {
  if (status === "completed" || status === "failed" || status === "cancelled") return status
  return "in_progress"
}

function unwrapGrok(update: AgentUpdate): { kind: "acp"; value: Record<string, unknown> } | { kind: "native"; method: string; params: unknown } | undefined {
  if (update.protocol === "acp") {
    const value = rec(update.value)
    if (!value) return
    return { kind: "acp", value }
  }
  const frame = rec(update.value)
  if (!frame) return
  const method = str(frame.method)
  if (!method) return
  return { kind: "native", method, params: frame.params }
}

export function createAcpNormalizer(options: { vendor?: "grok" } = {}): ((update: AgentUpdate) => NormalizedBody[]) & { flush: () => NormalizedBody[] } {
  const assistant = new Map<string, string>()
  const thoughts = new Map<string, string>()
  const toolNames = new Map<string, string>()
  let assistantSeq = 0
  let thoughtSeq = 0
  let lastCommandsJson: string | undefined

  function takeAssistant(): NormalizedBody[] {
    if (!assistant.size) return []
    const out: NormalizedBody[] = []
    for (const [id, text] of assistant) out.push({ kind: "assistant-message", messageId: id, text })
    assistant.clear()
    return out
  }

  function takeThoughts(): NormalizedBody[] {
    if (!thoughts.size) return []
    const out: NormalizedBody[] = []
    for (const [id, text] of thoughts) {
      out.push({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) })
    }
    thoughts.clear()
    return out
  }

  function mapAcp(value: Record<string, unknown>): NormalizedBody[] {
    const kind = value.sessionUpdate
    if (kind === "turn_completed" || kind === "user_message_chunk") return []
    if (kind === "agent_message_chunk") {
      const prefix = takeThoughts()
      if (prefix.length && !str(value.messageId)) thoughtSeq += 1
      if (!str(value.messageId) && assistant.size === 0) {
        if (assistantSeq === 0) assistantSeq = 1
      }
      const messageId = str(value.messageId) ?? `assistant:${assistantSeq || 1}`
      const text = contentText(value.content)
      assistant.set(messageId, (assistant.get(messageId) ?? "") + text)
      return [...prefix, { kind: "assistant-delta", messageId, text }]
    }
    if (kind === "agent_thought_chunk") {
      const prefix = takeAssistant()
      if (prefix.length && !str(value.messageId)) assistantSeq += 1
      if (!str(value.messageId) && thoughts.size === 0) {
        if (thoughtSeq === 0) thoughtSeq = 1
      }
      const reasoningId = str(value.messageId) ?? `reasoning:${thoughtSeq || 1}`
      const text = contentText(value.content)
      thoughts.set(reasoningId, (thoughts.get(reasoningId) ?? "") + text)
      return [...prefix, { kind: "reasoning-delta", reasoningId, text }]
    }
    if (kind === "tool_call" || kind === "tool_call_update") {
      const prefix = kind === "tool_call" ? [...takeThoughts(), ...takeAssistant()] : []
      if (prefix.length && kind === "tool_call") {
        assistantSeq += 1
        thoughtSeq += 1
      }
      const callId = str(value.toolCallId) ?? "tool"
      // tool_call_update frames usually omit the name: keep the one announced by the matching
      // tool_call so started/completed events describe the same tool (bounded map).
      const announced = str(value.name)
      if (announced) {
        if (toolNames.size >= 256) toolNames.delete(toolNames.keys().next().value as string)
        toolNames.set(callId, announced)
      }
      const name = announced ?? toolNames.get(callId) ?? str(value.title) ?? "tool"
      const phase = toolPhase(value.status, kind === "tool_call_update")
      const out: NormalizedBody[] = [{
        kind: "tool-call",
        callId,
        tool: name,
        ...(typeof value.title === "string" ? { title: value.title } : {}),
        phase,
        ...(category(value.kind) ? { category: category(value.kind) } : {}),
        ...(Array.isArray(value.locations) ? {
          locations: value.locations.flatMap(loc => {
            const row = rec(loc)
            return row && typeof row.path === "string" ? [{ path: row.path }] : []
          }),
        } : {}),
        ...(value.rawInput !== undefined ? { input: value.rawInput } : {}),
        ...(value.rawOutput !== undefined ? { output: value.rawOutput } : {}),
      }]
      const content = Array.isArray(value.content) ? value.content : []
      for (const part of content) {
        const row = rec(part)
        if (!row) continue
        if (row.type === "diff" && typeof row.path === "string" && typeof row.diff === "string") {
          out.push({ kind: "file-diff", callId, path: row.path, diff: row.diff })
        } else if (row.type === "terminal") {
          const delta = typeof row.output === "string" ? row.output : typeof row.text === "string" ? row.text : JSON.stringify(row)
          out.push({ kind: "command-output", callId, stream: "merged", delta })
        }
      }
      const cat = category(value.kind)
      if (cat === "search" || cat === "fetch") {
        out.push({
          kind: "web-search",
          callId,
          phase: phase === "failed" ? "failed" : phase === "completed" ? "completed" : "started",
          query: str(value.title),
        })
      }
      return prefix.length ? [...prefix, ...out] : out
    }
    if (kind === "plan" || kind === "plan_update") {
      const prefix = [...takeThoughts(), ...takeAssistant()]
      if (prefix.length) {
        assistantSeq += 1
        thoughtSeq += 1
      }
      const entriesRaw = Array.isArray(value.entries) ? value.entries : Array.isArray(value.items) ? value.items : []
      const entries = entriesRaw.map(entry => {
        const row = rec(entry) ?? {}
        const priority = planPriority(row.priority)
        return {
          content: str(row.content) ?? "",
          status: planStatus(row.status),
          ...(priority ? { priority } : {}),
        }
      })
      return prefix.length ? [...prefix, { kind: "plan", entries }] : [{ kind: "plan", entries }]
    }
    if (kind === "plan_removed") {
      return [{ kind: "plan", entries: [] }]
    }
    if (kind === "available_commands_update") {
      const commands = Array.isArray(value.availableCommands) ? value.availableCommands : []
      const mapped = commands.map(cmd => {
        const row = rec(cmd) ?? {}
        return { name: str(row.name) ?? "", ...(typeof row.description === "string" ? { description: row.description } : {}) }
      })
      const encoded = JSON.stringify(mapped)
      if (encoded === lastCommandsJson) return []
      lastCommandsJson = encoded
      return [{ kind: "commands-update", commands: mapped }]
    }
    if (kind === "current_mode_update") {
      return [{ kind: "mode-update", modeId: str(value.currentModeId) }]
    }
    if (kind === "config_option_update") {
      const optionsList = Array.isArray(value.configOptions) ? value.configOptions : []
      const modelOpt = optionsList.map(rec).find(row => row && (row.id === "model" || row.name === "model"))
      const model = modelOpt ? str(modelOpt.value) ?? str(modelOpt.currentValue) : undefined
      return [{ kind: "mode-update", ...(model ? { model } : {}) }]
    }
    if (kind === "session_info_update") {
      return [{ kind: "session-info", ...("title" in value ? { title: (value.title as string | null | undefined) ?? null } : {}) }]
    }
    if (kind === "usage_update") {
      const used = typeof value.used === "number" ? value.used : undefined
      const size = typeof value.size === "number" ? value.size : undefined
      const costRow = rec(value.cost)
      return [{
        kind: "usage",
        ...(used != null && size != null ? { context: { used, size } } : {}),
        ...(costRow && typeof costRow.amount === "number" && typeof costRow.currency === "string"
          ? { cost: { amount: costRow.amount, currency: costRow.currency } }
          : {}),
      }]
    }
    if (kind === "compaction_update") {
      const summaryBlocks = Array.isArray(value.summary) ? value.summary : []
      const summary = summaryBlocks.map(contentText).join("")
      return [{
        kind: "compaction",
        compactionId: str(value.compactionId) ?? "compaction",
        status: compactionStatus(value.status),
        ...(summary ? { summary } : {}),
        ...(typeof value.error === "string" ? { error: value.error } : {}),
      }]
    }
    if (kind === "compaction_summary_chunk") {
      return [{
        kind: "compaction",
        compactionId: str(value.compactionId) ?? "compaction",
        status: "in_progress",
        summary: contentText(value.content),
      }]
    }
    return []
  }

  function mapNative(method: string, params: unknown): NormalizedBody[] {
    if (options.vendor === "grok" && (method === "_x.ai/session_notification" || method === "_x.ai/session/update")) {
      const nested = rec(params)
      const update = nested && "update" in nested ? rec(nested.update) : nested
      if (!update) return []
      return mapAcp(update)
    }
    if (method === "session/request_permission" || method === "_x.ai/ask_user_question") {
      // Drivers answer these via context.requestPermission / requestAnswers; Session emits the event.
      return []
    }
    return []
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    const parsed = unwrapGrok(update)
    if (!parsed) return []
    if (parsed.kind === "acp") return mapAcp(parsed.value)
    return mapNative(parsed.method, parsed.params)
  }) as ((update: AgentUpdate) => NormalizedBody[]) & { flush: () => NormalizedBody[] }

  normalize.flush = () => {
    const out: NormalizedBody[] = []
    for (const [id, text] of assistant) out.push({ kind: "assistant-message", messageId: id, text })
    for (const [id, text] of thoughts) {
      out.push({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) })
    }
    assistant.clear()
    thoughts.clear()
    return out
  }
  return normalize
}
