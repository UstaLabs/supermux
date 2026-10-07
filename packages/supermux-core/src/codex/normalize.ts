/** Codex app-server mapper. Schema pin: `codex app-server generate-ts` dump used 2026-09-21. */
import type { AgentUpdate, SubagentSnapshot } from "../types.js"
import type { NormalizedBody, PlanEntryStatus, SubagentEndedBy, SubagentMessaging, SubagentStats, ToolCallPhase } from "../events/normalized.js"
import { REASON, SUBAGENT_STATE_METHOD, actionFields, actionsKey, endedReason, type SubagentActions } from "../subagent-actions.js"

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

const ITEM_TYPE_ALIAS: Record<string, string> = {
  command_execution: "commandExecution",
  file_change: "fileChange",
  mcp_tool_call: "mcpToolCall",
  dynamic_tool_call: "dynamicToolCall",
  web_search: "webSearch",
}

function itemType(item: Record<string, unknown> | undefined): string | undefined {
  const raw = str(item?.type)
  if (!raw) return
  return ITEM_TYPE_ALIAS[raw] ?? raw
}

function descriptionOf(item: Record<string, unknown>, extra?: Record<string, unknown>): string | undefined {
  return str(item.description) ?? str(extra?.description)
}

function changeKindOf(kind: unknown): string | undefined {
  if (typeof kind === "string" && kind) return kind
  const row = rec(kind)
  return str(row?.type)
}

function textParts(value: unknown): string {
  if (typeof value === "string") return value
  if (!Array.isArray(value)) {
    const row = rec(value)
    if (!row) return ""
    if (typeof row.text === "string") return row.text
    if (Array.isArray(row.content)) return textParts(row.content)
    return ""
  }
  const parts: string[] = []
  for (const item of value) {
    if (typeof item === "string") {
      parts.push(item)
      continue
    }
    const row = rec(item)
    if (!row) continue
    if ((row.type === "text" || row.type === "inputText") && typeof row.text === "string") parts.push(row.text)
    else if (typeof row.text === "string") parts.push(row.text)
  }
  return parts.join("\n")
}

function jsonText(value: unknown): string {
  if (value == null) return ""
  if (typeof value === "string") return value
  try { return JSON.stringify(value) ?? "" } catch { return String(value) }
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

function mapQuestions(questions: unknown[], _fallback: string, opts: { freeTextFromOther?: boolean } = {}): Extract<NormalizedBody, { kind: "user-question" }>["questions"] {
  const used = new Set<string>()
  return questions.map((q, i) => {
    const row = rec(q) ?? {}
    const header = str(row.header) ?? str(row.title)
    const candidate = str(row.id) ?? header
    const id = candidate && !used.has(candidate) ? candidate : `q${i + 1}`
    used.add(id)
    const options = Array.isArray(row.options)
      ? row.options.map((opt, j) => {
          const o = rec(opt)
          const label = o ? (str(o.label) ?? str(o.text) ?? String(opt)) : String(opt)
          return { id: `o${j + 1}`, label }
        })
      : []
    return {
      id,
      prompt: str(row.question) ?? str(row.title) ?? str(row.header) ?? "",
      ...(header ? { header } : {}),
      multiSelect: row.multiSelect === true,
      allowFreeText: opts.freeTextFromOther ? row.isOther === true : false,
      options,
    }
  })
}

export type CodexNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & {
  flush: () => NormalizedBody[]
  /** Child thread ids learned from spawnAgent / subAgentActivity items. */
  isChild: (threadId: string) => boolean
  /** v2 (multi_agent_v2) children refuse direct app-server input. */
  messaging: (threadId: string) => SubagentMessaging | undefined
  /** Register a child known from elsewhere (keeper re-attach) without a `started` event. */
  adopt: (threadId: string) => void
  /** Native facts from `thread/read` on the child (nickname, canAcceptDirectInput). */
  setNative: (threadId: string, info: { nickname?: string; canAcceptDirectInput?: boolean | null }) => void
  /** The client interrupted the child's turn: that run ends `endedBy: "client"`. */
  markClientStop: (threadId: string) => void
  /** Whether the child has a turn running right now (Codex's own stop condition). */
  running: (threadId: string) => boolean
  /** Seed children remembered from before a resume; returns their state as this process sees it. */
  restore: (snapshots: SubagentSnapshot[], options: { stillRunning: boolean }) => SubagentSnapshot[]
}

type Child = {
  id: string
  open: boolean
  messaging: SubagentMessaging
  activity?: string
  result?: string
  stats: SubagentStats
  turnId?: string
  /** `thread/read` agentNickname: the subagent's real name ("Anscombe"). */
  nickname?: string
  /** `thread/read` canAcceptDirectInput (null/undefined: Codex did not say). */
  canAcceptDirectInput?: boolean | null
  ended?: "completed" | "failed" | "cancelled"
  endedBy?: SubagentEndedBy
  /** The client interrupted this run. */
  clientStop?: boolean
  /** The parent model called close_agent / interrupt_agent on it. */
  parentClose?: boolean
  emitted?: string
  emittedName?: string
}

const MAX_CHILDREN = 256

/** "/bin/bash -lc 'ls -la'" → "ls -la" for an activity line. */
function shortCommand(command: string): string {
  const m = /^\S*\/(?:ba|z)?sh\s+-l?c\s+([\s\S]*)$/.exec(command.trim())
  let inner = m ? m[1]!.trim() : command.trim()
  if (m && inner.length >= 2 && ((inner.startsWith("'") && inner.endsWith("'")) || (inner.startsWith('"') && inner.endsWith('"')))) inner = inner.slice(1, -1)
  return inner.split("\n")[0]!.slice(0, 200)
}

function oneLine(text: string): string {
  const line = text.trim().split("\n").find(l => l.trim()) ?? ""
  return line.length > 200 ? `${line.slice(0, 197)}...` : line
}

function childTerminal(status: unknown): "completed" | "failed" | "cancelled" {
  if (status === "interrupted" || status === "shutdown" || status === "cancelled") return "cancelled"
  if (status === "failed" || status === "errored" || status === "notFound") return "failed"
  return "completed"
}

export function createCodexNormalizer(): CodexNormalizer {
  const assistant = new Map<string, string>()
  const reasoning = new Map<string, { text: string; summary: string[] }>()
  const plans = new Map<string, string>()
  /** Buffered message/reasoning item id → owning child thread (flush attribution). */
  const owners = new Map<string, string>()
  const children = new Map<string, Child>()

  function attribute<T extends NormalizedBody>(body: T, subagentId: string | undefined): T {
    return subagentId ? { ...body, subagentId } : body
  }

  function childBody(child: Child, phase: Extract<NormalizedBody, { kind: "subagent" }>["phase"], extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody {
    const actions = actionsOf(child)
    child.emitted = actionsKey(actions)
    if (child.nickname) child.emittedName = child.nickname
    const terminal = phase === "completed" || phase === "failed" || phase === "cancelled"
    return {
      kind: "subagent", subagentId: child.id, phase,
      ...(child.nickname ? { name: child.nickname } : {}),
      ...(terminal && child.endedBy ? { endedBy: child.endedBy } : {}),
      ...actionFields(actions),
      ...extra,
    }
  }

  /**
   * Codex's own answers: the child thread says whether it accepts direct input
   * (`canAcceptDirectInput`; multi_agent v2 children do not), and Stop is `turn/interrupt` on the
   * child's running turn — nothing to interrupt otherwise. A child its parent closed (notLoaded)
   * is still messageable: the driver resumes the thread first.
   */
  function actionsOf(child: Child): SubagentActions {
    const native = typeof child.canAcceptDirectInput === "boolean"
    const accepts = native ? child.canAcceptDirectInput === true : child.messaging !== "none"
    const running = child.turnId !== undefined
    return {
      canMessage: accepts,
      ...(accepts ? {} : { cannotMessageReason: REASON.codexNoInput }),
      canStop: running,
      ...(running ? {} : { cannotStopReason: child.open ? REASON.notRunning : endedReason(child.ended) }),
      actionsSource: native || child.messaging === "none" ? "native" : "derived",
    }
  }

  /** A progress body when this child's flags or name changed outside a lifecycle body. */
  function refresh(child: Child | undefined, out: NormalizedBody[]): NormalizedBody[] {
    if (!child || out.some(body => body.kind === "subagent" && body.subagentId === child.id)) return out
    if (child.emitted === actionsKey(actionsOf(child)) && child.emittedName === child.nickname) return out
    return [...out, childBody(child, "progress")]
  }

  function spawn(threadId: string, extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>>, messaging: SubagentMessaging): NormalizedBody[] {
    if (children.has(threadId)) return []
    const child: Child = { id: threadId, open: true, messaging, stats: {} }
    children.set(threadId, child)
    while (children.size > MAX_CHILDREN) {
      const oldest = children.keys().next().value
      if (oldest === undefined) break
      children.delete(oldest)
    }
    return [childBody(child, "started", { ...extra, messaging })]
  }

  function finish(child: Child, phase: "completed" | "failed" | "cancelled", result?: string): NormalizedBody[] {
    if (!child.open) return []
    child.open = false
    child.ended = phase
    child.endedBy = phase !== "cancelled" ? "self" : child.parentClose ? "parent" : child.clientStop ? "client" : undefined
    child.clientStop = false
    child.parentClose = false
    const text = result ?? child.result
    const stats = Object.keys(child.stats).length ? { ...child.stats } : undefined
    return [childBody(child, phase, { ...(text ? { result: text } : {}), ...(stats ? { stats } : {}) })]
  }

  function activity(child: Child, line: string): NormalizedBody[] {
    if (!line || line === child.activity || !child.open) return []
    child.activity = line
    const stats = Object.keys(child.stats).length ? { ...child.stats } : undefined
    return [childBody(child, "progress", { activity: line, ...(stats ? { stats } : {}) })]
  }

  /** Main-thread collab/subAgentActivity items drive the subagent lifecycle. */
  function mapCollab(item: Record<string, unknown>, started: boolean): NormalizedBody[] {
    const type = itemType(item)
    const id = str(item.id) ?? "item"
    if (type === "subAgentActivity") {
      const threadId = str(item.agentThreadId)
      if (!threadId) return []
      const kind = item.kind
      if (kind === "started") {
        const name = str(item.agentPath)
        // multi_agent_v2 children refuse direct app-server input (-32600).
        return spawn(threadId, { parentCallId: id, ...(name ? { name } : {}) }, "none")
      }
      const child = children.get(threadId)
      if (!child) return []
      if (kind === "completed") return finish(child, "completed")
      if (kind === "interrupted") return finish(child, "cancelled")
      return []
    }
    // collabAgentToolCall
    const tool = str(item.tool)
    const receivers = Array.isArray(item.receiverThreadIds) ? item.receiverThreadIds.filter((x): x is string => typeof x === "string" && !!x) : []
    if (started && (tool === "closeAgent" || tool === "interruptAgent")) {
      // The parent is ending these children: their interrupted turn is the parent's doing.
      for (const threadId of receivers) {
        const child = children.get(threadId)
        if (child?.open) child.parentClose = true
      }
    }
    if (started) return []
    const states = rec(item.agentsStates) ?? {}
    const out: NormalizedBody[] = []
    if (tool === "spawnAgent") {
      for (const threadId of receivers) {
        const prompt = str(item.prompt)
        const model = str(item.model)
        out.push(...spawn(threadId, { parentCallId: id, ...(prompt ? { prompt } : {}), ...(model ? { model } : {}) }, "direct"))
      }
    }
    // wait / closeAgent / interruptAgent report the children's last states.
    for (const [threadId, raw] of Object.entries(states)) {
      const child = children.get(threadId)
      const state = rec(raw)
      if (!child || !state) continue
      const status = state.status
      if (status === "pendingInit" || status === "running") continue
      const message = str(state.message)
      out.push(...finish(child, childTerminal(status), message))
    }
    return out
  }

  function mapChildItem(child: Child, item: Record<string, unknown>, started: boolean): NormalizedBody[] {
    const type = itemType(item)
    // A child's own collab items describe grandchildren: those subagent bodies keep their own id.
    const out = mapItem(item, started).map(body => body.kind === "subagent" ? body : attribute(body, child.id))
    if (type === "commandExecution" && started) {
      child.stats.toolCalls = (child.stats.toolCalls ?? 0) + 1
      const command = str(item.command)
      if (command) out.push(...activity(child, `Running ${shortCommand(command)}`))
    } else if ((type === "mcpToolCall" || type === "dynamicToolCall" || type === "webSearch") && started) {
      child.stats.toolCalls = (child.stats.toolCalls ?? 0) + 1
      out.push(...activity(child, `Using ${str(item.tool) ?? type}`))
    } else if (type === "fileChange" && started) {
      child.stats.toolCalls = (child.stats.toolCalls ?? 0) + 1
      out.push(...activity(child, "Editing files"))
    } else if (type === "agentMessage" && !started) {
      const text = typeof item.text === "string" ? item.text : ""
      if (item.phase === "final_answer") child.result = text
      else if (text) out.push(...activity(child, oneLine(text)))
    }
    return out
  }

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
      // Inline questions are asked by the driver through context.requestAnswers (answerable,
      // steered into the still-running turn); the Session emits the user-question event.
      void questions
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
      const desc = descriptionOf(item)
      const aggregated = typeof item.aggregatedOutput === "string" ? item.aggregatedOutput
        : typeof item.aggregated_output === "string" ? item.aggregated_output
        : undefined
      const exitCode = num(item.exitCode) ?? num(item.exit_code)
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: "commandExecution",
        title: str(item.command),
        phase: toolPhaseItem(started, item),
        category: "execute",
        input: {
          command: item.command,
          ...(item.cwd !== undefined ? { cwd: item.cwd } : {}),
          ...(desc ? { description: desc } : {}),
        },
        ...(desc ? { description: desc } : {}),
      }
      if (aggregated !== undefined) tool.output = aggregated
      if (exitCode !== undefined) tool.exitCode = exitCode
      return [tool]
    }
    if (type === "fileChange") {
      const changes = Array.isArray(item.changes) ? item.changes : []
      const desc = descriptionOf(item)
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: "fileChange",
        phase: toolPhaseItem(started, item),
        category: "edit",
        input: {
          ...(typeof item.path === "string" ? { path: item.path } : {}),
          ...(typeof item.file === "string" ? { file: item.file } : {}),
          ...(changes.length ? { changes } : {}),
        },
        ...(desc ? { description: desc } : {}),
      }
      const diffs: NormalizedBody[] = []
      for (const change of changes) {
        const row = rec(change)
        if (!row || typeof row.path !== "string") continue
        const diff = typeof row.diff === "string" ? row.diff
          : typeof row.unified_diff === "string" ? row.unified_diff
          : ""
        const ck = changeKindOf(row.kind)
        diffs.push({
          kind: "file-diff",
          callId: id,
          path: row.path,
          diff,
          ...(ck ? { changeKind: ck } : {}),
        })
      }
      return [tool, ...diffs]
    }
    if (type === "mcpToolCall") {
      const server = str(item.server) ?? ""
      const toolName = str(item.tool) ?? str(item.toolName) ?? str(item.tool_name) ?? ""
      const phase = toolPhaseItem(started, item)
      const mcpPhase = started ? "started" as const : phase === "failed" ? "failed" as const : "completed" as const
      const args = item.arguments ?? item.args
      const result = item.result ?? item.error
      const resultRow = rec(result)
      const output = typeof result === "string" ? result
        : textParts(resultRow?.content) || (resultRow?.structuredContent != null ? jsonText(resultRow.structuredContent) : "") || (result != null ? jsonText(result) : undefined)
      const desc = descriptionOf(item, rec(args))
      const mcp: NormalizedBody = {
        kind: "mcp-tool",
        callId: id,
        server,
        tool: toolName,
        phase: mcpPhase,
        arguments: args,
        result,
      }
      const tool: NormalizedBody = {
        kind: "tool-call",
        callId: id,
        tool: "mcpToolCall",
        phase,
        category: "mcp",
        input: { server, tool: toolName, toolName, arguments: args, args },
        ...(output !== undefined && output !== "" ? { output } : result != null ? { output: result } : {}),
        ...(desc ? { description: desc } : {}),
      }
      return [tool, mcp]
    }
    if (type === "dynamicToolCall") {
      const args = item.arguments ?? item.args
      const desc = descriptionOf(item, rec(args))
      const content = item.contentItems ?? item.content_items
      const output = textParts(content)
      return [{
        kind: "tool-call",
        callId: id,
        tool: str(item.tool) ?? "dynamic",
        phase: toolPhaseItem(started, item),
        category: "other",
        input: args,
        ...(output ? { output } : content != null ? { output: content } : {}),
        ...(desc ? { description: desc } : {}),
      }]
    }
    if (type === "webSearch") {
      const phase = started ? "started" as const : "completed" as const
      const desc = descriptionOf(item, rec(item.action))
      return [
        {
          kind: "tool-call",
          callId: id,
          tool: "webSearch",
          phase: started ? "started" : "completed",
          category: "web-search",
          input: { query: item.query, action: item.action },
          ...(desc ? { description: desc } : {}),
        },
        { kind: "web-search", callId: id, phase, query: str(item.query), results: item.results },
      ]
    }
    if (type === "subAgentActivity" || type === "collabAgentToolCall") return mapCollab(item, started)
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

  function mapChild(child: Child, method: string, params: Record<string, unknown>, frame: Frame): NormalizedBody[] {
    if (method === "turn/started") {
      const turn = rec(params.turn)
      child.turnId = str(turn?.id)
      if (child.open) return []
      // A new child turn after its run ended: a direct message (or a parent follow-up) resumed it.
      child.open = true
      child.ended = undefined
      child.endedBy = undefined
      child.activity = undefined
      child.result = undefined
      // Per-run counters restart; tokens stay the thread total Codex reports.
      delete child.stats.toolCalls
      return [childBody(child, "resumed", { messaging: child.messaging })]
    }
    if (method === "turn/completed") {
      const turn = rec(params.turn)
      child.turnId = undefined
      const status = turn?.status
      const error = str(rec(turn?.error)?.message)
      return finish(child, childTerminal(status), status === "failed" ? error : undefined)
    }
    if (method === "item/started" || method === "item/completed") {
      const item = rec(params.item)
      if (!item) return []
      return mapChildItem(child, item, method === "item/started")
    }
    if (method === "thread/tokenUsage/updated") {
      const total = num(rec(rec(params.tokenUsage)?.total)?.totalTokens)
      if (total !== undefined) child.stats.tokens = total
      return []
    }
    if (method === "account/rateLimits/updated" || method === "thread/name/updated" || method === "model/rerouted") return []
    const out = mapNotification(frame, true)
    for (const body of out) {
      if (body.kind === "assistant-delta") owners.set(body.messageId, child.id)
      if (body.kind === "reasoning-delta") owners.set(body.reasoningId, child.id)
    }
    return out.map(body => attribute(body, child.id))
  }

  function mapNotification(frame: Frame, nested = false): NormalizedBody[] {
    const method = frame.method
    const params = rec(frame.params) ?? {}
    if (!method) return []
    const threadId = str(params.threadId)
    const child = !nested && threadId ? children.get(threadId) : undefined
    if (child) return mapChild(child, method, params, frame)
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
        if (!row || typeof row.path !== "string") continue
        const diff = typeof row.diff === "string" ? row.diff
          : typeof row.unified_diff === "string" ? row.unified_diff
          : ""
        const ck = changeKindOf(row.kind)
        out.push({
          kind: "file-diff",
          callId: id,
          path: row.path,
          diff,
          ...(ck ? { changeKind: ck } : {}),
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
        questions: mapQuestions(questions, requestId, { freeTextFromOther: true }),
      }]
    }
    return []
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    if (update.protocol !== "native") return []
    const frame = rec(update.value)
    if (!frame) return []
    const params = rec(frame.params)
    if (frame.method === SUBAGENT_STATE_METHOD) return refresh(children.get(str(params?.subagentId) ?? ""), [])
    const out = mapNotification(frame as Frame)
    // A child turn starting/ending moves Stop even when no lifecycle body says so.
    const threadId = str(params?.threadId)
    return threadId ? refresh(children.get(threadId), out) : out
  }) as CodexNormalizer

  normalize.flush = () => {
    const out: NormalizedBody[] = []
    for (const [id, text] of assistant) {
      out.push(attribute({ kind: "assistant-message", messageId: id, text }, owners.get(id)))
    }
    for (const [id, value] of reasoning) {
      out.push(attribute({
        kind: "reasoning",
        reasoningId: id,
        redacted: !value.text,
        ...(value.text ? { text: value.text } : {}),
        ...(value.summary.length ? { summary: value.summary } : {}),
      }, owners.get(id)))
    }
    assistant.clear()
    reasoning.clear()
    owners.clear()
    return out
  }
  normalize.isChild = (threadId: string) => children.has(threadId)
  normalize.adopt = (threadId: string) => {
    if (children.has(threadId)) return
    children.set(threadId, { id: threadId, open: false, messaging: "direct", stats: {} })
  }
  normalize.messaging = (threadId: string) => children.get(threadId)?.messaging
  normalize.setNative = (threadId, info) => {
    const child = children.get(threadId)
    if (!child) return
    if (info.nickname) child.nickname = info.nickname
    if (typeof info.canAcceptDirectInput === "boolean") child.canAcceptDirectInput = info.canAcceptDirectInput
  }
  normalize.markClientStop = (threadId: string) => { const child = children.get(threadId); if (child?.open) child.clientStop = true }
  normalize.running = (threadId: string) => children.get(threadId)?.turnId !== undefined
  normalize.restore = (snapshots, { stillRunning }) => {
    const out: SubagentSnapshot[] = []
    for (const snap of snapshots) {
      if (!snap?.subagentId || children.has(snap.subagentId)) continue
      const running = snap.status === "running" && stillRunning
      const ended = snap.status === "running" ? "cancelled" : snap.status
      const child: Child = {
        id: snap.subagentId, open: running, messaging: snap.messaging ?? "direct", stats: {},
        ...(snap.name ? { nickname: snap.name } : {}),
        ...(snap.actionsSource === "native" && typeof snap.canMessage === "boolean" ? { canAcceptDirectInput: snap.canMessage } : {}),
        ...(running ? {} : { ended, ...(snap.endedBy ? { endedBy: snap.endedBy } : {}) }),
      }
      children.set(child.id, child)
      const actions = actionsOf(child)
      child.emitted = actionsKey(actions)
      child.emittedName = child.nickname
      const { cannotMessageReason: _m, cannotStopReason: _s, ...base } = snap
      out.push({ ...base, status: running ? "running" : ended, ...actionFields(actions) } as SubagentSnapshot)
    }
    return out
  }
  return normalize
}
