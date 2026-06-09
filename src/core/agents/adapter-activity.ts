import type { AgentKind } from "./types"
import type { ActivityEvent } from "./claude/activity-event"
import { normalizeToolName } from "./tool-normalize"
import { clip, firstLine, pickString } from "./activity-format"

const TITLE_MAX = 120
const DETAIL_MAX = 2000

interface ToolCallEventLike { tool: string; phase: "started" | "completed" | "failed"; call_id: string; detail?: unknown }

function summarizeDetail(agent: AgentKind, ev: ToolCallEventLike): { summary: string; resultDetail: string } {
  const obj = ev.detail && typeof ev.detail === "object" ? ev.detail as Record<string, unknown> : undefined
  if (!obj) return { summary: "", resultDetail: "" }

  if (agent === "opencode") {
    const state = obj.state as Record<string, unknown> | undefined
    const input = state?.input as Record<string, unknown> | undefined
    const output = typeof state?.output === "string" ? state.output : ""
    const error = typeof state?.error === "string" ? state.error : ""
    const rawTitle = typeof state?.title === "string" ? state.title : ""
    // Extract summary from input args (primary), or the state-provided title (fallback
    // for delta SSE updates where input may be absent). The input fallback prevents
    // the old behavior of picking the tool-name string and showing "Bash: bash".
    const summary = input ? pickString(input, ["command", "path", "file", "pattern", "query", "text", "url", "port", "name", "args"]) : rawTitle
    const result = ev.phase === "completed" ? (rawTitle || output) : ev.phase === "failed" ? error : ""
    return { summary, resultDetail: result }
  }

  if (agent === "codex") {
    if (obj.type === "mcpToolCall" || obj.type === "mcp_tool_call") {
      const toolName = (typeof obj.toolName === "string" && obj.toolName) || (typeof obj.tool_name === "string" && obj.tool_name) || ""
      const args = (obj.arguments ?? obj.args) as Record<string, unknown> | undefined
      const arg = args ? pickString(args, ["command", "path", "workdir", "query", "pattern", "text", "port", "name"]) : ""
      const result = ev.phase === "completed" ? (typeof obj.result === "string" ? obj.result : JSON.stringify(obj.result ?? "")) : ""
      return { summary: arg ? `${toolName} ${arg}` : toolName, resultDetail: result }
    }
    const summary = pickString(obj, ["command", "path", "file", "name", "query", "pattern", "text"])
    let result = ""
    if (ev.phase === "completed") {
      result = typeof obj.aggregated_output === "string" ? obj.aggregated_output : ""
    }
    return { summary, resultDetail: result }
  }

  if (agent === "cursor") {
    const tc = obj.tool_call as Record<string, unknown> | undefined
    const args = tc && typeof tc === "object" ? (tc[ev.tool] ?? Object.values(tc)[0]) as Record<string, unknown> | undefined : undefined
    const summary = args ? pickString(args, ["path", "command", "pattern", "query", "file", "target_file", "text"]) : ""
    let result = ""
    if (ev.phase === "completed" || ev.phase === "failed") {
      const res = (obj.result as Record<string, unknown> | undefined)?.tool_call_result as Record<string, unknown> | undefined
      result = typeof res?.content === "string" ? res.content : ""
    }
    return { summary, resultDetail: result }
  }

  // claude: extract from transcript blocks (handled by transcript-parser, not this path)
  const summary = pickString(obj, ["command", "path", "file", "name", "query", "pattern"])
  return { summary, resultDetail: "" }
}

export function toActivityEvents(agent: AgentKind, ev: ToolCallEventLike, now: number): ActivityEvent[] {
  const ts = new Date(now).toISOString()
  const callId = ev.call_id || undefined
  const { summary: rawSummary, resultDetail } = summarizeDetail(agent, ev)

  if (ev.phase === "started") {
    const tool = normalizeToolName(agent, ev.tool)
    const summary = firstLine(rawSummary)
    const titleRaw = summary ? `${tool}: ${summary}` : tool
    const title = clip(titleRaw, TITLE_MAX)
    const detail = clip(summary, DETAIL_MAX)
    return [{ ts, kind: "tool", tool, title: title.text, detail: detail.text, phase: "started",
      ...(callId ? { callId } : {}), ...(title.truncated || detail.truncated ? { truncated: true } : {}) }]
  }
  const result = firstLine(resultDetail)
  const detail = clip(result, DETAIL_MAX)
  return [{ ts, kind: "tool_result", title: ev.phase === "failed" ? "error" : "done",
    detail: detail.text, phase: "completed", ...(detail.truncated ? { truncated: true } : {}),
    ...(callId ? { callId } : {}) }]
}
