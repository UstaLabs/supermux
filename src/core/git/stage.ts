// src/core/git/stage.ts
// What goes into the next commit (#72): a file's place in git's index, and putting it in or taking it out.
// The Changes pane shows this as a checkbox per file; it is plain git underneath (add / restore --staged).

import { isAbsolute } from "path"
import { gitAsync } from "./exec"

export type Staged = "all" | "partial" | "none" | "conflict"

export interface IndexState { staged: Set<string>; unstaged: Set<string>; conflicted?: Set<string> }

/** Paths whose index differs from HEAD (staged), whose working copy differs from the index (unstaged), and
 *  that clash mid-merge (conflicted): ONE `git status` (porcelain v2), so a Changes listing stays cheap. */
export async function indexState(repoAbs: string): Promise<IndexState> {
  const out = await gitAsync(repoAbs, ["status", "--porcelain=v2", "-z", "--untracked-files=no", "--no-renames"], { trim: false })
  return parseIndexState(out)
}

/** Parses `git status --porcelain=v2 -z --no-renames`: "1 XY …8 fields… path" and "u XY …10 fields… path". */
export function parseIndexState(out: string): IndexState {
  const staged = new Set<string>(), unstaged = new Set<string>(), conflicted = new Set<string>()
  for (const rec of out.split("\0")) {
    if (rec.startsWith("1 ")) {
      const path = rec.split(" ").slice(8).join(" ")
      if (rec[2] !== ".") staged.add(path)
      if (rec[3] !== ".") unstaged.add(path)
    } else if (rec.startsWith("u ")) {
      conflicted.add(rec.split(" ").slice(10).join(" "))
    }
  }
  return { staged, unstaged, conflicted }
}

/** null: the file has nothing uncommitted (a change since the base that is already committed). */
export function stagedOf(path: string, s: IndexState, untracked = false): Staged | null {
  if (untracked) return "none"
  if (s.conflicted?.has(path)) return "conflict"
  const inIndex = s.staged.has(path), inWork = s.unstaged.has(path)
  if (inIndex) return inWork ? "partial" : "all"
  return inWork ? "none" : null
}

/** A repo-relative path a client may name: no absolute paths, no `..`, no NUL. */
export function safeRepoPath(p: unknown): p is string {
  if (typeof p !== "string" || !p || p.includes("\0") || isAbsolute(p) || /^[a-zA-Z]:/.test(p)) return false
  return !p.split(/[\\/]/).some((seg) => seg === "..")
}

/** Puts whole files into the next commit, or takes them out. Paths are literal (no globs). */
export async function setStaged(repoAbs: string, paths: string[], staged: boolean): Promise<void> {
  const spec = paths.map((p) => `:(literal)${p}`)
  if (staged) {
    await gitAsync(repoAbs, ["add", "-A", "--", ...spec])
    return
  }
  const hasHead = await gitAsync(repoAbs, ["rev-parse", "--verify", "--quiet", "HEAD"]).then(() => true, () => false)
  if (hasHead) await gitAsync(repoAbs, ["restore", "--staged", "--", ...spec])
  else await gitAsync(repoAbs, ["rm", "--cached", "-r", "-q", "--ignore-unmatch", "--", ...spec])
}
