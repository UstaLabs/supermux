import { readdir, lstat, stat } from "fs/promises"
import { join } from "path"
import { toFsError } from "./errors"
import { mapLimit } from "./pool"
import type { RepoInfoCache } from "./repo-info"
import type { FsEntry } from "./types"

export interface CachedDir {
  real: string
  version: string
  entries: FsEntry[]
  truncated?: { total: number }
}

export interface DirCacheOpts {
  repo: RepoInfoCache
  bootId: string
  maxEntries?: number
  maxDirs?: number
  statConcurrency?: number
}

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" })
const isDirish = (e: FsEntry) => e.type === "dir" || e.target === "dir"

export function sortEntries(entries: FsEntry[]): FsEntry[] {
  return entries.sort((a, b) => {
    const da = isDirish(a), db = isDirish(b)
    if (da !== db) return da ? -1 : 1
    return collator.compare(a.name, b.name) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  })
}

/** Folder listings keyed by real path. Loads are async, single-flight, compared, versioned. */
export class DirCache {
  private readonly dirs = new Map<string, CachedDir>() // insertion order = LRU order
  private readonly inflight = new Map<string, Promise<{ snap: CachedDir; changed: boolean }>>()
  private readonly pins = new Map<string, number>()
  private counter = 0
  /** Number of real directory reads performed (tests). */
  readCount = 0

  constructor(private readonly opts: DirCacheOpts) {}

  get(real: string): CachedDir | undefined {
    const d = this.dirs.get(real)
    if (d) { this.dirs.delete(real); this.dirs.set(real, d) }
    return d
  }

  pin(real: string): void { this.pins.set(real, (this.pins.get(real) ?? 0) + 1) }
  unpin(real: string): void {
    const n = (this.pins.get(real) ?? 0) - 1
    if (n <= 0) this.pins.delete(real)
    else this.pins.set(real, n)
    this.evict()
  }
  isPinned(real: string): boolean { return this.pins.has(real) }

  forget(real: string): void { this.dirs.delete(real) }

  /** Cached listing, or load it. */
  async getOrLoad(real: string): Promise<CachedDir> {
    return this.get(real) ?? (await this.load(real)).snap
  }

  /** Read the folder now (joining an in-flight read) and compare with the previous listing. */
  load(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    const running = this.inflight.get(real)
    if (running) return running
    const p = this.read(real).finally(() => this.inflight.delete(real))
    this.inflight.set(real, p)
    return p
  }

  private async read(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    this.readCount++
    let dirents: import("fs").Dirent[]
    try {
      dirents = await readdir(real, { withFileTypes: true })
    } catch (e) {
      this.dirs.delete(real)
      throw toFsError(e)
    }
    const all = await mapLimit(dirents, this.opts.statConcurrency ?? 32, async (d) => this.entry(real, d.name))
    const entries = sortEntries(all.filter((e): e is FsEntry => e !== null))
    const max = this.opts.maxEntries ?? 5_000
    const truncated = entries.length > max ? { total: entries.length } : undefined
    const kept = truncated ? entries.slice(0, max) : entries
    await this.opts.repo.annotate(real, kept)

    const prev = this.dirs.get(real)
    const same = prev !== undefined
      && JSON.stringify(prev.entries) === JSON.stringify(kept)
      && JSON.stringify(prev.truncated) === JSON.stringify(truncated)
    if (same) {
      this.get(real) // touch LRU
      return { snap: prev!, changed: false }
    }
    const snap: CachedDir = { real, version: `${this.opts.bootId}:${++this.counter}`, entries: kept, ...(truncated ? { truncated } : {}) }
    this.dirs.delete(real)
    this.dirs.set(real, snap)
    this.evict()
    return { snap, changed: true }
  }

  private async entry(dir: string, name: string): Promise<FsEntry | null> {
    const full = join(dir, name)
    let l: import("fs").Stats
    try { l = await lstat(full) } catch { return null }
    if (l.isSymbolicLink()) {
      try {
        const s = await stat(full)
        const target = s.isDirectory() ? "dir" : "file"
        return target === "file"
          ? { name, type: "symlink", target, size: s.size, mtime: Math.round(s.mtimeMs), ignored: false }
          : { name, type: "symlink", target, mtime: Math.round(s.mtimeMs), ignored: false }
      } catch {
        return { name, type: "symlink", mtime: Math.round(l.mtimeMs), ignored: false }
      }
    }
    if (l.isDirectory()) return { name, type: "dir", mtime: Math.round(l.mtimeMs), ignored: false }
    return { name, type: "file", size: l.size, mtime: Math.round(l.mtimeMs), ignored: false }
  }

  private evict(): void {
    const max = this.opts.maxDirs ?? 2_000
    if (this.dirs.size <= max) return
    for (const key of this.dirs.keys()) {
      if (this.dirs.size <= max) break
      if (!this.pins.has(key)) this.dirs.delete(key)
    }
  }
}
