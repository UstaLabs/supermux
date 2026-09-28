/** ACP SessionUpdate mapper. Schema pin: @agentclientprotocol/sdk 1.4.0 types.gen.d.ts. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, PlanEntryPriority, PlanEntryStatus, SubagentMessaging, SubagentPhase, SubagentStats, ToolCallPhase, ToolCategory } from "../events/normalized.js"

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
  const nested = rec(block.content)
  if (nested && typeof nested.text === "string") return nested.text
  return ""
}

function grokContentText(content: unknown): string {
  if (!Array.isArray(content)) return contentText(content)
  const out: string[] = []
  for (const item of content) {
    const t = contentText(item)
    if (t) out.push(t)
  }
  return out.join("\n")
}

function argDescription(input: unknown): string | undefined {
  const row = rec(input)
  return str(row?.description) ?? str(row?.desc) ?? str(row?.explanation)
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

export type AcpVendor = "grok" | "cursor" | "opencode"

export type AcpNormalizerOptions = {
  vendor?: AcpVendor
  /** The main ACP session; updates from any other session belong to a subagent. Defaults to the first session seen. */
  mainSessionId?: () => string | undefined
  /** Cursor only: the agent negotiated the ACP subagents extension (subagent_spawned + child streams). */
  cursorSubagents?: () => boolean
}

export type AcpSubagentInfo = { id: string; nativeId?: string; open: boolean }

export type AcpNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & {
  flush: () => NormalizedBody[]
  /** Subagent that owns a tool call id (child tool calls; permission attribution). */
  subagentForTool: (toolCallId: string) => string | undefined
  /** Subagent a child session id belongs to. */
  subagentForSession: (sessionId: string) => string | undefined
  subagent: (id: string) => AcpSubagentInfo | undefined
  /** Open subagents (OpenCode side channel: which running tasks still lack a child session). */
  openSubagents: () => AcpSubagentInfo[]
}

/** Driver-synthesized native frame for a turn the client started directly on a child session. */
export const SUBAGENT_TURN_METHOD = "supermux/subagent-turn"
/** Driver-synthesized native frame: a running subagent's child session became known ({subagentId, nativeId}). */
export const SUBAGENT_SESSION_METHOD = "supermux/subagent-session"
/** Driver-synthesized native frame: a warning the driver wants shown ({message}). */
export const DRIVER_WARNING_METHOD = "supermux/warning"

type Scope = {
  subagentId?: string
  assistant: Map<string, string>
  thoughts: Map<string, string>
  assistantSeq: number
  thoughtSeq: number
  /** Last final assistant text of this scope (a child's answer when the CLI gives none). */
  lastText?: string
}

type Sub = {
  id: string
  nativeId?: string
  open: boolean
  messaging: SubagentMessaging
  parentCallId?: string
  activity?: string
  stats: SubagentStats
  /** Cursor: terminal reported by subagent_state_update / Task completion, finalized by cursor/task. */
  pending?: { phase: "completed" | "failed" | "cancelled"; result?: string }
  /** Cursor Task tool call id (also the key cursor/task reports). */
  taskCallId?: string
}

type SpawnCall = { callId: string; description?: string; prompt?: string; subagentType?: string; background?: boolean; resumeFrom?: string }

const MAX_TRACKED = 512

function remember<K, V>(map: Map<K, V>, key: K, value: V): void {
  if (map.has(key)) map.delete(key)
  map.set(key, value)
  while (map.size > MAX_TRACKED) {
    const oldest = map.keys().next().value
    if (oldest === undefined) break
    map.delete(oldest)
  }
}

function num(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined
}

function terminalOf(status: unknown): "completed" | "failed" | "cancelled" | undefined {
  if (status === "completed" || status === "success" || status === "done") return "completed"
  if (status === "failed" || status === "error" || status === "errored") return "failed"
  if (status === "cancelled" || status === "canceled" || status === "aborted" || status === "stopped" || status === "killed" || status === "interrupted") return "cancelled"
  return
}

function taskResult(text: string): string {
  const m = /<task_result>([\s\S]*?)<\/task_result>/.exec(text)
  return (m ? m[1]! : text).trim()
}

export function createAcpNormalizer(options: AcpNormalizerOptions = {}): AcpNormalizer {
  const vendor = options.vendor
  const toolNames = new Map<string, string>()
  let lastCommandsJson: string | undefined
  let firstSession: string | undefined
  const scopes = new Map<string, Scope>()
  const subs = new Map<string, Sub>()
  /** Child session id / nativeId / resume call id → subagent id. */
  const aliases = new Map<string, string>()
  /** Child tool call id → subagent id. */
  const childTools = new Map<string, string>()
  /** Grok spawn_subagent calls waiting for their subagent_spawned. */
  const spawnCalls: SpawnCall[] = []
  /** Cursor Task calls (rawInput) keyed by tool call id, for enrichment. */
  const taskCalls = new Map<string, { description?: string; prompt?: string; subagentType?: string }>()
  /** OpenCode task calls not yet announced (their rawInput arrives on an update). */
  const opencodeTasks = new Set<string>()

  const mainScope = scopeFor(undefined)

  function scopeFor(subagentId: string | undefined): Scope {
    const key = subagentId ?? ""
    let scope = scopes.get(key)
    if (!scope) {
      scope = { ...(subagentId ? { subagentId } : {}), assistant: new Map(), thoughts: new Map(), assistantSeq: 0, thoughtSeq: 0 }
      scopes.set(key, scope)
    }
    return scope
  }

  function attribute<T extends NormalizedBody>(body: T, subagentId: string | undefined): T {
    return subagentId ? { ...body, subagentId } : body
  }

  function takeAssistant(scope: Scope): NormalizedBody[] {
    if (!scope.assistant.size) return []
    const out: NormalizedBody[] = []
    for (const [id, text] of scope.assistant) {
      out.push(attribute({ kind: "assistant-message", messageId: id, text }, scope.subagentId))
      if (text.trim()) scope.lastText = text
    }
    scope.assistant.clear()
    return out
  }

  function takeThoughts(scope: Scope): NormalizedBody[] {
    if (!scope.thoughts.size) return []
    const out: NormalizedBody[] = []
    for (const [id, text] of scope.thoughts) {
      out.push(attribute({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) }, scope.subagentId))
    }
    scope.thoughts.clear()
    return out
  }

  function isMain(sessionId: string | undefined): boolean {
    if (!sessionId) return true
    const main = options.mainSessionId?.() || firstSession
    if (!main) { firstSession = sessionId; return true }
    return sessionId === main
  }

  function subBody(sub: Sub, phase: SubagentPhase, extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody {
    return {
      kind: "subagent",
      subagentId: sub.id,
      phase,
      ...(phase === "started" || phase === "resumed" ? { messaging: sub.messaging, ...(sub.parentCallId ? { parentCallId: sub.parentCallId } : {}) } : {}),
      ...(sub.nativeId && sub.nativeId !== sub.id ? { nativeId: sub.nativeId } : {}),
      ...extra,
    }
  }

  function newSub(id: string, messaging: SubagentMessaging, parentCallId?: string, nativeId?: string): Sub {
    const sub: Sub = { id, open: true, messaging, stats: {}, ...(parentCallId ? { parentCallId } : {}), ...(nativeId ? { nativeId } : {}) }
    remember(subs, id, sub)
    remember(aliases, id, id)
    if (nativeId) remember(aliases, nativeId, id)
    return sub
  }

  function lookup(idOrAlias: string | undefined): Sub | undefined {
    if (!idOrAlias) return
    const id = aliases.get(idOrAlias) ?? idOrAlias
    return subs.get(id)
  }

  function defaultMessaging(): SubagentMessaging {
    return vendor === "cursor" ? "relay" : vendor === "grok" || vendor === "opencode" ? "direct" : "none"
  }

  function reopen(sub: Sub, extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody[] {
    if (sub.open) return []
    sub.open = true
    sub.pending = undefined
    sub.activity = undefined
    sub.stats = {}
    scopeFor(sub.id).lastText = undefined
    return [subBody(sub, "resumed", extra)]
  }

  function finish(sub: Sub, phase: "completed" | "failed" | "cancelled", result?: string, extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody[] {
    if (!sub.open) return []
    const scope = scopeFor(sub.id)
    const out = [...takeThoughts(scope), ...takeAssistant(scope)]
    sub.open = false
    sub.pending = undefined
    const text = result ?? (phase === "completed" ? scope.lastText : undefined)
    const stats = Object.keys(sub.stats).length ? { ...sub.stats } : undefined
    out.push(subBody(sub, phase, { ...(text ? { result: text } : {}), ...(stats ? { stats } : {}), ...extra }))
    return out
  }

  function progress(sub: Sub, activity?: string, stats?: SubagentStats, extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody[] {
    if (!sub.open) return []
    let changed = Object.keys(extra).length > 0
    if (activity && activity !== sub.activity) { sub.activity = activity; changed = true }
    if (stats) {
      const next = { ...sub.stats, ...stats }
      if (JSON.stringify(next) !== JSON.stringify(sub.stats)) { sub.stats = next; changed = true }
    }
    if (!changed) return []
    const current = Object.keys(sub.stats).length ? { ...sub.stats } : undefined
    return [subBody(sub, "progress", { ...(sub.activity ? { activity: sub.activity } : {}), ...(current ? { stats: current } : {}), ...extra })]
  }

  /** Frames from a session we have not been told about: still a subagent, never the parent. */
  function childFor(sessionId: string): { sub: Sub; created: NormalizedBody[] } {
    const known = lookup(sessionId)
    if (known) return { sub: known, created: [] }
    const sub = newSub(sessionId, defaultMessaging())
    return { sub, created: [subBody(sub, "started")] }
  }

  function mapAcp(value: Record<string, unknown>, scope: Scope, sub?: Sub): NormalizedBody[] {
    const kind = value.sessionUpdate
    const subagentId = scope.subagentId
    if (kind === "turn_completed" || kind === "user_message_chunk") return []
    if (sub && (kind === "available_commands_update" || kind === "usage_update" || kind === "session_info_update" || kind === "current_mode_update" || kind === "config_option_update")) return []
    if (kind === "agent_message_chunk") {
      const prefix = takeThoughts(scope)
      if (prefix.length && !str(value.messageId)) scope.thoughtSeq += 1
      if (!str(value.messageId) && scope.assistant.size === 0) {
        if (scope.assistantSeq === 0) scope.assistantSeq = 1
      }
      const base = str(value.messageId) ?? `assistant:${scope.assistantSeq || 1}`
      const messageId = subagentId ? `${subagentId}:${base}` : base
      const text = contentText(value.content)
      scope.assistant.set(messageId, (scope.assistant.get(messageId) ?? "") + text)
      return [...prefix, attribute({ kind: "assistant-delta", messageId, text }, subagentId)]
    }
    if (kind === "agent_thought_chunk") {
      const prefix = takeAssistant(scope)
      if (prefix.length && !str(value.messageId)) scope.assistantSeq += 1
      if (!str(value.messageId) && scope.thoughts.size === 0) {
        if (scope.thoughtSeq === 0) scope.thoughtSeq = 1
      }
      const base = str(value.messageId) ?? `reasoning:${scope.thoughtSeq || 1}`
      const reasoningId = subagentId ? `${subagentId}:${base}` : base
      const text = contentText(value.content)
      scope.thoughts.set(reasoningId, (scope.thoughts.get(reasoningId) ?? "") + text)
      return [...prefix, attribute({ kind: "reasoning-delta", reasoningId, text }, subagentId)]
    }
    if (kind === "tool_call" || kind === "tool_call_update") {
      const prefix = kind === "tool_call" ? [...takeThoughts(scope), ...takeAssistant(scope)] : []
      if (prefix.length && kind === "tool_call") {
        scope.assistantSeq += 1
        scope.thoughtSeq += 1
      }
      const callId = str(value.toolCallId) ?? "tool"
      // tool_call_update frames usually omit the name: keep the one announced by the matching
      // tool_call so started/completed events describe the same tool (bounded map).
      const announced = str(value.name) ?? (kind === "tool_call" ? str(value.title) : undefined)
      if (announced) remember(toolNames, callId, announced)
      const name = announced ?? toolNames.get(callId) ?? str(value.title) ?? "tool"
      const phase = toolPhase(value.status, kind === "tool_call_update")
      const desc = argDescription(value.rawInput)
      const content = Array.isArray(value.content) ? value.content : []
      const contentOutput = grokContentText(content)
      const output = contentOutput || (value.rawOutput !== undefined ? value.rawOutput : undefined)
      const cat = category(value.kind)
      const out: NormalizedBody[] = [attribute({
        kind: "tool-call",
        callId,
        tool: name,
        ...(typeof value.title === "string" ? { title: value.title } : {}),
        phase,
        ...(cat ? { category: cat } : {}),
        ...(Array.isArray(value.locations) ? {
          locations: value.locations.flatMap(loc => {
            const row = rec(loc)
            return row && typeof row.path === "string" ? [{ path: row.path }] : []
          }),
        } : {}),
        ...(value.rawInput !== undefined ? { input: value.rawInput } : {}),
        ...(output !== undefined ? { output } : {}),
        ...(desc ? { description: desc } : {}),
      }, subagentId)]
      for (const part of content) {
        const row = rec(part)
        if (!row) continue
        if (row.type === "diff" && typeof row.path === "string" && typeof row.diff === "string") {
          out.push(attribute({ kind: "file-diff", callId, path: row.path, diff: row.diff, ...(typeof row.changeKind === "string" ? { changeKind: row.changeKind } : {}) }, subagentId))
        } else if (row.type === "terminal") {
          const delta = typeof row.output === "string" ? row.output : typeof row.text === "string" ? row.text : JSON.stringify(row)
          out.push(attribute({ kind: "command-output", callId, stream: "merged", delta }, subagentId))
        } else if (row.type === "content" || row.type === "text") {
          const delta = contentText(row)
          if (delta) out.push(attribute({ kind: "command-output", callId, stream: "merged", delta }, subagentId))
        }
      }
      if (cat === "search" || cat === "fetch") {
        out.push(attribute({
          kind: "web-search",
          callId,
          phase: phase === "failed" ? "failed" : phase === "completed" ? "completed" : "started",
          query: str(value.title),
        }, subagentId))
      }
      if (sub) {
        remember(childTools, callId, sub.id)
        const title = str(value.title)
        const counts = kind === "tool_call" && vendor !== "grok"
        const stats = counts ? { toolCalls: (sub.stats.toolCalls ?? 0) + 1 } : undefined
        if (title || stats) out.push(...progress(sub, title, stats))
      } else {
        out.push(...parentToolSubagents(kind, callId, name, value))
      }
      return prefix.length ? [...prefix, ...out] : out
    }
    if (kind === "plan" || kind === "plan_update") {
      const prefix = [...takeThoughts(scope), ...takeAssistant(scope)]
      if (prefix.length) {
        scope.assistantSeq += 1
        scope.thoughtSeq += 1
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
      const plan = attribute({ kind: "plan", entries } as NormalizedBody, subagentId)
      return prefix.length ? [...prefix, plan] : [plan]
    }
    if (kind === "plan_removed") {
      return [attribute({ kind: "plan", entries: [] } as NormalizedBody, subagentId)]
    }
    if (vendor === "grok" && !sub && (kind === "subagent_spawned" || kind === "subagent_progress" || kind === "subagent_finished")) return grokSubagent(kind, value)
    if (kind === "subagent_spawned" && !sub) return cursorSpawned(value)
    if (kind === "subagent_state_update" && !sub) return cursorState(value)
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
      return [attribute({
        kind: "compaction",
        compactionId: str(value.compactionId) ?? "compaction",
        status: compactionStatus(value.status),
        ...(summary ? { summary } : {}),
        ...(typeof value.error === "string" ? { error: value.error } : {}),
      } as NormalizedBody, subagentId)]
    }
    if (kind === "compaction_summary_chunk") {
      return [attribute({
        kind: "compaction",
        compactionId: str(value.compactionId) ?? "compaction",
        status: "in_progress",
        summary: contentText(value.content),
      } as NormalizedBody, subagentId)]
    }
    return []
  }

  /** Parent tool calls that are (or describe) subagents: Grok spawn_subagent, Cursor Task, OpenCode task. */
  function parentToolSubagents(kind: string, callId: string, name: string, value: Record<string, unknown>): NormalizedBody[] {
    const input = rec(value.rawInput)
    if (vendor === "grok" && name === "spawn_subagent" && kind === "tool_call" && input) {
      spawnCalls.push({
        callId,
        ...(str(input.description) ? { description: str(input.description) } : {}),
        ...(str(input.prompt) ? { prompt: str(input.prompt) } : {}),
        ...(str(input.subagent_type) ? { subagentType: str(input.subagent_type) } : {}),
        ...(typeof input.background === "boolean" ? { background: input.background } : {}),
        ...(str(input.resume_from) ? { resumeFrom: str(input.resume_from) } : {}),
      })
      if (spawnCalls.length > 64) spawnCalls.shift()
      return []
    }
    if (vendor === "cursor") return cursorTaskCall(kind, callId, value, input)
    if (vendor === "opencode" && name === "task") return opencodeTask(kind, callId, value, input)
    return []
  }

  function grokSubagent(kind: string, value: Record<string, unknown>): NormalizedBody[] {
    const childSession = str(value.child_session_id) ?? str(value.subagent_id)
    if (!childSession) return []
    if (kind === "subagent_spawned") {
      const description = str(value.description)
      const index = spawnCalls.findIndex(call => description !== undefined && call.description === description)
      const call = index >= 0 ? spawnCalls.splice(index, 1)[0] : spawnCalls.shift()
      const model = str(value.model)
      const name = str(value.subagent_type) ?? call?.subagentType
      const resumed = call?.resumeFrom ? lookup(call.resumeFrom) : undefined
      if (resumed && !resumed.open) {
        // spawn_subagent resume_from: Grok continues the conversation in a new child session.
        remember(aliases, childSession, resumed.id)
        resumed.nativeId = childSession
        resumed.parentCallId = call!.callId
        return reopen(resumed, { ...(call?.prompt ? { prompt: call.prompt } : {}), ...(description ? { description } : {}), ...(model ? { model } : {}) })
      }
      if (lookup(childSession)) return []
      const sub = newSub(childSession, "direct", call?.callId)
      return [subBody(sub, "started", {
        ...(name ? { name } : {}),
        ...(description ? { description } : {}),
        ...(call?.prompt ? { prompt: call.prompt } : {}),
        ...(call?.background !== undefined ? { background: call.background } : {}),
        ...(model ? { model } : {}),
      })]
    }
    const sub = lookup(childSession)
    if (!sub) return []
    const stats: SubagentStats = {}
    const toolCalls = num(value.tool_call_count) ?? num(value.tool_calls)
    const turns = num(value.turn_count) ?? num(value.turns)
    const tokens = num(value.tokens_used)
    const durationMs = num(value.duration_ms)
    if (toolCalls !== undefined) stats.toolCalls = toolCalls
    if (turns !== undefined) stats.turns = turns
    if (tokens !== undefined) stats.tokens = tokens
    if (durationMs !== undefined) stats.durationMs = durationMs
    if (kind === "subagent_progress") return progress(sub, undefined, stats)
    sub.stats = { ...sub.stats, ...stats }
    const phase = terminalOf(value.status) ?? "completed"
    const output = str(value.output) ?? str(value.error)
    return finish(sub, phase, output)
  }

  function cursorSpawned(value: Record<string, unknown>): NormalizedBody[] {
    if (vendor !== "cursor") return []
    const session = str(value.subagentSessionId)
    if (!session) return []
    const meta = rec(rec(value._meta)?.cursor)
    const id = str(meta?.agentId) ?? session
    const toolCallId = str(meta?.toolCallId)
    const model = str(meta?.model)
    const name = str(value.name)
    const task = toolCallId ? taskCalls.get(toolCallId) : undefined
    const prompt = task?.prompt ?? str(value.task)
    const existing = lookup(id)
    if (existing) {
      remember(aliases, session, existing.id)
      if (session !== existing.id) existing.nativeId = session
      if (!existing.open) {
        if (toolCallId) { existing.parentCallId = toolCallId; existing.taskCallId = toolCallId }
        return reopen(existing, { ...(prompt ? { prompt } : {}), ...(task?.description ? { description: task.description } : {}), ...(model ? { model } : {}) })
      }
      return []
    }
    const sub = newSub(id, "relay", toolCallId, session !== id ? session : undefined)
    if (toolCallId) sub.taskCallId = toolCallId
    remember(aliases, session, id)
    return [subBody(sub, "started", {
      ...(name ? { name } : {}),
      ...(task?.description ? { description: task.description } : {}),
      ...(prompt ? { prompt } : {}),
      ...(model ? { model } : {}),
    })]
  }

  function cursorState(value: Record<string, unknown>): NormalizedBody[] {
    if (vendor !== "cursor") return []
    const sub = lookup(str(value.subagentSessionId)) ?? lookup(str(rec(rec(value._meta)?.cursor)?.agentId))
    if (!sub || !sub.open) return []
    const phase = terminalOf(value.state)
    if (!phase) return []
    // Emit the child's final text now; the terminal waits for cursor/task (durationMs) or the turn end.
    const scope = scopeFor(sub.id)
    const out = [...takeThoughts(scope), ...takeAssistant(scope)]
    sub.pending = { phase, ...(phase === "completed" && scope.lastText ? { result: scope.lastText } : {}) }
    return out
  }

  function cursorTaskCall(kind: string, callId: string, value: Record<string, unknown>, input: Record<string, unknown> | undefined): NormalizedBody[] {
    const isTask = input?._toolName === "task" || (kind === "tool_call" && /^Task:/.test(str(value.title) ?? ""))
    if (kind === "tool_call" && isTask) {
      const info = {
        ...(str(input?.description) ? { description: str(input?.description) } : {}),
        ...(str(input?.prompt) ? { prompt: str(input?.prompt) } : {}),
        ...(str(input?.subagentType) ? { subagentType: str(input?.subagentType) } : {}),
      }
      remember(taskCalls, callId, info)
      const spawned = [...subs.values()].find(sub => sub.taskCallId === callId && sub.open)
      if (spawned) return progress(spawned, undefined, undefined, { ...(info.description ? { description: info.description } : {}), ...(info.prompt ? { prompt: info.prompt } : {}) })
      if (options.cursorSubagents?.() === true) return []
      // No subagents extension: the Task call is all there is of the subagent.
      if (lookup(callId)) return []
      const sub = newSub(callId, "relay", callId)
      sub.taskCallId = callId
      return [subBody(sub, "started", { ...(info.description ? { description: info.description } : {}), ...(info.prompt ? { prompt: info.prompt } : {}) })]
    }
    if (kind === "tool_call_update" && options.cursorSubagents?.() !== true) {
      const sub = lookup(callId)
      const phase = terminalOf(value.status)
      if (sub && sub.open && phase && !sub.pending) sub.pending = { phase }
    }
    return []
  }

  function cursorTask(params: Record<string, unknown>): NormalizedBody[] {
    const toolCallId = str(params.toolCallId)
    const sub = [...subs.values()].find(candidate => candidate.taskCallId === toolCallId) ?? lookup(toolCallId)
    if (!sub || !sub.open) return []
    const durationMs = num(params.durationMs)
    if (durationMs !== undefined) sub.stats = { ...sub.stats, durationMs }
    const description = str(params.description)
    if (!sub.pending) return progress(sub, undefined, undefined, description ? { description } : {})
    const pending = sub.pending
    return finish(sub, pending.phase, pending.result)
  }

  function opencodeTask(kind: string, callId: string, value: Record<string, unknown>, input: Record<string, unknown> | undefined): NormalizedBody[] {
    if (kind === "tool_call" && !lookup(callId)) {
      opencodeTasks.add(callId)
      if (opencodeTasks.size > 64) opencodeTasks.delete(opencodeTasks.values().next().value as string)
    }
    const out: NormalizedBody[] = []
    const described = input && (str(input.prompt) || str(input.description))
    if (opencodeTasks.has(callId) && (described || terminalOf(value.status))) {
      opencodeTasks.delete(callId)
      const description = str(input?.description)
      const prompt = str(input?.prompt)
      const name = str(input?.subagent_type)
      const resumeOf = lookup(str(input?.task_id))
      if (resumeOf && !resumeOf.open) {
        remember(aliases, callId, resumeOf.id)
        resumeOf.parentCallId = callId
        out.push(...reopen(resumeOf, { ...(prompt ? { prompt } : {}), ...(description ? { description } : {}) }))
      } else {
        const sub = newSub(callId, "direct", callId)
        out.push(subBody(sub, "started", { ...(name ? { name } : {}), ...(description ? { description } : {}), ...(prompt ? { prompt } : {}) }))
      }
    }
    const phase = terminalOf(value.status)
    const sub = lookup(callId)
    if (!phase || !sub) return out
    const rawOutput = rec(value.rawOutput)
    const childSession = str(rec(rawOutput?.metadata)?.sessionId)
    if (childSession) {
      sub.nativeId = childSession
      remember(aliases, childSession, sub.id)
    }
    const text = str(rawOutput?.output) ?? grokContentText(Array.isArray(value.content) ? value.content : [])
    out.push(...finish(sub, phase, text ? taskResult(text) : undefined))
    return out
  }

  function subagentTurn(params: Record<string, unknown>): NormalizedBody[] {
    const id = str(params.subagentId)
    if (!id) return []
    const phase = str(params.phase)
    if (phase === "started") {
      const sub = lookup(id)
      if (!sub) {
        const created = newSub(id, defaultMessaging())
        return [subBody(created, "started")]
      }
      return reopen(sub)
    }
    const sub = lookup(id)
    if (!sub) return []
    const terminal = terminalOf(phase) ?? "completed"
    return finish(sub, terminal, terminal === "failed" ? str(params.error) : undefined)
  }

  function mapNative(method: string, params: unknown): NormalizedBody[] {
    if (method === "stderr") {
      // OpenCode with --print-logs --log-level ERROR (verified 1.16.2): the provider failure
      // that the ACP turn hides is logged as
      //   ERROR <ts> +<ms> service=session.processor session.id=<id> messageID=<id> error=<message> stack=...
      // The llm-service line before it repeats the same failure with the raw HTTP body; keep only
      // the processor line so each failure surfaces once.
      const line = typeof rec(params)?.line === "string" ? String(rec(params)!.line) : ""
      const m = /^ERROR\b.*\bservice=session\.processor\b.*?\berror=(.*?)(?:\s+stack=|$)/.exec(line)
      if (!m) return []
      return [{ kind: "error", message: m[1]!.trim(), errorType: "provider", recoverable: false }]
    }
    if (vendor === "grok" && (method === "_x.ai/session_notification" || method === "_x.ai/session/update")) {
      const nested = rec(params)
      const update = nested && "update" in nested ? rec(nested.update) : nested
      if (!update) return []
      const sessionId = str(nested?.sessionId)
      if (sessionId && !isMain(sessionId)) return []
      return mapAcp(update, mainScope)
    }
    if (method === "cursor/task") return cursorTask(rec(params) ?? {})
    if (method === SUBAGENT_TURN_METHOD) return subagentTurn(rec(params) ?? {})
    if (method === SUBAGENT_SESSION_METHOD) {
      const sub = lookup(str(rec(params)?.subagentId))
      const nativeId = str(rec(params)?.nativeId)
      if (!sub || !nativeId || sub.nativeId === nativeId) return []
      sub.nativeId = nativeId
      remember(aliases, nativeId, sub.id)
      return progress(sub, undefined, undefined, { nativeId })
    }
    if (method === DRIVER_WARNING_METHOD) {
      const message = str(rec(params)?.message)
      return message ? [{ kind: "warning", source: "acp", message }] : []
    }
    if (method === "session/request_permission" || method === "_x.ai/ask_user_question") {
      // Drivers answer these via context.requestPermission / requestAnswers; Session emits the event.
      return []
    }
    if (method === "permission-auto") {
      const recParams = rec(params)
      const tool = rec(recParams?.toolCall)
      const optionId = typeof recParams?.optionId === "string" ? recParams.optionId : ""
      const callId = typeof tool?.callId === "string" ? tool.callId : ""
      return [attribute({
        kind: "permission-auto",
        toolCall: {
          callId,
          tool: typeof tool?.tool === "string" ? tool.tool : "",
          title: typeof tool?.title === "string" ? tool.title : "",
          ...(tool?.input !== undefined ? { input: tool.input } : {}),
        },
        optionId,
      } as NormalizedBody, childTools.get(callId) ?? str(recParams?.subagentId))]
    }
    return []
  }

  // Real OpenCode (1.16) ends a turn as a normal completion when its provider rejects the
  // request: no message, no tool, no error frame, only a usage_update. A silent "completed"
  // is dishonest, so the turn's flush reports it as a warning when nothing else was produced.
  const SILENT = new Set(["usage", "commands-update", "session-info", "mode-update"])
  let turnHadContent = false
  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    const parsed = unwrapGrok(update)
    if (!parsed) return []
    let out: NormalizedBody[]
    if (parsed.kind === "acp") {
      const sessionId = update.protocol === "acp" ? update.sessionId : undefined
      if (isMain(sessionId)) out = mapAcp(parsed.value, mainScope)
      else {
        const { sub, created } = childFor(sessionId!)
        out = [...created, ...mapAcp(parsed.value, scopeFor(sub.id), sub)]
      }
    } else out = mapNative(parsed.method, parsed.params)
    if (out.some(body => !SILENT.has(body.kind))) turnHadContent = true
    return out
  }) as AcpNormalizer

  normalize.flush = () => {
    const out: NormalizedBody[] = []
    for (const scope of scopes.values()) {
      out.push(...takeAssistant(scope))
      out.push(...takeThoughts(scope))
    }
    // Cursor terminals still waiting for cursor/task close with the turn.
    for (const sub of subs.values()) if (sub.open && sub.pending) out.push(...finish(sub, sub.pending.phase, sub.pending.result))
    if (!turnHadContent && !out.length) {
      out.push({ kind: "warning", source: "acp", message: "The agent ended the turn without producing any message, reasoning or tool call; check the agent's own logs (for OpenCode: ~/.local/share/opencode/log) for a provider error." })
    }
    turnHadContent = false
    return out
  }
  normalize.subagentForTool = (toolCallId: string) => childTools.get(toolCallId)
  normalize.subagentForSession = (sessionId: string) => (isMain(sessionId) ? undefined : lookup(sessionId)?.id)
  normalize.openSubagents = () => [...subs.values()].filter(sub => sub.open).map(sub => ({ id: sub.id, ...(sub.nativeId ? { nativeId: sub.nativeId } : {}), open: true }))
  normalize.subagent = (id: string) => {
    const sub = lookup(id)
    return sub ? { id: sub.id, ...(sub.nativeId ? { nativeId: sub.nativeId } : {}), open: sub.open } : undefined
  }
  return normalize
}
