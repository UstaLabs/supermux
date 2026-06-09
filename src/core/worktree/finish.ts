// src/core/worktree/finish.ts
import { execFileSync } from "child_process"
import { syncBaseIntoBranch, integrateFastForward, dirtyFiles, mergeInProgress } from "../git/integrate"
import { resolveVerifyCommand, runVerify } from "./verify"

export interface FinishSession { repoRoot: string; worktreeDir: string; sessionBranch: string; baseBranch: string }
export interface FinishOpts { skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string }

export type FinishResult =
  | { status: "integrated"; base: string; branch: string; mergedSha: string; verified: string | null }
  | { status: "nothing_to_do" }
  | { status: "sync_conflict"; files: string[] }
  | { status: "tests_failed"; command: string; output: string }
  | { status: "dirty_overlap"; files: string[] }
  | { status: "non_ff" }
  | { status: "no_verify" }
  | { status: "uncommitted"; files: string[] }
  | { status: "error"; message: string }

function hasCommitsToIntegrate(repoRoot: string, base: string, branch: string): boolean {
  try {
    return execFileSync("git", ["-C", repoRoot, "rev-list", "--count", `${base}..${branch}`], { encoding: "utf-8" }).trim() !== "0"
  } catch { return false }
}

/** Re-entrant finish: sync base in → verify → ff-only integrate. Returns the
 *  first non-advancing state (conflict / red tests / dirty overlap / non-ff) so
 *  the caller can surface it; the worktree is left resolvable and NOT removed
 *  (the session is still live in it). */
export async function finishWorktree(s: FinishSession, opts?: FinishOpts, onProgress?: (stage: string) => void): Promise<FinishResult> {
  if (!s.baseBranch || s.baseBranch === "HEAD") return { status: "error", message: "session has no known base branch to finish into" }
  // Emit a stage label, then yield a tick so the WS frame flushes before the
  // (synchronous) git/test step blocks the event loop.
  const progress = async (stage: string) => { onProgress?.(stage); await new Promise<void>((r) => setImmediate(r)) }

  // 0) Uncommitted work? Finish integrates COMMITS, not the working tree — so
  //    capture it first (when the user opts in via the card) or surface it loudly
  //    rather than silently reporting "nothing to do". Runs before sync because a
  //    merge into a dirty tree is unsafe. Skipped mid-merge: a resolved-but-uncommitted
  //    conflict looks "dirty" but is sync's job to complete (else we'd commit conflict markers).
  const dirty = mergeInProgress(s.worktreeDir) ? [] : dirtyFiles(s.worktreeDir)
  if (dirty.length) {
    if (!opts?.commitFirst) return { status: "uncommitted", files: dirty }
    await progress("Committing…")
    const msg = opts.commitMessage?.trim() || "Session changes"
    try {
      execFileSync("git", ["-C", s.worktreeDir, "add", "-A"], { encoding: "utf-8" })
      execFileSync("git", ["-C", s.worktreeDir, "commit", "-m", msg, "--no-verify"], { encoding: "utf-8" })
    } catch (e: any) {
      return { status: "error", message: `commit failed: ${(e?.stderr ?? e?.message ?? e)?.toString?.().trim?.() ?? String(e)}` }
    }
  }

  // 1) Sync base into the session branch (resolves a prior conflicted merge if the agent fixed it).
  await progress(`Syncing ${s.baseBranch}…`)
  const sync = syncBaseIntoBranch(s.worktreeDir, s.baseBranch)
  if (sync.status === "conflict") return { status: "sync_conflict", files: sync.files }

  // 2) Nothing to integrate? (after sync, the branch has no commits beyond base)
  if (!hasCommitsToIntegrate(s.repoRoot, s.baseBranch, s.sessionBranch)) return { status: "nothing_to_do" }

  // 3) Verify on the merged result by running .mux/verify.sh (or refuse loudly).
  let verified: string | null = null
  if (!opts?.skipVerify) {
    const cmd = resolveVerifyCommand(s.worktreeDir)
    if (!cmd) return { status: "no_verify" }
    await progress("Running tests…")
    const res = runVerify(s.worktreeDir, cmd)
    if (!res.ok) return { status: "tests_failed", command: cmd, output: res.output.slice(-4000) }
    verified = cmd
  }

  // 4) Integrate ff-only, checkout-aware.
  await progress("Merging…")
  const r = integrateFastForward(s.repoRoot, s.baseBranch, s.sessionBranch)
  if (r.status === "integrated") return { status: "integrated", base: s.baseBranch, branch: s.sessionBranch, mergedSha: r.mergedSha, verified }
  if (r.status === "dirty_overlap") return { status: "dirty_overlap", files: r.files }
  if (r.status === "non_ff") return { status: "non_ff" }
  return { status: "error", message: r.message }
}
