import { EventEmitter } from "events"
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "fs"
import { dirname, join } from "path"
import { STATE_DIR } from "../../shared/paths"
import {
  fetchClaudeUsage,
  fetchCodexUsage,
  fetchCursorUsage,
  fetchGrokUsage,
  fetchOpenCodeUsage,
  type ClaudeUsage,
  type CodexUsage,
  type CursorUsage,
  type GrokUsage,
  type OpenCodeUsage,
  type UsageResponse,
} from "./index"
import { readClaudeLocalUsage, readCodexLocalUsage } from "./local"

export type UsageProvider = "claude" | "codex" | "cursor" | "opencode" | "grok"
export type UsageSource = "live" | "agent" | "local" | "cache"

const PROVIDERS: UsageProvider[] = ["claude", "codex", "cursor", "opencode", "grok"]
const DEFAULT_STALE_MS = 5 * 60_000
const PERSIST_DEBOUNCE_MS = 250
const UNTHROTTLED = new Set<UsageProvider>(["opencode"])

export interface UsageSnapshot extends UsageResponse {
  fetchedAt: Record<UsageProvider, string | null>
  source: Record<UsageProvider, UsageSource | null>
  refreshing: UsageProvider[]
}

export interface UsageStoreOpts {
  filePath?: string
  staleMs?: number
  fetchers?: Partial<Record<UsageProvider, () => Promise<unknown | null>>>
  localReaders?: Partial<Record<UsageProvider, () => Promise<{ data: unknown; fetchedAt: Date } | null>>>
  now?: () => number
}

const DEFAULT_FETCHERS: Record<UsageProvider, () => Promise<unknown | null>> = {
  claude: () => fetchClaudeUsage(),
  codex: () => fetchCodexUsage(),
  cursor: () => fetchCursorUsage(),
  opencode: () => fetchOpenCodeUsage(),
  grok: () => fetchGrokUsage(),
}

const DEFAULT_LOCAL_READERS: Partial<
  Record<UsageProvider, () => Promise<{ data: unknown; fetchedAt: Date } | null>>
> = {
  claude: () => Promise.resolve(readClaudeLocalUsage()),
  codex: () => Promise.resolve(readCodexLocalUsage()),
}

function emptyMeta<T>(): Record<UsageProvider, T | null> {
  return { claude: null, codex: null, cursor: null, opencode: null, grok: null }
}

function nullError(provider: UsageProvider): string {
  if (provider === "claude" || provider === "grok") return "credentials not found or token expired"
  if (provider === "opencode") return "no usage recorded yet"
  return "credentials not found"
}

function errorMessage(err: unknown): string {
  if (err instanceof Error && err.message) return err.message
  return String(err)
}

function usageField(res: UsageResponse, provider: UsageProvider): unknown {
  switch (provider) {
    case "claude":
      return res.claude
    case "codex":
      return res.codex
    case "cursor":
      return res.cursor
    case "opencode":
      return res.opencode
    case "grok":
      return res.grok
  }
}

export class UsageStore extends EventEmitter {
  private readonly filePath: string
  private readonly staleMs: number
  private readonly fetchers: Partial<Record<UsageProvider, () => Promise<unknown | null>>>
  private readonly localReaders: Partial<
    Record<UsageProvider, () => Promise<{ data: unknown; fetchedAt: Date } | null>>
  >
  private readonly nowFn: () => number

  private claude: ClaudeUsage | null = null
  private codex: CodexUsage | null = null
  private cursor: CursorUsage | null = null
  private opencode: OpenCodeUsage | null = null
  private grok: GrokUsage | null = null
  private errors: Record<string, string> = {}
  private fetchedAt: Record<UsageProvider, string | null> = emptyMeta()
  private source: Record<UsageProvider, UsageSource | null> = emptyMeta()
  private lastLiveAt: Record<UsageProvider, number | null> = emptyMeta()
  private readonly refreshingSet = new Set<UsageProvider>()
  private readonly inFlight = new Map<UsageProvider, Promise<void>>()
  private readonly activityTimers = new Map<UsageProvider, ReturnType<typeof setTimeout>>()
  private persistTimer: ReturnType<typeof setTimeout> | null = null
  private disposed = false

  constructor(opts: UsageStoreOpts = {}) {
    super()
    this.filePath = opts.filePath ?? join(STATE_DIR, "usage-snapshot.json")
    this.staleMs = opts.staleMs ?? DEFAULT_STALE_MS
    this.fetchers = opts.fetchers ?? DEFAULT_FETCHERS
    this.localReaders = opts.localReaders ?? DEFAULT_LOCAL_READERS
    this.nowFn = opts.now ?? Date.now
    this.load()
  }

  snapshot(): UsageSnapshot {
    return {
      claude: this.claude,
      codex: this.codex,
      cursor: this.cursor,
      opencode: this.opencode,
      grok: this.grok,
      errors: { ...this.errors },
      fetchedAt: { ...this.fetchedAt },
      source: { ...this.source },
      refreshing: PROVIDERS.filter((p) => this.refreshingSet.has(p)),
    }
  }

  ensureFresh(): void {
    const targets = PROVIDERS.filter((p) => {
      if (!this.fetchers[p]) return false
      const at = this.fetchedAt[p]
      if (at == null) return true
      const ts = Date.parse(at)
      if (!Number.isFinite(ts)) return true
      return this.nowFn() - ts >= this.staleMs
    })
    if (targets.length > 0) void this.refresh(targets)
  }

  async refresh(providers?: UsageProvider[], opts?: { force?: boolean }): Promise<UsageSnapshot> {
    const list = providers ?? PROVIDERS
    const force = opts?.force === true
    const jobs: Promise<void>[] = []
    for (const provider of list) {
      if (!this.fetchers[provider]) continue
      if (!force && !UNTHROTTLED.has(provider)) {
        const last = this.lastLiveAt[provider]
        if (last != null && this.nowFn() - last < this.staleMs) continue
      }
      jobs.push(this.fetchOne(provider))
    }
    if (jobs.length > 0) await Promise.all(jobs)
    return this.snapshot()
  }

  apply(provider: UsageProvider, data: unknown, source: UsageSource, fetchedAt?: Date): void {
    const at = fetchedAt ?? new Date(this.nowFn())
    const atMs = at.getTime()
    if (!Number.isFinite(atMs)) return
    const held = this.fetchedAt[provider]
    if (held) {
      const heldMs = Date.parse(held)
      if (Number.isFinite(heldMs) && atMs < heldMs) return
    }
    this.setProviderData(provider, data)
    this.fetchedAt[provider] = new Date(atMs).toISOString()
    this.source[provider] = source
    if (source === "live") this.lastLiveAt[provider] = atMs
    delete this.errors[provider]
    this.schedulePersist()
    this.emitUpdated()
  }

  applyResponse(res: UsageResponse, fetchedAt?: Date): void {
    const at = fetchedAt ?? new Date(this.nowFn())
    for (const provider of PROVIDERS) {
      const data = usageField(res, provider)
      if (data != null) {
        this.apply(provider, data, "live", at)
        continue
      }
      const message = res.errors[provider]
      if (typeof message === "string") {
        this.lastLiveAt[provider] = at.getTime()
        this.setError(provider, message)
      }
    }
  }

  noteActivity(provider: UsageProvider): void {
    void this.handleActivity(provider)
  }

  async seedFromLocal(): Promise<void> {
    await Promise.all(
      PROVIDERS.map(async (provider) => {
        const reader = this.localReaders[provider]
        if (!reader) return
        try {
          const result = await reader()
          if (result) this.apply(provider, result.data, "local", result.fetchedAt)
        } catch {
          // local readers are best-effort
        }
      }),
    )
  }

  dispose(): void {
    this.disposed = true
    if (this.persistTimer) {
      clearTimeout(this.persistTimer)
      this.persistTimer = null
      this.persistNow()
    }
    for (const timer of this.activityTimers.values()) clearTimeout(timer)
    this.activityTimers.clear()
  }

  private async handleActivity(provider: UsageProvider): Promise<void> {
    if (this.disposed) return
    if (provider === "claude") {
      const reader = this.localReaders.claude
      if (reader) {
        try {
          const local = await reader()
          if (local) this.apply("claude", local.data, "local", local.fetchedAt)
        } catch {
          // ignore
        }
      }
    }
    if (this.disposed) return
    const last = this.lastLiveAt[provider]
    const now = this.nowFn()
    if (last == null || now - last >= this.staleMs) {
      this.clearActivityTimer(provider)
      void this.refresh([provider])
      return
    }
    this.armActivityTimer(provider, Math.max(0, last + this.staleMs - now))
  }

  private armActivityTimer(provider: UsageProvider, delay: number): void {
    this.clearActivityTimer(provider)
    const timer = setTimeout(() => {
      this.activityTimers.delete(provider)
      if (this.disposed) return
      // Remainder already elapsed in wall-clock time (DESIGN.md 5b). Force so
      // an injected now() that does not advance with setTimeout still fetches.
      void this.refresh([provider], { force: true })
    }, delay)
    this.activityTimers.set(provider, timer)
  }

  private clearActivityTimer(provider: UsageProvider): void {
    const timer = this.activityTimers.get(provider)
    if (!timer) return
    clearTimeout(timer)
    this.activityTimers.delete(provider)
  }

  private fetchOne(provider: UsageProvider): Promise<void> {
    const existing = this.inFlight.get(provider)
    if (existing) return existing
    this.refreshingSet.add(provider)
    this.emitUpdated()
    const job = this.runFetch(provider).finally(() => {
      this.refreshingSet.delete(provider)
      this.inFlight.delete(provider)
      this.emitUpdated()
    })
    this.inFlight.set(provider, job)
    return job
  }

  private async runFetch(provider: UsageProvider): Promise<void> {
    const fetcher = this.fetchers[provider]
    if (!fetcher) return
    try {
      const data = await fetcher()
      this.lastLiveAt[provider] = this.nowFn()
      if (data != null) this.apply(provider, data, "live", new Date(this.nowFn()))
      else this.setError(provider, nullError(provider))
    } catch (err) {
      this.lastLiveAt[provider] = this.nowFn()
      this.setError(provider, errorMessage(err))
    }
  }

  private setProviderData(provider: UsageProvider, data: unknown): void {
    switch (provider) {
      case "claude":
        this.claude = data as ClaudeUsage
        break
      case "codex":
        this.codex = data as CodexUsage
        break
      case "cursor":
        this.cursor = data as CursorUsage
        break
      case "opencode":
        this.opencode = data as OpenCodeUsage
        break
      case "grok":
        this.grok = data as GrokUsage
        break
    }
  }

  private setError(provider: UsageProvider, message: string): void {
    if (this.errors[provider] === message) return
    this.errors[provider] = message
    this.schedulePersist()
    this.emitUpdated()
  }

  private emitUpdated(): void {
    this.emit("updated", this.snapshot())
  }

  private load(): void {
    if (!existsSync(this.filePath)) return
    let raw: any
    try {
      raw = JSON.parse(readFileSync(this.filePath, "utf-8"))
    } catch {
      return
    }
    if (!raw || typeof raw !== "object") return

    if (raw.claude !== undefined) this.claude = raw.claude
    if (raw.codex !== undefined) this.codex = raw.codex
    if (raw.cursor !== undefined) this.cursor = raw.cursor
    if (raw.opencode !== undefined) this.opencode = raw.opencode
    if (raw.grok !== undefined) this.grok = raw.grok
    if (raw.errors && typeof raw.errors === "object") this.errors = { ...raw.errors }

    for (const provider of PROVIDERS) {
      const fetched = raw.fetchedAt?.[provider]
      if (typeof fetched === "string") this.fetchedAt[provider] = fetched
      else if (fetched === null) this.fetchedAt[provider] = null

      const live = raw.lastLiveAt?.[provider]
      if (typeof live === "number" && Number.isFinite(live)) {
        this.lastLiveAt[provider] = live
      } else if (typeof live === "string") {
        const ts = Date.parse(live)
        if (Number.isFinite(ts)) this.lastLiveAt[provider] = ts
      } else if (raw.source?.[provider] === "live" && typeof fetched === "string") {
        const ts = Date.parse(fetched)
        if (Number.isFinite(ts)) this.lastLiveAt[provider] = ts
      }

      if (this.fetchedAt[provider] != null || this.providerData(provider) != null) {
        this.source[provider] = "cache"
      }
    }
  }

  private providerData(provider: UsageProvider): unknown {
    switch (provider) {
      case "claude":
        return this.claude
      case "codex":
        return this.codex
      case "cursor":
        return this.cursor
      case "opencode":
        return this.opencode
      case "grok":
        return this.grok
    }
  }

  private schedulePersist(): void {
    if (this.disposed) {
      this.persistNow()
      return
    }
    if (this.persistTimer) clearTimeout(this.persistTimer)
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null
      this.persistNow()
    }, PERSIST_DEBOUNCE_MS)
  }

  private persistNow(): void {
    const lastLiveAt: Record<UsageProvider, string | null> = emptyMeta()
    for (const provider of PROVIDERS) {
      const ms = this.lastLiveAt[provider]
      lastLiveAt[provider] = ms == null ? null : new Date(ms).toISOString()
    }
    const payload = {
      claude: this.claude,
      codex: this.codex,
      cursor: this.cursor,
      opencode: this.opencode,
      grok: this.grok,
      errors: this.errors,
      fetchedAt: this.fetchedAt,
      source: this.source,
      lastLiveAt,
    }
    try {
      mkdirSync(dirname(this.filePath), { recursive: true })
      const tmp = `${this.filePath}.${process.pid}.tmp`
      writeFileSync(tmp, JSON.stringify(payload))
      renameSync(tmp, this.filePath)
    } catch {
      // persistence is best-effort
    }
  }
}

let singleton: UsageStore | null = null

export function getUsageStore(): UsageStore {
  if (!singleton) singleton = new UsageStore()
  return singleton
}
