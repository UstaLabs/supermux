import type { Database as Db } from "bun:sqlite"
import type { ProjectStore } from "./store"
import type { ProjectImages } from "./images"
import { type ProjectDto, type ProjectLocationRecord, type ProjectRecord, projectDto } from "./types"
import { effectiveLocation, normalizeLocationPath, pathLabel } from "./paths"

export class ProjectConflictError extends Error {
  constructor(readonly projectId: string) { super("location already belongs to a project") }
}
export class ProjectNotFoundError extends Error {
  constructor(message = "project not found") { super(message) }
}

type Located = { workdir: string; repo_root?: string | null }

export type ProjectServiceOptions = {
  /** Home directory, for default project names (pathLabel). */
  home: string
  /** Managed worktrees root. A workdir under it with no repo_root stays unresolved. */
  managedWorktreesRoot: string
  images: ProjectImages
}

/**
 * Projects: metadata, locations and path matching (spec "Resolution and boundaries").
 *
 * Membership is never stored. A workspace belongs to the project that owns the
 * location equal to its normalized `repo_root ?? workdir` — exact match only, no
 * prefix or Git-remote matching. Reads (resolve, list, membership) never write and
 * never touch the filesystem; only ensureLocation, reconcile and the explicit
 * mutations below write.
 */
export class ProjectService {
  private readonly worktreesRoot: string

  constructor(private readonly store: ProjectStore, private readonly opts: ProjectServiceOptions) {
    this.worktreesRoot = normalizeLocationPath(opts.managedWorktreesRoot) ?? opts.managedWorktreesRoot
  }

  /** Read-only. Never writes, never stats. */
  resolve(w: Located): string | undefined {
    const path = effectiveLocation(w, this.worktreesRoot)
    return path ? this.store.findLocationByPath(path)?.project_id : undefined
  }

  /**
   * Idempotent + transactional: an unknown location gets a new project named
   * pathLabel(path, home), appended to the order. Undefined for an unresolvable path.
   * bun:sqlite is synchronous, so the transaction cannot interleave with another
   * registration of the same path; the UNIQUE(path) constraint backs that up, and
   * a failure rolls the new project back with it — no orphans.
   */
  ensureLocation(w: Located): { projectId: string; created: boolean } | undefined {
    const path = effectiveLocation(w, this.worktreesRoot)
    if (!path) return undefined
    return this.store.db.transaction(() => {
      const found = this.store.findLocationByPath(path)
      if (found) return { projectId: found.project_id, created: false }
      const p = this.store.create({ name: pathLabel(path, this.opts.home), sort_order: this.store.maxSortOrder() + 1 })
      this.store.addLocation(p.id, path)
      return { projectId: p.id, created: true }
    })()
  }

  /**
   * Startup backfill/reconciliation over workspaces (active and archived) plus legacy
   * sessions without a workspace. Registers every valid effective location not yet
   * known, one project each, in pathLabel order after the current max sort_order, so
   * the first run preserves today's alphabetical grouping. Persisted values only —
   * never reads the filesystem. Returns the created project ids.
   */
  reconcile(db: Db): string[] {
    const rows = [
      ...(db.query("SELECT workdir, repo_root FROM workspaces").all() as Located[]),
      ...(db.query("SELECT workdir, repo_root FROM sessions WHERE workspace_id IS NULL").all() as Located[]),
    ]
    const paths = new Set<string>()
    for (const r of rows) {
      const p = effectiveLocation(r, this.worktreesRoot)
      if (p) paths.add(p)
    }
    return this.store.db.transaction(() => {
      const fresh = [...paths]
        .filter((p) => !this.store.findLocationByPath(p))
        .map((p) => ({ path: p, label: pathLabel(p, this.opts.home) }))
        .sort((a, b) => cmp(a.label, b.label) || cmp(a.path, b.path))
      let order = this.store.maxSortOrder()
      return fresh.map(({ path, label }) => {
        const p = this.store.create({ name: label, sort_order: ++order })
        this.store.addLocation(p.id, path)
        return p.id
      })
    })()
  }

  create(name: string): ProjectDto {
    const n = requireName(name)
    const p = this.store.create({ name: n, sort_order: this.store.maxSortOrder() + 1 })
    return projectDto(p, [])
  }

  rename(id: string, name: string): ProjectDto {
    const n = requireName(name)
    this.mustGet(id)
    this.store.rename(id, n)
    return this.get(id)!
  }

  /**
   * Validates ids (all known, no duplicates), then appends any project not listed
   * after the listed ones, keeping their relative order — so the write is always a
   * full, dense 0..n-1 permutation regardless of how partial `ids` is.
   */
  reorder(ids: string[]): void {
    const seen = new Set<string>()
    for (const id of ids) {
      if (!this.store.getById(id)) throw new Error(`unknown project id: ${id}`)
      if (seen.has(id)) throw new Error(`duplicate project id: ${id}`)
      seen.add(id)
    }
    const rest = this.store.list().map((p) => p.id).filter((id) => !seen.has(id))
    this.store.reorder([...ids, ...rest])
  }

  /**
   * Registers `rawPath` on the project. An already-owned path is a conflict naming its
   * owner — ownership is never silently moved; use moveLocation for that. Adding a
   * path the project already owns is a no-op.
   */
  addLocation(projectId: string, rawPath: string): ProjectDto {
    const path = normalizeLocationPath(rawPath)
    if (!path) throw new Error("absolute path required")
    return this.store.db.transaction(() => {
      this.mustGet(projectId)
      const found = this.store.findLocationByPath(path)
      if (found && found.project_id !== projectId) throw new ProjectConflictError(found.project_id)
      if (!found) this.store.addLocation(projectId, path)
      return this.get(projectId)!
    })()
  }

  /** Reassigns a location, keeping its id. The source project stays, even when empty. Returns the target. */
  moveLocation(locationId: string, projectId: string): ProjectDto {
    return this.store.db.transaction(() => {
      if (!this.store.getLocation(locationId)) throw new ProjectNotFoundError("location not found")
      this.mustGet(projectId)
      this.store.moveLocation(locationId, projectId)
      return this.get(projectId)!
    })()
  }

  /** Writes the new file first, then points the row at it, then deletes the old file. */
  setImage(id: string, bytes: Uint8Array, mime: string): ProjectDto {
    const old = this.mustGet(id).image_id
    const imageId = this.opts.images.write(bytes, mime)
    try {
      this.store.setImage(id, imageId)
    } catch (e) {
      this.opts.images.remove(imageId)
      throw e
    }
    if (old) this.opts.images.remove(old)
    return this.get(id)!
  }

  clearImage(id: string): ProjectDto {
    const old = this.mustGet(id).image_id
    this.store.setImage(id, null)
    if (old) this.opts.images.remove(old)
    return this.get(id)!
  }

  /** Where the project's image lives. Does not stat — the caller handles a missing file. */
  imageFile(id: string): { path: string; mime: string } | undefined {
    const imageId = this.store.getById(id)?.image_id
    const path = imageId ? this.opts.images.path(imageId) : undefined
    return imageId && path ? { path, mime: this.opts.images.mimeOf(imageId) } : undefined
  }

  get(id: string): ProjectDto | undefined {
    const p = this.store.getById(id)
    return p ? projectDto(p, this.store.listLocations(id)) : undefined
  }

  /** Every project in display order, each with its locations ordered by path. */
  list(): ProjectDto[] {
    const byProject = new Map<string, ProjectLocationRecord[]>()
    for (const l of this.store.allLocations()) {
      const xs = byProject.get(l.project_id)
      if (xs) xs.push(l)
      else byProject.set(l.project_id, [l])
    }
    return this.store.list().map((p) =>
      projectDto(p, (byProject.get(p.id) ?? []).sort((a, b) => cmp(a.path, b.path))))
  }

  /** workspaceId → projectId for every row given (active and archived). Unresolved rows are omitted. */
  membership(workspaces: Array<{ id: string } & Located>): Record<string, string> {
    const out: Record<string, string> = {}
    for (const w of workspaces) {
      const projectId = this.resolve(w)
      if (projectId) out[w.id] = projectId
    }
    return out
  }

  private mustGet(id: string): ProjectRecord {
    const p = this.store.getById(id)
    if (!p) throw new ProjectNotFoundError()
    return p
  }
}

function requireName(name: string): string {
  const n = name.trim()
  if (!n) throw new Error("name required")
  return n
}

/** Code-unit order, matching SQLite's default BINARY collation used by the store. */
function cmp(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0
}
