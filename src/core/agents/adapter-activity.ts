import type { AgentKind } from "./types"
import type { ActivityEvent } from "./claude/activity-event"
import { normalizeToolName } from "./tool-normalize"
import { clip, firstLine, pickString } from "./activity-format"

const TITLE_MAX = 120
const DETAIL_MAX = 2000

interface ToolCallEventLike { tool: string; phase: "started" | "completed" | "failed"; call_id: string; detail?: unknown }

const OPENCODE_INPUT_FIELDS = [
  "command", "path", "filePath", "file_path", "file", "pattern", "query", "text", "url", "port", "name",
  "args", "description", "glob", "include", "skill", "oldString", "newString", "content",
]

/** Pull human-readable tool output from opencode's ToolState. Primary field is
 * `output` (v1 SSE); newer builds may also populate `content: [{type:"text",text}]`. */
function extractOpenCodeOutput(state: Record<string, unknown>): string {
  const output = typeof state.output === "string" ? state.output : ""
  if (output) return output
  const content = state.content
  if (!Array.isArray(content)) return ""
  const texts: string[] = []
  for (const item of content) {
    if (!item || typeof item !== "object") continue
    const row = item as { type?: string; text?: string }
    if (row.type === "text" && typeof row.text === "string" && row.text) texts.push(row.text)
  }
  return texts.join("\n")
}

/** Extract a human-readable result string from a cursor-agent tool body's `.result`
 * oneof. protobuf-es toJSON() unwraps the oneof so `result` is a single-key object
 * like `{ success: { stdout, stderr, interleavedOutput } }` or
 * `{ failure: { exitCode, stderr } }` (other cases: error/cancelled/timeout/...). */
function extractCursorResult(toolBody: Record<string, unknown> | undefined): string {
  const result = toolBody?.result as Record<string, unknown> | undefined
  if (!result || typeof result !== "object") return ""
  const caseKey = Object.keys(result)[0]
  if (!caseKey) return ""
  const caseVal = result[caseKey] as Record<string, unknown> | undefined
  if (!caseVal || typeof caseVal !== "object") return ""
  if (caseKey === "success") return pickString(caseVal, ["stdout", "interleavedOutput", "stderr"])
  return pickString(caseVal, ["stderr", "error", "message", "stdout"])
}

function summarizeDetail(agent: AgentKind, ev: ToolCallEventLike): { summary: string; resultDetail: string } {
  const obj = ev.detail && typeof ev.detail === "object" ? ev.detail as Record<string, unknown> : undefined
  if (!obj) return { summary: "", resultDetail: "" }

  if (agent === "opencode") {
    const state = obj.state as Record<string, unknown> | undefined
    const input = state?.input as Record<string, unknown> | undefined
    const output = state ? extractOpenCodeOutput(state) : ""
    const error = typeof state?.error === "string" ? state.error : ""
    const rawTitle = typeof state?.title === "string" ? state.title : ""
    const rawPending = typeof state?.raw === "string" ? state.raw.trim() : ""
    // Extract summary from input args (primary), pending `raw`, or state title (for
    // delta SSE updates where structured input may be absent on the first frame).
    const summary = input && Object.keys(input).length
      ? pickString(input, OPENCODE_INPUT_FIELDS)
      : rawPending
        ? firstLine(rawPending)
        : rawTitle
    // For completed events, prefer the actual output over the title — the title is a
    // label (typically the command/input), so `rawTitle || output` showed the input as
    // the output. Flip to `output || rawTitle` so the real result is surfaced.
    const result = ev.phase === "completed" ? (output || rawTitle) : ev.phase === "failed" ? error : ""
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
    // cursor-agent emits the raw protobuf `agent.v1.ToolCall` message as `tool_call`.
    // protobuf-es toJSON() unwraps its oneof `tool` so the case name becomes the JSON
    // key: tool_call = { <caseName>: { args: {...}, result: {...} } } — args are nested
    // under `.args`, NOT at the top level (the old code read them flat, producing empty
    // summaries). The result oneof similarly unwraps to { success: { stdout,... } } |
    // { failure: { stderr,... } } | { error: {...} } — the old code looked for a
    // non-existent `obj.result.tool_call_result.content` and always came up empty.
    const tc = obj.tool_call as Record<string, unknown> | undefined
    const toolBody = tc && typeof tc === "object" ? (tc[ev.tool] ?? Object.values(tc)[0]) as Record<string, unknown> | undefined : undefined
    const innerArgs = toolBody?.args as Record<string, unknown> | undefined
    const summary = innerArgs ? pickString(innerArgs, ["command", "pattern", "query", "globPattern", "glob_pattern", "description", "url", "path", "file", "target_file", "text"]) : ""
    const result = (ev.phase === "completed" || ev.phase === "failed") ? extractCursorResult(toolBody) : ""
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
