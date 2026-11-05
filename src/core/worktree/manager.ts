// src/core/worktree/manager.ts
import { existsSync, readFileSync, readdirSync } from "fs"
import { cp, mkdir, rm } from "fs/promises"
import { basename, dirname, join, resolve, sep } from "path"
import { createHash, randomUUID } from "crypto"
import { home } from "../../shared/home"
import { normalizeName } from "../../shared/slug"
import { ensureUnique } from "../session-manager/naming"
import { gitAsync, runAsync } from "../git/exec"
import { makeLogger } from "../../shared/log"
import { msSince } from "../perf/start-trace"

const log = makeLogger("worktree")

export interface WorktreeHandle {
  worktreeDir: string
  sessionBranch: string
  baseBranch: string
  repoRoot: string
}

/** Where session worktrees live. MUX_WORKTREES_ROOT (read per call, not at module load)
 *  overrides it — the test preload points it at a throwaway dir so tests never create
 *  folders in the live ~/.mux/worktrees (~24k leaked before 2026-09-22). */
export function worktreesRoot(): string {
  return process.env.MUX_WORKTREES_ROOT || join(home(), ".mux", "worktrees")
}

export function repoSlug(repoRoot: string): string {
  const h = createHash("sha1").update(repoRoot).digest("hex").slice(0, 8)
  return `${basename(repoRoot)}-${h}`
}

export async function existingBranchNames(repoRoot: string): Promise<Set<string>> {
  try {
    const out = await gitAsync(repoRoot, ["for-each-ref", "--format=%(refname:short)", "refs/heads"])
    return new Set(out.split("\n").map((s) => s.trim()).filter(Boolean))
  } catch { return new Set() }
}

/** `mux/<slug>` with the leaf made unique against existing branch leaves. */
export function deriveSessionBranch(sessionName: string, taken: Set<string>): string {
  const slug = normalizeName(sessionName) || "session"
  const takenLeaves = new Set([...taken].map((b) => b.replace(/^mux\//, "")))
  return `mux/${ensureUnique(slug, takenLeaves)}`
}

/** Card #75: how long one step of createWorktree took (ms), and what it did. */
export type WorktreePhase = (name: "branches" | "add" | "includes" | "hook", fields: Record<string, unknown>) => void

export async function createWorktree(opts: {
  repoRoot: string; baseBranch: string; sessionName: string; onPhase?: WorktreePhase
}): Promise<WorktreeHandle> {
  const phase = opts.onPhase
  let t = performance.now()
  const branches = await existingBranchNames(opts.repoRoot)
  phase?.("branches", { ms: msSince(t), count: branches.size })
  const sessionBranch = deriveSessionBranch(opts.sessionName, branches)
  const worktreeDir = join(worktreesRoot(), repoSlug(opts.repoRoot), randomUUID())
  t = performance.now()
  await mkdir(dirname(worktreeDir), { recursive: true })
  await gitAsync(opts.repoRoot, ["worktree", "add", "-b", sessionBranch, worktreeDir, opts.baseBranch])
  phase?.("add", { ms: msSince(t), branch: sessionBranch })
  t = performance.now()
  const copied = await copyWorktreeIncludes(opts.repoRoot, worktreeDir)
  phase?.("includes", { ms: msSince(t), copied })
  t = performance.now()
  const hook = await runSetupHook(opts.repoRoot, worktreeDir, sessionBranch, opts.baseBranch)
  phase?.("hook", { ms: msSince(t), ...hook })
  return { worktreeDir, sessionBranch, baseBranch: opts.baseBranch, repoRoot: opts.repoRoot }
}

export async function removeWorktree(repoRoot: string, worktreeDir: string, sessionBranch: string, opts?: { force?: boolean; keepBranch?: boolean }): Promise<void> {
  try { await gitAsync(repoRoot, ["worktree", "remove", ...(opts?.force ? ["--force"] : []), worktreeDir]) } catch { /* may already be gone */ }
  try { await gitAsync(repoRoot, ["worktree", "prune"]) } catch {}
  if (!opts?.keepBranch) { try { await gitAsync(repoRoot, ["branch", "-D", sessionBranch]) } catch {} }
}

/** Card #75: a session born asleep gets its branch at Send (so two quick Sends can't pick one name); the checkout waits for its wake. */
export async function createSessionBranch(repoRoot: string, branch: string, base: string): Promise<void> {
  await gitAsync(repoRoot, ["branch", branch, base])
}

/**
 * Makes sure the session's worktree is checked out at `workdir`: a session born asleep (branch made
 * at Send), or one whose folder was removed (merged, cleaned up). A fresh checkout gets what
 * createWorktree's gets: the `.worktreeinclude` copies and the setup hook. A folder with anything in
 * it is used as it is, never removed: it may hold work. Only an empty one inside supermux's
 * worktrees root (a crash before the checkout) is removed and made again.
 */
export async function ensureWorktreeAt(opts: {
  repoRoot: string; workdir: string; sessionBranch: string; baseBranch: string
}): Promise<{ created: boolean }> {
  if (existsSync(opts.workdir)) {
    if (readdirSync(opts.workdir).length > 0) return { created: false }
    if (!resolve(opts.workdir).startsWith(resolve(worktreesRoot()) + sep)) return { created: false }
    log.warn("worktree_empty_folder_removed", { workdir: opts.workdir })
    await rm(opts.workdir, { recursive: true, force: true })
  }
  await mkdir(dirname(opts.workdir), { recursive: true })
  await gitAsync(opts.repoRoot, ["worktree", "prune"])
  if ((await existingBranchNames(opts.repoRoot)).has(opts.sessionBranch)) {
    // The branch exists (made at Send, or only the dir was removed): check it out at the same path.
    await gitAsync(opts.repoRoot, ["worktree", "add", opts.workdir, opts.sessionBranch])
  } else {
    // Branch was deleted (e.g., after merge) — recreate it from the base so work can continue.
    await gitAsync(opts.repoRoot, ["worktree", "add", "-b", opts.sessionBranch, opts.workdir, opts.baseBranch])
  }
  await copyWorktreeIncludes(opts.repoRoot, opts.workdir)
  await runSetupHook(opts.repoRoot, opts.workdir, opts.sessionBranch, opts.baseBranch)
  return { created: true }
}

/** Copies the `.worktreeinclude` entries into the new worktree; returns how many it copied. */
async function copyWorktreeIncludes(repoRoot: string, worktreeDir: string): Promise<number> {
  const inc = join(repoRoot, ".worktreeinclude")
  if (!existsSync(inc)) return 0
  let copied = 0
  for (const raw of readFileSync(inc, "utf-8").split("\n")) {
    const line = raw.trim()
    if (!line || line.startsWith("#")) continue
    const src = join(repoRoot, line)
    const dst = join(worktreeDir, line)
    // Containment guard: never read outside the repo or write outside the worktree.
    if (!resolve(src).startsWith(resolve(repoRoot) + sep) || !resolve(dst).startsWith(resolve(worktreeDir) + sep)) continue
    if (!existsSync(src)) continue
    try { await mkdir(dirname(dst), { recursive: true }); await cp(src, dst, { recursive: true }); copied++ } catch { /* best effort */ }
  }
  return copied
}

/** Runs `.mux/worktree-setup.sh` if the repo has one; says whether it did and how it ended. */
async function runSetupHook(repoRoot: string, worktreeDir: string, branch: string, base: string): Promise<{ present: boolean; code?: number }> {
  const hook = join(repoRoot, ".mux", "worktree-setup.sh")
  if (!existsSync(hook)) return { present: false }
  const r = await runAsync("bash", [hook], {
    cwd: worktreeDir, timeoutMs: 600_000,
    env: { ...process.env, MUX_WORKTREE_DIR: worktreeDir, MUX_REPO_ROOT: repoRoot, MUX_BASE_BRANCH: base, MUX_SESSION_BRANCH: branch },
  })
  // Non-fatal: deps install can fail; the session still spawns.
  if (r.code !== 0) log.warn("worktree_setup_hook_failed", { worktreeDir, code: r.code, timedOut: r.timedOut, tail: r.output.slice(-2000) })
  return { present: true, code: r.code }
}
