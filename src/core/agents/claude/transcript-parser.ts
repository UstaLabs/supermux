// src/core/agents/claude/transcript-parser.ts
import type { ActivityEvent } from "./activity-event"
import type { ActivityToolBody } from "../activity-body"
import {
  cleanToolDescription,
  clipToolBody,
  ensureEditDiff,
  pickDescriptionField,
  strField,
} from "../activity-body"
import { relativizePath } from "../path-relativize"
import { clip, firstLine } from "../activity-format"

const TITLE_MAX = 120
const DETAIL_MAX = 2000

const TITLE_FIELDS: Record<string, string> = {
  Bash: "command", Read: "file_path", Edit: "file_path", Write: "file_path",
  Glob: "pattern", Grep: "pattern", Task: "description", Skill: "skill", WebFetch: "url",
}

function shortInput(name: string, input: unknown, workdir: string | undefined): string {
  if (!input || typeof input !== "object" || Array.isArray(input)) return ""
  const obj = input as Record<string, unknown>
  const field = TITLE_FIELDS[name]
  const raw = field && typeof obj[field] === "string" ? (obj[field] as string)
    : Object.values(obj).find((v) => typeof v === "string") as string | undefined
  const pick = raw ? relativizePath(raw, workdir) : ""
  return pick ? firstLine(pick) : ""
}

function resultText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content.map((b) => (b && typeof b === "object" && typeof (b as any).text === "string" ? (b as any).text : "")).join("")
  }
  return ""
}

function bodyFromClaudeTool(name: string, input: unknown, workdir: string | undefined): ActivityToolBody | undefined {
  if (!input || typeof input !== "object" || Array.isArray(input)) return undefined
  const obj = input as Record<string, unknown>
  const lower = name.toLowerCase()

  if (name === "Bash" || lower === "bash" || lower === "shell") {
    const command = strField(obj, ["command"])
    return command ? { kind: "bash", command } : undefined
  }

  if (name === "Write" || lower === "write") {
    const rawPath = strField(obj, ["file_path", "filePath", "path"])
    if (!rawPath) return undefined
    const content = strField(obj, ["content", "contents", "file_text"])
    return {
      kind: "write",
      path: relativizePath(rawPath, workdir),
      rawPath,
      ...(content ? { content } : {}),
    }
  }

  if (name === "Edit" || lower === "edit" || lower === "multiedit") {
    const rawPath = strField(obj, ["file_path", "filePath", "path"])
    if (!rawPath) return undefined
    const path = relativizePath(rawPath, workdir)
    const oldText = strField(obj, ["old_string", "oldString"])
    const newText = strField(obj, ["new_string", "newString"])
    const diff = ensureEditDiff({ path, oldText: oldText || undefined, newText: newText || undefined })
    return {
      kind: "edit",
      path,
      rawPath,
      mode: "update",
      ...(diff ? { diff } : {}),
      ...(oldText ? { oldText } : {}),
      ...(newText ? { newText } : {}),
    }
  }

  // Generic: keep a readable input snapshot for High expand.
  try {
    const json = JSON.stringify(obj)
    return json && json !== "{}" ? { kind: "generic", input: json } : undefined
  } catch {
    return undefined
  }
}

function block(b: any, ts: string, workdir: string | undefined): ActivityEvent | null {
  if (!b || typeof b !== "object") return null
  if (b.type === "thinking") {
    return null  // content is redacted; duration-aware "Thought for Ns" markers come from agentStateStore
  }
  if (b.type === "text" && typeof b.text === "string" && b.text.startsWith("[Request interrupted by user")) {
    return { ts, kind: "interrupt", title: "Interrupted" }
  }
  if (b.type === "tool_use" && typeof b.name === "string") {
    const arg = shortInput(b.name, b.input, workdir)
    const title = clip(arg ? `${b.name}: ${arg}` : b.name, TITLE_MAX)
    const detail = clip(JSON.stringify(b.input ?? {}), DETAIL_MAX)
    const { body, truncated: bodyTrunc } = clipToolBody(bodyFromClaudeTool(b.name, b.input, workdir))
    const truncated = !!(detail.truncated || bodyTrunc)
    // Claude Bash/Task/Agent/Monitor (etc.) often include a human "why" in input.description.
    const inputObj = b.input && typeof b.input === "object" && !Array.isArray(b.input)
      ? b.input as Record<string, unknown>
      : undefined
    const command = strField(inputObj, ["command"])
    const path = strField(inputObj, ["file_path", "path"])
    const description = cleanToolDescription(pickDescriptionField(inputObj), [command, path, arg])
    return {
      ts,
      kind: "tool",
      tool: b.name,
      title: title.text,
      detail: detail.text,
      phase: "started",
      ...(typeof b.id === "string" ? { callId: b.id } : {}),
      ...(truncated ? { truncated: true } : {}),
      ...(description ? { description } : {}),
      ...(body ? { body } : {}),
    }
  }
  if (b.type === "tool_result") {
    const text = resultText(b.content)
    const detail = clip(text, DETAIL_MAX)
    // Result alone has no tool name — emit generic output body; UI pairs with the tool card.
    const { body, truncated: bodyTrunc } = clipToolBody(
      text ? { kind: "generic", output: text } : undefined,
    )
    const truncated = !!(detail.truncated || bodyTrunc)
    return {
      ts,
      kind: "tool_result",
      title: b.is_error ? "error" : "done",
      detail: detail.text,
      phase: "completed",
      ...(typeof b.tool_use_id === "string" ? { callId: b.tool_use_id } : {}),
      ...(truncated ? { truncated: true } : {}),
      ...(body ? { body } : {}),
    }
  }
  return null
}

export function parseTranscriptLine(line: string, workdir: string | undefined): ActivityEvent[] {
  try {
    const trimmed = line.trim()
    if (!trimmed) return []
    const obj = JSON.parse(trimmed)
    if (!obj || (obj.type !== "assistant" && obj.type !== "user")) return []
    const content = obj?.message?.content
    if (!Array.isArray(content)) return []
    const ts = typeof obj.timestamp === "string" ? obj.timestamp : new Date(0).toISOString()
    const out: ActivityEvent[] = []
    for (const b of content) { const ev = block(b, ts, workdir); if (ev) out.push(ev) }
    return out
  } catch {
    return []
  }
}
