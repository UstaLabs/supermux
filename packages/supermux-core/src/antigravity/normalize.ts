/** Antigravity (`agy`) headless stream-json mapper. */
import type { AgentUpdate } from "../types.js"
import type { NormalizedBody, ToolCategory } from "../events/normalized.js"
import { ACCOUNT_UNAVAILABLE_ERROR } from "../accounts/policy.js"

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

export type AntigravityNormalizer = ((update: AgentUpdate) => NormalizedBody[]) & {
  /** What a dead process left open: text → final messages, tools → failed. */
  flush(): NormalizedBody[]
  /**
   * A new agy process: ids become `${epoch}:${step_index}`; the turn's usage resets. Text and tools
   * the old process left open stay (their ids cannot collide): the next flush or result ends them.
   */
  newGeneration(epoch: string): void
  /** Native MCP server name → the session-context name ("supermux-mux-shim" → "mux-shim"). */
  setServerAliases(aliases: Record<string, string>): void
}

const CATEGORIES: Record<string, ToolCategory> = {
  run_command: "execute",
  send_command_input: "execute",
  command_status: "execute",
  write_to_file: "edit",
  replace_file_content: "edit",
  multi_replace_file_content: "edit",
  sed_file: "edit",
  notebook_edit: "edit",
  view_file: "read",
  read_resource: "read",
  list_resources: "read",
  list_dir: "search",
  find_by_name: "search",
  grep_search: "search",
  read_url_content: "fetch",
  read_browser_page: "fetch",
  search_web: "web-search",
}

/** Canonical key ← agy parameter names (first present wins). */
const ALIASES: [string, string[]][] = [
  ["command", ["CommandLine"]],
  ["file_path", ["TargetFile", "AbsolutePath", "FilePath"]],
  ["path", ["DirectoryPath", "SearchPath", "SearchDirectory"]],
  ["query", ["Query"]],
  ["pattern", ["Pattern"]],
  ["url", ["Url", "URL"]],
  ["content", ["CodeContent"]],
  ["old_string", ["TargetContent"]],
  ["new_string", ["ReplacementContent"]],
]

/** agy tool parameters plus canonical aliases; raw keys kept. */
export function agyToolInput(parameters: unknown): Record<string, unknown> | undefined {
  const params = rec(parameters)
  if (!params) return
  const out: Record<string, unknown> = { ...params }
  for (const [canonical, sources] of ALIASES) {
    if (canonical in out) continue
    const source = sources.find(key => params[key] !== undefined)
    if (source) out[canonical] = params[source]
  }
  return out
}

const ACCOUNT_ERROR = /eligib|not signed in|unauthenticated|PERMISSION_DENIED|\b40[13]\b/i

type OpenTool = {
  callId: string
  epoch: string
  tool: string
  category?: ToolCategory
  mcp?: { server: string; tool: string; arguments: unknown }
}

export function createAntigravityNormalizer(options: { epoch?: string } = {}): AntigravityNormalizer {
  let epoch = options.epoch ?? "0"
  let aliases: Record<string, string> = {}
  let text = new Map<string, string>()
  let tools = new Map<string, OpenTool>()
  let turn = { input: 0, output: 0, total: 0, seen: false }

  const finals = (): NormalizedBody[] => {
    const out: NormalizedBody[] = []
    for (const [messageId, body] of text) if (body) out.push({ kind: "assistant-message", messageId, text: body })
    text = new Map()
    return out
  }

  const resetTurn = () => { turn = { input: 0, output: 0, total: 0, seen: false } }

  const failTools = (which: (open: OpenTool) => boolean): NormalizedBody[] => {
    const out: NormalizedBody[] = []
    for (const [id, open] of tools) {
      if (!which(open)) continue
      tools.delete(id)
      if (open.mcp) out.push({ kind: "mcp-tool", callId: open.callId, server: open.mcp.server, tool: open.mcp.tool, phase: "failed" })
      out.push({ kind: "tool-call", callId: open.callId, tool: open.tool, phase: "failed", ...(open.category ? { category: open.category } : {}) })
    }
    return out
  }

  function agentResponse(id: string, step: Record<string, unknown>): NormalizedBody[] {
    const out: NormalizedBody[] = []
    const delta = str(step.text_delta)
    if (delta) {
      text.set(id, (text.get(id) ?? "") + delta)
      out.push({ kind: "assistant-delta", messageId: id, text: delta })
    }
    if (step.state !== "DONE") return out
    const body = text.get(id)
    text.delete(id)
    if (body) out.push({ kind: "assistant-message", messageId: id, text: body })
    const usage = rec(step.usage)
    if (usage) {
      turn.input += num(usage.input_tokens) ?? 0
      turn.output += num(usage.output_tokens) ?? 0
      turn.total += num(usage.total_tokens) ?? 0
      turn.seen = true
    }
    return out
  }

  function toolStep(id: string, step: Record<string, unknown>): NormalizedBody[] {
    const info = rec(step.tool_info)
    const out: NormalizedBody[] = []
    let open = tools.get(id)
    if (!open) {
      const name = str(step.tool_name) ?? str(info?.name)
      if (!name) return []
      const params = rec(info?.parameters)
      let started: NormalizedBody
      if (name === "call_mcp_tool") {
        const native = str(params?.ServerName) ?? "unknown"
        const server = aliases[native] ?? native
        const mcpTool = str(params?.ToolName) ?? "unknown"
        const args = params?.Arguments
        open = { callId: id, epoch, tool: `mcp__${server}__${mcpTool}`, category: "mcp", mcp: { server, tool: mcpTool, arguments: args } }
        started = { kind: "tool-call", callId: id, tool: open.tool, phase: "started", category: "mcp", input: { server, tool: mcpTool, arguments: args } }
        out.push(started, { kind: "mcp-tool", callId: id, server, tool: mcpTool, phase: "started", arguments: args })
      } else {
        const category = CATEGORIES[name]
        const input = agyToolInput(params)
        const path = str(input?.file_path) ?? str(input?.path)
        open = { callId: id, epoch, tool: name, ...(category ? { category } : {}) }
        started = {
          kind: "tool-call", callId: id, tool: name, phase: "started",
          ...(category ? { category } : {}),
          ...(input ? { input } : {}),
          ...(path ? { locations: [{ path }] } : {}),
        }
        out.push(started)
      }
      tools.set(id, open)
    }
    if (step.state !== "DONE") return out
    tools.delete(id)
    const output = info?.output
    const hasOutput = output !== undefined && output !== null
    if (open.mcp) {
      out.push({ kind: "mcp-tool", callId: id, server: open.mcp.server, tool: open.mcp.tool, phase: "completed", ...(hasOutput ? { result: output } : {}) })
    } else if (open.tool === "run_command" && typeof output === "string" && output) {
      out.push({ kind: "command-output", callId: id, stream: "merged", delta: output })
    }
    out.push({
      kind: "tool-call", callId: id, tool: open.tool, phase: "completed",
      ...(open.category ? { category: open.category } : {}),
      ...(hasOutput ? { output } : {}),
    })
    return out
  }

  function result(res: Record<string, unknown>): NormalizedBody[] {
    const out = finals()
    const failed = res.status === "ERROR"
    // A failed turn ends every open tool; a finished one still ends those an older process left.
    out.push(...failTools(open => failed || open.epoch !== epoch))
    tools = new Map()
    if (turn.seen) {
      out.push({ kind: "usage", tokens: { input: turn.input, output: turn.output, total: turn.total } })
    } else {
      const usage = rec(res.usage)
      if (usage) {
        const input = num(usage.input_tokens) ?? 0
        const output = num(usage.output_tokens) ?? 0
        out.push({ kind: "usage", tokens: { input, output, total: num(usage.total_tokens) ?? input + output } })
      }
    }
    const error = str(res.error)
    if (failed && error !== "interrupted") {
      const message = error ?? "Antigravity turn failed"
      out.push({ kind: "error", message, ...(ACCOUNT_ERROR.test(message) ? { errorType: ACCOUNT_UNAVAILABLE_ERROR } : {}) })
    }
    const denied = Array.isArray(res.denied_actions) ? res.denied_actions : []
    if (denied.length) {
      const names = denied.map(d => str(rec(d)?.display_name) ?? str(rec(d)?.action) ?? "action")
      out.push({ kind: "warning", source: "antigravity", message: `Antigravity denied: ${names.join(", ")} — switch permission mode to allow` })
    }
    resetTurn()
    return out
  }

  const normalize = ((update: AgentUpdate): NormalizedBody[] => {
    if (update.protocol !== "native") return []
    const frame = rec(update.value)
    if (!frame) return []
    if (frame.event === "init") {
      const cwd = str(rec(frame.init)?.cwd)
      return [{ kind: "session-info", ...(cwd ? { cwd } : {}) }]
    }
    if (frame.event === "result") {
      const res = rec(frame.result)
      return res ? result(res) : []
    }
    if (frame.event !== "step_update") return []
    const step = rec(frame.step_update)
    const index = num(step?.step_index)
    if (!step || index === undefined) return []
    const id = `${epoch}:${index}`
    switch (step.step_type) {
      case "user_input": return finals()
      case "agent_response": return agentResponse(id, step)
      case "tool": return toolStep(id, step)
      default: return []
    }
  }) as AntigravityNormalizer

  normalize.flush = () => [...finals(), ...failTools(() => true)]
  normalize.newGeneration = (next: string) => {
    epoch = next
    resetTurn()
  }
  normalize.setServerAliases = (next: Record<string, string>) => { aliases = { ...next } }
  return normalize
}
