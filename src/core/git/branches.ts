// src/core/git/branches.ts
import { execFileSync } from "child_process"
import { realpathSync } from "fs"

function git(cwd: string, args: string[], timeout = 30_000): { ok: boolean; out: string } {
  try {
    return { ok: true, out: execFileSync("git", args, { cwd, encoding: "utf-8", timeout, stdio: ["pipe", "pipe", "pipe"] }).trim() }
  } catch (e: any) {
    return { ok: false, out: `${e?.stdout?.toString?.() ?? ""}${e?.stderr?.toString?.() ?? ""}`.trim() }
  }
}

export interface LocalBranch { name: string; checkedOutAt: string | null }
export interface BranchList {
  repoRoot: string | null     // toplevel of the enclosing repo, null outside one
  current: string | null      // checked-out branch, null when detached
  detachedSha: string | null  // short HEAD sha when detached
  local: LocalBranch[]        // checkedOutAt = worktree path when the branch is checked out somewhere
  remote: string[]            // e.g. "origin/main"; origin/HEAD symref filtered out
}

/** Repo toplevel (realpath) for a path, or null when not inside a work tree. */
export function repoToplevel(workdir: string): string | null {
  const r = git(workdir, ["rev-parse", "--show-toplevel"])
  if (!r.ok || !r.out) return null
  try { return realpathSync(r.out) } catch { return r.out }
}

/** List local + remote branches with worktree-occupancy info. Local refs only — no network. */
export function listBranches(workdir: string): BranchList {
  const repoRoot = repoToplevel(workdir)
  if (!repoRoot) return { repoRoot: null, current: null, detachedSha: null, local: [], remote: [] }

  const br = git(workdir, ["symbolic-ref", "--quiet", "--short", "HEAD"])
  const current = br.ok && br.out ? br.out : null
  let detachedSha: string | null = null
  if (!current) {
    const sha = git(workdir, ["rev-parse", "--short", "HEAD"])
    detachedSha = sha.ok && sha.out ? sha.out : null
  }

  const locals = git(workdir, ["for-each-ref", "--format=%(refname:short)\t%(worktreepath)", "refs/heads"])
  const local: LocalBranch[] = !locals.ok || !locals.out ? [] : locals.out.split("\n").filter(Boolean).map((line) => {
    const [name, wt] = line.split("\t")
    return { name: name!, checkedOutAt: wt || null }
  })

  // %(refname:short) renders the origin/HEAD symref as "origin/HEAD" or bare
  // "origin" depending on git version — drop /HEAD suffixes and slash-less names.
  const remotes = git(workdir, ["for-each-ref", "--format=%(refname:short)", "refs/remotes"])
  const remote = !remotes.ok || !remotes.out ? [] : remotes.out.split("\n").map((s) => s.trim())
    .filter((n) => n && n.includes("/") && !n.endsWith("/HEAD"))

  return { repoRoot, current, detachedSha, local, remote }
}
