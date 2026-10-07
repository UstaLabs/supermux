import { chmod, mkdir, open, readFile, rename, rm } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { dirname } from "node:path"
import type { UsageWindow } from "./types.js"

const SAVE_DEBOUNCE_MS = 1000

/**
 * Latest rate-limit windows per account, persisted to `<stateDirectory>/accounts/usage.json`
 * (atomic, 0600, debounced writes) so the switch policy has data after a restart. Only windows
 * with a reset time are persisted, and windows whose reset passed are dropped on load and save:
 * a window without one could never be known to have reset. An unreadable file is a cache miss.
 */
export class UsageStore {
  private readonly windows = new Map<string, UsageWindow[]>()
  private loaded?: Promise<void>
  private timer?: ReturnType<typeof setTimeout>
  private saving: Promise<void> = Promise.resolve()

  constructor(private readonly file: string) {}

  load(): Promise<void> {
    return this.loaded ??= (async () => {
      let value: unknown
      try { value = JSON.parse(await readFile(this.file, "utf8")) } catch { return }
      const accounts = value && typeof value === "object" && (value as { version?: unknown }).version === 1 ? (value as { accounts?: unknown }).accounts : undefined
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

  delete(id: string): void {
    if (this.windows.delete(id)) this.schedule()
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
    const text = JSON.stringify({ version: 1, accounts })
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
