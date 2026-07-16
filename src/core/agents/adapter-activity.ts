import type { AgentKind } from "./types"
import type { ActivityEvent } from "./claude/activity-event"
import { normalizeToolName } from "./tool-normalize"
import { clip, firstLine, pickString } from "./activity-format"
import { relativizePath } from "./path-relativize"

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

/** grok tool_call_update `content` is an array of `{ type:"content", content:{ type:"text", text }}`
 * (also plain `{ type:"text", text }`). Join all text parts. */
function extractGrokContent(content: unknown): string {
  if (!Array.isArray(content)) return ""
  const out: string[] = []
  for (const item of content) {
    if (!item || typeof item !== "object") continue
    const row = item as { type?: string; text?: string; content?: { type?: string; text?: string } }
    if (row.type === "text" && typeof row.text === "string") out.push(row.text)
    else if (row.content?.type === "text" && typeof row.content.text === "string") out.push(row.content.text)
  }
  return out.join("\n")
}

type DetailSummary = { summary: string; rawSummary: string; resultDetail: string; inputDetail?: string }

function jsonText(value: unknown): string {
  if (typeof value === "string") return value
  if (value == null) return ""
  try { return JSON.stringify(value, null, 2) ?? "" } catch { return String(value) }
}

function codexOutputContent(value: unknown): string {
  if (!Array.isArray(value)) return ""
  const parts: string[] = []
  for (const item of value) {
    if (item && typeof item === "object") {
      const row = item as Record<string, unknown>
      if ((row.type === "text" || row.type === "inputText") && typeof row.text === "string") {
        parts.push(row.text)
        continue
      }
    }
    const fallback = jsonText(item)
    if (fallback) parts.push(fallback)
  }
  return parts.join("\n")
}

function codexFileChanges(value: unknown, workdir: string | undefined): { summary: string; detail: string; result: string } {
  if (!Array.isArray(value)) return { summary: "", detail: "", result: "" }
  const summaries: string[] = []
  const details: string[] = []
  const results: string[] = []
  for (const item of value) {
    if (!item || typeof item !== "object") continue
    const change = item as Record<string, unknown>
    if (typeof change.path !== "string" || !change.path) continue
    const kindObj = change.kind && typeof change.kind === "object" ? change.kind as Record<string, unknown> : undefined
    const kind = typeof change.kind === "string" ? change.kind
      : typeof kindObj?.type === "string" ? kindObj.type
      : "update"
    const movePath = typeof kindObj?.move_path === "string" ? kindObj.move_path
      : typeof kindObj?.movePath === "string" ? kindObj.movePath
      : ""
    const relativePath = relativizePath(change.path, workdir)
    const relativeMovePath = movePath ? relativizePath(movePath, workdir) : ""
    const pathLabel = relativeMovePath ? `${relativePath} → ${relativeMovePath}` : relativePath
    const rawPathLabel = movePath ? `${change.path} → ${movePath}` : change.path
    summaries.push(pathLabel)
    results.push(`${kind} ${pathLabel}`)
    const diff = typeof change.diff === "string" ? change.diff.trim() : ""
    details.push(diff ? `${kind} ${rawPathLabel}\n${diff}` : `${kind} ${rawPathLabel}`)
  }
  return { summary: summaries.join(", "), detail: details.join("\n\n"), result: results.join("\n") }
}

function summarizeDetail(agent: AgentKind, ev: ToolCallEventLike, workdir: string | undefined): DetailSummary {
  const obj = ev.detail && typeof ev.detail === "object" ? ev.detail as Record<string, unknown> : undefined
  if (!obj) return { summary: "", rawSummary: "", resultDetail: "" }

  if (agent === "opencode") {
    const state = obj.state as Record<string, unknown> | undefined
    const input = state?.input as Record<string, unknown> | undefined
    const output = state ? extractOpenCodeOutput(state) : ""
    const error = typeof state?.error === "string" ? state.error : ""
    const rawTitle = typeof state?.title === "string" ? state.title : ""
    const rawPending = typeof state?.raw === "string" ? state.raw.trim() : ""
    // Extract summary from input args (primary), pending `raw`, or state title (for
    // delta SSE updates where structured input may be absent on the first frame).
    // `summary` is the workdir-relativized form (used in the card title); `rawSummary`
    // keeps the absolute path (used in the expand panel — per Q4 we don't rewrite the
    // raw input JSON). When no input is picked, both fall back to the same pending
    // `raw` / state title so the title and detail match.
    const rawPicked = input && Object.keys(input).length ? pickString(input, OPENCODE_INPUT_FIELDS) : ""
    const picked = rawPicked ? relativizePath(rawPicked, workdir) : ""
    const fallback = rawPending ? firstLine(rawPending) : rawTitle
    const summary = picked || fallback
    // For completed events, prefer the actual output over the title — the title is a
    // label (typically the command/input), so `rawTitle || output` showed the input as
    // the output. Flip to `output || rawTitle` so the real result is surfaced.
    const result = ev.phase === "completed" ? (output || rawTitle) : ev.phase === "failed" ? error : ""
    return { summary, rawSummary: rawPicked || fallback, resultDetail: result }
  }

  if (agent === "codex") {
    if (obj.type === "mcpToolCall" || obj.type === "mcp_tool_call") {
      const toolName = (typeof obj.tool === "string" && obj.tool)
        || (typeof obj.toolName === "string" && obj.toolName)
        || (typeof obj.tool_name === "string" && obj.tool_name) || ""
      const args = (obj.arguments ?? obj.args) as Record<string, unknown> | undefined
      const rawArg = args ? pickString(args, ["command", "path", "workdir", "query", "pattern", "text", "port", "name"]) : ""
      const arg = rawArg ? relativizePath(rawArg, workdir) : ""
      const resultObj = obj.result && typeof obj.result === "object" ? obj.result as Record<string, unknown> : undefined
      const errorObj = obj.error && typeof obj.error === "object" ? obj.error as Record<string, unknown> : undefined
      const result = ev.phase === "completed"
        ? (typeof obj.result === "string" ? obj.result : codexOutputContent(resultObj?.content) || jsonText(resultObj?.structuredContent) || jsonText(obj.result))
        : ev.phase === "failed"
          ? (typeof obj.error === "string" ? obj.error : typeof errorObj?.message === "string" ? errorObj.message : "")
          : ""
      return { summary: arg ? `${toolName} ${arg}` : toolName, rawSummary: arg ? `${toolName} ${rawArg}` : toolName, resultDetail: result }
    }
    if (obj.type === "dynamicToolCall" || obj.type === "dynamic_tool_call") {
      const args = (obj.arguments ?? obj.args) as Record<string, unknown> | undefined
      const rawArg = args ? pickString(args, ["command", "path", "workdir", "query", "pattern", "prompt", "text", "name"]) : ""
      const arg = rawArg ? relativizePath(rawArg, workdir) : ""
      const result = (ev.phase === "completed" || ev.phase === "failed")
        ? codexOutputContent(obj.contentItems ?? obj.content_items)
        : ""
      return { summary: arg, rawSummary: rawArg, resultDetail: result }
    }
    if (obj.type === "fileChange" || obj.type === "file_change") {
      const changes = codexFileChanges(obj.changes, workdir)
      const legacyPath = pickString(obj, ["path", "file"])
      if (!changes.summary && legacyPath) {
        return {
          summary: relativizePath(legacyPath, workdir),
          rawSummary: legacyPath,
          resultDetail: ev.phase === "completed" || ev.phase === "failed" ? legacyPath : "",
        }
      }
      return {
        summary: changes.summary,
        rawSummary: changes.summary,
        inputDetail: changes.detail,
        resultDetail: ev.phase === "completed" || ev.phase === "failed" ? changes.result : "",
      }
    }
    const rawPicked = pickString(obj, ["command", "path", "file", "name", "query", "pattern", "text"])
    const summary = relativizePath(rawPicked, workdir)
    let result = ""
    if (ev.phase === "completed" || ev.phase === "failed") {
      result = typeof obj.aggregatedOutput === "string" ? obj.aggregatedOutput
        : typeof obj.aggregated_output === "string" ? obj.aggregated_output
        : ""
      const exitCode = obj.exitCode ?? obj.exit_code
      if (!result && ev.phase === "failed" && typeof exitCode === "number") result = `Exit code ${exitCode}`
    }
    return { summary, rawSummary: rawPicked, resultDetail: result }
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
    const rawPicked = innerArgs ? pickString(innerArgs, ["command", "pattern", "query", "globPattern", "glob_pattern", "description", "url", "path", "file", "target_file", "text"]) : ""
    const summary = rawPicked ? relativizePath(rawPicked, workdir) : ""
    const result = (ev.phase === "completed" || ev.phase === "failed") ? extractCursorResult(toolBody) : ""
    return { summary, rawSummary: rawPicked, resultDetail: result }
  }

  if (agent === "grok") {
    // grok ACP: `tool_call` carries `rawInput` (args) + `title`; `tool_call_update`
    // carries `status` + `content: [{ type:"content", content:{ type:"text", text }}]`.
    const rawInput = obj.rawInput as Record<string, unknown> | undefined
    const rawPicked = rawInput
      ? pickString(rawInput, ["command", "file_path", "path", "file", "pattern", "query", "url", "content", "text", "name"])
      : (typeof obj.title === "string" ? obj.title : "")
    // `summary` is the workdir-relativized form for the card title; `rawSummary` keeps
    // the absolute path for anything that needs the on-disk location.
    const summary = relativizePath(rawPicked, workdir)
    let result = ""
    if (ev.phase === "completed" || ev.phase === "failed") {
      result = extractGrokContent(obj.content)
    }
    return { summary, rawSummary: rawPicked, resultDetail: result }
  }

  // claude: extract from transcript blocks (handled by transcript-parser, not this path)
  const rawPicked = pickString(obj, ["command", "path", "file", "name", "query", "pattern"])
  const summary = relativizePath(rawPicked, workdir)
  return { summary, rawSummary: rawPicked, resultDetail: "" }
}

export function toActivityEvents(agent: AgentKind, ev: ToolCallEventLike, now: number, workdir: string | undefined): ActivityEvent[] {
  const ts = new Date(now).toISOString()
  const callId = ev.call_id || undefined
  const { summary: relativeSummary, rawSummary, resultDetail, inputDetail } = summarizeDetail(agent, ev, workdir)

  if (ev.phase === "started") {
    const tool = normalizeToolName(agent, ev.tool)
    const summary = firstLine(relativeSummary)
    const titleRaw = summary ? `${tool}: ${summary}` : tool
    const title = clip(titleRaw, TITLE_MAX)
    // The expand-panel detail keeps the raw (un-relativized) value so the user can
    // see the real host path — only the card title gets the workdir-relative form.
    const detail = clip(inputDetail ?? firstLine(rawSummary), DETAIL_MAX)
    return [{ ts, kind: "tool", tool, title: title.text, detail: detail.text, phase: "started",
      ...(callId ? { callId } : {}), ...(title.truncated || detail.truncated ? { truncated: true } : {}) }]
  }
  const result = agent === "codex" ? resultDetail.trim() : firstLine(resultDetail)
  const detail = clip(result, DETAIL_MAX)
  return [{ ts, kind: "tool_result", title: ev.phase === "failed" ? "error" : "done",
    detail: detail.text, phase: "completed", ...(detail.truncated ? { truncated: true } : {}),
    ...(callId ? { callId } : {}) }]
}
