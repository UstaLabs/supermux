import { join } from "node:path"
import type { GitLiteStatus } from "./lite-status"

export interface ServiceSession {
  id: string
  workdir: string
  repo_root?: string | null
  base_branch?: string | null
  session_branch?: string | null
}

export interface WatchHandle { close(): void }

export interface GitStatusServiceDeps {
  compute(s: ServiceSession): Promise<GitLiteStatus | null>
  resolveGitDirs(s: ServiceSession): { gitDir: string; commonDir: string } | null
  watch(dir: string, onEvent: () => void): WatchHandle
  onChange(id: string, git: GitLiteStatus | null): void
  schedule(fn: () => void, ms: number): unknown
  cancel(handle: unknown): void
  debounceMs?: number
}

interface Tracked { s: ServiceSession; dirs: string[] }

/**
 * Holds per-session GitLiteStatus, recomputes it on demand / fs change / turn-end,
 * and broadcasts changes. Watches are refcounted by directory so many worktree
 * sessions of one repo share the common-dir + refs/heads watch (instance-cap safe).
 */
export class GitStatusService {
  private readonly cache = new Map<string, GitLiteStatus | null>()
  private readonly tracked = new Map<string, Tracked>()
  private readonly dirWatch = new Map<string, { handle: WatchHandle; refs: Set<string> }>()
  private readonly timers = new Map<string, unknown>()
  private readonly inflight = new Set<string>()
  private readonly rerun = new Set<string>()

  constructor(private readonly deps: GitStatusServiceDeps) {}

  get(id: string): GitLiteStatus | undefined {
    const v = this.cache.get(id)
    return v ?? undefined
  }

  /** Idempotent reconcile: track new repo sessions, untrack vanished ones. */
  sync(sessions: ServiceSession[]): void {
    const want = new Set(sessions.map((s) => s.id))
    for (const id of [...this.tracked.keys()]) if (!want.has(id)) this.untrack(id)
    for (const s of sessions) if (!this.tracked.has(s.id)) this.track(s)
  }

  scheduleRecompute(id: string): void {
    if (!this.tracked.has(id)) return
    const prev = this.timers.get(id)
    if (prev !== undefined) this.deps.cancel(prev)
    // best-effort badge: swallow recompute errors (computeLiteStatus already degrades to null)
    this.timers.set(id, this.deps.schedule(() => { this.timers.delete(id); void this.recompute(id).catch(() => {}) }, this.deps.debounceMs ?? 400))
  }

  private track(s: ServiceSession): void {
    const dirs = this.deps.resolveGitDirs(s)
    if (!dirs) { this.cache.set(s.id, null); this.tracked.set(s.id, { s, dirs: [] }); return }
    const watchDirs = [...new Set([dirs.gitDir, dirs.commonDir, join(dirs.commonDir, "refs", "heads")])]
    this.tracked.set(s.id, { s, dirs: watchDirs })
    for (const d of watchDirs) this.addDirRef(d, s.id)
    this.scheduleRecompute(s.id)
  }

  private untrack(id: string): void {
    const t = this.tracked.get(id)
    if (t) for (const d of t.dirs) this.removeDirRef(d, id)
    this.tracked.delete(id)
    const timer = this.timers.get(id)
    if (timer !== undefined) { this.deps.cancel(timer); this.timers.delete(id) }
    this.cache.delete(id)
    this.rerun.delete(id)
  }

  private addDirRef(dir: string, id: string): void {
    const w = this.dirWatch.get(dir)
    if (w) { w.refs.add(id); return }
    const handle = this.deps.watch(dir, () => this.onDirEvent(dir))
    this.dirWatch.set(dir, { handle, refs: new Set([id]) })
  }

  private removeDirRef(dir: string, id: string): void {
    const w = this.dirWatch.get(dir)
    if (!w) return
    w.refs.delete(id)
    if (w.refs.size === 0) { w.handle.close(); this.dirWatch.delete(dir) }
  }

  private onDirEvent(dir: string): void {
    const w = this.dirWatch.get(dir)
    if (!w) return
    for (const id of w.refs) this.scheduleRecompute(id)   // base-ref dir → fans out to all sharers
  }

  private async recompute(id: string): Promise<void> {
    const t = this.tracked.get(id)
    if (!t) return
    if (this.inflight.has(id)) { this.rerun.add(id); return }
    this.inflight.add(id)
    try {
      const git = await this.deps.compute(t.s)
      if (!this.tracked.has(id)) return        // untracked while computing
      this.cache.set(id, git)
      this.deps.onChange(id, git)
    } finally {
      this.inflight.delete(id)
      if (this.rerun.delete(id)) void this.recompute(id).catch(() => {})
    }
  }
}
