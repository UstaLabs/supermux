import { stat } from "fs/promises"
import { dirname, join, relative, sep } from "path"
import { gitAsync } from "../git/exec"
import type { FsEntry, GitLetter } from "./types"

export interface RepoState {
  root: string
  ignored: Set<string>          // repo-relative, no trailing slash
  status: Map<string, GitLetter> // repo-relative file → letter
  dirty: Set<string>            // repo-relative folders with changes inside ("" = root)
  loadedAt: number
}

interface Slot { state?: RepoState; loading?: Promise<RepoState>; stale: boolean }

const GIT_TIMEOUT_MS = 5_000

/**
 * Parse `git status --porcelain=v2 -z --untracked-files=all` into path → letter.
 *
 * Verified against real `git status --porcelain=v2 -z` output: ordinary "1"
 * records have 8 space-separated fields before the path (1 XY sub mH mI mW hH hI),
 * rename/copy "2" records have 9 (the extra field is the score, e.g. "R100"),
 * and unmerged "u" records have 10 (1 XY sub m1 m2 m3 mW h1 h2 h3).
 */
export function parseStatusZ(out: string): Map<string, GitLetter> {
  const m = new Map<string, GitLetter>()
  const recs = out.split("\0")
  for (let i = 0; i < recs.length; i++) {
    const r = recs[i]!
    if (!r) continue
    const kind = r[0]
    if (kind === "?") { m.set(r.slice(2), "?"); continue }
    if (kind === "!") continue
    if (kind === "1") {
      const parts = r.split(" ")
      const xy = parts[1]!
      m.set(parts.slice(8).join(" "), xy.includes("A") ? "A" : xy.includes("D") ? "D" : "M")
      continue
    }
    if (kind === "2") {
      const parts = r.split(" ")
      m.set(parts.slice(9).join(" "), "R")
      i++ // the next record is the original path
      continue
    }
    if (kind === "u") {
      const parts = r.split(" ")
      m.set(parts.slice(10).join(" "), "U")
    }
  }
  return m
}

function ancestorsOf(rel: string): string[] {
  const out: string[] = [""]
  const parts = rel.split("/")
  for (let i = 1; i < parts.length; i++) out.push(parts.slice(0, i).join("/"))
  return out
}

/**
 * Git facts per repository, read with async git and cached. A state younger than `ttlMs` is reused;
 * `invalidate(root)` forces the next read to go back to git (the watchers call it in A2).
 */
export class RepoInfoCache {
  private readonly slots = new Map<string, Slot>()
  private readonly rootOf = new Map<string, string | null>()
  private readonly ttlMs: number

  constructor(opts: { ttlMs?: number } = {}) {
    this.ttlMs = opts.ttlMs ?? 2_000
  }

  /** Nearest folder at or above `dirReal` that contains a `.git` entry (folder or file), else null. */
  async repoFor(dirReal: string): Promise<string | null> {
    const cached = this.rootOf.get(dirReal)
    if (cached !== undefined) return cached
    let d = dirReal
    let found: string | null = null
    while (true) {
      try {
        await stat(join(d, ".git"))
        found = d
        break
      } catch {
        const parent = dirname(d)
        if (parent === d) break
        d = parent
      }
    }
    this.rootOf.set(dirReal, found)
    return found
  }

  /** Cached repo root for a folder if known (no I/O). */
  knownRepoFor(dirReal: string): string | null | undefined {
    return this.rootOf.get(dirReal)
  }

  invalidate(root: string): void {
    const s = this.slots.get(root)
    if (s) s.stale = true
  }

  async state(root: string): Promise<RepoState> {
    let slot = this.slots.get(root)
    if (!slot) { slot = { stale: true }; this.slots.set(root, slot) }
    const fresh = slot.state && !slot.stale && Date.now() - slot.state.loadedAt < this.ttlMs
    if (fresh) return slot.state!
    if (slot.loading) return slot.loading
    const s = slot
    s.stale = false
    s.loading = this.load(root).then(
      (st) => { s.state = st; s.loading = undefined; return st },
      (err) => { s.loading = undefined; s.stale = true; throw err },
    )
    return s.loading
  }

  private async load(root: string): Promise<RepoState> {
    const [ign, stat] = await Promise.all([
      gitAsync(root, ["ls-files", "--others", "--ignored", "--exclude-standard", "--directory", "-z"], { timeoutMs: GIT_TIMEOUT_MS, trim: false }).catch(() => ""),
      gitAsync(root, ["status", "--porcelain=v2", "-z", "--untracked-files=all"], { timeoutMs: GIT_TIMEOUT_MS, trim: false }).catch(() => ""),
    ])
    const ignored = new Set<string>()
    for (const p of ign.split("\0")) if (p) ignored.add(p.endsWith("/") ? p.slice(0, -1) : p)
    const status = parseStatusZ(stat)
    const dirty = new Set<string>()
    for (const p of status.keys()) for (const a of ancestorsOf(p)) dirty.add(a)
    return { root, ignored, status, dirty, loadedAt: Date.now() }
  }

  /** Fill `ignored` and `git` on entries of the folder `dirReal`, in place. Never throws. */
  async annotate(dirReal: string, entries: FsEntry[]): Promise<void> {
    const root = await this.repoFor(dirReal)
    if (!root) return
    let st: RepoState
    try { st = await this.state(root) } catch { return }
    const base = relative(root, dirReal).split(sep).join("/")
    const baseIgnored = base !== "" && isIgnored(st.ignored, base)
    for (const e of entries) {
      const rel = base ? `${base}/${e.name}` : e.name
      if (rel === ".git" || rel.startsWith(".git/")) continue
      e.ignored = baseIgnored || st.ignored.has(rel)
      const isDir = e.type === "dir" || e.target === "dir"
      const letter = st.status.get(rel)
      if (letter) e.git = letter
      else if (isDir && st.dirty.has(rel)) e.git = "*"
    }
  }
}

function isIgnored(set: Set<string>, rel: string): boolean {
  if (set.has(rel)) return true
  const parts = rel.split("/")
  for (let i = 1; i < parts.length; i++) if (set.has(parts.slice(0, i).join("/"))) return true
  return false
}
