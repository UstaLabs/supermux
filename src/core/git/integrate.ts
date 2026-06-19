// src/core/git/integrate.ts
import { execFileSync } from "child_process"

function git(cwd: string, args: string[], timeout = 120_000): { ok: boolean; out: string } {
  try {
    return { ok: true, out: execFileSync("git", args, { cwd, encoding: "utf-8", timeout, stdio: ["pipe", "pipe", "pipe"] }).trim() }
  } catch (e: any) {
    return { ok: false, out: `${e?.stdout?.toString?.() ?? ""}${e?.stderr?.toString?.() ?? ""}`.trim() }
  }
}

export interface Worktree { path: string; branch?: string }

export function listWorktrees(repoRoot: string): Worktree[] {
  const r = git(repoRoot, ["worktree", "list", "--porcelain"])
  if (!r.ok) return []
  const out: Worktree[] = []
  let cur: Worktree | null = null
  for (const line of r.out.split("\n")) {
    if (line.startsWith("worktree ")) { if (cur) out.push(cur); cur = { path: line.slice("worktree ".length) } }
    else if (line.startsWith("branch ") && cur) cur.branch = line.slice("branch ".length).replace(/^refs\/heads\//, "")
  }
  if (cur) out.push(cur)
  return out
}

/** Path where `branch` is checked out, or null if nowhere. */
export function findBranchCheckout(repoRoot: string, branch: string): string | null {
  return listWorktrees(repoRoot).find((w) => w.branch === branch)?.path ?? null
}

/** True iff `ancestor` is a strict ancestor of `descendant` (fast-forwardable). */
export function isAncestor(repoRoot: string, ancestor: string, descendant: string): boolean {
  return git(repoRoot, ["merge-base", "--is-ancestor", ancestor, descendant]).ok
}

/** Repo-relative paths dirty (modified/staged/untracked) in a checkout. */
export function dirtyFiles(checkoutDir: string): string[] {
  // Robust path list: tracked changes vs HEAD (renames → new path) + individual
  // untracked files. Avoids fragile porcelain-status parsing.
  const out = new Set<string>()
  const tracked = git(checkoutDir, ["diff", "--name-only", "HEAD"])
  const untracked = git(checkoutDir, ["ls-files", "--others", "--exclude-standard"])
  for (const r of [tracked, untracked]) {
    if (r.ok && r.out) for (const l of r.out.split("\n")) { const p = l.trim(); if (p) out.add(p) }
  }
  return [...out]
}

/** Files changed between two revs. */
export function changedFiles(repoRoot: string, from: string, to: string): string[] {
  const r = git(repoRoot, ["diff", "--name-only", from, to])
  return r.ok ? r.out.split("\n").map((s) => s.trim()).filter(Boolean) : []
}

function unmergedFiles(worktreeDir: string): string[] {
  const r = git(worktreeDir, ["diff", "--name-only", "--diff-filter=U"])
  return r.ok ? r.out.split("\n").filter(Boolean) : []
}
export function mergeInProgress(worktreeDir: string): boolean {
  return git(worktreeDir, ["rev-parse", "-q", "--verify", "MERGE_HEAD"]).ok
}

export type SyncResult = { status: "clean" } | { status: "up_to_date" } | { status: "conflict"; files: string[] }

/** Merge `baseBranch` INTO the worktree's current branch. Re-entrant: resumes a
 *  prior conflicted merge once the agent has resolved it. On conflict, leaves the
 *  merge in progress (so the agent can resolve + the next call completes it). */
export function syncBaseIntoBranch(worktreeDir: string, baseBranch: string): SyncResult {
  if (mergeInProgress(worktreeDir)) {
    const u = unmergedFiles(worktreeDir)
    if (u.length) return { status: "conflict", files: u }
    // Complete the merge the agent resolved. --no-verify: commit hooks (lint/test)
    // must not gate internal merge plumbing — the verify step runs tests separately.
    const c = git(worktreeDir, ["commit", "--no-edit", "--no-verify"])
    if (!c.ok) return { status: "conflict", files: unmergedFiles(worktreeDir) }
    return { status: "clean" }
  }
  const ahead = git(worktreeDir, ["rev-list", "--count", `HEAD..${baseBranch}`])
  if (ahead.ok && ahead.out === "0") return { status: "up_to_date" }
  const m = git(worktreeDir, ["merge", "--no-edit", "--no-verify", baseBranch])
  if (m.ok) return { status: "clean" }
  const u = unmergedFiles(worktreeDir)
  if (u.length) return { status: "conflict", files: u } // leave it for the agent to resolve
  git(worktreeDir, ["merge", "--abort"]) // non-conflict failure → don't leave a broken state
  return { status: "conflict", files: [] }
}

export type IntegrateResult =
  | { status: "integrated"; mergedSha: string }
  | { status: "non_ff" }
  | { status: "dirty_overlap"; files: string[] }
  | { status: "error"; message: string }

export function aheadBehind(worktreeOrRepo: string, base: string): { ahead: number; behind: number } {
  const r = git(worktreeOrRepo, ["rev-list", "--count", "--left-right", `${base}...HEAD`])
  if (!r.ok) return { ahead: 0, behind: 0 }
  const [b, a] = r.out.split(/\s+/)
  return { ahead: Number(a) || 0, behind: Number(b) || 0 }
}

export function diffStats(repoRoot: string, from: string, to: string): { filesChanged: number; insertions: number; deletions: number } {
  const r = git(repoRoot, ["diff", "--numstat", `${from}..${to}`])
  if (!r.ok || !r.out) return { filesChanged: 0, insertions: 0, deletions: 0 }
  let files = 0, ins = 0, del = 0
  for (const line of r.out.split("\n")) {
    if (!line.trim()) continue
    const [a, d] = line.split("\t")
    if (a === undefined) continue
    files++; ins += Number(a) || 0; del += Number(d) || 0  // "-" (binary) → 0
  }
  return { filesChanged: files, insertions: ins, deletions: del }
}

export function mergeTreePreflight(repoRoot: string, base: string, branch: string): "clean" | "will_conflict" | "unknown" {
  // git 2.38+ `merge-tree --write-tree` is non-destructive (no MERGE_HEAD) and exits
  // NON-ZERO on conflict — so the primary conflict detection is in the non-ok branch
  // (where "CONFLICT" text appears in r.out). The `<<<<<<<` check in the ok-branch is
  // a defensive fallback. Older git lacks the flag → return "unknown" rather than risk a mutation.
  const r = git(repoRoot, ["merge-tree", "--write-tree", base, branch])
  if (r.ok) return r.out.includes("<<<<<<<") ? "will_conflict" : "clean"
  const lower = r.out.toLowerCase()
  if (lower.includes("conflict") || r.out.includes("<<<<<<<")) return "will_conflict"
  return "unknown"
}

export function gitOk(cwd: string, args: string[]): boolean { return git(cwd, args).ok }

/** Advance `baseBranch` to `sessionBranch`, fast-forward ONLY, checkout-aware.
 *  Never creates a merge commit, never moves base backward, never `checkout`s a
 *  dirty base. */
export function integrateFastForward(repoRoot: string, baseBranch: string, sessionBranch: string): IntegrateResult {
  const V = git(repoRoot, ["rev-parse", sessionBranch])
  if (!V.ok) return { status: "error", message: `cannot resolve ${sessionBranch}` }
  const OLDM = git(repoRoot, ["rev-parse", baseBranch])
  if (!OLDM.ok) return { status: "error", message: `cannot resolve ${baseBranch}` }
  if (!isAncestor(repoRoot, baseBranch, sessionBranch)) return { status: "non_ff" } // base moved under us

  const checkout = findBranchCheckout(repoRoot, baseBranch)
  if (!checkout) {
    const r = git(repoRoot, ["update-ref", `refs/heads/${baseBranch}`, V.out, OLDM.out]) // CAS
    return r.ok ? { status: "integrated", mergedSha: V.out } : { status: "non_ff" }
  }
  const overlap = changedFiles(repoRoot, OLDM.out, V.out).filter((f) => dirtyFiles(checkout).includes(f))
  if (overlap.length) return { status: "dirty_overlap", files: overlap }
  const ff = git(checkout, ["merge", "--ff-only", sessionBranch])
  return ff.ok ? { status: "integrated", mergedSha: V.out } : { status: "non_ff" }
}
