import type { ActivityEvent } from "../claude/activity-event"
import type { ActivityToolBody } from "../activity-body"
import {
  cleanToolDescription,
  clipToolBody,
  ensureEditDiff,
  numField,
  pickDescriptionField,
  strField,
} from "../activity-body"
import { clip, firstLine, pickString } from "../activity-format"
import { relativizePath } from "../path-relativize"
import { normalizeToolName } from "../tool-normalize"
import type { NormalizedBody, NormalizedEvent, ToolCategory } from "../../../../packages/supermux-core/src/events/normalized.js"

const TITLE_MAX = 120
const DETAIL_MAX = 2000

function rec(value: unknown): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return
  return value as Record<string, unknown>
}

function jsonText(value: unknown): string {
  if (typeof value === "string") return value
  if (value == null) return ""
  try { return JSON.stringify(value, null, 2) ?? "" } catch { return String(value) }
}

function outputText(value: unknown): string {
  if (value == null) return ""
  if (typeof value === "string") return value
  if (Array.isArray(value)) {
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
  const row = rec(value)
  if (row) {
    if (typeof row.text === "string") return row.text
    const nested = outputText(row.content)
    if (nested) return nested
    if (row.structuredContent != null) return jsonText(row.structuredContent)
  }
  return jsonText(value)
}

function stripTrailingNl(s: string): string {
  return s.endsWith("\n") ? s.replace(/\n+$/, "") : s
}

function isBashTool(norm: string, raw: string): boolean {
  return norm === "Bash" || /(^|_)(bash|shell|run_terminal_command|run_terminal_cmd|command_execution|commandexecution)(_|$)/i.test(raw)
}
function isWriteLike(tool: string, category: ToolCategory | undefined, args: Record<string, unknown> | undefined): boolean {
  const norm = normalizeToolName("codex", tool)
  if (norm === "Write" || /write/i.test(tool)) return true
  if (!args) return false
  const oldText = strField(args, ["old_string", "oldString", "old_str", "oldText", "old_text"])
  const newText = strField(args, ["new_string", "newString", "new_str", "newText", "new_text"])
  const diff = strField(args, ["diff", "patch", "unifiedDiff", "unified_diff"])
  const content = strField(args, ["content", "contents", "file_text", "fileText", "new_file_contents", "streamContent"])
  return !oldText && !newText && !diff && !!content && category !== "edit"
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
  const isWrite = opts?.forceWrite || (!oldText && !newText && !diff && !!content)
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
  return { kind: "edit", path, rawPath, mode: "update" }
}

function filesFromChanges(value: unknown, workdir: string | undefined): NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]> {
  if (!Array.isArray(value)) return []
  const files: NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]> = []
  for (const item of value) {
    if (!item || typeof item !== "object") continue
    const change = item as Record<string, unknown>
    if (typeof change.path !== "string" || !change.path) continue
    const kindObj = change.kind && typeof change.kind === "object" ? change.kind as Record<string, unknown> : undefined
    const kind = typeof change.kind === "string" ? change.kind
      : typeof kindObj?.type === "string" ? kindObj.type
      : typeof change.changeKind === "string" ? change.changeKind
      : "update"
    const relativePath = relativizePath(change.path, workdir)
    const diff = typeof change.diff === "string" ? change.diff.trim()
      : typeof change.unified_diff === "string" ? change.unified_diff.trim()
      : ""
    files.push({
      path: relativePath,
      rawPath: change.path,
      mode: kind,
      ...(diff ? { diff } : {}),
    })
  }
  return files
}

function editBodyFromFiles(
  files: NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]>,
): ActivityToolBody | undefined {
  if (!files.length) return undefined
  const first = files[0]!
  const joinedDiff = files.map((f) => {
    const header = f.mode ? `${f.mode} ${f.rawPath ?? f.path}` : (f.rawPath ?? f.path)
    return f.diff ? `${header}\n${f.diff}` : header
  }).join("\n\n")
  return {
    kind: "edit",
    path: first.path,
    rawPath: first.rawPath,
    mode: first.mode,
    diff: joinedDiff || first.diff,
    files: files.length > 1 ? files : undefined,
  }
}

function mergeFile(
  files: NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]>,
  next: { path: string; rawPath: string; mode?: string; diff?: string },
) {
  const i = files.findIndex((f) => f.rawPath === next.rawPath || f.path === next.path)
  if (i >= 0) {
    const prev = files[i]!
    files[i] = {
      ...prev,
      ...(next.mode ? { mode: next.mode } : {}),
      ...(next.diff ? { diff: next.diff } : {}),
    }
  } else {
    files.push(next)
  }
}

type Pending = {
  tool: string
  category?: ToolCategory
  input?: unknown
  description?: string
  outputAcc: string
  exitCode?: number
  files: NonNullable<Extract<ActivityToolBody, { kind: "edit" }>["files"]>
  command?: string
}

function inputRecord(input: unknown): Record<string, unknown> | undefined {
  if (Array.isArray(input)) return { changes: input }
  return rec(input)
}

export function createNormalizedActivity(opts: { workdir?: string }) {
  const workdir = opts.workdir
  const pending = new Map<string, Pending>()

  function get(callId: string): Pending {
    let p = pending.get(callId)
    if (!p) {
      p = { tool: "tool", outputAcc: "", files: [] }
      pending.set(callId, p)
    }
    return p
  }

  function summarize(p: Pending, phase: "started" | "completed" | "failed"): {
    summary: string
    rawSummary: string
    resultDetail: string
    inputDetail?: string
    description?: string
    body?: ActivityToolBody
  } {
    const args = inputRecord(p.input)
    const nestedArgs = rec(args?.arguments) ?? rec(args?.args) ?? args
    const norm = normalizeToolName("codex", p.tool)
    const command = strField(nestedArgs, ["command"]) || strField(args, ["command"])
    const mcpTool = strField(args, ["tool", "toolName", "tool_name"])
    const changes = filesFromChanges(args?.changes, workdir)
    for (const f of changes) mergeFile(p.files, { ...f, rawPath: f.rawPath ?? f.path })

    let summary = ""
    let rawSummary = ""
    let inputDetail: string | undefined
    let resultDetail = ""
    let body: ActivityToolBody | undefined

    const category = p.category
    const writeLike = isWriteLike(p.tool, category, nestedArgs)

    if (category === "web-search" || norm === "WebFetch") {
      const action = rec(args?.action)
      const queries = Array.isArray(action?.queries)
        ? action.queries.filter((value): value is string => typeof value === "string" && !!value.trim())
        : []
      const actionValue = pickString(action ?? {}, ["query", "url", "pattern"])
      rawSummary = (typeof args?.query === "string" ? args.query : "") || queries[0] || actionValue
      summary = rawSummary
      inputDetail = queries.length > 1 ? queries.join("\n") : rawSummary
      body = inputDetail ? { kind: "generic", input: inputDetail } : undefined
    } else if (category === "mcp" || /^mcp/i.test(p.tool)) {
      const rawArg = nestedArgs
        ? pickString(nestedArgs, ["command", "path", "workdir", "query", "pattern", "text", "port", "name"])
        : ""
      const arg = rawArg ? relativizePath(rawArg, workdir) : ""
      const label = arg ? `${mcpTool} ${arg}` : mcpTool
      summary = label
      rawSummary = arg ? `${mcpTool} ${rawArg}` : mcpTool
      body = phase === "started"
        ? (label ? { kind: "generic", input: label } : undefined)
        : undefined
    } else if (category === "execute" || command || /shell|bash|terminal|command/i.test(p.tool) || norm === "Bash") {
      rawSummary = command || pickString(args ?? {}, ["path", "file", "name", "query", "pattern", "text"])
      summary = relativizePath(rawSummary, workdir)
      inputDetail = command || undefined
      const out = phase === "started" ? undefined : stripTrailingNl(p.outputAcc) || undefined
      body = bashBody(command || undefined, out, phase === "started" ? undefined : p.exitCode)
    } else if (p.files.length || category === "edit" || writeLike || norm === "Edit" || norm === "Write") {
      if (p.files.length) {
        const first = p.files[0]!
        summary = p.files.map((f) => f.path).join(", ")
        rawSummary = summary
        inputDetail = p.files.map((f) => {
          const header = f.mode ? `${f.mode} ${f.rawPath ?? f.path}` : (f.rawPath ?? f.path)
          return f.diff ? `${header}\n${f.diff}` : header
        }).join("\n\n")
        body = editBodyFromFiles(p.files)
      } else {
        body = editBodyFromArgs(workdir, nestedArgs, { forceWrite: writeLike || norm === "Write" })
        const rawPicked = nestedArgs
          ? pickString(nestedArgs, ["command", "file_path", "path", "file", "pattern", "query", "url", "name"])
          : ""
        rawSummary = rawPicked
        summary = relativizePath(rawPicked, workdir)
        inputDetail = rawPicked || undefined
      }
    } else {
      const rawPicked = nestedArgs
        ? pickString(nestedArgs, ["command", "file_path", "path", "file", "pattern", "query", "url", "name", "prompt", "text"])
        : typeof p.input === "string" ? p.input : ""
      rawSummary = rawPicked
      summary = relativizePath(rawPicked, workdir)
      inputDetail = rawPicked || undefined
      if (phase === "started" && rawPicked) body = { kind: "generic", input: rawPicked }
    }

    const resultSrc = p.outputAcc
    if (phase === "completed" || phase === "failed") {
      if (body?.kind === "bash") {
        let result = stripTrailingNl(resultSrc)
        const exitCode = numField(rec({ exitCode: (p as Pending & { exitCode?: number }).exitCode }), ["exitCode"])
        void exitCode
        if (!result && phase === "failed") {
          /* filled by caller via exit */
        }
        resultDetail = result
        body = bashBody(command || body.command, result || undefined)
      } else if (p.files.length) {
        resultDetail = p.files.map((f) => `${f.mode ?? "update"} ${f.path}`).join("\n")
        body = editBodyFromFiles(p.files)
      } else if (category === "mcp" || /^mcp/i.test(p.tool)) {
        resultDetail = stripTrailingNl(resultSrc)
        body = resultDetail ? { kind: "generic", output: resultDetail } : undefined
      } else if (body?.kind === "edit" || body?.kind === "write") {
        resultDetail = stripTrailingNl(resultSrc)
      } else {
        resultDetail = stripTrailingNl(resultSrc)
        // Legacy cards keep the raw output (trailing newline included) in the body; only the
        // medium-expand detail is trimmed.
        if (resultDetail) body = { kind: "generic", output: resultSrc }
        else body = body?.kind === "generic" && body.input ? body : (resultDetail ? { kind: "generic", output: resultDetail } : undefined)
      }
    }

    const description = cleanToolDescription(
      p.description || pickDescriptionField(nestedArgs) || pickDescriptionField(args),
      [command, rawSummary, summary, mcpTool, p.tool, norm],
    )

    return { summary, rawSummary, resultDetail, inputDetail, description, body }
  }

  function card(p: Pending, phase: "started" | "completed" | "failed", now: number, callId: string, extra?: { output?: unknown; exitCode?: number }): ActivityEvent[] {
    if (extra?.output != null) {
      const text = outputText(extra.output)
      if (text) p.outputAcc = text
    }
    if (extra?.exitCode !== undefined) p.exitCode = extra.exitCode
    const ts = new Date(now).toISOString()
    const {
      summary: relativeSummary,
      rawSummary,
      resultDetail,
      inputDetail,
      description,
      body: rawBody,
    } = summarize(p, phase)

    let body = rawBody
    let result = resultDetail
    const bashLike = body?.kind === "bash" || p.category === "execute" || isBashTool(normalizeToolName("codex", p.tool), p.tool)
    if ((phase === "completed" || phase === "failed") && bashLike) {
      let out = stripTrailingNl(p.outputAcc) || (body?.kind === "generic" ? stripTrailingNl(body.output ?? "") : "")
      const exitCode = extra?.exitCode
      // Same fallback as the legacy path: a failed command with no output still tells the user why.
      if (!out && phase === "failed" && typeof exitCode === "number") out = `Exit code ${exitCode}`
      result = out
      body = bashBody(body?.kind === "bash" ? body.command : undefined, out || undefined, exitCode)
    }

    const { body: clipped, truncated: bodyTrunc } = clipToolBody(body)
    const tool = normalizeToolName("codex", p.tool)

    if (phase === "started") {
      const summary = firstLine(relativeSummary)
      const titleRaw = summary ? `${tool}: ${summary}` : tool
      const title = clip(titleRaw, TITLE_MAX)
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
        ...(clipped ? { body: clipped } : {}),
      }]
    }

    const detail = clip(result.trim(), DETAIL_MAX)
    const truncated = detail.truncated || bodyTrunc
    return [{
      ts,
      kind: "tool_result",
      title: phase === "failed" ? "error" : "done",
      detail: detail.text,
      phase: "completed",
      ...(truncated ? { truncated: true } : {}),
      ...(callId ? { callId } : {}),
      ...(description ? { description } : {}),
      ...(clipped ? { body: clipped } : {}),
    }]
  }

  function handle(event: NormalizedEvent, now: number): ActivityEvent[] {
    const body = event.event
    if (!body) return []
    if (body.kind === "command-output") {
      const p = get(body.callId)
      p.outputAcc += body.delta
      return []
    }
    if (body.kind === "file-diff") {
      const callId = body.callId ?? ""
      const p = get(callId)
      const relativePath = relativizePath(body.path, workdir)
      mergeFile(p.files, {
        path: relativePath,
        rawPath: body.path,
        mode: body.changeKind,
        ...(body.diff ? { diff: body.diff.trim() } : {}),
      })
      return []
    }
    if (body.kind === "reasoning" || body.kind === "reasoning-delta") {
      if (body.kind === "reasoning-delta") return []
      const redacted = body.redacted === true
      const text = redacted ? "" : (body.text ?? body.summary?.join("\n") ?? "")
      const title = redacted ? "Thinking (redacted)" : "Thinking"
      const detail = redacted ? undefined : (text ? clip(text, DETAIL_MAX).text : undefined)
      return [{
        ts: new Date(now).toISOString(),
        kind: "reasoning",
        title,
        ...(detail ? { detail } : {}),
      }]
    }
    if (body.kind === "plan") {
      const lines = body.entries.map((e) => `${e.status}: ${e.content}`)
      const detail = [body.explanation, ...lines].filter(Boolean).join("\n")
      return [{
        ts: new Date(now).toISOString(),
        kind: "plan",
        title: "Plan",
        ...(detail ? { detail: clip(detail, DETAIL_MAX).text } : {}),
      }]
    }
    if (body.kind === "task") {
      const title = body.label || body.taskKind
      return [{
        ts: new Date(now).toISOString(),
        kind: "task",
        title,
        detail: `${body.taskKind} ${body.phase}`,
        phase: body.phase === "started" ? "started" : "completed",
        ...(body.parentCallId ? { callId: body.parentCallId } : {}),
      }]
    }
    if (body.kind !== "tool-call") return []
    const p = get(body.callId)
    p.tool = body.tool || p.tool
    if (body.category) p.category = body.category
    if (body.input !== undefined) p.input = body.input
    if (body.description) p.description = body.description
    if (typeof body.exitCode === "number") p.exitCode = body.exitCode
    if (body.output != null) {
      const text = outputText(body.output)
      if (text) p.outputAcc = text
    }
    if (body.phase === "updated") return []
    if (body.phase === "started") {
      return card(p, "started", now, body.callId)
    }
    if (body.phase === "completed" || body.phase === "failed") {
      const out = card(p, body.phase, now, body.callId, { output: body.output, exitCode: body.exitCode })
      pending.delete(body.callId)
      return out
    }
    return []
  }

  return { handle }
}
