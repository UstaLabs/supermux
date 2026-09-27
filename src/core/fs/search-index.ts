import { readdir } from "fs/promises"
import { join, relative, sep } from "path"
import { gitAsync } from "../git/exec"
import { GIT_READ, type RepoInfoCache } from "./repo-info"
import type { SearchHit } from "./types"

const SKIP = new Set(["node_modules", ".git", "build", "dist", ".next", ".nuxt", "out", "target", ".gradle", "Pods"])
const MAX_PATHS = 200_000
const REBUILD_AFTER_MS = 30_000
const EVICT_AFTER_MS = 10 * 60_000

const isBoundary = (s: string, i: number) => {
  if (i === 0) return true
  const p = s[i - 1]!
  if (p === "/" || p === "-" || p === "_" || p === "." || p === " ") return true
  return p === p.toLowerCase() && s[i] !== s[i]!.toLowerCase() // camelCase hump
}

function greedy(q: string, s: string, from: number): number[] | null {
  const ql = q.toLowerCase(), sl = s.toLowerCase()
  const hits: number[] = []
  let j = from
  for (const ch of ql) {
    const k = sl.indexOf(ch, j)
    if (k < 0) return null
    hits.push(k)
    j = k + 1
  }
  return hits
}

function scoreHits(s: string, hits: number[], nameStart: number): number {
  let score = 0
  for (let n = 0; n < hits.length; n++) {
    const i = hits[n]!
    score += 1
    if (i >= nameStart) score += 10
    if (n > 0 && hits[n - 1] === i - 1) score += 8
    if (isBoundary(s, i)) score += 6
  }
  return score - s.length * 0.05
}

/** fzf-style subsequence match on a relative path. Null when `q` is not a subsequence. */
export function fuzzyMatch(q: string, s: string): { score: number; hits: number[] } | null {
  if (!q || !q.trim()) return null
  const nameStart = s.lastIndexOf("/") + 1
  // Cheap pre-check: the leftmost greedy alignment succeeds iff q is a subsequence of s at all.
  if (!greedy(q, s, 0)) return null
  const sl = s.toLowerCase()
  const c0 = q.toLowerCase()[0]!
  let best: { hits: number[]; score: number } | null = null
  for (let i = 0; i < sl.length; i++) {
    if (sl[i] !== c0) continue
    const hits = greedy(q, s, i)
    if (!hits) continue
    const score = scoreHits(s, hits, nameStart)
    if (!best || score > best.score) best = { hits, score }
  }
  return best
}

interface Built { scope: string; rels: Array<{ rel: string; dir: boolean }>; builtAt: number; lastUsed: number }

async function walk(scope: string): Promise<Array<{ rel: string; dir: boolean }>> {
  const out: Array<{ rel: string; dir: boolean }> = []
  const queue = [scope]
  let head = 0
  while (head < queue.length && out.length < MAX_PATHS) {
    const d = queue[head++]!
    let ents: import("fs").Dirent[]
    try { ents = await readdir(d, { withFileTypes: true }) } catch { continue }
    for (const e of ents) {
      if (out.length >= MAX_PATHS) break
      const full = join(d, e.name)
      const rel = relative(scope, full).split(sep).join("/")
      if (e.isDirectory()) {
        if (SKIP.has(e.name)) continue
        out.push({ rel, dir: true })
        queue.push(full)
      } else {
        out.push({ rel, dir: false })
      }
    }
  }
  return out
}

function withDirs(files: string[]): Array<{ rel: string; dir: boolean }> {
  const dirs = new Set<string>()
  for (const f of files) {
    const parts = f.split("/")
    for (let i = 1; i < parts.length; i++) dirs.add(parts.slice(0, i).join("/"))
  }
  return [...[...dirs].map((rel) => ({ rel, dir: true })), ...files.map((rel) => ({ rel, dir: false }))].slice(0, MAX_PATHS)
}

/** One lazily built path index per scope folder, rebuilt after 30 s, evicted after 10 min idle. */
export class SearchIndexes {
  private readonly built = new Map<string, Built>()
  private readonly building = new Map<string, Promise<Built>>()
  /** Bumped per scope by invalidation so a build already in flight does not store a stale index. */
  private readonly epoch = new Map<string, number>()

  /** Builds running per scope, including ones invalidation already detached from `building`. */
  private readonly running = new Map<string, number>()
  private readonly now: () => number

  constructor(private readonly repo: RepoInfoCache, opts: { now?: () => number } = {}) {
    this.now = opts.now ?? Date.now
  }

  private async build(scope: string): Promise<Built> {
    this.running.set(scope, (this.running.get(scope) ?? 0) + 1)
    try {
      return await this.buildNow(scope)
    } finally {
      const n = (this.running.get(scope) ?? 1) - 1
      if (n > 0) this.running.set(scope, n)
      else this.running.delete(scope)
    }
  }

  private async buildNow(scope: string): Promise<Built> {
    const startEpoch = this.epoch.get(scope) ?? 0
    const root = await this.repo.repoFor(scope)
    let rels: Array<{ rel: string; dir: boolean }>
    if (root) {
      const out = await gitAsync(scope, [...GIT_READ, "ls-files", "-co", "--exclude-standard", "-z"], { timeoutMs: 5_000, trim: false }).catch(() => null)
      rels = out === null ? await walk(scope) : withDirs(out.split("\0").filter(Boolean))
    } else {
      rels = await walk(scope)
    }
    const b: Built = { scope, rels, builtAt: this.now(), lastUsed: this.now() }
    if ((this.epoch.get(scope) ?? 0) === startEpoch) this.built.set(scope, b)
    return b
  }

  private async get(scope: string): Promise<Built> {
    const now = this.now()
    for (const [k, v] of this.built) if (now - v.lastUsed > EVICT_AFTER_MS) this.built.delete(k)
    // An epoch only guards builds in flight; once a scope has neither an index nor a build, drop it.
    for (const k of this.epoch.keys()) if (!this.built.has(k) && !this.running.has(k)) this.epoch.delete(k)
    const b = this.built.get(scope)
    if (b && now - b.builtAt < REBUILD_AFTER_MS) { b.lastUsed = now; return b }
    const running = this.building.get(scope)
    if (running) return running
    const p: Promise<Built> = this.build(scope).finally(() => { if (this.building.get(scope) === p) this.building.delete(scope) })
    this.building.set(scope, p)
    return p
  }

  async query(scope: string, q: string, limit: number): Promise<SearchHit[]> {
    if (!q || !q.trim()) return []
    const b = await this.get(scope)
    const offset = scope === "/" ? 1 : scope.length + 1
    const scored: SearchHit[] = []
    for (const { rel, dir } of b.rels) {
      const m = fuzzyMatch(q, rel)
      if (!m) continue
      scored.push({
        path: scope === "/" ? `/${rel}` : `${scope}/${rel}`,
        name: rel.slice(rel.lastIndexOf("/") + 1),
        type: dir ? "dir" : "file",
        score: m.score,
        hits: m.hits.map((i) => i + offset),
      })
    }
    scored.sort((x, y) => y.score - x.score || x.path.length - y.path.length || (x.path < y.path ? -1 : 1))
    return scored.slice(0, Math.max(1, Math.min(limit, 200)))
  }

  /** Drop a scope's index (tests, or after a burst of changes). */
  invalidate(scope: string): void {
    this.built.delete(scope)
    this.building.delete(scope) // later queries start a fresh build instead of joining a stale one
    this.epoch.set(scope, (this.epoch.get(scope) ?? 0) + 1)
  }

  /** Something in folder `dir` changed: drop every index (built or building) whose scope contains it. */
  invalidateContaining(dir: string): void {
    for (const scope of new Set([...this.built.keys(), ...this.building.keys()])) {
      if (dir === scope || scope === "/" || dir.startsWith(scope + sep)) this.invalidate(scope)
    }
  }
}
