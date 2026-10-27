// src/core/worktree/service.ts
// What the web routes call. Adds to inventory.ts: the owner rows, a cached inventory and size
// cache, and the WS broadcasts every mutation needs so other devices don't go stale.
//
// Listing never scans: it answers from the cache. The cache is filled by one background scan
// (at start and on an explicit refresh) and kept current per worktree — a shallow watch on the
// root, each slug folder and each worktree folder, plus `touch` when a session ends a turn.
// Worktrees whose repository is gone are summarized as one `orphans` group, not listed.
import { realpathSync } from "fs"
import { realpath } from "fs/promises"
import { basename, dirname, join, resolve, sep } from "path"
import { makeLogger } from "../../shared/log"
import {
  canonRows, canonicalizer, deleteWorktrees, listWorktrees, ownersOf, pool, resolveId, slugEntries, summarizeWorktree,
  worktreeChanges, worktreeSize, REPO_GONE,
  type DeleteResult, type OwnerRow, type WorktreeChanges, type WorktreeOwner, type WorktreeSummary,
} from "./inventory"

const log = makeLogger("worktree")

export interface WatchHandle { close(): void }

export interface WorktreeServiceDeps {
  root: string
  owners: () => OwnerRow[]
  broadcast: (frame: object) => void
  /** Non-recursive directory watch; `file` is the changed entry's name when the OS reports it.
   *  Omitted = no watching (tests drive `touch` directly). */
  watch?: (dir: string, onEvent: (file: string | null) => void) => WatchHandle
  /** Delay before a touched worktree is re-inspected; bursts of events collapse into one. */
  debounceMs?: number
}

export interface OrphanSummary {
  count: number
  /** Sum of the sizes measured so far; `sized` says how many of `count` that covers. */
  bytes: number
  sized: number
  /** Where the missing repositories used to live, most first. */
  byLocation: Array<{ dir: string; count: number }>
}

export interface WorktreeListing {
  worktrees: WorktreeSummary[]
  orphans: OrphanSummary
  /** A background scan is running; the lists may still grow. */
  scanning: boolean
}

/** Sync realpath of the longest existing ancestor + the missing rest (no trailing slash).
 *  Same shape as inventory's async `canonicalizer`, but sync: idForWorkdir is a hot, sync
 *  lookup (used per-request in routes) and has no reason to await a filesystem round trip. */
function canonSync(p: string): string {
  const abs = resolve(p)
  try {
    return realpathSync(abs)
  } catch {
    const parent = dirname(abs)
    return parent === abs ? abs : join(canonSync(parent), basename(abs))
  }
}

const isOrphan = (w: WorktreeSummary) => w.error === REPO_GONE

export class WorktreeService {
  private entries = new Map<string, WorktreeSummary>()
  private sizes = new Map<string, { mtime: number; bytes: number }>()
  private scan: Promise<void> | null = null
  private scanned = false
  private sizing: Promise<void> = Promise.resolve()
  /** Ids queued or being sized — never queued twice. */
  private sizingIds = new Set<string>()
  private timers = new Map<string, ReturnType<typeof setTimeout>>()
  private watches = new Map<string, WatchHandle>()

  constructor(private readonly deps: WorktreeServiceDeps) {}

  /** "<slug>/<uuid>" when workdir is a worktree folder under root, or any path inside one
   *  (a session's workdir can be a subfolder). Resolves symlinks on both sides so an aliased
   *  root or a symlinked workdir still matches. */
  idForWorkdir(workdir: string): string | undefined {
    const root = canonSync(this.deps.root)
    const target = canonSync(workdir)
    if (target !== root && !target.startsWith(root + sep)) return undefined
    const rel = target === root ? "" : target.slice(root.length + 1)
    if (!rel) return undefined
    const parts = rel.split(sep)
    return parts.length >= 2 && parts[0] && parts[1] ? `${parts[0]}/${parts[1]}` : undefined
  }

  /** Kick off the first scan in the background. */
  start(): void { void this.rescan() }

  stop(): void {
    for (const t of this.timers.values()) clearTimeout(t)
    this.timers.clear()
    for (const w of this.watches.values()) w.close()
    this.watches.clear()
  }

  /** Full scan of the root, replacing the cache. Concurrent calls share one run. */
  rescan(): Promise<void> {
    if (this.scan) return this.scan
    this.scan = (async () => {
      const list = await listWorktrees(this.deps.root, this.deps.owners())
      this.entries = new Map(list.map((w) => [w.id, w]))
      for (const id of [...this.sizes.keys()]) if (!this.entries.has(id)) this.sizes.delete(id)
      this.scanned = true
      this.syncWatches()
      this.queueSizes(list.filter((w) => this.sizes.get(w.id)?.mtime !== w.mtime))
    })()
      .catch((err) => log.warn("worktree_scan_failed", { err: String((err as any)?.message ?? err) }))
      .finally(() => { this.scan = null })
    return this.scan
  }

  /** Test seam: resolves once the running scan (if any) is done. */
  whenScanned(): Promise<void> { return this.scan ?? Promise.resolve() }

  /** Answers from the cache. The first call (or `refresh`) starts a background scan. */
  async list(opts: { refresh?: boolean } = {}): Promise<WorktreeListing> {
    if (opts.refresh || (!this.scanned && !this.scan)) void this.rescan()
    const all = [...this.entries.values()]
    const kept = all.filter((w) => !isOrphan(w))
    const orphans = all.filter(isOrphan)
    const byLocation = new Map<string, number>()
    let bytes = 0
    let sized = 0
    for (const w of orphans) {
      const dir = w.repoRoot ? dirname(w.repoRoot) : "unknown"
      byLocation.set(dir, (byLocation.get(dir) ?? 0) + 1)
      const b = this.sizes.get(w.id)?.bytes
      if (b !== undefined) { bytes += b; sized++ }
    }
    return {
      worktrees: await this.present(kept),
      orphans: {
        count: orphans.length, bytes, sized,
        byLocation: [...byLocation].map(([dir, count]) => ({ dir, count })).sort((a, b) => b.count - a.count),
      },
      scanning: this.scan !== null,
    }
  }

  /** A page of the orphans group, in id order. */
  async orphans(offset: number, limit: number): Promise<{ worktrees: WorktreeSummary[]; total: number }> {
    const all = [...this.entries.values()].filter(isOrphan).sort((a, b) => a.id.localeCompare(b.id))
    return { worktrees: await this.present(all.slice(offset, offset + limit)), total: all.length }
  }

  /** Cached rows with fresh owners (sessions archive and resume between scans) and known sizes. */
  private async present(rows: WorktreeSummary[]): Promise<WorktreeSummary[]> {
    const realRoot = await realpath(this.deps.root).catch(() => undefined)
    if (!realRoot) return []
    const owners = await canonRows(this.deps.owners(), canonicalizer())
    return rows.map((w) => {
      const c = this.sizes.get(w.id)
      return { ...w, owners: ownersOf(join(realRoot, w.id), owners), ...(c ? { bytes: c.bytes } : {}) }
    })
  }

  /** Something in this session's worktree may have changed: re-inspect it shortly. */
  touchWorkdir(workdir: string): void {
    const id = this.idForWorkdir(workdir)
    if (id) this.touch(id)
  }

  touch(id: string): void {
    const prev = this.timers.get(id)
    if (prev) clearTimeout(prev)
    this.timers.set(id, setTimeout(() => {
      this.timers.delete(id)
      void this.refresh(id).catch((err) => log.warn("worktree_refresh_failed", { id, err: String((err as any)?.message ?? err) }))
    }, this.deps.debounceMs ?? 2000))
  }

  /** Re-inspect one worktree now: update or drop its cache entry and re-measure it. */
  async refresh(id: string): Promise<void> {
    const w = await summarizeWorktree(this.deps.root, id, this.deps.owners())
    if (!w) {
      if (this.entries.delete(id)) this.dropped([id])
      return
    }
    const isNew = !this.entries.has(id)
    this.entries.set(id, w)
    this.sizes.delete(id)
    if (isNew) this.syncWatches()
    this.queueSizes([w])
  }

  private dropped(ids: string[]): void {
    for (const id of ids) this.sizes.delete(id)
    this.syncWatches()
    try { this.deps.broadcast({ type: "worktrees_removed", ids }) } catch (err: any) {
      log.warn("worktrees_removed_broadcast_failed", { err: String(err?.message ?? err) })
    }
  }

  /** Root + the slug and worktree folders of every non-orphan worktree; orphans never change. */
  private syncWatches(): void {
    const watch = this.deps.watch
    if (!watch) return
    const root = this.deps.root
    const want = new Map<string, () => WatchHandle>()
    want.set("root", () => watch(root, (slug) => { if (slug) void this.refreshSlug(slug) }))
    for (const w of this.entries.values()) {
      if (isOrphan(w)) continue
      const slug = w.id.split("/")[0]!
      want.set(`slug:${slug}`, () => watch(join(root, slug), (name) => { if (name) this.touch(`${slug}/${name}`) }))
      want.set(`wt:${w.id}`, () => watch(join(root, w.id), () => this.touch(w.id)))
    }
    for (const [key, h] of this.watches) if (!want.has(key)) { h.close(); this.watches.delete(key) }
    for (const [key, open] of want) if (!this.watches.has(key)) this.watches.set(key, open())
  }

  /** A slug folder appeared, changed or went away under the root. */
  private async refreshSlug(slug: string): Promise<void> {
    const now = new Set(await slugEntries(this.deps.root, slug))
    const known = [...this.entries.keys()].filter((id) => id.startsWith(`${slug}/`))
    for (const id of now) if (!this.entries.has(id)) this.touch(id)
    const gone = known.filter((id) => !now.has(id))
    if (gone.length) {
      for (const id of gone) this.entries.delete(id)
      this.dropped(gone)
    }
  }

  /** Measure in the background, real worktrees before orphans, two at a time. */
  private queueSizes(items: WorktreeSummary[]): void {
    const todo = items.filter((w) => !this.sizingIds.has(w.id))
      .sort((a, b) => Number(isOrphan(a)) - Number(isOrphan(b)))
    if (!todo.length) return
    for (const w of todo) this.sizingIds.add(w.id)
    // Always resolves: one failed run (e.g. a throwing broadcast) must not disable sizes forever.
    this.sizing = this.sizing
      .then(() => this.computeSizes(todo))
      .catch((err) => log.warn("worktree_sizes_failed", { err: String((err as any)?.message ?? err) }))
      .finally(() => { for (const w of todo) this.sizingIds.delete(w.id) })
  }

  /** Test seam: resolves once queued size work has broadcast. */
  whenSizesSettled(): Promise<void> { return this.sizing }

  private async computeSizes(items: WorktreeSummary[]): Promise<void> {
    let batch: Array<{ id: string; bytes: number }> = []
    const flush = () => { if (batch.length) { this.deps.broadcast({ type: "worktree_sizes", sizes: batch }); batch = [] } }
    await pool(items, 2, async (w) => {
      if (!this.entries.has(w.id) && this.scanned) return
      const bytes = await worktreeSize(this.deps.root, w.id).catch(() => undefined)
      if (bytes === undefined) return
      this.sizes.set(w.id, { mtime: w.mtime, bytes })
      batch.push({ id: w.id, bytes })
      if (batch.length >= 50) flush()
    })
    flush()
  }

  changes(id: string): Promise<WorktreeChanges> {
    return worktreeChanges(this.deps.root, id, this.deps.owners())
  }

  /** The size now: cached, else measured (and cached). */
  async size(id: string): Promise<number | undefined> {
    const c = this.sizes.get(id)
    if (c) return c.bytes
    const bytes = await worktreeSize(this.deps.root, id).catch(() => undefined)
    if (bytes !== undefined) this.sizes.set(id, { mtime: this.entries.get(id)?.mtime ?? 0, bytes })
    return bytes
  }

  /** What an archive dialog needs for one session's workdir: the worktree id, ALL
   *  its owners (the client decides "shared" by excluding the sessions it is about
   *  to archive), the changes and — only when already known — the size; the dialog asks
   *  for it separately so it never waits on a folder walk. Undefined when workdir is not a
   *  worktree folder that exists. */
  async forWorkdir(workdir: string): Promise<{ id: string; branch?: string; owners: WorktreeOwner[]; changes: WorktreeChanges; bytes?: number } | undefined> {
    const id = this.idForWorkdir(workdir)
    if (!id) return undefined
    const rows = this.deps.owners()
    const changes = await worktreeChanges(this.deps.root, id, rows).catch(() => undefined)
    if (!changes) return undefined
    // Owners of the whole worktree folder (not just the caller's subfolder), computed the same
    // canonical way listWorktrees/deleteOne do — never off the raw, possibly-symlinked workdir.
    let path: string
    try { path = await resolveId(this.deps.root, id) } catch { return undefined }
    const canon = canonicalizer()
    const owners = ownersOf(path, await canonRows(rows, canon))
    const bytes = this.sizes.get(id)?.bytes
    return { id, branch: changes.branch, owners, changes, ...(bytes !== undefined ? { bytes } : {}) }
  }

  /** Delete exactly these ids (the ones the user confirmed). Owner rows are re-read per id
   *  inside deleteWorktrees, so a session that goes live mid-batch keeps its worktree. */
  async remove(ids: string[]): Promise<DeleteResult[]> {
    const results = await deleteWorktrees(this.deps.root, [...new Set(ids)], () => this.deps.owners())
    const removed = results.filter((r) => r.ok).map((r) => r.id)
    for (const id of removed) this.entries.delete(id)
    // The deletes already happened: a broadcast failure is logged, never reported as a failed delete.
    if (removed.length) this.dropped(removed)
    return results
  }
}
