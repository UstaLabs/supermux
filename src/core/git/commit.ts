// src/core/git/commit.ts
// Commit, amend and undo the last commit (#72). Plain git with the repo's own identity and hooks.
// An empty message is written from the change itself; a model-written one is a separate step.

import { basename } from "path"
import { gitAsync } from "./exec"

export type CommitResult = { ok: true; sha: string; subject: string } | { ok: false; reason: "nothing" | "failed"; message?: string }

const hasHead = (repo: string) => gitAsync(repo, ["rev-parse", "--verify", "--quiet", "HEAD"]).then(() => true, () => false)

/** "Update App.jsx", "Update App.jsx and server.ts", "Update App.jsx and 3 more files". */
export function messageFromFiles(paths: string[]): string {
  const names = [...new Set(paths.map((p) => basename(p)))]
  if (names.length === 0) return "Update files"
  if (names.length === 1) return `Update ${names[0]}`
  if (names.length === 2) return `Update ${names[0]} and ${names[1]}`
  return `Update ${names[0]} and ${names.length - 1} more files`
}

async function stagedPaths(repo: string): Promise<string[]> {
  const out = await gitAsync(repo, ["diff", "--cached", "--name-only", "-z", "--no-renames"], { trim: false })
  return out.split("\0").filter(Boolean)
}

/**
 * Commits what is in the next commit. `all`: everything uncommitted goes in first (what the button
 * offers when nothing is ticked). `amend`: rewrites the last commit; an empty message keeps its own.
 */
export async function commit(repo: string, opts: { message?: string; all?: boolean; amend?: boolean }): Promise<CommitResult> {
  try {
    if (opts.all) await gitAsync(repo, ["add", "-A"])
    const merging = await gitAsync(repo, ["rev-parse", "-q", "--verify", "MERGE_HEAD"]).then(() => true, () => false)
    const paths = await stagedPaths(repo)
    if (!paths.length && !opts.amend && !merging) return { ok: false, reason: "nothing" }
    const msg = opts.message?.trim()
    const args = ["commit", "--quiet"]
    if (opts.amend) args.push("--amend", ...(msg ? ["-m", msg] : ["--no-edit"]))
    else if (merging && !msg) args.push("--no-edit")   // "Continue merge": git's own merge message
    else args.push("-m", msg || messageFromFiles(paths))
    await gitAsync(repo, args, { timeoutMs: 120_000, env: { ...process.env, GIT_EDITOR: "true" } })
    const [sha, subject] = (await gitAsync(repo, ["log", "-1", "--format=%H%x00%s"])).split("\0")
    return { ok: true, sha: sha!, subject: subject ?? "" }
  } catch (err) {
    return { ok: false, reason: "failed", message: err instanceof Error ? err.message : String(err) }
  }
}

export type UncommitResult = { ok: true; subject: string } | { ok: false; reason: "none" | "merge" | "pushed" | "failed"; message?: string }

/**
 * Undoes the last commit and keeps its changes in the next commit (`reset --soft HEAD~1`). Refused for
 * a merge commit, for the first commit, and for one the upstream already has: those are not undone here.
 */
export async function uncommit(repo: string): Promise<UncommitResult> {
  try {
    if (!(await hasHead(repo))) return { ok: false, reason: "none" }
    const parents = (await gitAsync(repo, ["rev-list", "--parents", "-n", "1", "HEAD"])).split(" ").length - 1
    if (parents === 0) return { ok: false, reason: "none" }
    if (parents > 1) return { ok: false, reason: "merge" }
    const upstream = await gitAsync(repo, ["rev-parse", "--verify", "--quiet", "@{upstream}"]).catch(() => "")
    if (upstream) {
      const pushed = await gitAsync(repo, ["merge-base", "--is-ancestor", "HEAD", "@{upstream}"]).then(() => true, () => false)
      if (pushed) return { ok: false, reason: "pushed" }
    }
    const subject = await gitAsync(repo, ["log", "-1", "--format=%s"])
    await gitAsync(repo, ["reset", "--soft", "HEAD~1"])
    return { ok: true, subject }
  } catch (err) {
    return { ok: false, reason: "failed", message: err instanceof Error ? err.message : String(err) }
  }
}
