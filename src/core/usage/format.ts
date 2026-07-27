import type { UsageResponse, ClaudeUsage, CodexUsage, CursorUsage, OpenCodeUsage, GrokUsage, UsageWindow } from "./index"

// ── Helpers ──

function pct(value: number): string {
  return `${Math.round(value)}% used`
}

function money(value: number, currency: string): string {
  try {
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: currency.toUpperCase(),
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(value)
  } catch {
    return `$${value.toFixed(2)}`
  }
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

  if (c.sevenDaySonnet) {
    const rs = windowResetStr(c.sevenDaySonnet, "iso")
    lines.push(`  7d Sonnet: ${pct(c.sevenDaySonnet.used)}${rs ? ` · resets ${rs}` : ""}`)
  }

  if (c.sevenDayFable) {
    const rf = windowResetStr(c.sevenDayFable, "iso")
    lines.push(`  7d Fable: ${pct(c.sevenDayFable.used)}${rf ? ` · resets ${rf}` : ""}`)
  }

  if (c.extraUsage && c.extraUsage.enabled) {
    const used = money(c.extraUsage.usedCredits, c.extraUsage.currency)
    const limit = money(c.extraUsage.monthlyLimit, c.extraUsage.currency)
    lines.push(`  Extra: ${used} / ${limit}`)
  }

  return lines.join("\n")
}

function fmtCodex(c: CodexUsage): string {
  const lines: string[] = [`Codex (${c.plan})`]

  for (const window of c.windows) {
    const reset = windowResetStr(window, "unix-s")
    lines.push(`  ${window.label}: ${pct(window.used)}${reset ? ` · resets ${reset}` : ""}`)
  }

  if (c.credits?.hasCredits) {
    lines.push(`  Credits: ${c.credits.balance} credits`)
  }

  if (c.resetCredits > 0) lines.push(`  Resets banked: ${c.resetCredits}`)

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


function fmtGrok(c: GrokUsage): string {
  const lines: string[] = [`Grok (${c.plan})`]
  const rst = c.billingPeriodEnd ? resetLabel(new Date(c.billingPeriodEnd).getTime()) : ""
  lines.push(`  ${pct(c.percentUsed)}${rst ? ` · resets ${rst}` : ""}`)
  if (c.monthlyLimit > 0) {
    lines.push(`  Credits: ${Math.round(c.used)} / ${Math.round(c.monthlyLimit)}`)
  }
  if (c.onDemandCap > 0) {
    lines.push(`  On-demand: ${Math.round(c.onDemandUsed)} / ${Math.round(c.onDemandCap)}`)
  }
  if (c.prepaidBalance > 0) {
    lines.push(`  Prepaid: ${Math.round(c.prepaidBalance)}`)
  }
  return lines.join("\n")
}

// ── Public ──

export function formatUsageTelegram(data: UsageResponse): string {
  const sections: string[] = []
  if (data.claude) sections.push(fmtClaude(data.claude))
  if (data.codex) sections.push(fmtCodex(data.codex))
  if (data.cursor) sections.push(fmtCursor(data.cursor))
  if (data.opencode) sections.push(fmtOpenCode(data.opencode))
  if (data.grok) sections.push(fmtGrok(data.grok))
  if (sections.length === 0) return "usage data unavailable"
  return sections.join("\n\n")
}
