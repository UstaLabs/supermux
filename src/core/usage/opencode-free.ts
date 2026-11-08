// OpenCode's free models: a daily request limit per network (IP), counted by OpenCode's servers and exposed nowhere
// (no route, header or CLI says what is left; the numbers are server config). It resets at 00:00 UTC. The one thing
// supermux can know is that a session HIT it: OpenCode then waits for the reset (see the OpenCode driver's
// `opencode_free_limit` warning), and that hit is kept here until the reset passes.
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "fs"
import { dirname, join } from "path"
import { STATE_DIR } from "../../shared/paths"

export interface OpenCodeFreeTier {
  /** When today's free allowance starts over: the recorded hit's reset, else the next 00:00 UTC. */
  resetsAtIso: string
  /** A session hit the limit and it has not reset yet. */
  reached: boolean
  reachedAtIso?: string
}

const FILE = join(STATE_DIR, "opencode-free-limit.json")
const DAY_MS = 86_400_000
const listeners = new Set<() => void>()

function nextUtcMidnight(now: number): string {
  return new Date((Math.floor(now / DAY_MS) + 1) * DAY_MS).toISOString()
}

/** A session saw OpenCode waiting out the free limit until `resetsAt`. A reset already past (or unreadable) is ignored. */
export function recordOpenCodeFreeLimit(resetsAt: string, file = FILE, now = Date.now()): void {
  const at = Date.parse(resetsAt)
  if (!Number.isFinite(at) || at <= now) return
  try {
    mkdirSync(dirname(file), { recursive: true })
    const tmp = `${file}.tmp`
    writeFileSync(tmp, JSON.stringify({ resetsAt: new Date(at).toISOString(), reachedAt: new Date(now).toISOString() }))
    renameSync(tmp, file)
  } catch { return }
  for (const listener of listeners) { try { listener() } catch { /* a listener cannot break the record */ } }
}

/** Called after each recorded hit (the broker refreshes the usage card). Returns the unsubscribe. */
export function onOpenCodeFreeLimit(listener: () => void): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function openCodeFreeTier(now = Date.now(), file = FILE): OpenCodeFreeTier {
  try {
    const hit = JSON.parse(readFileSync(file, "utf8")) as { resetsAt?: unknown; reachedAt?: unknown }
    const at = typeof hit.resetsAt === "string" ? Date.parse(hit.resetsAt) : NaN
    if (Number.isFinite(at) && at > now) {
      return {
        resetsAtIso: new Date(at).toISOString(),
        reached: true,
        ...(typeof hit.reachedAt === "string" ? { reachedAtIso: hit.reachedAt } : {}),
      }
    }
  } catch { /* no hit recorded */ }
  return { resetsAtIso: nextUtcMidnight(now), reached: false }
}
