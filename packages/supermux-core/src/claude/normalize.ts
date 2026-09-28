/** Claude headless stream-json mapper. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, PlanEntryStatus, SubagentStats, TaskKind } from "../events/normalized.js"

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

export type ClaudeNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & {
  flush: () => NormalizedBody[]
  /** Subagent that owns a child tool_use id (for permission attribution). */
  subagentForTool: (toolUseId: string) => string | undefined
  /** Look a subagent up by its id, task_id or spawning tool_use id. */
  subagent: (id: string) => { id: string; taskId: string; open: boolean } | undefined
}

type Tool = { name: string; input?: Record<string, unknown> }

type Subagent = {
  id: string
  /** task_id once known (when the id is a provisional tool_use id). */
  taskId?: string
  toolUseId?: string
  name?: string
  description?: string
  prompt?: string
  background?: boolean
  activity?: string
  stats?: SubagentStats
  /** A run is open (started/resumed and no terminal yet). */
  open: boolean
  /** Terminal status announced by task_updated, waiting for task_notification's summary. */
  pendingStatus?: string
  tools: Map<string, Tool>
  blocks: Map<string, number>
}

type BackgroundTask = { taskId: string; kind: TaskKind; label?: string; parentCallId?: string; subagentId?: string; done: boolean }

const MAX_TRACKED = 512

function bounded<K, V>(map: Map<K, V>, key: K, value: V): void {
  if (map.has(key)) map.delete(key)
  map.set(key, value)
  while (map.size > MAX_TRACKED) {
    const oldest = map.keys().next().value
    if (oldest === undefined) break
    map.delete(oldest)
  }
}

function taskKindOf(type: string | undefined): TaskKind {
  if (type === "local_bash") return "shell"
  if (type && type.includes("monitor")) return "monitor"
  return "workflow"
}

function isAgentTask(frame: Record<string, unknown>): boolean {
  const type = str(frame.task_type)
  return type === "local_agent" || type === "remote_agent" || (type === undefined && str(frame.subagent_type) !== undefined)
}

function terminalOf(status: string | undefined): "completed" | "failed" | "cancelled" | undefined {
  if (!status) return
  if (status === "completed" || status === "success") return "completed"
  if (status === "failed" || status === "error" || status === "errored") return "failed"
  if (status === "stopped" || status === "killed" || status === "cancelled" || status === "canceled" || status === "aborted" || status === "interrupted") return "cancelled"
  return
}

function statsOf(usage: unknown): SubagentStats | undefined {
  const row = rec(usage)
  if (!row) return
  const stats: SubagentStats = {}
  const toolCalls = num(row.tool_uses)
  const tokens = num(row.total_tokens)
  const durationMs = num(row.duration_ms)
  if (toolCalls !== undefined) stats.toolCalls = toolCalls
  if (tokens !== undefined) stats.tokens = tokens
  if (durationMs !== undefined) stats.durationMs = durationMs
  return Object.keys(stats).length ? stats : undefined
}

export function createClaudeNormalizer(): ClaudeNormalizer {
  let streamMessageId = "message"
  /** Index of the stream block currently open (content_block_start), per streamed message. */
  let streamBlock: { messageId: string; index: number } | undefined
  const assistant = new Map<string, string>()
  const reasoning = new Map<string, string>()
  const tools = new Map<string, Tool>()
  /** Per-message count of blocks already seen in assistant frames (Claude sends one frame per block). */
  const mainBlocks = new Map<string, number>()
  const subagents = new Map<string, Subagent>()
  /** tool_use_id (Agent call, SendMessage call) and task_id → canonical subagent id. */
  const aliases = new Map<string, string>()
  /** Child tool_use_id → subagent id (for tasks a subagent owns). */
  const childTools = new Map<string, string>()
  const tasks = new Map<string, BackgroundTask>()
  /** Non-agent tasks announced in the foreground (not yet a `task`), in case they get backgrounded. */
  const foregroundTasks = new Map<string, BackgroundTask>()
  /** Listed by background_tasks_changed before their task_started arrived. */
  const listedTasks = new Set<string>()

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

  function attribute<T extends NormalizedBody>(body: T, subagentId: string | undefined): T {
    return subagentId ? { ...body, subagentId } : body
  }

  function subagentBody(sub: Subagent, phase: Extract<NormalizedBody, { kind: "subagent" }>["phase"], extra: Partial<Extract<NormalizedBody, { kind: "subagent" }>> = {}): NormalizedBody {
    const body: Extract<NormalizedBody, { kind: "subagent" }> = { kind: "subagent", subagentId: sub.id, phase }
    if (phase === "started" || phase === "resumed") {
      if (sub.toolUseId) body.parentCallId = sub.toolUseId
      if (sub.name) body.name = sub.name
      if (sub.description) body.description = sub.description
      if (sub.prompt) body.prompt = sub.prompt
      if (sub.background !== undefined) body.background = sub.background
      body.messaging = "relay"
    }
    if (sub.taskId && sub.taskId !== sub.id) body.nativeId = sub.taskId
    return { ...body, ...extra }
  }

  function subagentFor(parentToolUseId: string): { sub: Subagent; created: NormalizedBody[] } {
    const known = aliases.get(parentToolUseId)
    const existing = known ? subagents.get(known) : undefined
    if (existing) return { sub: existing, created: [] }
    // A child frame arrived before task_started: the tool_use id stands in, and stays the id.
    const sub: Subagent = { id: parentToolUseId, toolUseId: parentToolUseId, open: true, tools: new Map(), blocks: new Map() }
    bounded(subagents, sub.id, sub)
    bounded(aliases, parentToolUseId, sub.id)
    return { sub, created: [subagentBody(sub, "started")] }
  }

  function blockIndex(blocks: Map<string, number>, messageId: string, contentLength: number, position: number, streamed?: number): number {
    if (contentLength > 1) return position
    if (streamed !== undefined) {
      blocks.set(messageId, Math.max(blocks.get(messageId) ?? 0, streamed + 1))
      return streamed
    }
    const next = blocks.get(messageId) ?? 0
    bounded(blocks, messageId, next + 1)
    return next
  }

  function extrasForTool(name: string, callId: string, input: Record<string, unknown> | undefined, result: unknown): NormalizedBody[] {
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
    // Task/Agent calls are described by system/task_* frames as `subagent` bodies.
    return extra
  }

  function mapAssistantContent(message: Record<string, unknown>, sub?: Subagent): NormalizedBody[] {
    const messageId = str(message.id) ?? "message"
    const content = Array.isArray(message.content) ? message.content : []
    const blocks = sub ? sub.blocks : mainBlocks
    const streamed = !sub && streamBlock && streamBlock.messageId === messageId ? streamBlock.index : undefined
    const ids = content.map((_block, position) => `${messageId}:${blockIndex(blocks, messageId, content.length, position, streamed)}`)
    const out: NormalizedBody[] = []
    if (!sub) {
      // The full assistant frame supersedes whatever the partial stream accumulated for the SAME
      // blocks: drop those before flushing, otherwise each block gets two finals. Only unrelated
      // open runs are flushed here.
      for (const own of ids) { assistant.delete(own); reasoning.delete(own) }
      out.push(...take("reasoning"), ...take("assistant"))
    }
    const toolMap = sub ? sub.tools : tools
    const subagentId = sub?.id
    content.forEach((block, position) => {
      const row = rec(block)
      if (!row) return
      const type = row.type
      const id = ids[position]!
      if (type === "text") {
        const text = typeof row.text === "string" ? row.text : ""
        out.push(attribute({ kind: "assistant-message", messageId: id, text }, subagentId))
        return
      }
      if (type === "thinking") {
        const text = typeof row.thinking === "string" ? row.thinking : typeof row.text === "string" ? row.text : ""
        out.push(attribute({ kind: "reasoning", reasoningId: id, redacted: !text, ...(text ? { text } : {}) }, subagentId))
        return
      }
      if (type === "tool_use") {
        const callId = str(row.id) ?? id
        const name = str(row.name) ?? "tool"
        const input = rec(row.input) ?? (row.input as Record<string, unknown> | undefined)
        bounded(toolMap, callId, { name, input })
        if (subagentId) bounded(childTools, callId, subagentId)
        out.push(attribute({ kind: "tool-call", callId, tool: name, phase: "started", input: row.input }, subagentId))
        for (const extra of extrasForTool(name, callId, input, undefined)) out.push(attribute(extra, subagentId))
      }
    })
    return out
  }

  function mapUserContent(message: Record<string, unknown>, sub?: Subagent): NormalizedBody[] {
    const content = Array.isArray(message.content) ? message.content : []
    const out: NormalizedBody[] = sub ? [] : [...take("reasoning"), ...take("assistant")]
    const toolMap = sub ? sub.tools : tools
    const subagentId = sub?.id
    for (const block of content) {
      const row = rec(block)
      if (!row || row.type !== "tool_result") continue
      const callId = str(row.tool_use_id) ?? "tool"
      const failed = row.is_error === true
      const prior = toolMap.get(callId)
      const name = str(row.name) ?? prior?.name ?? "tool"
      const input = rec(row.input) ?? prior?.input
      const output = outputText(row.content ?? row.output)
      out.push(attribute({ kind: "tool-call", callId, tool: name, phase: failed ? "failed" : "completed", output }, subagentId))
      for (const extra of extrasForTool(name, callId, input, row.content ?? row.output)) out.push(attribute(extra, subagentId))
      toolMap.delete(callId)
      if (!sub) out.push(...foregroundFallback(callId, name, failed, output))
    }
    return out
  }

  /** A foreground Agent call's tool_result closes its subagent if task_notification never did. */
  function foregroundFallback(callId: string, name: string, failed: boolean, output: unknown): NormalizedBody[] {
    if (name !== "Agent" && name !== "Task") return []
    const id = aliases.get(callId)
    const sub = id ? subagents.get(id) : undefined
    if (!sub || !sub.open || sub.background || sub.toolUseId !== callId) return []
    sub.open = false
    const text = typeof output === "string" ? output : undefined
    return [subagentBody(sub, failed ? "failed" : "completed", text ? { result: text } : {})]
  }

  function mapStreamEvent(event: Record<string, unknown>): NormalizedBody[] {
    const type = event.type
    if (type === "message_start") {
      const message = rec(event.message)
      streamMessageId = str(message?.id) ?? "message"
      streamBlock = undefined
      return []
    }
    if (type === "content_block_start") {
      const index = typeof event.index === "number" ? event.index : 0
      streamBlock = { messageId: streamMessageId, index }
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

  function taskStarted(frame: Record<string, unknown>): NormalizedBody[] {
    const taskId = str(frame.task_id)
    if (!taskId) return []
    const toolUseId = str(frame.tool_use_id)
    if (!isAgentTask(frame)) {
      // Background shell/workflow/monitor tasks stay `task` bodies. Foreground bash inside a
      // turn is already a tool call; only backgrounded work gets its own lifecycle.
      if (tasks.has(taskId)) return []
      const task: BackgroundTask = {
        taskId,
        kind: taskKindOf(str(frame.task_type)),
        done: false,
        ...(str(frame.description) ? { label: str(frame.description) } : {}),
        ...(toolUseId ? { parentCallId: toolUseId } : {}),
        ...(toolUseId && childTools.get(toolUseId) ? { subagentId: childTools.get(toolUseId) } : {}),
      }
      if (frame.is_backgrounded !== true && !listedTasks.has(taskId)) {
        bounded(foregroundTasks, taskId, task)
        return []
      }
      listedTasks.delete(taskId)
      bounded(tasks, taskId, task)
      return [taskBody(task, "started")]
    }
    const canonical = aliases.get(taskId) ?? (toolUseId ? aliases.get(toolUseId) : undefined)
    const existing = canonical ? subagents.get(canonical) : undefined
    const description = str(frame.description)
    const name = str(frame.subagent_type)
    const prompt = str(frame.prompt)
    const background = typeof frame.is_backgrounded === "boolean" ? frame.is_backgrounded : undefined
    if (existing) {
      bounded(aliases, taskId, existing.id)
      if (toolUseId) bounded(aliases, toolUseId, existing.id)
      if (existing.id !== taskId) existing.taskId = taskId
      if (description) existing.description = description
      if (name) existing.name = name
      if (background !== undefined) existing.background = background
      if (existing.open) {
        // First task_started for a provisional (tool_use id) subagent: enrich, it already started.
        if (prompt) existing.prompt = prompt
        return [subagentBody(existing, "progress", {
          ...(existing.name ? { name: existing.name } : {}),
          ...(existing.description ? { description: existing.description } : {}),
          ...(existing.prompt ? { prompt: existing.prompt } : {}),
          ...(existing.background !== undefined ? { background: existing.background } : {}),
        })]
      }
      // task_started for a finished task_id: SendMessage resumed it.
      existing.open = true
      existing.pendingStatus = undefined
      existing.activity = undefined
      existing.prompt = prompt
      const resumeCall = toolUseId
      const body = subagentBody(existing, "resumed")
      if (resumeCall) (body as Extract<NormalizedBody, { kind: "subagent" }>).parentCallId = resumeCall
      if (!prompt) delete (body as { prompt?: string }).prompt
      return [body]
    }
    const sub: Subagent = {
      id: taskId,
      taskId,
      open: true,
      tools: new Map(),
      blocks: new Map(),
      ...(toolUseId ? { toolUseId } : {}),
      ...(name ? { name } : {}),
      ...(description ? { description } : {}),
      ...(prompt ? { prompt } : {}),
      ...(background !== undefined ? { background } : {}),
    }
    bounded(subagents, sub.id, sub)
    bounded(aliases, taskId, sub.id)
    if (toolUseId) bounded(aliases, toolUseId, sub.id)
    return [subagentBody(sub, "started")]
  }

  function taskBody(task: BackgroundTask, phase: Extract<NormalizedBody, { kind: "task" }>["phase"]): NormalizedBody {
    return {
      kind: "task",
      taskId: task.taskId,
      taskKind: task.kind,
      phase,
      ...(task.label ? { label: task.label } : {}),
      ...(task.parentCallId ? { parentCallId: task.parentCallId } : {}),
      ...(task.subagentId ? { subagentId: task.subagentId } : {}),
    }
  }

  function subagentByTask(taskId: string | undefined): Subagent | undefined {
    if (!taskId) return
    const id = aliases.get(taskId)
    return id ? subagents.get(id) : undefined
  }

  function taskProgress(frame: Record<string, unknown>): NormalizedBody[] {
    const sub = subagentByTask(str(frame.task_id))
    if (!sub || !sub.open) return []
    const activity = str(frame.description)
    const stats = statsOf(frame.usage)
    const changed = (activity !== undefined && activity !== sub.activity)
      || (stats !== undefined && JSON.stringify(stats) !== JSON.stringify(sub.stats))
    if (!changed) return []
    if (activity !== undefined) sub.activity = activity
    if (stats) sub.stats = stats
    return [subagentBody(sub, "progress", {
      ...(sub.activity ? { activity: sub.activity } : {}),
      ...(sub.stats ? { stats: sub.stats } : {}),
    })]
  }

  function taskUpdated(frame: Record<string, unknown>): NormalizedBody[] {
    const taskId = str(frame.task_id)
    const patch = rec(frame.patch) ?? {}
    const sub = subagentByTask(taskId)
    if (sub) {
      const out: NormalizedBody[] = []
      if (patch.is_backgrounded === true && sub.background !== true && sub.open) {
        sub.background = true
        out.push(subagentBody(sub, "progress", { background: true }))
      }
      const status = str(patch.status)
      if (sub.open && terminalOf(status)) sub.pendingStatus = status
      return out
    }
    return []
  }

  function taskNotification(frame: Record<string, unknown>): NormalizedBody[] {
    const taskId = str(frame.task_id)
    const status = str(frame.status)
    const summary = str(frame.summary)
    const sub = subagentByTask(taskId)
    if (sub) {
      if (!sub.open) return []
      sub.open = false
      const phase = terminalOf(status) ?? terminalOf(sub.pendingStatus) ?? "completed"
      sub.pendingStatus = undefined
      const stats = statsOf(frame.usage)
      if (stats) sub.stats = stats
      return [subagentBody(sub, phase, {
        ...(summary && phase === "completed" ? { result: summary } : summary && phase === "failed" ? { result: summary } : {}),
        ...(stats ? { stats } : {}),
      })]
    }
    if (taskId) foregroundTasks.delete(taskId)
    const task = taskId ? tasks.get(taskId) : undefined
    if (!task || task.done) return []
    task.done = true
    const terminal = terminalOf(status) ?? "completed"
    return [taskBody(task, terminal === "cancelled" ? "interrupted" : terminal)]
  }

  function backgroundTasksChanged(frame: Record<string, unknown>): NormalizedBody[] {
    const list = Array.isArray(frame.tasks) ? frame.tasks : []
    const out: NormalizedBody[] = []
    for (const entry of list) {
      const row = rec(entry)
      const taskId = str(row?.task_id)
      if (!row || !taskId) continue
      if (str(row.task_type) === "local_agent") {
        const sub = subagentByTask(taskId)
        if (sub && sub.open && sub.background !== true) {
          sub.background = true
          out.push(subagentBody(sub, "progress", { background: true }))
        }
        continue
      }
      if (tasks.has(taskId)) continue
      const foreground = foregroundTasks.get(taskId)
      if (foreground) {
        // A foreground task that got backgrounded: it becomes a background task now.
        foregroundTasks.delete(taskId)
        bounded(tasks, taskId, foreground)
        out.push(taskBody(foreground, "started"))
        continue
      }
      // Usually listed just before its own task_started (which carries the tool_use id).
      if (listedTasks.size >= MAX_TRACKED) listedTasks.clear()
      listedTasks.add(taskId)
    }
    return out
  }

  function mapFrame(frame: Record<string, unknown>): NormalizedBody[] {
    const type = frame.type
    const parent = str(frame.parent_tool_use_id)
    if (parent && (type === "assistant" || type === "user" || type === "stream_event")) {
      // Subagent frames: never touch main-thread message state or flush the parent's text.
      if (type === "stream_event") return []
      const message = rec(frame.message)
      if (!message) return []
      const { sub, created } = subagentFor(parent)
      return [...created, ...(type === "assistant" ? mapAssistantContent(message, sub) : mapUserContent(message, sub))]
    }
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
      if (subtype === "task_started") return taskStarted(frame)
      if (subtype === "task_progress") return taskProgress(frame)
      if (subtype === "task_updated") return taskUpdated(frame)
      if (subtype === "task_notification") return taskNotification(frame)
      if (subtype === "background_tasks_changed") return backgroundTasksChanged(frame)
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
      // can_use_tool is answered via context.requestPermission; Session emits the event.
      return []
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
  normalize.subagentForTool = (toolUseId: string) => childTools.get(toolUseId)
  normalize.subagent = (id: string) => {
    const canonical = aliases.get(id)
    const sub = canonical ? subagents.get(canonical) : undefined
    return sub ? { id: sub.id, taskId: sub.taskId ?? sub.id, open: sub.open } : undefined
  }
  return normalize
}
