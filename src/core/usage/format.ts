import type { UsageResponse, ClaudeUsage, CodexUsage, CursorUsage, OpenCodeUsage, GrokUsage, UsageWindow } from "./index"
import type { OpenCodePlan } from "./opencode-go"

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

function relativeReset(ms: number, now: number): string {
  const diff = ms - now
  if (diff <= 0) return "now"
  const h = Math.floor(diff / 3_600_000)
  const m = Math.floor((diff % 3_600_000) / 60_000)
  return `in ${h}h ${String(m).padStart(2, "0")}m`
}

function dateReset(ms: number): string {
  const d = new Date(ms)
  return d.toLocaleDateString("en-US", { month: "short", day: "numeric" })
}

function resetLabel(ms: number, now: number): string {
  const diff = ms - now
  if (diff < 24 * 3_600_000) return relativeReset(ms, now)
  return dateReset(ms)
}

function windowResetStr(w: UsageWindow, kind: "iso" | "unix-s" | "unix-ms-str", now: number): string {
  if (w.resetsAt == null) return ""
  let ms: number
  if (kind === "iso") ms = new Date(w.resetsAt as string).getTime()
  else if (kind === "unix-s") ms = (w.resetsAt as number) * 1000
  else ms = Number(w.resetsAt as string)
  return resetLabel(ms, now)
}

// ── Sections ──

function fmtClaude(c: ClaudeUsage, now: number): string {
  const lines: string[] = ["Claude"]

  const r5 = windowResetStr(c.fiveHour, "iso", now)
  lines.push(`  5h: ${pct(c.fiveHour.used)}${r5 ? ` · resets ${r5}` : ""}`)

  const r7 = windowResetStr(c.sevenDay, "iso", now)
  lines.push(`  7d: ${pct(c.sevenDay.used)}${r7 ? ` · resets ${r7}` : ""}`)

  if (c.sevenDaySonnet) {
    const rs = windowResetStr(c.sevenDaySonnet, "iso", now)
    lines.push(`  7d Sonnet: ${pct(c.sevenDaySonnet.used)}${rs ? ` · resets ${rs}` : ""}`)
  }

  if (c.sevenDayFable) {
    const rf = windowResetStr(c.sevenDayFable, "iso", now)
    lines.push(`  7d Fable: ${pct(c.sevenDayFable.used)}${rf ? ` · resets ${rf}` : ""}`)
  }

  if (c.extraUsage && c.extraUsage.enabled) {
    const used = money(c.extraUsage.usedCredits, c.extraUsage.currency)
    const limit = money(c.extraUsage.monthlyLimit, c.extraUsage.currency)
    lines.push(`  Extra: ${used} / ${limit}`)
  }

  if (c.resets && c.resets.resetsLeft > 0) {
    const ends = c.resets.grants.find((g) => g.id === c.resets!.nextGrantId)?.endsAtIso
    const by = ends ? dateReset(new Date(ends).getTime()) : ""
    lines.push(`  Resets banked: ${c.resets.resetsLeft}${by ? ` · use by ${by}` : ""}`)
  }

  return lines.join("\n")
}

function fmtCodex(c: CodexUsage, now: number): string {
  const lines: string[] = [`Codex (${c.plan})`]

  for (const window of c.windows) {
    const reset = windowResetStr(window, "unix-s", now)
    lines.push(`  ${window.label}: ${pct(window.used)}${reset ? ` · resets ${reset}` : ""}`)
  }

  // Per-model gates: only the LOCKED ones are worth a line here — an available
  // model is the normal state and the card already lists every model in full.
  for (const model of c.models ?? []) {
    if (model.available) continue
    const back = model.availableAtIso ? resetLabel(new Date(model.availableAtIso).getTime(), now) : ""
    const hint = back ? ` · back ${back}` : model.creditsWouldEnable ? " · credits would unlock" : ""
    lines.push(`  ${model.label}: locked${hint}`)
  }

  if (c.credits?.hasCredits) {
    lines.push(`  Credits: ${c.credits.balance} credits`)
  }

  if (c.resetCredits > 0) lines.push(`  Resets banked: ${c.resetCredits}`)

  return lines.join("\n")
}

function fmtCursor(c: CursorUsage, now: number): string {
  const lines: string[] = ["Cursor"]
  const ms = Number(c.billingCycleEnd)
  const rst = isNaN(ms) ? "" : resetLabel(ms, now)
  lines.push(`  ${pct(c.totalPercentUsed)}${rst ? ` · resets ${rst}` : ""}`)
  return lines.join("\n")
}

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`
  return String(n)
}

/** One line for an OpenCode plan with no meters to show; null when there is nothing to say. */
export function openCodePlanNote(plan: OpenCodePlan): string | null {
  switch (plan.status) {
    case "none": return "No OpenCode Go plan."
    case "expired": return "OpenCode sign-in expired. Sign in again."
    case "unavailable": return `OpenCode Go limits unavailable${plan.error ? `: ${plan.error}` : "."}`
    default: return null
  }
}

// The OpenCode Go windows when the login has a plan; local cost + token totals after them.
function fmtOpenCode(c: OpenCodeUsage, now: number): string {
  const lines: string[] = [c.plan?.go ? "OpenCode Go" : "opencode"]
  const go = c.plan?.go
  if (go) {
    for (const [label, m] of [["5-hour", go.rolling], ["Weekly", go.weekly], ["Monthly", go.monthly]] as const) {
      const rst = resetLabel(Date.parse(m.resetsAtIso), now)
      lines.push(`  ${label}: ${pct(m.percent)}${m.limited ? " · limit reached" : ""} · resets ${rst}`)
    }
  } else if (c.plan) {
    const note = openCodePlanNote(c.plan)
    if (note) lines.push(`  ${note}`)
  }
  lines.push(`  Local: $${c.totalCostUsd.toFixed(2)} · ${fmtTokens(c.inputTokens)} in / ${fmtTokens(c.outputTokens)} out`)
  lines.push(`  ${c.sessions} sessions · ${c.messages} messages`)
  return lines.join("\n")
}


function fmtGrok(c: GrokUsage, now: number): string {
  const lines: string[] = [`Grok (${c.plan})`]
  const rst = c.billingPeriodEnd ? resetLabel(new Date(c.billingPeriodEnd).getTime(), now) : ""
  // xAI's unified billing bills a weekly window; older accounts stay monthly.
  const cadence = c.periodType === "weekly" ? "Weekly: " : c.periodType === "monthly" ? "Monthly: " : ""
  lines.push(`  ${cadence}${pct(c.percentUsed)}${rst ? ` · resets ${rst}` : ""}`)
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

/** [now] is the clock the relative times ("in 2h 05m") are measured from; tests pin it. */
export function formatUsageTelegram(data: UsageResponse, now: number = Date.now()): string {
  const sections: string[] = []
  if (data.claude) sections.push(fmtClaude(data.claude, now))
  if (data.codex) sections.push(fmtCodex(data.codex, now))
  if (data.cursor) sections.push(fmtCursor(data.cursor, now))
  if (data.opencode) sections.push(fmtOpenCode(data.opencode, now))
  if (data.grok) sections.push(fmtGrok(data.grok, now))
  if (sections.length === 0) return "usage data unavailable"
  return sections.join("\n\n")
}
