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
  /** Test-only hook run once a read has fully gathered its data, just before it is compared and
   *  committed; used to hold a read's promise open so tests can force overlap with another
   *  load() call deterministically. */
  beforeRead?: (real: string) => Promise<void>
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

/** Same-listing comparison used to decide `changed`. A dir-ish entry's own mtime is ignored: it
 *  ticks whenever its children change, which is noise here (nothing in this listing shows it). */
function entrySameForChange(a: FsEntry, b: FsEntry): boolean {
  if (a.name !== b.name || a.type !== b.type || a.target !== b.target || a.ignored !== b.ignored || a.git !== b.git) return false
  if (isDirish(a)) return true
  return a.size === b.size && a.mtime === b.mtime
}

function sameListing(prev: CachedDir, entries: FsEntry[], truncated: { total: number } | undefined): boolean {
  if (prev.entries.length !== entries.length) return false
  if ((prev.truncated?.total ?? -1) !== (truncated?.total ?? -1)) return false
  for (let i = 0; i < entries.length; i++) {
    if (!entrySameForChange(prev.entries[i]!, entries[i]!)) return false
  }
  return true
}

/** Folder listings keyed by real path. Loads are async, single-flight, compared, versioned. */
export class DirCache {
  private readonly dirs = new Map<string, CachedDir>() // insertion order = LRU order
  private readonly inflight = new Map<string, Promise<{ snap: CachedDir; changed: boolean }>>()
  /** A pending "one more read" promise for callers that arrived while a read was already in flight. */
  private readonly joiners = new Map<string, Promise<{ snap: CachedDir; changed: boolean }>>()
  /** Bumped by forget() so a read that was already in flight knows not to re-insert its result. */
  private readonly gen = new Map<string, number>()
  private readonly pins = new Map<string, number>()
  private counter = 0
  /** Number of real directory reads performed (tests). */
  readCount = 0
  /** Number of entries lstat-ed (tests): a truncated folder lstats only the entries it keeps. */
  entryStatCount = 0

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

  forget(real: string): void {
    this.dirs.delete(real)
    this.gen.set(real, (this.gen.get(real) ?? 0) + 1)
  }

  /** Cached listing, or load it. */
  async getOrLoad(real: string): Promise<CachedDir> {
    return this.get(real) ?? (await this.load(real)).snap
  }

  /**
   * Read the folder now (joining an in-flight read) and compare with the previous listing.
   *
   * If a read of `real` is already in flight when this is called, that read may predate whatever
   * change triggered this call, so we don't just hand back its result: we wait for it, then run
   * exactly one more read, and resolve with THAT result. Every caller that arrives while the first
   * read (or that one extra read) is in flight shares this single follow-up read and its result;
   * only the caller that started the original read gets that read's own result.
   */
  load(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    const joiner = this.joiners.get(real)
    if (joiner) return joiner
    const running = this.inflight.get(real)
    if (running) {
      const j = running.then(() => this.runRead(real))
      this.joiners.set(real, j)
      // `.catch` keeps this bookkeeping branch from surfacing as an unhandled rejection; callers
      // still get the error through `j` itself.
      j.finally(() => { if (this.joiners.get(real) === j) this.joiners.delete(real) }).catch(() => {})
      return j
    }
    return this.runRead(real)
  }

  private runRead(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    const p = this.read(real).finally(() => { this.inflight.delete(real) })
    this.inflight.set(real, p)
    return p
  }

  private async read(real: string): Promise<{ snap: CachedDir; changed: boolean }> {
    const startGen = this.gen.get(real) ?? 0
    this.readCount++
    let dirents: import("fs").Dirent[]
    try {
      dirents = await readdir(real, { withFileTypes: true })
    } catch (e) {
      this.dirs.delete(real)
      throw toFsError(e)
    }
    const max = this.opts.maxEntries ?? 5_000
    const conc = this.opts.statConcurrency ?? 32
    // A huge folder is cut BEFORE the per-entry lstat: order the names (dirs first, from the dirent
    // types; only symlinks need a stat to know whether they point at a folder), keep `max`, lstat those.
    const truncated = dirents.length > max ? { total: dirents.length } : undefined
    const names = truncated ? (await this.firstNames(real, dirents, max, conc)) : dirents.map((d) => d.name)
    const all = await mapLimit(names, conc, async (name) => this.entry(real, name))
    const kept = sortEntries(all.filter((e): e is FsEntry => e !== null))
    await this.opts.repo.annotate(real, kept)
    // Test-only hook, run once this read's data is fully gathered but before it is compared and
    // committed — used to hold a read open (its promise unresolved) so tests can force overlap
    // with another load() call without racing real disk timing.
    if (this.opts.beforeRead) await this.opts.beforeRead(real)

    const prev = this.dirs.get(real)
    if (prev !== undefined && sameListing(prev, kept, truncated)) {
      this.get(real) // touch LRU
      return { snap: prev, changed: false }
    }
    const snap: CachedDir = { real, version: `${this.opts.bootId}:${++this.counter}`, entries: kept, ...(truncated ? { truncated } : {}) }
    // A forget() during this read bumps the generation counter; a read that started before it
    // must still hand its result to its callers, but must not resurrect the (evicted) entry.
    if ((this.gen.get(real) ?? 0) === startGen) {
      this.dirs.delete(real)
      this.dirs.set(real, snap)
      this.evict()
    }
    return { snap, changed: true }
  }

  /** The first `max` names of a folder in listing order (dirs first, then by name), without lstat-ing all. */
  private async firstNames(dir: string, dirents: import("fs").Dirent[], max: number, conc: number): Promise<string[]> {
    const dirish = await mapLimit(dirents, conc, async (d) => {
      if (d.isDirectory()) return true
      if (!d.isSymbolicLink()) return false
      return stat(join(dir, d.name)).then((s) => s.isDirectory(), () => false)
    })
    const order = dirents.map((d, i) => ({ name: d.name, dir: dirish[i]! }))
    order.sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : collator.compare(a.name, b.name) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)))
    return order.slice(0, max).map((o) => o.name)
  }

  private async entry(dir: string, name: string): Promise<FsEntry | null> {
    const full = join(dir, name)
    this.entryStatCount++
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
