import { existsSync, readFileSync } from "fs"

export interface RenderOpts {
  includeToolCalls?: boolean // default true
  grep?: string
  maxResultChars?: number // elide large tool_result bodies (default 800)
}

/** Render a Claude Code JSONL transcript into readable, token-bounded text. */
export function renderTranscript(path: string, opts: RenderOpts): string {
  if (!existsSync(path)) return `(no transcript file at ${path})`
  const includeTools = opts.includeToolCalls ?? true
  const cap = opts.maxResultChars ?? 800
  const out: string[] = []
  for (const raw of readFileSync(path, "utf8").split("\n")) {
    const line = raw.trim()
    if (!line) continue
    let obj: any
    try { obj = JSON.parse(line) } catch { continue }
    if (obj?.type !== "assistant" && obj?.type !== "user") continue
    const content = obj?.message?.content
    if (typeof content === "string") {
      if (content.trim()) out.push(`USER: ${content.trim()}`)
      continue
    }
    if (!Array.isArray(content)) continue
    for (const b of content) {
      if (!b || typeof b !== "object") continue
      if (b.type === "text" && typeof b.text === "string" && b.text.trim()) {
        out.push(`ASSISTANT: ${b.text.trim()}`)
      } else if (b.type === "thinking" && typeof b.thinking === "string" && b.thinking.trim()) {
        out.push(`THINKING: ${b.thinking.trim()}`)
      } else if (b.type === "tool_use" && includeTools) {
        out.push(`TOOL ${b.name}: ${clip(JSON.stringify(b.input ?? {}), cap)}`)
      } else if (b.type === "tool_result" && includeTools) {
        out.push(`RESULT${b.is_error ? " (error)" : ""}: ${clip(resultText(b.content), cap)}`)
      }
    }
  }
  const lines = opts.grep ? out.filter((l) => l.toLowerCase().includes(opts.grep!.toLowerCase())) : out
  return lines.length ? lines.join("\n") : "(no matching transcript lines)"
}

function clip(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max - 1) + "…"
}

function resultText(content: unknown): string {
  if (typeof content === "string") return content
  if (Array.isArray(content)) return content.map((b: any) => (b && typeof b.text === "string" ? b.text : "")).join("")
  return ""
}
