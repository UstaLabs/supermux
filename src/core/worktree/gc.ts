// src/core/worktree/gc.ts
import { execFileSync } from "child_process"
import { existsSync } from "fs"

function git(cwd: string, args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8", timeout: 15_000, stdio: ["pipe", "pipe", "pipe"] }).trim()
}

/** Reclaimable iff the worktree is clean (no uncommitted/untracked changes) AND
 *  its branch has no commits that aren't already in base.
 *
 *  Refuses (keeps the worktree) when the base is unknown/detached ("HEAD" or
 *  empty): `HEAD..<branch>` evaluated inside the worktree resolves HEAD to the
 *  worktree's OWN branch tip, so the count is always 0 and committed-but-unmerged
 *  work would be silently classified reclaimable and deleted. Conservative-keep
 *  is the safe choice — better to leak a worktree than lose committed work. */
export function isWorktreeReclaimable(worktreeDir: string, sessionBranch: string, baseBranch: string): boolean {
  if (!existsSync(worktreeDir)) return true // already gone → safe to prune refs
  let dirty: string
  try { dirty = git(worktreeDir, ["status", "--porcelain"]) } catch { return false }
  if (dirty !== "") return false
  if (!baseBranch || baseBranch === "HEAD") return false // base unknown → keep
  try { return git(worktreeDir, ["rev-list", "--count", `${baseBranch}..${sessionBranch}`]) === "0" }
  catch { return false }
}
