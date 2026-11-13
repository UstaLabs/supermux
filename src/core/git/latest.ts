// src/core/git/latest.ts
// "Get latest from <base>" and its conflicts (#72): the base merged INTO this chat's branch (never a
// rebase), from its freshly fetched tip; a conflicted file resolved as mine, the base's, or both; a
// merge cancelled. The base is explicit (the session's), never a guessed main.

import { mkdtemp, rm, writeFile } from "fs/promises"
import { tmpdir } from "os"
import { join } from "path"
import { gitAsync } from "./exec"
import { freshBase } from "../worktree/manager"

export type LatestResult =
  | { status: "clean"; from: string; commits: number }
  | { status: "up_to_date"; from: string }
  | { status: "conflict"; from: string; files: string[] }
  | { status: "failed"; message: string }

const lit = (p: string) => `:(literal)${p}`

export async function mergeInProgress(dir: string): Promise<boolean> {
  return gitAsync(dir, ["rev-parse", "-q", "--verify", "MERGE_HEAD"]).then(() => true, () => false)
}

export async function unmerged(dir: string): Promise<string[]> {
  const out = await gitAsync(dir, ["diff", "--name-only", "-z", "--diff-filter=U"], { trim: false }).catch(() => "")
  return out.split("\0").filter(Boolean)
}

/** Merges the base (its upstream's tip when that holds everything local) into the branch checked out in `dir`. */
export async function getLatest(dir: string, repoRoot: string, base: string): Promise<LatestResult> {
  if (!base || base === "HEAD") return { status: "failed", message: "this chat has no base branch" }
  if (await mergeInProgress(dir)) return { status: "conflict", from: base, files: await unmerged(dir) }
  const from = await freshBase(repoRoot, base)
  const commits = Number(await gitAsync(dir, ["rev-list", "--count", `HEAD..${from}`]).catch(() => "0")) || 0
  if (!commits) return { status: "up_to_date", from }
  try {
    await gitAsync(dir, ["merge", "--no-edit", from], { timeoutMs: 120_000, env: { ...process.env, GIT_EDITOR: "true" } })
    return { status: "clean", from, commits }
  } catch (err) {
    const files = await unmerged(dir)
    if (files.length) return { status: "conflict", from, files }
    await gitAsync(dir, ["merge", "--abort"]).catch(() => {})
    return { status: "failed", message: err instanceof Error ? err.message : String(err) }
  }
}

export type Side = "mine" | "theirs" | "both"

const stage = (dir: string, n: 1 | 2 | 3, path: string) => gitAsync(dir, ["show", `:${n}:${path}`], { trim: false }).catch(() => null)

/** Resolves one conflicted file and marks it resolved. A side that deleted the file deletes it. */
export async function resolveConflict(dir: string, path: string, side: Side): Promise<void> {
  if (side === "both") {
    const [base, ours, theirs] = await Promise.all([stage(dir, 1, path), stage(dir, 2, path), stage(dir, 3, path)])
    if (ours === null || theirs === null) throw new Error("one side deleted this file; choose mine or theirs")
    const tmp = await mkdtemp(join(tmpdir(), "mux-both-"))
    try {
      const [o, b, t] = [join(tmp, "ours"), join(tmp, "base"), join(tmp, "theirs")]
      await Promise.all([writeFile(o, ours), writeFile(b, base ?? ""), writeFile(t, theirs)])
      const merged = await gitAsync(dir, ["merge-file", "-p", "--union", o, b, t], { trim: false, okExitCodes: [1] })
      await writeFile(join(dir, path), merged)
    } finally {
      await rm(tmp, { recursive: true, force: true })
    }
    await gitAsync(dir, ["add", "--", lit(path)])
    return
  }
  const kept = await stage(dir, side === "mine" ? 2 : 3, path)
  if (kept === null) { await gitAsync(dir, ["rm", "-q", "--", lit(path)]); return }
  await gitAsync(dir, ["checkout", side === "mine" ? "--ours" : "--theirs", "--", lit(path)])
  await gitAsync(dir, ["add", "--", lit(path)])
}

export async function abortMerge(dir: string): Promise<void> {
  await gitAsync(dir, ["merge", "--abort"])
}
