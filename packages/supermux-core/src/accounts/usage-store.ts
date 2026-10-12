import { chmod, mkdir, open, readFile, rename, rm } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { dirname } from "node:path"
import type { AccountUnavailable, UsageWindow } from "./types.js"

const SAVE_DEBOUNCE_MS = 1000

/**
 * Latest rate-limit windows per account, persisted to `<stateDirectory>/accounts/usage.json`
 * (atomic, 0600, debounced writes) so the switch policy has data after a restart. Only windows
 * with a reset time are persisted, and windows whose reset passed are dropped on load and save:
 * a window without one could never be known to have reset. An unreadable file is a cache miss.
 * The same file keeps which accounts the agent's API refused (`unavailable`), until cleared.
 */
export class UsageStore {
  private readonly windows = new Map<string, UsageWindow[]>()
  private readonly refused = new Map<string, AccountUnavailable>()
  private loaded?: Promise<void>
  private timer?: ReturnType<typeof setTimeout>
  private saving: Promise<void> = Promise.resolve()

  constructor(private readonly file: string) {}

  load(): Promise<void> {
    return this.loaded ??= (async () => {
      let value: unknown
      try { value = JSON.parse(await readFile(this.file, "utf8")) } catch { return }
      const root = value && typeof value === "object" && (value as { version?: unknown }).version === 1 ? value as { accounts?: unknown; unavailable?: unknown } : undefined
      if (root?.unavailable && typeof root.unavailable === "object") {
        for (const [id, raw] of Object.entries(root.unavailable as Record<string, unknown>)) {
          if (this.refused.has(id) || !raw || typeof raw !== "object") continue
          const { reason, since } = raw as Record<string, unknown>
          const at = typeof since === "string" ? Date.parse(since) : NaN
          if (typeof reason === "string" && Number.isFinite(at)) this.refused.set(id, { reason, since: new Date(at) })
        }
      }
      const accounts = root?.accounts
      if (!accounts || typeof accounts !== "object") return
      const now = Date.now()
      for (const [id, raw] of Object.entries(accounts as Record<string, unknown>)) {
        if (this.windows.has(id) || !Array.isArray(raw)) continue
        const windows = raw.flatMap(w => {
          if (!w || typeof w !== "object") return []
          const { name, usedPercent, resetsAt } = w as Record<string, unknown>
          const at = typeof resetsAt === "string" ? Date.parse(resetsAt) : NaN
          if (typeof name !== "string" || typeof usedPercent !== "number" || !Number.isFinite(usedPercent) || !Number.isFinite(at) || at <= now) return []
          return [{ name, usedPercent, resetsAt: new Date(at) }]
        })
        if (windows.length) this.windows.set(id, windows)
      }
    })()
  }

  get(id: string): UsageWindow[] | undefined { return this.windows.get(id) }

  set(id: string, windows: UsageWindow[]): void {
    this.windows.set(id, windows)
    this.schedule()
  }

  /** Drops the windows and the unavailable mark (account removed). */
  delete(id: string): void {
    const had = this.windows.delete(id)
    if (this.refused.delete(id) || had) this.schedule()
  }

  unavailable(id: string): AccountUnavailable | undefined { return this.refused.get(id) }

  /** Marks `id` unavailable; false when it already was (the first reason and time are kept). */
  markUnavailable(id: string, reason: string, since = new Date()): boolean {
    if (this.refused.has(id)) return false
    this.refused.set(id, { reason, since })
    this.schedule()
    return true
  }

  /** Clears the mark; false when there was none. */
  clearUnavailable(id: string): boolean {
    if (!this.refused.delete(id)) return false
    this.schedule()
    return true
  }

  /** Writes pending changes now (core close). */
  async flush(): Promise<void> {
    if (this.timer) { clearTimeout(this.timer); this.timer = undefined; await this.save() }
    await this.saving
  }

  private schedule(): void {
    if (this.timer) return
    this.timer = setTimeout(() => { this.timer = undefined; void this.save() }, SAVE_DEBOUNCE_MS)
    this.timer.unref?.()
  }

  private save(): Promise<void> {
    const now = Date.now()
    const accounts: Record<string, Array<{ name: string; usedPercent: number; resetsAt: string }>> = {}
    for (const [id, windows] of this.windows) {
      const kept = windows.filter(w => w.resetsAt && w.resetsAt.getTime() > now).map(w => ({ name: w.name, usedPercent: w.usedPercent, resetsAt: w.resetsAt!.toISOString() }))
      if (kept.length) accounts[id] = kept
    }
    const unavailable: Record<string, { reason: string; since: string }> = {}
    for (const [id, mark] of this.refused) unavailable[id] = { reason: mark.reason, since: mark.since.toISOString() }
    const text = JSON.stringify({ version: 1, accounts, ...(this.refused.size ? { unavailable } : {}) })
    const run = this.saving.then(async () => {
      await mkdir(dirname(this.file), { recursive: true, mode: 0o700 })
      await chmod(dirname(this.file), 0o700)
      const temp = `${this.file}.${randomUUID()}.tmp`
      try {
        const file = await open(temp, "wx", 0o600)
        try { await file.writeFile(text); await file.sync() } finally { await file.close() }
        await rename(temp, this.file)
      } finally { await rm(temp, { force: true }) }
    }).catch(() => { /* a cache: the next change writes again */ })
    this.saving = run
    return run
  }
}
