import { execFile } from "node:child_process"
import { promisify } from "node:util"

const pexec = promisify(execFile)

export interface GitLiteStatus {
  mode: "base" | "remote"   // worktree → base, plain repo → remote
  compareRef: string        // "main" | "origin/feature-x" — for the label
  ahead: number             // commits in HEAD not in compareRef
  behind: number            // commits in compareRef not in HEAD
  dirty: number             // uncommitted + untracked (gitignore-respected)
  unpublished?: boolean     // mode:"remote" only — no upstream yet
  computedAt: number        // epoch ms
}

export interface LiteStatusInput {
  workdir: string
  repo_root?: string | null
  base_branch?: string | null
  session_branch?: string | null
}

async function runGit(cwd: string, args: string[], timeout = 30_000): Promise<{ ok: boolean; out: string }> {
  try {
    const { stdout } = await pexec("git", args, { cwd, encoding: "utf-8", timeout, maxBuffer: 16 * 1024 * 1024 })
    return { ok: true, out: stdout.trim() }
  } catch (e: any) {
    return { ok: false, out: String(e?.stdout ?? "").trim() }
  }
}

/** Count of uncommitted + untracked files (excludes ignored — git's own rules). */
async function dirtyCount(cwd: string): Promise<number> {
  const r = await runGit(cwd, ["status", "--porcelain"])
  if (!r.ok || !r.out) return 0
  return r.out.split("\n").filter((l) => l.trim().length > 0).length
}

/**
 * Cheap, async per-session git status for the at-a-glance badge.
 * Worktree-backed sessions compare vs base_branch; plain repos vs @{upstream}.
 * Returns null for non-repos or on git failure (badge hidden).
 */
export async function computeLiteStatus(s: LiteStatusInput, now: number = Date.now()): Promise<GitLiteStatus | null> {
  const cwd = s.workdir
  const worktreeBacked = !!(s.repo_root && s.base_branch && s.session_branch)

  if (worktreeBacked) {
    const base = s.base_branch as string
    const ab = await runGit(cwd, ["rev-list", "--count", "--left-right", `${base}...HEAD`])
    if (!ab.ok) return null
    const [b, a] = ab.out.split(/\s+/)
    const dirty = await dirtyCount(cwd)
    return { mode: "base", compareRef: base, ahead: Number(a) || 0, behind: Number(b) || 0, dirty, computedAt: now }
  }

  // remote mode
  const inside = await runGit(cwd, ["rev-parse", "--is-inside-work-tree"])
  if (!(inside.ok && inside.out === "true")) return null
  const br = await runGit(cwd, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const branch = br.ok && br.out ? br.out : null
  const dirty = await dirtyCount(cwd)
  if (!branch) return { mode: "remote", compareRef: "", ahead: 0, behind: 0, dirty, unpublished: true, computedAt: now }
  const up = await runGit(cwd, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"])
  const upstream = up.ok && up.out ? up.out : null
  if (!upstream) return { mode: "remote", compareRef: branch, ahead: 0, behind: 0, dirty, unpublished: true, computedAt: now }
  const counts = await runGit(cwd, ["rev-list", "--count", "--left-right", "@{upstream}...HEAD"])
  let ahead = 0, behind = 0
  if (counts.ok) { const [b, a] = counts.out.split(/\s+/); behind = Number(b) || 0; ahead = Number(a) || 0 }
  return { mode: "remote", compareRef: upstream, ahead, behind, dirty, computedAt: now }
}
