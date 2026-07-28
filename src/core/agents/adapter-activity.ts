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

const OPENCODE_SUMMARY_FIELDS = [
  "command", "path", "filePath", "file_path", "file", "pattern", "query", "text", "url", "port", "name",
  "args", "description", "glob", "include", "skill",
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

type DetailSummary = {
  summary: string
  rawSummary: string
  resultDetail: string
  inputDetail?: string
  /** Human "why" label when the agent provides one. */
  description?: string
  body?: ActivityToolBody
}

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

function codexFileChanges(value: unknown, workdir: string | undefined): {
  summary: string
  detail: string
  result: string
  body?: ActivityToolBody
} {
  if (!Array.isArray(value)) return { summary: "", detail: "", result: "" }
  const summaries: string[] = []
  const details: string[] = []
  const results: string[] = []
  const files: NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]> = []
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
    files.push({
      path: relativePath,
      rawPath: change.path,
      mode: kind,
      ...(diff ? { diff } : {}),
    })
  }
  if (!files.length) return { summary: "", detail: "", result: "" }
  const first = files[0]!
  const joinedDiff = files.map((f) => {
    const header = f.mode ? `${f.mode} ${f.rawPath ?? f.path}` : (f.rawPath ?? f.path)
    return f.diff ? `${header}\n${f.diff}` : header
  }).join("\n\n")
  return {
    summary: summaries.join(", "),
    detail: details.join("\n\n"),
    result: results.join("\n"),
    body: {
      kind: "edit",
      path: first.path,
      rawPath: first.rawPath,
      mode: first.mode,
      diff: joinedDiff || first.diff,
      files: files.length > 1 ? files : undefined,
    },
  }
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

  if (agent === "opencode") {
    const state = obj.state as Record<string, unknown> | undefined
    const input = state?.input as Record<string, unknown> | undefined
    const output = state ? extractOpenCodeOutput(state) : ""
    const error = typeof state?.error === "string" ? state.error : ""
    const rawTitle = typeof state?.title === "string" ? state.title : ""
    const rawPending = typeof state?.raw === "string" ? state.raw.trim() : ""
    // Summary prefers path/command fields (not oldString/newString).
    const rawPicked = input && Object.keys(input).length ? pickString(input, OPENCODE_SUMMARY_FIELDS) : ""
    const picked = rawPicked ? relativizePath(rawPicked, workdir) : ""
    const fallback = rawPending ? firstLine(rawPending) : rawTitle
    const summary = picked || fallback
    const result = ev.phase === "completed" ? (output || rawTitle) : ev.phase === "failed" ? error : ""

    let body: ActivityToolBody | undefined
    const command = input && typeof input.command === "string" ? input.command
      : (isBashTool(norm, ev.tool) && rawPending ? rawPending : "")
    if (isBashTool(norm, ev.tool) || command) {
      body = bashBody(
        command || (typeof rawPicked === "string" && !rawPicked.includes("/") ? rawPicked : undefined),
        ev.phase === "started" ? undefined : (result || undefined),
      )
    } else if (isEditTool(norm, ev.tool) || norm === "Write") {
      body = editBodyFromArgs(workdir, input, { forceWrite: norm === "Write" })
      if (ev.phase !== "started" && result && body?.kind === "generic") {
        body = { kind: "generic", output: result }
      }
    } else if (ev.phase === "started" && (rawPicked || fallback)) {
      body = { kind: "generic", input: rawPicked || fallback }
    } else if (result) {
      body = { kind: "generic", output: result }
    }

    // Full command/path for medium expand (not firstLine of summary only).
    let inputDetail: string | undefined
    if (command) inputDetail = command
    else if (body?.kind === "edit" && body.diff) inputDetail = body.diff
    else if (body?.kind === "write" && body.content) inputDetail = body.content
    else if (rawPicked || fallback) inputDetail = rawPicked || fallback

    // Prefer explicit input.description; else state.title when it isn't just the command/path.
    const description = cleanToolDescription(
      pickDescriptionField(input) || rawTitle,
      [command, rawPicked, picked, rawPending],
    )

    return {
      summary,
      rawSummary: rawPicked || fallback,
      resultDetail: result,
      inputDetail,
      description,
      body,
    }
  }

  if (agent === "codex") {
    if (obj.type === "webSearch" || obj.type === "web_search") {
      const action = obj.action && typeof obj.action === "object"
        ? obj.action as Record<string, unknown>
        : undefined
      const queries = Array.isArray(action?.queries)
        ? action.queries.filter((value): value is string => typeof value === "string" && !!value.trim())
        : []
      const actionValue = pickString(action ?? {}, ["query", "url", "pattern"])
      const rawSummary = pickString(obj, ["query"]) || queries[0] || actionValue
      const inputDetail = queries.length > 1 ? queries.join("\n") : rawSummary
      return {
        summary: rawSummary,
        rawSummary,
        inputDetail,
        resultDetail: "",
        description: cleanToolDescription(pickDescriptionField(obj) || pickDescriptionField(action), [rawSummary]),
        body: inputDetail ? { kind: "generic", input: inputDetail } : undefined,
      }
    }
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
      const label = arg ? `${toolName} ${arg}` : toolName
      return {
        summary: label,
        rawSummary: arg ? `${toolName} ${rawArg}` : toolName,
        resultDetail: result,
        description: cleanToolDescription(pickDescriptionField(obj) || pickDescriptionField(args), [label, rawArg, toolName]),
        body: ev.phase === "started"
          ? { kind: "generic", input: label }
          : result ? { kind: "generic", output: result } : undefined,
      }
    }
    if (obj.type === "dynamicToolCall" || obj.type === "dynamic_tool_call") {
      const args = (obj.arguments ?? obj.args) as Record<string, unknown> | undefined
      const rawArg = args ? pickString(args, ["command", "path", "workdir", "query", "pattern", "prompt", "text", "name"]) : ""
      const arg = rawArg ? relativizePath(rawArg, workdir) : ""
      const result = (ev.phase === "completed" || ev.phase === "failed")
        ? codexOutputContent(obj.contentItems ?? obj.content_items)
        : ""
      return {
        summary: arg,
        rawSummary: rawArg,
        resultDetail: result,
        description: cleanToolDescription(pickDescriptionField(obj) || pickDescriptionField(args), [rawArg, arg]),
        body: ev.phase === "started"
          ? (rawArg ? { kind: "generic", input: rawArg } : undefined)
          : result ? { kind: "generic", output: result } : undefined,
      }
    }
    if (obj.type === "fileChange" || obj.type === "file_change") {
      const changes = codexFileChanges(obj.changes, workdir)
      const legacyPath = pickString(obj, ["path", "file"])
      if (!changes.summary && legacyPath) {
        return {
          summary: relativizePath(legacyPath, workdir),
          rawSummary: legacyPath,
          resultDetail: ev.phase === "completed" || ev.phase === "failed" ? legacyPath : "",
          description: cleanToolDescription(pickDescriptionField(obj), [legacyPath]),
          body: { kind: "edit", path: relativizePath(legacyPath, workdir), rawPath: legacyPath, mode: "update" },
        }
      }
      return {
        summary: changes.summary,
        rawSummary: changes.summary,
        inputDetail: changes.detail,
        resultDetail: ev.phase === "completed" || ev.phase === "failed" ? changes.result : "",
        description: cleanToolDescription(pickDescriptionField(obj), [changes.summary]),
        body: changes.body,
      }
    }
    // commandExecution / shell — only treat explicit `command` as the shell command
    // (do not fall back to pickString's first-string fallback, which can grab `type`).
    const command = typeof obj.command === "string" ? obj.command : ""
    const rawPicked = command || pickString(obj, ["path", "file", "name", "query", "pattern", "text"])
    const summary = relativizePath(rawPicked, workdir)
    let result = ""
    let exitCode: number | undefined
    if (ev.phase === "completed" || ev.phase === "failed") {
      result = typeof obj.aggregatedOutput === "string" ? obj.aggregatedOutput
        : typeof obj.aggregated_output === "string" ? obj.aggregated_output
        : ""
      // Normalize trailing newline so medium detail matches historical expectations.
      if (result.endsWith("\n")) result = result.replace(/\n+$/, "")
      exitCode = numField(obj, ["exitCode", "exit_code"])
      if (!result && ev.phase === "failed" && typeof exitCode === "number") result = `Exit code ${exitCode}`
    }
    const body = isBashTool(norm, ev.tool)
      ? bashBody(command || undefined, ev.phase === "started" ? undefined : (result || undefined), exitCode)
      : undefined
    return {
      summary,
      rawSummary: rawPicked,
      resultDetail: result,
      inputDetail: command || undefined,
      description: cleanToolDescription(pickDescriptionField(obj), [command, rawPicked]),
      body,
    }
  }

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

  if (agent === "grok") {
    const rawInput = obj.rawInput as Record<string, unknown> | undefined
    const grokTitle = typeof obj.title === "string" ? obj.title : ""
    const rawPicked = rawInput
      ? pickString(rawInput, ["command", "file_path", "path", "file", "pattern", "query", "url", "name"])
      : grokTitle
    const summary = relativizePath(rawPicked, workdir)
    let result = ""
    if (ev.phase === "completed" || ev.phase === "failed") {
      result = extractGrokContent(obj.content)
    }

    let body: ActivityToolBody | undefined
    if (isBashTool(norm, ev.tool) || (rawInput && typeof rawInput.command === "string")) {
      body = bashBody(
        strField(rawInput, ["command"]) || undefined,
        result || undefined,
      )
    } else if (isEditTool(norm, ev.tool) || norm === "Write") {
      body = editBodyFromArgs(workdir, rawInput, { forceWrite: norm === "Write" || /write/i.test(ev.tool) })
    } else if (ev.phase === "started" && rawPicked) {
      body = { kind: "generic", input: rawPicked }
    } else if (result) {
      body = { kind: "generic", output: result }
    }

    const command = strField(rawInput, ["command"])
    // Grok ACP: `title` is often a short human label ("Write `/w/poem.txt`") or bare tool name.
    // Prefer rawInput.description; else title when it's more than a tool stem / path echo.
    const description = cleanToolDescription(
      pickDescriptionField(rawInput) || grokTitle,
      [command, rawPicked, summary, ev.tool, norm],
    )

    return {
      summary,
      rawSummary: rawPicked,
      resultDetail: result,
      inputDetail: command || rawPicked || undefined,
      description,
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
