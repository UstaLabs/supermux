import type { AgentKind } from "./types"
import type { ActivityEvent } from "./claude/activity-event"
import type { ActivityToolBody } from "./activity-body"
import {
  cleanToolDescription,
  clipToolBody,
  ensureEditDiff,
  numField,
  pickDescriptionField,
  strField,
} from "./activity-body"
import { normalizeToolName } from "./tool-normalize"
import { clip, firstLine, pickString } from "./activity-format"
import { relativizePath } from "./path-relativize"

const TITLE_MAX = 120
/** Medium expand preview cap (body carries the full High payload). */
const DETAIL_MAX = 2000

interface ToolCallEventLike { tool: string; phase: "started" | "completed" | "failed"; call_id: string; detail?: unknown }

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
  if (caseKey === "success") {
    // Prefer interleaved full stream, then stdout; append stderr if both present.
    const interleaved = typeof caseVal.interleavedOutput === "string" ? caseVal.interleavedOutput : ""
    if (interleaved) return interleaved
    const stdout = typeof caseVal.stdout === "string" ? caseVal.stdout : ""
    const stderr = typeof caseVal.stderr === "string" ? caseVal.stderr : ""
    if (stdout && stderr) return `${stdout}\n${stderr}`
    return stdout || stderr
  }
  return pickString(caseVal, ["stderr", "error", "message", "stdout"])
}

function extractCursorExitCode(toolBody: Record<string, unknown> | undefined): number | undefined {
  const result = toolBody?.result as Record<string, unknown> | undefined
  if (!result || typeof result !== "object") return undefined
  const caseKey = Object.keys(result)[0]
  if (!caseKey) return undefined
  const caseVal = result[caseKey] as Record<string, unknown> | undefined
  return numField(caseVal, ["exitCode", "exit_code"])
}

type DetailSummary = {
  summary: string
  rawSummary: string
  resultDetail: string
  inputDetail?: string
  /** Human "why" label when the agent provides one. */
  description?: string
  body?: ActivityToolBody
}

function editBodyFromArgs(
  workdir: string | undefined,
  args: Record<string, unknown> | undefined,
  opts?: { forceWrite?: boolean },
): ActivityToolBody | undefined {
  if (!args) return undefined
  const rawPath = strField(args, [
    "file_path", "filePath", "path", "file", "target_file", "targetFile",
  ])
  if (!rawPath) return undefined
  const path = relativizePath(rawPath, workdir)
  const oldText = strField(args, ["old_string", "oldString", "old_str", "oldText", "old_text"])
  const newText = strField(args, ["new_string", "newString", "new_str", "newText", "new_text"])
  const content = strField(args, ["content", "contents", "file_text", "fileText", "new_file_contents", "streamContent"])
  const diff = strField(args, ["diff", "patch", "unifiedDiff", "unified_diff"])

  const isWrite = opts?.forceWrite
    || (!oldText && !newText && !diff && !!content)

  if (isWrite) {
    return { kind: "write", path, rawPath, content: content || newText || undefined }
  }
  if (oldText || newText || diff || content) {
    const resolvedDiff = ensureEditDiff({
      path,
      diff: diff || undefined,
      oldText: oldText || undefined,
      newText: newText || content || undefined,
    })
    return {
      kind: "edit",
      path,
      rawPath,
      mode: "update",
      ...(resolvedDiff ? { diff: resolvedDiff } : {}),
      ...(oldText ? { oldText } : {}),
      ...(newText || content ? { newText: newText || content } : {}),
    }
  }
  // Path-only edit (body still useful for High header).
  return { kind: "edit", path, rawPath, mode: "update" }
}

function bashBody(command: string | undefined, output?: string, exitCode?: number | null): ActivityToolBody | undefined {
  if (!command && !output && exitCode == null) return undefined
  return {
    kind: "bash",
    ...(command ? { command } : {}),
    ...(output ? { output } : {}),
    ...(exitCode !== undefined ? { exitCode } : {}),
  }
}

function isBashTool(norm: string, raw: string): boolean {
  if (norm === "Bash") return true
  const k = raw.toLowerCase()
  return k.includes("shell") || k.includes("bash") || k.includes("terminal") || k.includes("command")
}

function isEditTool(norm: string, raw: string): boolean {
  if (norm === "Edit" || norm === "Write") return true
  const k = raw.toLowerCase()
  // "replace" covers search_replace / str_replace (Grok Build, Cursor, etc.)
  return k.includes("edit") || k.includes("write") || k.includes("patch")
    || k.includes("replace") || k.includes("filechange") || k.includes("file_change")
}

function summarizeDetail(agent: AgentKind, ev: ToolCallEventLike, workdir: string | undefined): DetailSummary {
  const obj = ev.detail && typeof ev.detail === "object" ? ev.detail as Record<string, unknown> : undefined
  if (!obj) return { summary: "", rawSummary: "", resultDetail: "" }
  const norm = normalizeToolName(agent, ev.tool)

  if (agent === "cursor") {
    const tc = obj.tool_call as Record<string, unknown> | undefined
    const toolBody = tc && typeof tc === "object" ? (tc[ev.tool] ?? Object.values(tc)[0]) as Record<string, unknown> | undefined : undefined
    const innerArgs = toolBody?.args as Record<string, unknown> | undefined
    const rawPicked = innerArgs
      ? pickString(innerArgs, [
        "command", "pattern", "query", "globPattern", "glob_pattern", "description", "url",
        "path", "file", "target_file", "targetFile", "file_path", "text",
      ])
      : ""
    const summary = rawPicked ? relativizePath(rawPicked, workdir) : ""
    const result = (ev.phase === "completed" || ev.phase === "failed") ? extractCursorResult(toolBody) : ""
    const exitCode = (ev.phase === "completed" || ev.phase === "failed") ? extractCursorExitCode(toolBody) : undefined

    let body: ActivityToolBody | undefined
    if (isBashTool(norm, ev.tool)) {
      const command = strField(innerArgs, ["command"])
      body = bashBody(command || undefined, result || undefined, exitCode)
    } else if (isEditTool(norm, ev.tool)) {
      body = editBodyFromArgs(workdir, innerArgs, { forceWrite: norm === "Write" || /write/i.test(ev.tool) })
      // Some cursor edit results stream a diff/content in success payload.
      if ((ev.phase === "completed" || ev.phase === "failed") && toolBody?.result) {
        const resultObj = toolBody.result as Record<string, unknown>
        const caseKey = Object.keys(resultObj)[0]
        const caseVal = caseKey ? resultObj[caseKey] as Record<string, unknown> | undefined : undefined
        const resultDiff = strField(caseVal, ["diff", "patch", "unifiedDiff", "beforeAfterDiff"])
        const resultContent = strField(caseVal, ["content", "contents", "fileContent", "after", "newContent"])
        if (body?.kind === "edit" && (resultDiff || resultContent) && !body.diff) {
          body = {
            ...body,
            diff: ensureEditDiff({
              path: body.path,
              diff: resultDiff || undefined,
              oldText: body.oldText,
              newText: body.newText || resultContent || undefined,
            }),
            ...(resultContent && !body.newText ? { newText: resultContent } : {}),
          }
        } else if (!body && result) {
          body = { kind: "generic", output: result }
        }
      }
    } else if (ev.phase === "started" && rawPicked) {
      body = { kind: "generic", input: rawPicked }
    } else if (result) {
      body = { kind: "generic", output: result }
    }

    const command = strField(innerArgs, ["command"])
    return {
      summary,
      rawSummary: rawPicked,
      resultDetail: result,
      inputDetail: command || rawPicked || undefined,
      description: cleanToolDescription(pickDescriptionField(innerArgs), [command, rawPicked, summary]),
      body,
    }
  }

  // claude stream path (rare via adapter): extract from transcript-like blocks
  const rawPicked = pickString(obj, ["command", "path", "file", "name", "query", "pattern"])
  const summary = relativizePath(rawPicked, workdir)
  return { summary, rawSummary: rawPicked, resultDetail: "" }
}

export function toActivityEvents(agent: AgentKind, ev: ToolCallEventLike, now: number, workdir: string | undefined): ActivityEvent[] {
  const ts = new Date(now).toISOString()
  const callId = ev.call_id || undefined
  const {
    summary: relativeSummary,
    rawSummary,
    resultDetail,
    inputDetail,
    description,
    body: rawBody,
  } = summarizeDetail(agent, ev, workdir)
  const { body, truncated: bodyTrunc } = clipToolBody(rawBody)

  if (ev.phase === "started") {
    const tool = normalizeToolName(agent, ev.tool)
    const summary = firstLine(relativeSummary)
    const titleRaw = summary ? `${tool}: ${summary}` : tool
    const title = clip(titleRaw, TITLE_MAX)
    // Prefer full command / multi-line input for medium expand (not firstLine alone).
    const detailSrc = inputDetail ?? rawSummary
    const detail = clip(detailSrc, DETAIL_MAX)
    const truncated = title.truncated || detail.truncated || bodyTrunc
    return [{
      ts,
      kind: "tool",
      tool,
      title: title.text,
      detail: detail.text,
      phase: "started",
      ...(callId ? { callId } : {}),
      ...(truncated ? { truncated: true } : {}),
      ...(description ? { description } : {}),
      ...(body ? { body } : {}),
    }]
  }

  // Full multiline result for medium expand + High body (no firstLine destruction).
  const detail = clip(resultDetail.trim(), DETAIL_MAX)
  const truncated = detail.truncated || bodyTrunc
  return [{
    ts,
    kind: "tool_result",
    title: ev.phase === "failed" ? "error" : "done",
    detail: detail.text,
    phase: "completed",
    ...(truncated ? { truncated: true } : {}),
    ...(callId ? { callId } : {}),
    // Results rarely re-send "why"; keep description if the agent included one.
    ...(description ? { description } : {}),
    ...(body ? { body } : {}),
  }]
}
