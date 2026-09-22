// Inspect and delete session worktrees under the worktrees root
// (~/.mux/worktrees/<repo-slug>/<uuid>). Pure over (root, owner rows): no DB,
// no broadcast — service.ts adds those. Every child process is async.
// Spec: docs/superpowers/specs/2026-09-22-explicit-worktree-cleanup-design.md
import { access, lstat, readdir, readFile, realpath, rm, stat } from "fs/promises"
import { basename, dirname, join, resolve, sep } from "path"
import { gitAsync, runAsync } from "../git/exec"

/** Ignored top-level entries shown gray: routine build output, not work. */
export const WELL_KNOWN_IGNORED = new Set([
  "node_modules", "build", "dist", "out", ".gradle", ".kotlin", "target", ".next", ".nuxt",
  ".venv", "venv", "__pycache__", ".turbo", ".cache", ".pytest_cache", "coverage",
])

/** The session columns inventory needs (one SELECT in service.ts). */
export interface OwnerRow {
  id: string
  name: string
  status: string            // active | suspended | archived
  user_status: string | null
  workdir: string
  base_branch: string | null
  session_branch: string | null
}

export interface WorktreeOwner { id: string; name: string; status: "live" | "archived" }
export interface IgnoredEntry { name: string; wellKnown: boolean; bytes?: number }

export interface WorktreeSummary {
  id: string                // "<repo-slug>/<uuid>"
  path: string
  repoRoot?: string
  repoName: string
  branch?: string
  owners: WorktreeOwner[]
  mtime: number             // epoch ms
  uncommitted: number
  unmerged: number | null   // null = base unknown (hasChanges is then true, except "repo gone")
  ignored: IgnoredEntry[]
  hasChanges: boolean
  bytes?: number
  /** Why it couldn't be (fully) inspected. Known values:
   *  - "repo gone": its main repository no longer exists on disk — a deletable orphan; no git
   *    is run for it, owners are still computed, unmerged null, uncommitted 0. hasChanges is
   *    true (conservative): the repo can also be "gone" because it was renamed/moved or sits on
   *    an unmounted drive, in which case the worktree folder may still hold real untracked or
   *    modified files we simply can't see — never presume it's safe to select for deletion.
   *  - "not a git worktree": the folder has no .git of its own (never inspected via an
   *    enclosing repo); hasChanges is true because its contents are unknown.
   *  Anything else is a git error message; hasChanges is then true (conservative). */
  error?: string
}

export interface WorktreeChanges {
  id: string
  branch?: string
  baseRef?: string
  files: Array<{ status: string; path: string }>
  commits: Array<{ sha: string; subject: string }>
  ignored: IgnoredEntry[]
  truncated: { files: number; commits: number; ignored: number }
}

export const LIST_CAP = 500

export const REPO_GONE = "repo gone"
export const NOT_A_WORKTREE = "not a git worktree"

const isLive = (s: string) => s === "active" || s === "suspended"

/** Rows that own worktree `path`: their workdir is the folder itself or anything inside it.
 *  Draft rows never own. Pure string comparison — pass canonical paths on both sides
 *  (see `canonicalizer`); a trailing slash is ignored. */
export function ownersOf(path: string, rows: OwnerRow[]): WorktreeOwner[] {
  const p = resolve(path)
  return rows
    .filter((r) => r.user_status !== "draft" && owns(p, r.workdir))
    .map((r) => ({ id: r.id, name: r.name, status: isLive(r.status) ? "live" as const : "archived" as const }))
}

function owns(worktree: string, workdir: string): boolean {
  const w = resolve(workdir)
  return w === worktree || w.startsWith(worktree + sep)
}

export type Canon = (p: string) => Promise<string>

/** Canonical path key: realpath of the longest existing ancestor + the missing rest, no trailing
 *  slash. Owner rows store both unresolved (manager.ts) and realpath'd (workdir-paths.ts) paths,
 *  so every ownership comparison goes through this. Async and memoised per call.
 *  Exported so service.ts can canonicalize a caller-supplied workdir/rows the same way listWorktrees
 *  and deleteOne do, instead of reimplementing the ancestor-walk fallback. */
export function canonicalizer(): Canon {
  const cache = new Map<string, Promise<string>>()
  const canon: Canon = (p) => {
    const abs = resolve(p)
    let hit = cache.get(abs)
    if (!hit) {
      hit = realpath(abs).catch(async () => {
        const parent = dirname(abs)
        return parent === abs ? abs : join(await canon(parent), basename(abs))
      })
      cache.set(abs, hit)
    }
    return hit
  }
  return canon
}

/** Non-draft rows with canonical workdirs. */
export async function canonRows(rows: OwnerRow[], canon: Canon): Promise<OwnerRow[]> {
  const owning = rows.filter((r) => r.user_status !== "draft" && r.workdir)
  return Promise.all(owning.map(async (r) => ({ ...r, workdir: await canon(r.workdir) })))
}

/** Canonical rows grouped by the worktree folder (realRoot/<slug>/<uuid>) they sit in. */
function ownerIndex(realRoot: string, rows: OwnerRow[]): Map<string, OwnerRow[]> {
  const index = new Map<string, OwnerRow[]>()
  for (const r of rows) {
    if (!r.workdir.startsWith(realRoot + sep)) continue
    const [slug, name] = r.workdir.slice(realRoot.length + 1).split(sep)
    if (!slug || !name) continue
    const key = join(realRoot, slug, name)
    index.set(key, [...(index.get(key) ?? []), r])
  }
  return index
}

const exists = (p: string) => access(p).then(() => true, () => false)
/** Definitely absent (ENOENT/ENOTDIR) — any other error (e.g. EACCES) counts as present. */
const missing = (p: string) => access(p).then(() => false, (e: any) => e?.code === "ENOENT" || e?.code === "ENOTDIR")

/** What the entry's own `.git` says, from the filesystem only (no git):
 *  - none: no `.git` of its own
 *  - gone: a `.git` file whose repository (common dir) no longer exists
 *  - git:  something git can inspect */
type Probe = { kind: "none" } | { kind: "gone"; repoRoot: string } | { kind: "git" }

async function probe(entry: string, goneCache?: Map<string, Promise<boolean>>): Promise<Probe> {
  const st = await stat(join(entry, ".git")).catch(() => undefined)
  if (!st) return { kind: "none" }
  if (!st.isFile()) return { kind: "git" }
  const link = await gitLinkOf(entry)
  if (!link) return { kind: "git" }
  let gone = goneCache?.get(link.commonDir)
  if (!gone) { gone = missing(link.commonDir); goneCache?.set(link.commonDir, gone) }
  return (await gone) ? { kind: "gone", repoRoot: link.repoRoot } : { kind: "git" }
}

/** Main repo of a worktree, parsed cheaply from its `.git` file ("gitdir: <path>", absolute or
 *  relative to the entry). Only used to decide "repo gone" without running git — the real
 *  repo root comes from `git rev-parse --git-common-dir` (see identify). */
export async function repoRootOf(worktreeDir: string): Promise<string | undefined> {
  return (await gitLinkOf(worktreeDir))?.repoRoot
}

async function gitLinkOf(worktreeDir: string): Promise<{ repoRoot: string; commonDir: string } | undefined> {
  try {
    const m = /^gitdir:\s*(.+)$/m.exec(await readFile(join(worktreeDir, ".git"), "utf-8"))
    const raw = m?.[1]?.trim()
    if (!raw) return undefined
    const gitdir = resolve(worktreeDir, raw)
    const commonDir = await readFile(join(gitdir, "commondir"), "utf-8")
      .then((c) => resolve(gitdir, c.trim()))
      .catch(() => basename(dirname(gitdir)) === "worktrees" ? dirname(dirname(gitdir)) : gitdir)
    return { repoRoot: basename(commonDir) === ".git" ? dirname(commonDir) : commonDir, commonDir }
  } catch { return undefined }
}

/** Git's own view of the entry, in one call. Throws NOT_A_WORKTREE unless the entry is its own
 *  top level (so an enclosing repo is never reported or touched). `linked` = a `git worktree`
 *  of repoRoot. `branch` = `rev-parse --abbrev-ref HEAD` ("HEAD" when detached). */
async function identify(entry: string, canonEntry: string, canon: Canon): Promise<{ repoRoot: string; linked: boolean; branch: string }> {
  const out = await gitAsync(entry, ["rev-parse", "--path-format=absolute", "--show-toplevel", "--git-dir", "--git-common-dir", "--abbrev-ref", "HEAD"])
  const [top, gitDir, commonDir, branch] = out.split("\n")
  if (!top || !gitDir || !commonDir || !branch || (await canon(top)) !== canonEntry) throw new Error(NOT_A_WORKTREE)
  const linked = (await canon(gitDir)) !== (await canon(commonDir))
  return { repoRoot: basename(commonDir) === ".git" ? dirname(commonDir) : commonDir, linked, branch }
}

/** Resolve an id to its real folder, strictly two levels inside root. Throws otherwise.
 *  Neither id component may be a symlink (an alias would delete another worktree). */
export async function resolveId(root: string, id: string): Promise<string> {
  const parts = id.split("/")
  if (parts.length !== 2 || parts.some((p) => !p || p === "." || p === "..")) throw new Error("invalid worktree id")
  for (const rel of [parts[0]!, id]) {
    const st = await lstat(join(root, rel)).catch(() => { throw new Error("worktree not found") })
    if (st.isSymbolicLink()) throw new Error("worktree id is a symlink")
    if (!st.isDirectory()) throw new Error("worktree not found")
  }
  const realRoot = await realpath(root)
  const real = await realpath(join(root, id)).catch(() => { throw new Error("worktree not found") })
  if (!real.startsWith(realRoot + sep) || real.slice(realRoot.length + 1).split(sep).length !== 2) throw new Error("worktree outside root")
  return real
}

async function enumerate(root: string): Promise<Array<{ id: string; path: string }>> {
  const slugs = (await readdir(root, { withFileTypes: true }).catch(() => [])).filter((s) => s.isDirectory())
  const perSlug = await pool(slugs, 64, async (s) => {
    const inner = await readdir(join(root, s.name), { withFileTypes: true }).catch(() => [])
    return inner.filter((d) => d.isDirectory()).map((d) => ({ id: `${s.name}/${d.name}`, path: join(root, s.name, d.name) }))
  })
  return perSlug.flat().sort((a, b) => a.id.localeCompare(b.id))
}

/** Parallel map with a fixed number of workers. */
export async function pool<T, R>(items: T[], n: number, fn: (t: T) => Promise<R>): Promise<R[]> {
  const out = new Array<R>(items.length)
  let next = 0
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) { const i = next++; out[i] = await fn(items[i]!) }
  }))
  return out
}

/** `git status --porcelain=v1 -z --ignored`, untrimmed: NUL-separated "XY path" records; a
 *  rename/copy record is followed by one extra field (its source path), which is skipped. */
async function statusOf(path: string): Promise<{ files: Array<{ status: string; path: string }>; ignored: IgnoredEntry[] }> {
  // --no-optional-locks: inspection must not take index.lock (the broker polls status too).
  const out = await gitAsync(path, ["--no-optional-locks", "status", "--porcelain=v1", "-z", "--ignored"], { trim: false })
  const recs = out.split("\0")
  const files: Array<{ status: string; path: string }> = []
  const ignored: IgnoredEntry[] = []
  for (let i = 0; i < recs.length; i++) {
    const r = recs[i]!
    if (r.length < 4) continue
    const xy = r.slice(0, 2)
    const p = r.slice(3)
    if (xy === "!!") {
      const name = p.replace(/\/$/, "")
      ignored.push({ name, wellKnown: WELL_KNOWN_IGNORED.has(basename(name)) })
    } else {
      files.push({ status: xy.trim() || xy, path: p })
      if (/[RC]/.test(xy)) i++
    }
  }
  return { files, ignored }
}

/** Unmerged commits (`<ref>..HEAD`): the owner's base branch, else the upstream. A ref git can't
 *  resolve is skipped; none resolvable → count null (unknown). Draft rows are already excluded. */
async function unmergedOf(path: string, owners: OwnerRow[]): Promise<{ ref?: string; count: number | null }> {
  const refs: string[] = []
  for (const o of owners) if (o.base_branch && o.base_branch !== "HEAD" && !refs.includes(o.base_branch)) refs.push(o.base_branch)
  const upstream = await gitAsync(path, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"]).catch(() => undefined)
  if (upstream && !refs.includes(upstream)) refs.push(upstream)
  for (const ref of refs) {
    const n = Number(await gitAsync(path, ["rev-list", "--count", `${ref}..HEAD`]).catch(() => "x"))
    if (Number.isInteger(n) && n >= 0) return { ref, count: n }
  }
  return { count: null }
}

interface Probed { e: { id: string; path: string }; canonEntry: string; ownerRows: OwnerRow[]; base: WorktreeSummary; probe: Probe }

/** Phase 1, filesystem only: owners, mtime and the probe. "repo gone" / "not a git worktree"
 *  summaries are final here. */
async function probeEntry(
  e: { id: string; path: string }, canonEntry: string, ownerRows: OwnerRow[], goneCache: Map<string, Promise<boolean>>,
): Promise<Probed> {
  const owners = ownersOf(canonEntry, ownerRows)
  const mtime = (await stat(e.path).catch(() => undefined))?.mtimeMs ?? 0
  const p = await probe(e.path, goneCache)
  let base: WorktreeSummary = {
    id: e.id, path: e.path, repoName: e.id.split("/")[0]!, owners, mtime,
    uncommitted: 0, unmerged: null, ignored: [], hasChanges: true,
  }
  if (p.kind === "gone") base = { ...base, repoRoot: p.repoRoot, repoName: basename(p.repoRoot), error: REPO_GONE }
  if (p.kind === "none") base = { ...base, error: NOT_A_WORKTREE }
  return { e, canonEntry, ownerRows, base, probe: p }
}

/** Phase 2, git: only for entries the probe says git can inspect. */
async function summarize({ e, canonEntry, ownerRows, base }: Probed, canon: Canon): Promise<WorktreeSummary> {
  let repoRoot: string | undefined
  try {
    const idn = await identify(e.path, canonEntry, canon)
    repoRoot = idn.repoRoot
    const branch = idn.branch
    const { files, ignored } = await statusOf(e.path)
    const { count: unmerged } = await unmergedOf(e.path, ownerRows)
    const hasChanges = files.length > 0 || unmerged === null || unmerged > 0 || ignored.some((i) => !i.wellKnown)
    return { ...base, repoRoot, repoName: basename(repoRoot), branch, uncommitted: files.length, unmerged, ignored, hasChanges }
  } catch (err: any) {
    return { ...base, ...(repoRoot ? { repoRoot, repoName: basename(repoRoot) } : {}), error: String(err?.message ?? err) }
  }
}

/** Every worktree folder under root (git-known or not). Read-only: never prunes or otherwise
 *  mutates a repository. Entries whose repo is gone cost only fs calls. */
export async function listWorktrees(root: string, rows: OwnerRow[]): Promise<WorktreeSummary[]> {
  const realRoot = await realpath(root).catch(() => undefined)
  if (!realRoot) return []
  const canon = canonicalizer()
  const index = ownerIndex(realRoot, await canonRows(rows, canon))
  const entries = await enumerate(root)
  const goneCache = new Map<string, Promise<boolean>>()
  const probed = await pool(entries, 64, (e) => {
    const canonEntry = join(realRoot, e.id)
    return probeEntry(e, canonEntry, index.get(canonEntry) ?? [], goneCache)
  })
  const inspected = await pool(probed.filter((p) => p.probe.kind === "git"), 8, (p) => summarize(p, canon))
  const byId = new Map(inspected.map((w) => [w.id, w]))
  return probed.map((p) => byId.get(p.e.id) ?? p.base)
}

export async function worktreeChanges(root: string, id: string, rows: OwnerRow[]): Promise<WorktreeChanges> {
  const path = await resolveId(root, id)
  const canon = canonicalizer()
  const ownerRows = (await canonRows(rows, canon)).filter((r) => owns(path, r.workdir))
  const empty: WorktreeChanges = { id, files: [], commits: [], ignored: [], truncated: { files: 0, commits: 0, ignored: 0 } }
  if ((await probe(path)).kind !== "git") return empty
  let branch: string
  try { branch = (await identify(path, path, canon)).branch } catch { return empty }
  const { files, ignored: ignoredAll } = await statusOf(path).catch(() => ({ files: [], ignored: [] as IgnoredEntry[] }))
  const { ref: baseRef } = await unmergedOf(path, ownerRows)
  const log = baseRef ? await gitAsync(path, ["log", "--format=%h%x09%s", `${baseRef}..HEAD`]).catch(() => "") : ""
  const commits = log ? log.split("\n").map((l) => { const [sha, ...s] = l.split("\t"); return { sha: sha!, subject: s.join("\t") } }) : []
  const ignored = await pool(ignoredAll.slice(0, LIST_CAP), 4, async (e) => ({ ...e, bytes: await duBytes(join(path, e.name)) }))
  return {
    id, branch, baseRef,
    files: files.slice(0, LIST_CAP),
    commits: commits.slice(0, LIST_CAP),
    ignored,
    truncated: {
      files: Math.max(0, files.length - LIST_CAP),
      commits: Math.max(0, commits.length - LIST_CAP),
      ignored: Math.max(0, ignoredAll.length - LIST_CAP),
    },
  }
}

async function duBytes(path: string): Promise<number | undefined> {
  const r = await runAsync("du", ["-sk", path], { cwd: "/", timeoutMs: 120_000 })
  const kb = Number(r.output.trim().split(/\s+/)[0])
  return Number.isFinite(kb) ? kb * 1024 : undefined
}

// Unbounded concurrency here; service.ts pools calls (review M2).
export async function worktreeSize(root: string, id: string): Promise<number | undefined> {
  return duBytes(await resolveId(root, id))
}

export interface DeleteResult { id: string; ok: boolean; error?: string; inUseBy?: string[] }

async function branchCheckedOutElsewhere(repoRoot: string, branch: string, except: string, canon: Canon): Promise<boolean> {
  const out = await gitAsync(repoRoot, ["worktree", "list", "--porcelain"]).catch(() => "")
  let current = ""
  for (const line of out.split("\n")) {
    if (line.startsWith("worktree ")) current = line.slice(9)
    else if (line === `branch refs/heads/${branch}` && (await canon(current)) !== except) return true
  }
  return false
}

async function deleteOne(root: string, id: string, owners: () => OwnerRow[], canon: Canon): Promise<DeleteResult> {
  let path: string
  try { path = await resolveId(root, id) } catch (e: any) { return { id, ok: false, error: String(e?.message ?? e) } }
  // Only a verified linked worktree gets git operations; "repo gone", a folder without its own
  // .git, or anything git can't identify as its own top level is just removed from disk.
  let repoRoot: string | undefined
  let branch: string | undefined
  if ((await probe(path)).kind === "git") {
    const idn = await identify(path, path, canon).catch(() => undefined)
    if (idn?.linked) {
      repoRoot = idn.repoRoot
      branch = idn.branch
    }
  }
  // Live owners are read from the provider NOW — per id, immediately before the destructive
  // step — never from a snapshot taken at the start of the batch: a session restored while an
  // earlier id was being deleted wins. Canonical on both sides; a session in any subfolder counts.
  const live = ownersOf(path, await canonRows(owners(), canon)).filter((o) => o.status === "live")
  if (live.length) return { id, ok: false, error: "in_use", inUseBy: live.map((o) => o.name) }
  try {
    if (repoRoot) await gitAsync(repoRoot, ["worktree", "remove", "--force", "--force", path]).catch(() => {})
    if (await exists(path)) await rm(path, { recursive: true, force: true })
    if (repoRoot) {
      await gitAsync(repoRoot, ["worktree", "prune"]).catch(() => {})
      if (branch?.startsWith("mux/") && !(await branchCheckedOutElsewhere(repoRoot, branch, path, canon))) {
        await gitAsync(repoRoot, ["branch", "-D", branch]).catch(() => {})
      }
    }
    return (await exists(path)) ? { id, ok: false, error: "folder still exists after delete" } : { id, ok: true }
  } catch (e: any) {
    return { id, ok: false, error: String(e?.message ?? e) }
  }
}

/** Delete each worktree regardless of its changes — the caller got explicit user consent.
 *  Never deletes one with a live owner: `owners` is a provider, called again for every id right
 *  before its live-owner check (see deleteOne), so rows are never a stale batch snapshot.
 *  Per-id results; one failure never aborts the batch. Only the path canonicalizer cache is
 *  shared across the batch. */
export async function deleteWorktrees(root: string, ids: string[], owners: () => OwnerRow[]): Promise<DeleteResult[]> {
  const canon = canonicalizer()
  const out: DeleteResult[] = []
  for (const id of ids) out.push(await deleteOne(root, id, owners, canon))
  return out
}
