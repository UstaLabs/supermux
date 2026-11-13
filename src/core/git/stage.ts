// src/core/git/stage.ts
// What goes into the next commit (#72): a file's place in git's index, and putting it in or taking it out.
// The Changes pane shows this as a checkbox per file; it is plain git underneath (add / restore --staged).

import { isAbsolute } from "path"
import { gitAsync } from "./exec"

export type Staged = "all" | "partial" | "none"

export interface IndexState { staged: Set<string>; unstaged: Set<string> }

const names = (out: string) => new Set(out.split("\0").filter(Boolean))

/** Paths whose index differs from HEAD (staged) and whose working copy differs from the index (unstaged). */
export async function indexState(repoAbs: string): Promise<IndexState> {
  const [staged, unstaged] = await Promise.all([
    gitAsync(repoAbs, ["diff", "--cached", "--name-only", "-z", "--no-renames"], { trim: false }),
    gitAsync(repoAbs, ["diff", "--name-only", "-z", "--no-renames"], { trim: false }),
  ])
  return { staged: names(staged), unstaged: names(unstaged) }
}

/** null: the file has nothing uncommitted (a change since the base that is already committed). */
export function stagedOf(path: string, s: IndexState, untracked = false): Staged | null {
  if (untracked) return "none"
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
