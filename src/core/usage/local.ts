import { closeSync, existsSync, openSync, readFileSync, readdirSync, readSync, statSync } from "fs"
import { homedir } from "os"
import { join } from "path"
import type { ClaudeUsage, CodexUsage, CodexUsageWindow, UsageWindow } from "./index"

const CLAUDE_LOCAL_MAX_AGE_MS = 60 * 60_000
const CODEX_SCAN_FILES = 8
const CODEX_TAIL_BYTES = 64 * 1024

function isoFromMs(ms: number): string | null {
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null
}

function isoFromIsoLike(value: unknown): string | null {
  if (typeof value !== "string" || value === "") return null
  return isoFromMs(new Date(value).getTime())
}

function isoFromUnixSeconds(value: unknown): string | null {
  if (value == null || value === "") return null
  return isoFromMs(Number(value) * 1000)
}

function mapClaudeWindow(w: any): UsageWindow {
  return {
    used: w?.utilization ?? 0,
    resetsAt: w?.resets_at ?? null,
    resetsAtIso: isoFromIsoLike(w?.resets_at),
  }
}

function optionalClaudeWindow(w: any): UsageWindow | null {
  return w && typeof w === "object" ? mapClaudeWindow(w) : null
}

export function readClaudeLocalUsage(
  claudeJsonPath = join(homedir(), ".claude.json"),
): { data: ClaudeUsage; fetchedAt: Date } | null {
  if (!existsSync(claudeJsonPath)) return null
  let raw: any
  try {
    raw = JSON.parse(readFileSync(claudeJsonPath, "utf-8"))
  } catch {
    return null
  }
  const cached = raw?.cachedUsageUtilization
  if (!cached || typeof cached !== "object") return null
  const fetchedAtMs = Number(cached.fetchedAtMs)
  if (!Number.isFinite(fetchedAtMs)) return null
  if (Date.now() - fetchedAtMs > CLAUDE_LOCAL_MAX_AGE_MS) return null

  const u = cached.utilization ?? {}
  return {
    fetchedAt: new Date(fetchedAtMs),
    data: {
      fiveHour: mapClaudeWindow(u.five_hour),
      sevenDay: mapClaudeWindow(u.seven_day),
      sevenDaySonnet: optionalClaudeWindow(u.seven_day_sonnet),
      sevenDayFable: optionalClaudeWindow(u.seven_day_fable),
      extraUsage: null,
    },
  }
}

function windowLabel(seconds: number | null, fallback: string): string {
  if (seconds == null || seconds <= 0) return fallback
  const units: Array<[number, string]> = [
    [86_400, "day"],
    [3_600, "hour"],
    [60, "minute"],
  ]
  for (const [unitSeconds, unit] of units) {
    if (seconds % unitSeconds !== 0) continue
    const count = seconds / unitSeconds
    return `${count}-${unit} window`
  }
  return fallback
}

function mapCodexWindow(id: string, w: any, fallbackLabel: string): CodexUsageWindow | null {
  if (w == null || typeof w !== "object") return null

  const minsRaw = w.windowDurationMins ?? w.window_minutes
  const mins = minsRaw == null || minsRaw === "" ? NaN : Number(minsRaw)
  let windowSeconds: number | null = null
  if (Number.isFinite(mins) && mins > 0) {
    windowSeconds = mins * 60
  } else {
    const rawSeconds = w.windowSeconds ?? w.window_seconds ?? w.limit_window_seconds
    const seconds = rawSeconds == null || rawSeconds === "" ? NaN : Number(rawSeconds)
    if (Number.isFinite(seconds)) windowSeconds = seconds
  }

  const resetsAt = w.resetsAt ?? w.resets_at ?? w.reset_at ?? null
  const used = w.usedPercent ?? w.used_percent ?? 0
  return {
    id,
    label: windowLabel(windowSeconds, fallbackLabel),
    windowSeconds,
    used: Number(used) || 0,
    resetsAt,
    resetsAtIso: isoFromUnixSeconds(resetsAt),
  }
}

function unwrapRateLimits(rl: any): any {
  if (rl == null || typeof rl !== "object") return null
  if (rl.primary != null || rl.secondary != null) return rl
  if (rl.rateLimits && typeof rl.rateLimits === "object") return rl.rateLimits
  if (rl.rate_limits && typeof rl.rate_limits === "object") return rl.rate_limits
  return rl
}

function limitReachedFrom(body: any): boolean {
  const reached =
    body.rateLimitReachedType ??
    body.rate_limit_reached_type ??
    body.limitReached ??
    body.limit_reached
  if (reached == null || reached === false || reached === "" || reached === "none") return false
  return true
}

export function codexUsageFromRateLimits(rl: any, prev?: CodexUsage | null): CodexUsage | null {
  const body = unwrapRateLimits(rl)
  if (!body || typeof body !== "object") return null

  const primary = mapCodexWindow("primary", body.primary, "5-hour window")
  if (!primary) return null
  const secondary = mapCodexWindow("secondary", body.secondary, "7-day window")

  const plan = body.planType ?? body.plan_type ?? body.plan ?? prev?.plan ?? "unknown"
  const creditsRaw = body.credits
  const credits =
    creditsRaw && typeof creditsRaw === "object"
      ? {
          hasCredits: Boolean(creditsRaw.hasCredits ?? creditsRaw.has_credits),
          balance: String(creditsRaw.balance ?? "0"),
        }
      : null

  const resetCredits =
    typeof body.resetCredits === "number"
      ? body.resetCredits
      : typeof body.reset_credits === "number"
        ? body.reset_credits
        : (prev?.resetCredits ?? 0)

  return {
    plan: String(plan),
    windows: [primary, secondary].filter((w): w is CodexUsageWindow => w != null),
    credits,
    limitReached: limitReachedFrom(body),
    resetCredits,
  }
}

function listNumericDirs(dir: string): string[] {
  try {
    return readdirSync(dir, { withFileTypes: true })
      .filter((d) => d.isDirectory() && /^\d+$/.test(d.name))
      .map((d) => d.name)
      .sort((a, b) => b.localeCompare(a))
  } catch {
    return []
  }
}

function listRolloutFiles(dayDir: string): string[] {
  try {
    return readdirSync(dayDir, { withFileTypes: true })
      .filter((d) => d.isFile() && d.name.startsWith("rollout-") && d.name.endsWith(".jsonl"))
      .map((d) => join(dayDir, d.name))
  } catch {
    return []
  }
}

function readTail(path: string, maxBytes: number): string {
  const st = statSync(path)
  const fd = openSync(path, "r")
  try {
    const start = Math.max(0, st.size - maxBytes)
    const len = st.size - start
    const buf = Buffer.alloc(len)
    readSync(fd, buf, 0, len, start)
    return buf.toString("utf8")
  } finally {
    closeSync(fd)
  }
}

// Rollout lines nest the block: `{"type":"event_msg","payload":{"type":"token_count",…,"rate_limits":{…}}}`.
// Look at the top level first, then one level down through the usual wrappers.
function extractRateLimits(obj: any): any | null {
  if (!obj || typeof obj !== "object") return null
  if (obj.rate_limits && typeof obj.rate_limits === "object") return obj.rate_limits
  if (obj.rateLimits && typeof obj.rateLimits === "object") return obj.rateLimits
  for (const key of ["payload", "msg", "params"]) {
    const inner = obj[key]
    if (inner && typeof inner === "object") {
      const found = extractRateLimits(inner)
      if (found) return found
    }
  }
  return null
}

function readCodexFromFile(path: string): { data: CodexUsage; fetchedAt: Date } | null {
  let text: string
  try {
    text = readTail(path, CODEX_TAIL_BYTES)
  } catch {
    return null
  }
  const lines = text.split(/\r?\n/)
  if (text.length >= CODEX_TAIL_BYTES) lines.shift()

  for (let i = lines.length - 1; i >= 0; i--) {
    const line = lines[i]?.trim()
    if (!line) continue
    if (!line.includes("rate_limits") && !line.includes("rateLimits")) continue
    let parsed: any
    try {
      parsed = JSON.parse(line)
    } catch {
      continue
    }
    const rl = extractRateLimits(parsed)
    if (!rl || rl.primary == null) continue
    const data = codexUsageFromRateLimits(rl)
    if (!data) continue
    let fetchedAt: Date
    try {
      fetchedAt = new Date(statSync(path).mtimeMs)
    } catch {
      fetchedAt = new Date()
    }
    return { data, fetchedAt }
  }
  return null
}

export function readCodexLocalUsage(
  sessionsDir = join(homedir(), ".codex", "sessions"),
): { data: CodexUsage; fetchedAt: Date } | null {
  if (!existsSync(sessionsDir)) return null

  let scanned = 0
  for (const year of listNumericDirs(sessionsDir)) {
    const yearDir = join(sessionsDir, year)
    for (const month of listNumericDirs(yearDir)) {
      const monthDir = join(yearDir, month)
      for (const day of listNumericDirs(monthDir)) {
        const dayDir = join(monthDir, day)
        const files = listRolloutFiles(dayDir)
          .map((path) => {
            try {
              return { path, mtimeMs: statSync(path).mtimeMs }
            } catch {
              return null
            }
          })
          .filter((f): f is { path: string; mtimeMs: number } => f != null)
          .sort((a, b) => b.mtimeMs - a.mtimeMs)

        for (const file of files) {
          scanned++
          const found = readCodexFromFile(file.path)
          if (found) return found
          if (scanned >= CODEX_SCAN_FILES) return null
        }
      }
    }
  }
  return null
}
