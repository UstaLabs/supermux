import type { UsageResponse, ClaudeUsage, CodexUsage, CursorUsage, OpenCodeUsage, UsageWindow } from "./index"

// ── Helpers ──

function pct(value: number): string {
  return `${Math.round(value)}% used`
}

function relativeReset(ms: number): string {
  const diff = ms - Date.now()
  if (diff <= 0) return "now"
  const h = Math.floor(diff / 3_600_000)
  const m = Math.floor((diff % 3_600_000) / 60_000)
  return `in ${h}h ${String(m).padStart(2, "0")}m`
}

function dateReset(ms: number): string {
  const d = new Date(ms)
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" })
}

function resetLabel(ms: number): string {
  const diff = ms - Date.now()
  if (diff < 24 * 3_600_000) return relativeReset(ms)
  return dateReset(ms)
}

function windowResetStr(w: UsageWindow, kind: "iso" | "unix-s" | "unix-ms-str"): string {
  if (w.resetsAt == null) return ""
  let ms: number
  if (kind === "iso") ms = new Date(w.resetsAt as string).getTime()
  else if (kind === "unix-s") ms = (w.resetsAt as number) * 1000
  else ms = Number(w.resetsAt as string)
  return resetLabel(ms)
}

// ── Sections ──

function fmtClaude(c: ClaudeUsage): string {
  const lines: string[] = ["Claude"]

  const r5 = windowResetStr(c.fiveHour, "iso")
  lines.push(`  5h: ${pct(c.fiveHour.used)}${r5 ? ` · resets ${r5}` : ""}`)

  const r7 = windowResetStr(c.sevenDay, "iso")
  lines.push(`  7d: ${pct(c.sevenDay.used)}${r7 ? ` · resets ${r7}` : ""}`)

  if (c.extraUsage && c.extraUsage.enabled) {
    const used = c.extraUsage.usedCredits.toLocaleString("en-US", { maximumFractionDigits: 0 })
    const limit = c.extraUsage.monthlyLimit.toLocaleString("en-US", { maximumFractionDigits: 0 })
    lines.push(`  Extra: $${used} / $${limit}`)
  }

  return lines.join("\n")
}

function fmtCodex(c: CodexUsage): string {
  const lines: string[] = [`Codex (${c.plan})`]

  const rp = windowResetStr(c.primaryWindow, "unix-s")
  lines.push(`  5h: ${pct(c.primaryWindow.used)}${rp ? ` · resets ${rp}` : ""}`)

  const rs = windowResetStr(c.secondaryWindow, "unix-s")
  lines.push(`  7d: ${pct(c.secondaryWindow.used)}${rs ? ` · resets ${rs}` : ""}`)

  if (c.credits?.hasCredits) {
    lines.push(`  Credits: $${c.credits.balance}`)
  }

  return lines.join("\n")
}

function fmtCursor(c: CursorUsage): string {
  const lines: string[] = ["Cursor"]
  const ms = Number(c.billingCycleEnd)
  const rst = isNaN(ms) ? "" : resetLabel(ms)
  lines.push(`  ${pct(c.totalPercentUsed)}${rst ? ` · resets ${rst}` : ""}`)
  return lines.join("\n")
}

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

// opencode has no quota — report cumulative local cost + token totals instead.
function fmtOpenCode(c: OpenCodeUsage): string {
  const lines: string[] = ["opencode"]
  lines.push(`  $${c.totalCostUsd.toFixed(2)} · ${fmtTokens(c.inputTokens)} in / ${fmtTokens(c.outputTokens)} out`)
  lines.push(`  ${c.sessions} sessions · ${c.messages} messages`)
  return lines.join("\n")
}

// ── Public ──

export function formatUsageTelegram(data: UsageResponse): string {
  const sections: string[] = []
  if (data.claude) sections.push(fmtClaude(data.claude))
  if (data.codex) sections.push(fmtCodex(data.codex))
  if (data.cursor) sections.push(fmtCursor(data.cursor))
  if (data.opencode) sections.push(fmtOpenCode(data.opencode))
  if (sections.length === 0) return "usage data unavailable"
  return sections.join("\n\n")
}
