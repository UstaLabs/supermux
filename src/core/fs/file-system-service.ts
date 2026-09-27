import { randomBytes } from "crypto"
import { dirname } from "path"
import { DirCache, type CachedDir } from "./dir-cache"
import { applyOp, readText, statEntry, writeText, type OpOptions } from "./file-ops"
import { normalizeAbsPath, realKey } from "./paths"
import { RepoInfoCache } from "./repo-info"
import { SearchIndexes } from "./search-index"
import type { DirSnapshot, FsEntry, FsOp, SearchHit } from "./types"

export interface FileSystemServiceOpts {
  bootId?: string
  trashDir?: string
  maxEntries?: number
  maxDirs?: number
}

const toSnapshot = (path: string, c: CachedDir): DirSnapshot => ({
  path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

/** The host's one file-system service: addressed by absolute path, shared by every caller. */
export class FileSystemService {
  readonly bootId: string
  readonly repo = new RepoInfoCache()
  readonly cache: DirCache
  private readonly searches: SearchIndexes
  private readonly opOpts: OpOptions

  constructor(opts: FileSystemServiceOpts = {}) {
    this.bootId = opts.bootId ?? randomBytes(4).toString("hex")
    this.cache = new DirCache({ repo: this.repo, bootId: this.bootId, maxEntries: opts.maxEntries, maxDirs: opts.maxDirs })
    this.searches = new SearchIndexes(this.repo)
    this.opOpts = { trashDir: opts.trashDir }
  }

  /** A folder's snapshot. Watched folders are served from cache; others are re-read. */
  async list(path: string): Promise<DirSnapshot> {
    const p = normalizeAbsPath(path)
    const real = await realKey(p)
    const c = this.cache.isPinned(real) ? await this.cache.getOrLoad(real) : (await this.cache.load(real)).snap
    return toSnapshot(p, c)
  }

  async stat(path: string): Promise<FsEntry & { real: string }> {
    return statEntry(normalizeAbsPath(path))
  }

  async read(path: string): Promise<string> {
    return readText(normalizeAbsPath(path))
  }

  async write(path: string, text: string): Promise<{ size: number; mtime: number }> {
    const p = normalizeAbsPath(path)
    const r = await writeText(p, text)
    await this.refresh([dirname(p)])
    return r
  }

  async search(scope: string, q: string, limit = 50): Promise<SearchHit[]> {
    const s = await realKey(normalizeAbsPath(scope))
    return this.searches.query(s, q, limit)
  }

  async op(op: FsOp): Promise<void> {
    const path = normalizeAbsPath(op.path)
    const norm: FsOp = "to" in op ? { ...op, path, to: normalizeAbsPath(op.to) } : { ...op, path }
    await applyOp(norm, this.opOpts)
    await this.refresh("to" in norm ? [dirname(norm.path), dirname(norm.to)] : [dirname(norm.path)])
  }

  /**
   * Re-read folders that changed because of our own write/op, if they are cached. Overridden
   * behaviour in A2 (Task 10): subscribers of those folders get the new snapshot.
   */
  protected async refresh(dirs: string[]): Promise<void> {
    for (const d of new Set(dirs)) {
      const real = await realKey(d).catch(() => null)
      if (!real) continue
      const root = this.repo.knownRepoFor(real)
      if (root) this.repo.invalidate(root)
      if (this.cache.get(real)) await this.onDirChanged(real)
    }
  }

  /** Hook for A2: called with a real folder path after it may have changed. */
  protected async onDirChanged(real: string): Promise<void> {
    await this.cache.load(real).catch(() => undefined)
  }
}
