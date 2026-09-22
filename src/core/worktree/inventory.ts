// Inspect and delete session worktrees under the worktrees root
// (~/.mux/worktrees/<repo-slug>/<uuid>). Pure over (root, owner rows): no DB,
// no broadcast — service.ts adds those. Every child process is async.
// Spec: docs/superpowers/specs/2026-09-22-explicit-worktree-cleanup-design.md
import { existsSync, readFileSync } from "fs"
import { readdir, realpath, rm, stat } from "fs/promises"
import { basename, join, sep } from "path"
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
  unmerged: number | null   // null = base unknown
  ignored: IgnoredEntry[]
  hasChanges: boolean
  bytes?: number
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

const isLive = (s: string) => s === "active" || s === "suspended"

export function ownersOf(path: string, rows: OwnerRow[]): WorktreeOwner[] {
  return rows
    .filter((r) => r.workdir === path && r.user_status !== "draft")
    .map((r) => ({ id: r.id, name: r.name, status: isLive(r.status) ? "live" as const : "archived" as const }))
}

/** Main repo of a linked worktree, from its `.git` file ("gitdir: <repo>/.git/worktrees/<n>"). */
export function repoRootOf(worktreeDir: string): string | undefined {
  try {
    const m = /^gitdir:\s*(.+)$/m.exec(readFileSync(join(worktreeDir, ".git"), "utf-8"))
    const gitdir = m?.[1]?.trim()
    const i = gitdir?.lastIndexOf(`${sep}.git${sep}worktrees${sep}`) ?? -1
    return i > 0 ? gitdir!.slice(0, i) : undefined
  } catch { return undefined }
}

/** Resolve an id to its real folder, strictly two levels inside root. Throws otherwise. */
export async function resolveId(root: string, id: string): Promise<string> {
  const parts = id.split("/")
  if (parts.length !== 2 || parts.some((p) => !p || p === "." || p === "..")) throw new Error("invalid worktree id")
  const realRoot = await realpath(root)
  const real = await realpath(join(root, id)).catch(() => { throw new Error("worktree not found") })
  if (!real.startsWith(realRoot + sep) || real.slice(realRoot.length + 1).split(sep).length !== 2) throw new Error("worktree outside root")
  return real
}

async function enumerate(root: string): Promise<Array<{ id: string; path: string }>> {
  const out: Array<{ id: string; path: string }> = []
  const slugs = await readdir(root, { withFileTypes: true }).catch(() => [])
  for (const s of slugs) {
    if (!s.isDirectory()) continue
    const inner = await readdir(join(root, s.name), { withFileTypes: true }).catch(() => [])
    for (const d of inner) if (d.isDirectory()) out.push({ id: `${s.name}/${d.name}`, path: join(root, s.name, d.name) })
  }
  return out.sort((a, b) => a.id.localeCompare(b.id))
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

function parseIgnored(porcelain: string): IgnoredEntry[] {
  return porcelain.split("\n")
    .filter((l) => l.startsWith("!! "))
    .map((l) => l.slice(3).replace(/\/$/, ""))
    .map((name) => ({ name, wellKnown: WELL_KNOWN_IGNORED.has(basename(name)) }))
}

/** `base..HEAD` ref to count unmerged commits: the owner's base branch, else the upstream, else unknown. */
async function baseRefFor(path: string, owners: OwnerRow[]): Promise<string | undefined> {
  const withBase = owners.find((o) => o.workdir === path && o.base_branch && o.base_branch !== "HEAD")
  if (withBase) return withBase.base_branch!
  try { return await gitAsync(path, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"]) } catch { return undefined }
}

async function summarize(root: string, id: string, path: string, rows: OwnerRow[]): Promise<WorktreeSummary> {
  const owners = ownersOf(path, rows)
  const repoRoot = repoRootOf(path)
  const repoName = repoRoot ? basename(repoRoot) : id.split("/")[0]!
  const mtime = (await stat(path).catch(() => undefined))?.mtimeMs ?? 0
  const base: WorktreeSummary = { id, path, repoRoot, repoName, owners, mtime, uncommitted: 0, unmerged: null, ignored: [], hasChanges: false }
  try {
    const branch = await gitAsync(path, ["rev-parse", "--abbrev-ref", "HEAD"])
    const status = await gitAsync(path, ["status", "--porcelain", "--ignored"])
    const lines = status ? status.split("\n") : []
    const uncommitted = lines.filter((l) => l && !l.startsWith("!! ")).length
    const ignored = parseIgnored(status)
    const ref = await baseRefFor(path, rows)
    const unmerged = ref ? Number(await gitAsync(path, ["rev-list", "--count", `${ref}..HEAD`]).catch(() => "0")) : null
    const hasChanges = uncommitted > 0 || (unmerged ?? 0) > 0 || ignored.some((e) => !e.wellKnown)
    return { ...base, branch, uncommitted, unmerged, ignored, hasChanges }
  } catch (e: any) {
    return { ...base, error: String(e?.message ?? e) }
  }
}

/** Every worktree folder under root (git-known or not), after pruning stale git registrations. */
export async function listWorktrees(root: string, rows: OwnerRow[]): Promise<WorktreeSummary[]> {
  const entries = await enumerate(root)
  const repos = new Set<string>()
  for (const e of entries) { const r = repoRootOf(e.path); if (r) repos.add(r) }
  for (const r of rows) {
    const rr = repoRootOf(r.workdir)
    if (rr) repos.add(rr)
  }
  for (const r of repos) await gitAsync(r, ["worktree", "prune"]).catch(() => {})
  return pool(entries, 8, (e) => summarize(root, e.id, e.path, rows))
}

export async function worktreeChanges(root: string, id: string, rows: OwnerRow[]): Promise<WorktreeChanges> {
  const path = await resolveId(root, id)
  const branch = await gitAsync(path, ["rev-parse", "--abbrev-ref", "HEAD"]).catch(() => undefined)
  const status = await gitAsync(path, ["status", "--porcelain", "--ignored"]).catch(() => "")
  const lines = status ? status.split("\n").filter(Boolean) : []
  const files = lines.filter((l) => !l.startsWith("!! ")).map((l) => ({ status: l.slice(0, 2).trim() || l.slice(0, 2), path: l.slice(3) }))
  const baseRef = await baseRefFor(path, rows)
  const log = baseRef ? await gitAsync(path, ["log", "--format=%h%x09%s", `${baseRef}..HEAD`]).catch(() => "") : ""
  const commits = log ? log.split("\n").map((l) => { const [sha, ...s] = l.split("\t"); return { sha: sha!, subject: s.join("\t") } }) : []
  const ignoredAll = parseIgnored(status)
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

export async function worktreeSize(root: string, id: string): Promise<number | undefined> {
  return duBytes(await resolveId(root, id))
}
