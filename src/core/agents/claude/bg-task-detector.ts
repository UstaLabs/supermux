// src/core/agents/claude/bg-task-detector.ts
// Stateful per-session detector for claude background-task lifecycle markers.
// Fed raw transcript lines by the tailer (alongside parseTranscriptLine, which
// stays pure/stateless). Emits open/close/wake — no store or broker knowledge.
import { kindFromId, type BgTaskClose, type BgTaskOpen } from "../../session-manager/background-task-store"

export interface BgTaskDetectorOpts {
  onOpen: (t: BgTaskOpen) => void
  onClose: (c: BgTaskClose) => void
  // A task-notification line is delivery evidence: the harness is waking claude.
  onWake?: (ts: number) => void
}

const PENDING_CAP = 50
const LABEL_MAX = 80
const LAUNCH_TOOLS = new Set(["Bash", "Agent", "Task", "Workflow"])

const SHELL_START_RE = /Command running in background with ID:\s*([A-Za-z0-9_-]+)/
const AGENT_START_RE = /Async agent launched[\s\S]{0,200}?agentId:\s*([A-Za-z0-9_.-]+)/
const WORKFLOW_START_RE = /\b(wf_[a-z0-9-]{6,})\b/
const NOTIFICATION_RE = /<task-notification>([\s\S]*?)<\/task-notification>/g

function firstLine(s: string): string {
  for (const ln of s.split("\n")) { const t = ln.trim(); if (t) return t }
  return s.trim()
}

function clip(s: string): string {
  return s.length <= LABEL_MAX ? s : s.slice(0, LABEL_MAX - 1) + "…"
}

function labelFor(name: string, input: Record<string, unknown>): string {
  const desc = typeof input.description === "string" ? input.description : ""
  if (desc) return clip(firstLine(desc))
  const cmd = typeof input.command === "string" ? input.command : ""
  if (cmd) return clip(firstLine(cmd))
  const prompt = typeof input.prompt === "string" ? input.prompt : ""
  if (prompt) return clip(firstLine(prompt))
  const wf = typeof input.name === "string" ? input.name : ""
  if (wf) return clip(firstLine(wf))
  return name.toLowerCase()
}

function resultText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) {
    return content.map((b) => (b && typeof b === "object" && typeof (b as any).text === "string" ? (b as any).text : "")).join("")
  }
  return ""
}

function tag(body: string, name: string): string {
  const m = body.match(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`))
  return m?.[1]?.trim() ?? ""
}

export class BgTaskDetector {
  private readonly pending = new Map<string, { tool: string; label: string }>()

  constructor(private readonly opts: BgTaskDetectorOpts) {}

  feedLine(line: string): void {
    let obj: any
    try { obj = JSON.parse(line) } catch { return }
    if (!obj || (obj.type !== "assistant" && obj.type !== "user")) return
    const ts = typeof obj.timestamp === "string" ? (Date.parse(obj.timestamp) || Date.now()) : Date.now()
    const content = obj?.message?.content

    if (typeof content === "string") {
      this.scanNotifications(content, ts)
      return
    }
    if (!Array.isArray(content)) return

    for (const b of content) {
      if (!b || typeof b !== "object") continue
      if (b.type === "tool_use" && typeof b.name === "string" && LAUNCH_TOOLS.has(b.name) && typeof b.id === "string") {
        const input = b.input && typeof b.input === "object" ? b.input as Record<string, unknown> : {}
        this.pending.set(b.id, { tool: b.name, label: labelFor(b.name, input) })
        if (this.pending.size > PENDING_CAP) {
          const oldest = this.pending.keys().next().value
          if (oldest !== undefined) this.pending.delete(oldest)
        }
        continue
      }
      if (b.type === "tool_result") {
        const text = resultText(b.content)
        if (!text) continue
        const callId = typeof b.tool_use_id === "string" ? b.tool_use_id : undefined
        const shell = text.match(SHELL_START_RE)
        const agent = shell ? null : text.match(AGENT_START_RE)
        const wf = !shell && !agent && /[Ww]orkflow/.test(text) ? text.match(WORKFLOW_START_RE) : null
        const id = shell?.[1] ?? agent?.[1] ?? wf?.[1]
        if (id) {
          const kind = shell ? "shell" as const : agent ? "agent" as const : kindFromId(id)
          const label = (callId ? this.pending.get(callId)?.label : undefined) ?? id
          if (callId) this.pending.delete(callId)
          this.opts.onOpen({ id, kind, label, ts, ...(callId ? { callId } : {}) })
        }
        // Array-content user messages can in principle carry a notification in a
        // text block; scan without the wake signal (wake is for string deliveries).
        this.scanNotifications(text, ts, /* wake */ false)
      }
    }
  }

  private scanNotifications(text: string, ts: number, wake = true): void {
    if (!text.includes("<task-notification>")) return
    let matched = false
    for (const m of text.matchAll(NOTIFICATION_RE)) {
      const body = m[1] ?? ""
      const id = tag(body, "task-id")
      if (!id) continue
      matched = true
      const status = tag(body, "status") === "completed" ? "completed" as const : "failed" as const
      const summary = tag(body, "summary")
      this.opts.onClose({ id, status, ...(summary ? { summary } : {}), ts })
    }
    if (matched && wake) this.opts.onWake?.(ts)
  }
}
