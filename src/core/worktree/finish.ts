// src/core/worktree/finish.ts
import { execFileSync } from "child_process"
import { resolve, sep } from "path"
import { syncBaseIntoBranch, integrateFastForward, dirtyFiles, mergeInProgress } from "../git/integrate"
import { resolveVerifyCommand, runVerify } from "./verify"
import { removeWorktree, worktreesRoot } from "./manager"
import { publishBranch } from "../git/remote"
import { openPullRequest, compareUrl } from "../git/pr"

export interface FinishSession { repoRoot: string; worktreeDir: string; sessionBranch: string; baseBranch: string }
export interface FinishOpts { skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string; cleanup?: boolean; deleteBranch?: boolean; draft?: boolean; prRequiresGreen?: boolean; prTitle?: string; prBody?: string }

export type FinishResult =
  | { status: "integrated"; base: string; branch: string; mergedSha: string; verified: string | null; cleanedUp: boolean }
  | { status: "pr_opened"; branch: string; prUrl: string; draft: boolean; verified: string | null }
  | { status: "branch_published"; branch: string; compareUrl: string | null; verified: string | null; prError?: string }
  | { status: "push_rejected"; branch: string; message: string }
  | { status: "kept"; branch: string }
  | { status: "discarded"; branch: string }
  | { status: "push_auth_failed"; message: string }
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

/** Only worktrees mux itself created (under worktreesRoot()) may be auto-removed. */
function isMuxOwned(worktreeDir: string): boolean {
  return resolve(worktreeDir).startsWith(resolve(worktreesRoot()) + sep)
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
  if (r.status === "integrated") {
    // Cleanup is atomic with a *successful* merge: only ever runs after the base
    // branch advanced. Gated on opt-in + env escape hatch + mux-owned provenance.
    let cleanedUp = false
    if (opts?.cleanup && !process.env.MUX_DISABLE_WORKTREE_CLEANUP && isMuxOwned(s.worktreeDir)) {
      try { await removeWorktree(s.repoRoot, s.worktreeDir, s.sessionBranch, { keepBranch: opts.deleteBranch === false }); cleanedUp = true }
      catch { cleanedUp = false }
    }
    return { status: "integrated", base: s.baseBranch, branch: s.sessionBranch, mergedSha: r.mergedSha, verified, cleanedUp }
  }
  if (r.status === "dirty_overlap") return { status: "dirty_overlap", files: r.files }
  if (r.status === "non_ff") return { status: "non_ff" }
  return { status: "error", message: r.message }
}

/** Deterministic PR title/body from a branch's commits (no LLM). Title = the
 *  oldest commit's subject (usually the headline change); body = a bullet list
 *  of all subjects. Falls back to the branch name when there are no commits. */
export function derivePrText(repoRoot: string, base: string, branch: string): { title: string; body: string } {
  let subjects: string[] = []
  try {
    const out = execFileSync("git", ["-C", repoRoot, "log", "--reverse", "--format=%s", `${base}..${branch}`], { encoding: "utf-8" }).trim()
    subjects = out ? out.split("\n").map((s) => s.trim()).filter(Boolean) : []
  } catch { subjects = [] }
  const title = subjects[0] || branch
  const body = subjects.length ? subjects.map((s) => `- ${s}`).join("\n") : ""
  return { title, body }
}

/** PR variant of finish: commit (opt-in) → sync base → verify → push → open PR.
 *  Mirrors finishWorktree's gating, but instead of fast-forwarding the base it
 *  publishes the branch and opens a PR (draft when tests are red, unless
 *  prRequiresGreen, which hard-blocks instead). Falls back to a compare URL when
 *  gh is unavailable. The worktree is left intact (no cleanup). */
export async function openPrForSession(s: FinishSession, opts?: FinishOpts, onProgress?: (stage: string) => void): Promise<FinishResult> {
  if (!s.baseBranch || s.baseBranch === "HEAD") return { status: "error", message: "session has no known base branch" }
  const progress = async (stage: string) => { onProgress?.(stage); await new Promise<void>((r) => setImmediate(r)) }

  // commit dirty work if opted in (same as merge)
  const dirty = mergeInProgress(s.worktreeDir) ? [] : dirtyFiles(s.worktreeDir)
  if (dirty.length) {
    if (!opts?.commitFirst) return { status: "uncommitted", files: dirty }
    await progress("Committing…")
    try {
      execFileSync("git", ["-C", s.worktreeDir, "add", "-A"], { encoding: "utf-8" })
      execFileSync("git", ["-C", s.worktreeDir, "commit", "-m", opts.commitMessage?.trim() || "Session changes", "--no-verify"], { encoding: "utf-8" })
    } catch (e: any) { return { status: "error", message: `commit failed: ${String(e?.stderr ?? e?.message ?? e).trim()}` } }
  }

  await progress(`Syncing ${s.baseBranch}…`)
  const sync = syncBaseIntoBranch(s.worktreeDir, s.baseBranch)
  if (sync.status === "conflict") return { status: "sync_conflict", files: sync.files }
  if (!hasCommitsToIntegrate(s.repoRoot, s.baseBranch, s.sessionBranch)) return { status: "nothing_to_do" }

  let verified: string | null = null
  let red = false
  if (!opts?.skipVerify) {
    const cmd = resolveVerifyCommand(s.worktreeDir)
    if (!cmd) return { status: "no_verify" }
    await progress("Running tests…")
    const res = runVerify(s.worktreeDir, cmd)
    if (!res.ok) { if (opts?.prRequiresGreen) return { status: "tests_failed", command: cmd, output: res.output.slice(-4000) }; red = true }
    else verified = cmd
  }

  await progress("Pushing…")
  const pub = publishBranch(s.worktreeDir)
  if (pub.status === "auth_failed") return { status: "push_auth_failed", message: pub.message }
  if (pub.status === "rejected_non_ff") return { status: "push_rejected", branch: s.sessionBranch, message: "Remote branch diverged — pull/rebase or force-push before opening a PR." }
  if (pub.status === "error") return { status: "error", message: pub.message }

  await progress("Opening PR…")
  const draft = red || !!opts?.draft
  const drafted = (!opts?.prTitle || !opts?.prBody) ? derivePrText(s.repoRoot, s.baseBranch, s.sessionBranch) : null
  const title = opts?.prTitle || drafted?.title || s.sessionBranch
  const body = opts?.prBody || drafted?.body || ""
  const pr = openPullRequest(s.worktreeDir, { title, body, base: s.baseBranch, draft })
  if (pr.status === "opened") return { status: "pr_opened", branch: s.sessionBranch, prUrl: pr.url, draft, verified }
  return { status: "branch_published", branch: s.sessionBranch, compareUrl: compareUrl(s.worktreeDir, s.baseBranch, s.sessionBranch), verified, prError: pr.status === "error" ? pr.message : undefined }
}

/** Throw the session away: force-remove the (mux-owned) worktree + its branch
 *  without integrating anything. The base branch is never touched. */
export async function discardSession(s: FinishSession): Promise<FinishResult> {
  if (isMuxOwned(s.worktreeDir)) await removeWorktree(s.repoRoot, s.worktreeDir, s.sessionBranch, { force: true })
  return { status: "discarded", branch: s.sessionBranch }
}
