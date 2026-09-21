/** Codex app-server mapper. Schema pin: `codex app-server generate-ts` dump used 2026-09-21. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, PlanEntryStatus, TaskPhase, ToolCallPhase } from "../events/normalized.js"

type Frame = { method?: string; params?: Record<string, unknown>; id?: unknown }

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

function itemType(item: Record<string, unknown> | undefined): string | undefined {
  return str(item?.type)
}

function phaseFromStatus(status: unknown, started: boolean): ToolCallPhase {
  if (started) return "started"
  if (status === "failed" || status === "declined") return "failed"
  if (status === "inProgress") return "updated"
  return "completed"
}

function planStatus(status: unknown): PlanEntryStatus {
  if (status === "inProgress" || status === "in_progress") return "in_progress"
  if (status === "completed") return "completed"
  return "pending"
}

function taskPhase(kind: unknown, status: unknown): TaskPhase {
  if (kind === "started" || kind === "interacted" || kind === "interrupted" || kind === "completed") return kind
  if (status === "interrupted") return "interrupted"
  if (status === "failed") return "failed"
  if (status === "completed") return "completed"
  if (status === "inProgress") return "started"
  return "started"
}

function toolPhaseItem(started: boolean, item: Record<string, unknown>): ToolCallPhase {
  return phaseFromStatus(item.status, started)
}

const TOOL_ITEM_TYPES = new Set([
  "commandExecution",
  "fileChange",
  "mcpToolCall",
  "dynamicToolCall",
  "webSearch",
  "imageView",
  "imageGeneration",
])

const NEVER_TOOL_ITEM_TYPES = new Set([
  "userMessage",
  "hookPrompt",
  "agentMessage",
  "reasoning",
  "plan",
  "contextCompaction",
  "enteredReviewMode",
  "exitedReviewMode",
])

export type CodexNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & { flush: () => NormalizedBody[] }

export function createCodexNormalizer(): CodexNormalizer {
  const assistant = new Map<string, string>()
  const reasoning = new Map<string, { text: string; summary: string[] }>()
  const plans = new Map<string, string>()

  function mapItem(item: Record<string, unknown>, started: boolean): NormalizedBody[] {
    const type = itemType(item)
    const id = str(item.id) ?? "item"
    if (!type) return []
    if (type === "agentMessage") {
      if (started) return []
      const text = typeof item.text === "string" ? item.text : ""
      assistant.delete(id)
      const questions = item.questions
      const out: NormalizedBody[] = []
      if (text) out.push({ kind: "assistant-message", messageId: id, text })
      if (Array.isArray(questions) && questions.length) {
        out.push({
          kind: "user-question",
          requestId: id,
          blocking: false,
          questions: questions.map((q, i) => {
            const row = rec(q) ?? {}
            const qid = str(row.id) ?? `${id}:${i}`
            const prompt = str(row.question) ?? str(row.title) ?? ""
            const options = Array.isArray(row.options)
              ? row.options.map((opt, j) => {
                  const o = rec(opt) ?? {}
                  return { id: str(o.id) ?? `${qid}:${j}`, label: str(o.label) ?? str(o.text) ?? String(opt) }
                })
              : undefined
            return { id: qid, prompt, ...(options ? { options } : {}) }
          }),
        })
      }
      return out
    }
    if (type === "reasoning") {
      if (started) return []
      const content = Array.isArray(item.content) ? item.content.filter(x => typeof x === "string") as string[] : []
      const summary = Array.isArray(item.summary) ? item.summary.filter(x => typeof x === "string") as string[] : []
      const text = content.join("")
      reasoning.delete(id)
      const redacted = text.length === 0 && summary.length === 0
      return [{
        kind: "reasoning",
        reasoningId: id,
        redacted: redacted || text.length === 0,
        ...(text ? { text } : {}),
        ...(summary.length ? { summary } : {}),
      }]
    }
    if (type === "plan") {
      const text = typeof item.text === "string" ? item.text : ""
      plans.set(id, text)
      return [{ kind: "plan", entries: [{ content: text, status: started ? "in_progress" : "completed" }] }]
    }
    if (type === "commandExecution") {
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: "commandExecution",
        title: str(item.command),
        phase: toolPhaseItem(started, item),
        category: "execute",
        input: { command: item.command, cwd: item.cwd },
      }
      if (typeof item.aggregatedOutput === "string") tool.output = item.aggregatedOutput
      if (typeof item.exitCode === "number") tool.exitCode = item.exitCode
      return [tool]
    }
    if (type === "fileChange") {
      const changes = Array.isArray(item.changes) ? item.changes : []
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: "fileChange",
        phase: toolPhaseItem(started, item),
        category: "edit",
        output: changes,
      }
      const diffs: NormalizedBody[] = []
      for (const change of changes) {
        const row = rec(change)
        if (!row || typeof row.path !== "string" || typeof row.diff !== "string") continue
        diffs.push({
          kind: "file-diff",
          callId: id,
          path: row.path,
          diff: row.diff,
          ...(typeof row.kind === "string" ? { changeKind: row.kind } : {}),
        })
      }
      return [tool, ...diffs]
    }
    if (type === "mcpToolCall") {
      const server = str(item.server) ?? ""
      const toolName = str(item.tool) ?? ""
      const phase = toolPhaseItem(started, item)
      const mcpPhase = started ? "started" as const : phase === "failed" ? "failed" as const : "completed" as const
      const mcp: NormalizedBody = {
        kind: "mcp-tool",
        callId: id,
        server,
        tool: toolName,
        phase: mcpPhase,
        arguments: item.arguments,
        result: item.result ?? item.error,
      }
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: toolName || "mcp",
        phase,
        category: "mcp",
        input: item.arguments,
        output: item.result ?? item.error,
      }
      return [tool, mcp]
    }
    if (type === "dynamicToolCall") {
      return [{
        kind: "tool-call",
        callId: id,
        tool: str(item.tool) ?? "dynamic",
        phase: toolPhaseItem(started, item),
        input: item.arguments,
        output: item.contentItems,
      }]
    }
    if (type === "webSearch") {
      const phase = started ? "started" as const : "completed" as const
      return [
        { kind: "tool-call", callId: id, tool: "webSearch", phase: started ? "started" : "completed", category: "web-search", input: item.query },
        { kind: "web-search", callId: id, phase, query: str(item.query), results: item.results },
      ]
    }
    if (type === "subAgentActivity") {
      return [{
        kind: "task",
        taskId: id,
        taskKind: "subagent",
        phase: taskPhase(item.kind, undefined),
        label: str(item.agentPath),
      }]
    }
    if (type === "collabAgentToolCall") {
      return [{
        kind: "task",
        taskId: id,
        taskKind: "collab",
        phase: taskPhase(undefined, item.status),
        label: str(item.tool) ?? str(item.prompt),
      }]
    }
    if (type === "contextCompaction") {
      return [{ kind: "compaction", compactionId: id, status: started ? "in_progress" : "completed" }]
    }
    if (NEVER_TOOL_ITEM_TYPES.has(type)) return []
    if (TOOL_ITEM_TYPES.has(type) || type === "functionCallOutput" || type === "sleep") {
      return [{
        kind: "tool-call",
        callId: id,
        tool: type,
        phase: started ? "started" : "completed",
      }]
    }
    return []
  }

  function mapNotification(frame: Frame): NormalizedBody[] {
    const method = frame.method
    const params = rec(frame.params) ?? {}
    if (!method) return []
    if (method === "item/started" || method === "item/completed") {
      const item = rec(params.item)
      if (!item) return []
      return mapItem(item, method === "item/started")
    }
    if (method === "item/agentMessage/delta") {
      const id = str(params.itemId) ?? "message"
      const delta = typeof params.delta === "string" ? params.delta : ""
      assistant.set(id, (assistant.get(id) ?? "") + delta)
      return [{ kind: "assistant-delta", messageId: id, text: delta }]
    }
    if (method === "item/reasoning/textDelta" || method === "item/reasoning/summaryTextDelta") {
      const id = str(params.itemId) ?? "reasoning"
      const delta = typeof params.delta === "string" ? params.delta : ""
      const prev = reasoning.get(id) ?? { text: "", summary: [] }
      if (method === "item/reasoning/summaryTextDelta") prev.summary = [...prev.summary, delta]
      else prev.text += delta
      reasoning.set(id, prev)
      return [{ kind: "reasoning-delta", reasoningId: id, text: delta }]
    }
    if (method === "item/reasoning/summaryPartAdded") {
      const id = str(params.itemId) ?? "reasoning"
      const prev = reasoning.get(id) ?? { text: "", summary: [] }
      const part = typeof params.summary === "string" ? params.summary : typeof params.text === "string" ? params.text : ""
      if (part) prev.summary = [...prev.summary, part]
      reasoning.set(id, prev)
      return []
    }
    if (method === "item/plan/delta") {
      const id = str(params.itemId) ?? "plan"
      const delta = typeof params.delta === "string" ? params.delta : ""
      plans.set(id, (plans.get(id) ?? "") + delta)
      return [{ kind: "plan", entries: [{ content: plans.get(id) ?? "", status: "in_progress" }] }]
    }
    if (method === "item/commandExecution/outputDelta" || method === "command/exec/outputDelta" || method === "process/outputDelta") {
      const id = str(params.itemId) ?? str(params.processId) ?? "command"
      const delta = typeof params.delta === "string" ? params.delta : ""
      return [{ kind: "command-output", callId: id, stream: "merged", delta }]
    }
    if (method === "item/fileChange/outputDelta") {
      const id = str(params.itemId) ?? "fileChange"
      const delta = typeof params.delta === "string" ? params.delta : ""
      return [{ kind: "command-output", callId: id, stream: "merged", delta }]
    }
    if (method === "item/fileChange/patchUpdated") {
      const id = str(params.itemId) ?? "fileChange"
      const changes = Array.isArray(params.changes) ? params.changes : []
      const out: NormalizedBody[] = []
      for (const change of changes) {
        const row = rec(change)
        if (!row || typeof row.path !== "string" || typeof row.diff !== "string") continue
        out.push({
          kind: "file-diff",
          callId: id,
          path: row.path,
          diff: row.diff,
          ...(typeof row.kind === "string" ? { changeKind: row.kind } : {}),
        })
      }
      return out
    }
    if (method === "turn/plan/updated") {
      const steps = Array.isArray(params.plan) ? params.plan : []
      const entries = steps.map(step => {
        const row = rec(step) ?? {}
        return { content: str(row.step) ?? str(row.content) ?? "", status: planStatus(row.status) }
      })
      return [{
        kind: "plan",
        entries,
        ...(typeof params.explanation === "string" ? { explanation: params.explanation } : {}),
      }]
    }
    if (method === "turn/diff/updated") {
      const diff = typeof params.diff === "string" ? params.diff : ""
      return [{ kind: "file-diff", path: ".", diff }]
    }
    if (method === "thread/tokenUsage/updated") {
      const usage = rec(params.tokenUsage)
      const total = rec(usage?.total)
      const last = rec(usage?.last) ?? total
      const tokens = last
        ? {
            input: num(last.inputTokens) ?? 0,
            output: num(last.outputTokens) ?? 0,
            total: num(last.totalTokens) ?? 0,
          }
        : undefined
      const size = num(usage?.modelContextWindow)
      const used = num(total?.totalTokens)
      return [{
        kind: "usage",
        ...(tokens ? { tokens } : {}),
        ...(size != null && used != null ? { context: { used, size } } : {}),
      }]
    }
    if (method === "account/rateLimits/updated") {
      return [{ kind: "usage", rateLimits: params.rateLimits ?? params }]
    }
    if (method === "thread/name/updated") {
      return [{ kind: "session-info", title: typeof params.threadName === "string" ? params.threadName : null }]
    }
    if (method === "model/rerouted") {
      return [{ kind: "mode-update", model: str(params.toModel) }]
    }
    if (method === "warning" || method === "guardianWarning" || method === "deprecationNotice" || method === "configWarning") {
      const message = str(params.message) ?? str(params.summary) ?? ""
      const details = str(params.details)
      return [{ kind: "warning", message: details ? `${message}: ${details}` : message, source: method }]
    }
    if (method === "error") {
      const err = rec(params.error)
      const message = str(err?.message) ?? str(params.message) ?? "Codex error"
      return [{ kind: "error", message, recoverable: params.willRetry === true }]
    }
    if (method === "item/mcpToolCall/progress") {
      const id = str(params.itemId) ?? "mcp"
      return [{
        kind: "mcp-tool",
        callId: id,
        server: "",
        tool: "",
        phase: "progress",
        result: params.message,
      }]
    }
    if (method === "thread/compacted") {
      return [{ kind: "compaction", compactionId: str(params.turnId) ?? str(params.threadId) ?? "compact", status: "completed" }]
    }
    if (method === "item/commandExecution/requestApproval" || method === "item/fileChange/requestApproval" || method === "item/permissions/requestApproval" || method === "applyPatchApproval" || method === "execCommandApproval") {
      // Drivers answer these via context.requestPermission; Session emits the event.
      return []
    }
    if (method === "item/tool/requestUserInput") {
      const requestId = frame.id != null ? String(frame.id) : str(params.itemId) ?? "question"
      const questions = Array.isArray(params.questions) ? params.questions : []
      return [{
        kind: "user-question",
        requestId,
        blocking: params.isBlocking === true,
        questions: questions.map((q, i) => {
          const row = rec(q) ?? {}
          const qid = str(row.id) ?? `${requestId}:${i}`
          const options = Array.isArray(row.options)
            ? row.options.map((opt, j) => {
                const o = rec(opt) ?? {}
                return { id: str(o.id) ?? `${qid}:${j}`, label: str(o.label) ?? str(o.text) ?? String(opt) }
              })
            : undefined
          return {
            id: qid,
            prompt: str(row.question) ?? str(row.header) ?? "",
            ...(options ? { options } : {}),
            ...(row.isOther === true ? { allowFreeText: true } : {}),
            ...(row.isSecret === true ? { secret: true } : {}),
          }
        }),
      }]
    }
    return []
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    if (update.protocol !== "native") return []
    const frame = rec(update.value)
    if (!frame) return []
    return mapNotification(frame as Frame)
  }) as CodexNormalizer

  normalize.flush = () => {
    const out: NormalizedBody[] = []
    for (const [id, text] of assistant) {
      out.push({ kind: "assistant-message", messageId: id, text })
    }
    for (const [id, value] of reasoning) {
      out.push({
        kind: "reasoning",
        reasoningId: id,
        redacted: !value.text,
        ...(value.text ? { text: value.text } : {}),
        ...(value.summary.length ? { summary: value.summary } : {}),
      })
    }
    assistant.clear()
    reasoning.clear()
    return out
  }
  return normalize
}
