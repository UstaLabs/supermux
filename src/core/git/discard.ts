// src/core/git/discard.ts
// "Discard Changes" (#72), as VS Code does it: a file's unstaged edits go back to what is staged (or to the last
// commit when nothing is), a new file is deleted. What is staged is never touched. "all" is the whole Changes
// group: every unstaged edit and every new file.

import { gitAsync } from "./exec"

export type DiscardResult =
  | { ok: true; discarded: string[] }
  | { ok: false; reason: "conflict"; paths: string[] }

const lit = (p: string) => `:(literal)${p}`

/** Unstaged tracked paths, new (untracked) paths and conflicted paths, from ONE `git status`. */
async function worktreeState(repoAbs: string): Promise<{ changed: Set<string>; untracked: Set<string>; conflicted: Set<string> }> {
  const out = await gitAsync(repoAbs, ["status", "--porcelain=v2", "-z", "--untracked-files=all", "--no-renames"], { trim: false })
  const changed = new Set<string>(), untracked = new Set<string>(), conflicted = new Set<string>()
  for (const rec of out.split("\0")) {
    if (rec.startsWith("1 ")) { if (rec[3] !== ".") changed.add(rec.split(" ").slice(8).join(" ")) }
    else if (rec.startsWith("u ")) conflicted.add(rec.split(" ").slice(10).join(" "))
    else if (rec.startsWith("? ")) untracked.add(rec.slice(2))
  }
  return { changed, untracked, conflicted }
}

/** Throws away the unstaged changes of `paths` (repo-relative, literal), or of the whole Changes group. A path with
 *  nothing to discard is skipped. A path in a conflict refuses the whole call, so a merge is never half thrown away. */
export async function discard(repoAbs: string, paths: string[] | "all"): Promise<DiscardResult> {
  const s = await worktreeState(repoAbs)
  if (paths !== "all") {
    const clash = paths.filter((p) => s.conflicted.has(p))
    if (clash.length) return { ok: false, reason: "conflict", paths: clash }
  }
  const want = paths === "all" ? null : new Set(paths)
  const tracked = [...s.changed].filter((p) => !want || want.has(p))
  const fresh = [...s.untracked].filter((p) => !want || want.has(p))
  if (tracked.length) await gitAsync(repoAbs, ["restore", "--worktree", "--", ...tracked.map(lit)])
  if (fresh.length) await gitAsync(repoAbs, ["clean", "-f", "-q", "--", ...fresh.map(lit)])
  return { ok: true, discarded: [...tracked, ...fresh] }
}
