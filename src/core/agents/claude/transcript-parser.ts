// src/core/agents/claude/transcript-parser.ts
import type { ActivityEvent } from "./activity-event"

const TITLE_MAX = 120
const DETAIL_MAX = 2000

const TITLE_FIELDS: Record<string, string> = {
  Bash: "command", Read: "file_path", Edit: "file_path", Write: "file_path",
  Glob: "pattern", Grep: "pattern", Task: "description", Skill: "skill", WebFetch: "url",
}

function clip(s: string, max: number): { text: string; truncated: boolean } {
  if (s.length <= max) return { text: s, truncated: false }
  return { text: s.slice(0, max - 1) + "…", truncated: true }
}

function firstLine(s: string): string {
  for (const ln of s.split("\n")) { const t = ln.trim(); if (t) return t }
  return s.trim()
}

function shortInput(name: string, input: unknown): string {
  if (!input || typeof input !== "object" || Array.isArray(input)) return ""
  const obj = input as Record<string, unknown>
  const field = TITLE_FIELDS[name]
  const pick = field && typeof obj[field] === "string" ? (obj[field] as string)
    : Object.values(obj).find((v) => typeof v === "string") as string | undefined
  return pick ? firstLine(pick) : ""
}

function resultText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content.map((b) => (b && typeof b === "object" && typeof (b as any).text === "string" ? (b as any).text : "")).join("")
  }
  return ""
}

function block(b: any, ts: string): ActivityEvent | null {
  if (!b || typeof b !== "object") return null
  if (b.type === "thinking") {
    return null  // content is redacted; duration-aware "Thought for Ns" markers come from agentStateStore
  }
  if (b.type === "text" && typeof b.text === "string" && b.text.startsWith("[Request interrupted by user")) {
    return { ts, kind: "interrupt", title: "Interrupted" }
  }
  if (b.type === "tool_use" && typeof b.name === "string") {
    const arg = shortInput(b.name, b.input)
    const title = clip(arg ? `${b.name}: ${arg}` : b.name, TITLE_MAX)
    const detail = clip(JSON.stringify(b.input ?? {}), DETAIL_MAX)
    return { ts, kind: "tool", tool: b.name, title: title.text, detail: detail.text, phase: "started", ...(typeof b.id === "string" ? { callId: b.id } : {}), ...(detail.truncated ? { truncated: true } : {}) }
  }
  if (b.type === "tool_result") {
    const detail = clip(resultText(b.content), DETAIL_MAX)
    return { ts, kind: "tool_result", title: b.is_error ? "error" : "done", detail: detail.text, phase: "completed", ...(typeof b.tool_use_id === "string" ? { callId: b.tool_use_id } : {}), ...(detail.truncated ? { truncated: true } : {}) }
  }
  return null
}

export function parseTranscriptLine(line: string): ActivityEvent[] {
  try {
    const trimmed = line.trim()
    if (!trimmed) return []
    const obj = JSON.parse(trimmed)
    if (!obj || (obj.type !== "assistant" && obj.type !== "user")) return []
    const content = obj?.message?.content
    if (!Array.isArray(content)) return []
    const ts = typeof obj.timestamp === "string" ? obj.timestamp : new Date(0).toISOString()
    const out: ActivityEvent[] = []
    for (const b of content) { const ev = block(b, ts); if (ev) out.push(ev) }
    return out
  } catch {
    return []
  }
}
