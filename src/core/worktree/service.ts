// src/core/worktree/service.ts
// What the web routes call. Adds to inventory.ts: the owner rows, a size cache
// (sizes are computed only when a client lists — never on a timer), and the
// WS broadcasts every mutation needs so other devices don't go stale.
import { realpathSync } from "fs"
import { basename, dirname, join, resolve, sep } from "path"
import { makeLogger } from "../../shared/log"
import {
  canonRows, canonicalizer, deleteWorktrees, listWorktrees, ownersOf, pool, resolveId, worktreeChanges, worktreeSize,
  type DeleteResult, type OwnerRow, type WorktreeChanges, type WorktreeOwner, type WorktreeSummary,
} from "./inventory"

const log = makeLogger("worktree")

export interface WorktreeServiceDeps {
  root: string
  owners: () => OwnerRow[]
  broadcast: (frame: object) => void
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

export class WorktreeService {
  private sizes = new Map<string, { mtime: number; bytes: number }>()
  private sizing: Promise<void> = Promise.resolve()
  /** Ids queued or being sized — a repeated list() never re-queues them. */
  private sizingIds = new Set<string>()

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

  async list(): Promise<WorktreeSummary[]> {
    const list = await listWorktrees(this.deps.root, this.deps.owners())
    for (const w of list) {
      const c = this.sizes.get(w.id)
      if (c && c.mtime === w.mtime) w.bytes = c.bytes
    }
    const missing = list.filter((w) => w.bytes === undefined && !this.sizingIds.has(w.id))
    if (missing.length) {
      for (const w of missing) this.sizingIds.add(w.id)
      // Always resolves: one failed run (e.g. a throwing broadcast) must not disable sizes forever.
      this.sizing = this.sizing
        .then(() => this.computeSizes(missing))
        .catch((err) => log.warn("worktree_sizes_failed", { err: String((err as any)?.message ?? err) }))
        .finally(() => { for (const w of missing) this.sizingIds.delete(w.id) })
    }
    return list
  }

  /** Test seam: resolves once queued size work has broadcast. */
  whenSizesSettled(): Promise<void> { return this.sizing }

  private async computeSizes(items: WorktreeSummary[]): Promise<void> {
    let batch: Array<{ id: string; bytes: number }> = []
    const flush = () => { if (batch.length) { this.deps.broadcast({ type: "worktree_sizes", sizes: batch }); batch = [] } }
    await pool(items, 4, async (w) => {
      const bytes = await worktreeSize(this.deps.root, w.id).catch(() => undefined)
      if (bytes === undefined) return
      this.sizes.set(w.id, { mtime: w.mtime, bytes })
      batch.push({ id: w.id, bytes })
      if (batch.length >= 10) flush()
    })
    flush()
  }

  changes(id: string): Promise<WorktreeChanges> {
    return worktreeChanges(this.deps.root, id, this.deps.owners())
  }

  /** What an archive dialog needs for one session's workdir: the worktree id, ALL
   *  its owners (the client decides "shared" by excluding the sessions it is about
   *  to archive), the changes and the size. Undefined when workdir is not a
   *  worktree folder that exists. */
  async forWorkdir(workdir: string): Promise<{ id: string; branch?: string; owners: WorktreeOwner[]; changes: WorktreeChanges; bytes?: number } | undefined> {
    const id = this.idForWorkdir(workdir)
    if (!id) return undefined
    const rows = this.deps.owners()
    const changes = await worktreeChanges(this.deps.root, id, rows).catch(() => undefined)
    if (!changes) return undefined
    const bytes = await worktreeSize(this.deps.root, id).catch(() => undefined)
    // Owners of the whole worktree folder (not just the caller's subfolder), computed the same
    // canonical way listWorktrees/deleteOne do — never off the raw, possibly-symlinked workdir.
    let path: string
    try { path = await resolveId(this.deps.root, id) } catch { return undefined }
    const canon = canonicalizer()
    const owners = ownersOf(path, await canonRows(rows, canon))
    return { id, branch: changes.branch, owners, changes, bytes }
  }

  /** Delete exactly these ids (the ones the user confirmed). Owner rows are re-read per id
   *  inside deleteWorktrees, so a session that goes live mid-batch keeps its worktree. */
  async remove(ids: string[]): Promise<DeleteResult[]> {
    const results = await deleteWorktrees(this.deps.root, [...new Set(ids)], () => this.deps.owners())
    const removed = results.filter((r) => r.ok).map((r) => r.id)
    for (const id of removed) this.sizes.delete(id)
    // The deletes already happened: a broadcast failure is logged, never reported as a failed delete.
    if (removed.length) {
      try { this.deps.broadcast({ type: "worktrees_removed", ids: removed }) } catch (err: any) {
        log.warn("worktrees_removed_broadcast_failed", { err: String(err?.message ?? err) })
      }
    }
    return results
  }
}
