// src/core/git/branches.ts
import { execFileSync } from "child_process"
import { realpathSync } from "fs"
import { mergeInProgress } from "./integrate"

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
  current: string | null      // checked-out branch name; null when detached; non-null but absent from local[] when unborn
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
    let checkedOutAt: string | null = wt || null
    if (checkedOutAt) { try { checkedOutAt = realpathSync(checkedOutAt) } catch { /* keep raw */ } }
    return { name: name!, checkedOutAt }
  })

  // %(refname:short) renders the origin/HEAD symref as "origin/HEAD" or bare
  // "origin" depending on git version — drop /HEAD suffixes and slash-less names.
  const remotes = git(workdir, ["for-each-ref", "--format=%(refname:short)", "refs/remotes"])
  const remote = !remotes.ok || !remotes.out ? [] : remotes.out.split("\n").map((s) => s.trim())
    .filter((n) => n && n.includes("/") && !n.endsWith("/HEAD"))

  return { repoRoot, current, detachedSha, local, remote }
}

export type SwitchResult =
  | { status: "switched"; branch: string }
  | { status: "clobber"; files: string[] }            // git refused: local changes would be overwritten
  | { status: "checked_out_elsewhere"; path: string } // branch held by another worktree
  | { status: "merge_in_progress" }
  | { status: "invalid_name"; message: string }
  | { status: "error"; message: string }

/** Parse `git switch` refusal output into a typed result. */
function classifySwitchFailure(out: string): SwitchResult {
  if (/would be overwritten by checkout/i.test(out)) {
    // Tracked and untracked clobber messages both list the files in an
    // indented block right under a line ending in "overwritten by checkout:".
    const files: string[] = []
    let collecting = false
    for (const line of out.split("\n")) {
      if (/overwritten by checkout:/i.test(line)) { collecting = true; continue }
      if (!collecting) continue
      const m = line.match(/^\s+(\S.*)$/)
      if (m) files.push(m[1]!.trim())
      else if (files.length) collecting = false
    }
    return { status: "clobber", files }
  }
  const wt = out.match(/already (?:checked out|used by worktree) at '([^']+)'/i)
  if (wt) return { status: "checked_out_elsewhere", path: wt[1]! }
  return { status: "error", message: out }
}

/** Switch the checkout to a branch. `create` makes a new branch off HEAD; a
 *  name from the remote list (e.g. "origin/foo") checks out a local tracking
 *  branch. Plain `git switch` semantics otherwise: non-conflicting uncommitted
 *  changes carry over, clobbering ones make git refuse (→ `clobber`). */
export function switchBranch(workdir: string, name: string, opts?: { create?: boolean }): SwitchResult {
  const target = name.trim()
  if (!target) return { status: "invalid_name", message: "branch name required" }
  if (mergeInProgress(workdir)) return { status: "merge_in_progress" }

  if (opts?.create) {
    if (target.startsWith("-")) return { status: "invalid_name", message: `invalid branch name: ${target}` }
    const v = git(workdir, ["check-ref-format", "--branch", target])
    if (!v.ok) return { status: "invalid_name", message: v.out || `invalid branch name: ${target}` }
    const r = git(workdir, ["switch", "-c", target])
    return r.ok ? { status: "switched", branch: target } : classifySwitchFailure(r.out)
  }

  const list = listBranches(workdir)

  if (list.local.some((b) => b.name === target)) {
    const r = git(workdir, ["switch", target])
    return r.ok ? { status: "switched", branch: target } : classifySwitchFailure(r.out)
  }
  return { status: "error", message: `no such branch: ${target}` }
}
