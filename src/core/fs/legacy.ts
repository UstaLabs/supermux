// The pre-2026-09-27 `/workspaces/:id/fs*` and `/sessions/:id/fs*` contract, served by the host
// FileSystemService. Relative paths, containment inside the workdir (the security boundary for
// these routes), and the old response shapes are all preserved here.
import { realpath } from "fs/promises"
import { dirname, join, relative, resolve, sep } from "path"
import type { FileSystemService } from "./file-system-service"
import type { FsEntry } from "./types"

export interface LegacyFsEntry { name: string; type: "file" | "dir"; size: number; modified: string; ignored: boolean }
export interface LegacySearchResult { path: string; name: string; type: "file" | "dir"; ignored: boolean }

export function toLegacyEntry(e: FsEntry): LegacyFsEntry {
  const type = e.type === "dir" || e.target === "dir" ? "dir" : "file"
  return { name: e.name, type, size: e.size ?? 0, modified: new Date(e.mtime ?? 0).toISOString(), ignored: e.ignored }
}

export class WorkdirFs {
  private rootReal?: Promise<string>

  // Only the request/response half is used, so any socket type will do.
  constructor(private readonly fss: FileSystemService<any>, private readonly workdir: string) {}

  private root(): Promise<string> {
    this.rootReal ??= realpath(this.workdir)
    return this.rootReal
  }

  private inside(root: string, p: string): boolean {
    return p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)
  }

  /** Resolve a workdir-relative path; refuse anything that lands outside the workdir. */
  private async resolveExisting(rel: string): Promise<string> {
    if (rel.includes("\0")) throw new Error("Path contains null byte")
    const root = await this.root()
    const joined = join(root, rel.replace(/^\/+/, ""))
    const resolved = await realpath(joined).catch(() => resolve(joined))
    if (!this.inside(root, resolved)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
    return resolved
  }

  /** Same, for a path that may not exist yet: the deepest existing ancestor must be inside. */
  private async resolveForWrite(rel: string): Promise<string> {
    if (rel.includes("\0")) throw new Error("Path contains null byte")
    const root = await this.root()
    const target = resolve(join(root, rel.replace(/^\/+/, "")))
    if (!this.inside(root, target)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
    let ancestor = dirname(target)
    while (true) {
      const real = await realpath(ancestor).catch(() => null)
      if (real) {
        if (!this.inside(root, real)) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir via symlink`)
        return target
      }
      const parent = dirname(ancestor)
      if (parent === ancestor) throw new Error(`Path traversal detected: "${rel}" resolves outside workdir`)
      ancestor = parent
    }
  }

  async listDir(rel: string): Promise<LegacyFsEntry[]> {
    const snap = await this.fss.list(await this.resolveExisting(rel))
    return snap.entries.map(toLegacyEntry)
  }

  async readFile(rel: string): Promise<string> {
    return this.fss.read(await this.resolveExisting(rel))
  }

  async writeFile(rel: string, content: string): Promise<{ ok: true; size: number }> {
    const r = await this.fss.write(await this.resolveForWrite(rel), content)
    return { ok: true, size: r.size }
  }

  async searchFiles(q: string, max = 20): Promise<LegacySearchResult[]> {
    if (!q.trim()) return []
    const root = await this.root()
    const hits = await this.fss.search(root, q, max)
    return hits.map((h) => ({ path: relative(root, h.path).split(sep).join("/"), name: h.name, type: h.type, ignored: false }))
  }
}
