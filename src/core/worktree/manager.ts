// src/core/worktree/manager.ts
import { existsSync, readFileSync, readdirSync } from "fs"
import { mkdir, rm } from "fs/promises"
import { basename, dirname, join, resolve } from "path"
import { createHash, randomUUID } from "crypto"
import { muxHomeNow } from "../../shared/paths"
import { normalizeName } from "../../shared/slug"
import { ensureUnique } from "../session-manager/naming"
import { gitAsync, runAsync } from "../git/exec"
import { makeLogger } from "../../shared/log"
import { files, paths, proc } from "@supermux/platform"
import { msSince } from "../perf/start-trace"

const log = makeLogger("worktree")

export interface WorktreeHandle {
  worktreeDir: string
  sessionBranch: string
  baseBranch: string
  repoRoot: string
}

/** Where session worktrees live: `<mux home>/worktrees`, the mux home as the environment says now (MUX_HOME, else
 *  ~/.mux). MUX_WORKTREES_ROOT (read per call, not at module load) overrides it — the test preload points it at a
 *  throwaway dir so tests never create folders in the live ~/.mux/worktrees (~24k leaked before 2026-09-22). */
export function worktreesRoot(): string {
  return process.env.MUX_WORKTREES_ROOT || join(muxHomeNow().path, "worktrees")
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
  const start = await freshBase(opts.repoRoot, opts.baseBranch)
  await gitAsync(opts.repoRoot, ["worktree", "add", "--no-track", "-b", sessionBranch, worktreeDir, start])
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

/** How long a copy waits for its base's upstream before starting from what it already has. */
const BASE_FETCH_TIMEOUT_MS = 20_000

/**
 * Card #72: where a new copy starts. The base is explicit (the folder's branch or the one picked); when it
 * tracks a remote branch, that branch is fetched first and the copy starts from it, but only when it
 * already holds every commit of the local base, so nothing local is dropped. No upstream, a failed fetch
 * or a local base with commits of its own: the local base. Never a guessed main.
 */
export async function freshBase(repoRoot: string, base: string): Promise<string> {
  if (!base || base === "HEAD") return base
  const upstream = await gitAsync(repoRoot, ["rev-parse", "--abbrev-ref", "--symbolic-full-name", `${base}@{upstream}`]).catch(() => "")
  if (!upstream) return base
  const remote = await gitAsync(repoRoot, ["config", `branch.${base}.remote`]).catch(() => "")
  const merge = await gitAsync(repoRoot, ["config", `branch.${base}.merge`]).catch(() => "")
  if (remote && remote !== "." && merge.startsWith("refs/heads/")) {
    const t = performance.now()
    try {
      await gitAsync(repoRoot, ["fetch", "--no-tags", remote, merge.slice("refs/heads/".length)], {
        timeoutMs: BASE_FETCH_TIMEOUT_MS, env: { ...process.env, GIT_TERMINAL_PROMPT: "0" },
      })
      log.info("base_fetched", { repoRoot, base, upstream, ms: msSince(t) })
    } catch (err) {
      log.warn("base_fetch_failed", { repoRoot, base, upstream, err: err instanceof Error ? err.message : String(err) })
    }
  }
  try {
    await gitAsync(repoRoot, ["merge-base", "--is-ancestor", base, upstream])
    return upstream
  } catch {
    return base
  }
}

/** Card #75: a session born asleep gets its branch at Send (so two quick Sends can't pick one name); the checkout waits for its wake. */
export async function createSessionBranch(repoRoot: string, branch: string, base: string): Promise<void> {
  await gitAsync(repoRoot, ["branch", branch, base])
}

/** A large (or LFS) repo's checkout at a session's wake: as long as the setup hook may run. */
const CHECKOUT_TIMEOUT_MS = 600_000

/**
 * Makes sure the session's worktree is checked out at `workdir`: a session born asleep (branch made
 * at Send), or one whose folder was removed (merged, cleaned up). A fresh checkout gets what
 * createWorktree's gets: the `.worktreeinclude` copies and the setup hook. A folder with anything in
 * it is used as it is, never removed: it may hold work. Only an empty one inside supermux's
 * worktrees root (a crash before the checkout) is removed and made again.
 */
export async function ensureWorktreeAt(opts: {
  repoRoot: string; workdir: string; sessionBranch: string; baseBranch: string
  /** git has checked the files out (the includes and the setup hook come after). */
  onCheckedOut?: () => void
}): Promise<{ created: boolean }> {
  if (existsSync(opts.workdir)) {
    if (readdirSync(opts.workdir).length > 0) return { created: false }
    if (!paths.isStrictlyInside(resolve(opts.workdir), resolve(worktreesRoot()))) return { created: false }
    log.warn("worktree_empty_folder_removed", { workdir: opts.workdir })
    await rm(opts.workdir, { recursive: true, force: true })
  }
  await mkdir(dirname(opts.workdir), { recursive: true })
  await gitAsync(opts.repoRoot, ["worktree", "prune"])
  if ((await existingBranchNames(opts.repoRoot)).has(opts.sessionBranch)) {
    // The branch exists (made at Send, or only the dir was removed): check it out at the same path.
    await startFromFreshBase(opts.repoRoot, opts.sessionBranch, opts.baseBranch)
    await gitAsync(opts.repoRoot, ["worktree", "add", opts.workdir, opts.sessionBranch], { timeoutMs: CHECKOUT_TIMEOUT_MS })
  } else {
    // Branch was deleted (e.g., after merge) — recreate it from the base so work can continue.
    const start = await freshBase(opts.repoRoot, opts.baseBranch)
    await gitAsync(opts.repoRoot, ["worktree", "add", "--no-track", "-b", opts.sessionBranch, opts.workdir, start], { timeoutMs: CHECKOUT_TIMEOUT_MS })
  }
  opts.onCheckedOut?.()
  await copyWorktreeIncludes(opts.repoRoot, opts.workdir)
  await runSetupHook(opts.repoRoot, opts.workdir, opts.sessionBranch, opts.baseBranch)
  return { created: true }
}

/** A branch made at Send sits on the local base. Before its first checkout it moves to the fetched base,
 *  but only while it is untouched: a branch with any commit of its own stays where it is. */
async function startFromFreshBase(repoRoot: string, branch: string, base: string): Promise<void> {
  if (!base || base === "HEAD") return
  const [tip, baseTip] = await Promise.all([
    gitAsync(repoRoot, ["rev-parse", "--verify", `refs/heads/${branch}`]).catch(() => ""),
    gitAsync(repoRoot, ["rev-parse", "--verify", base]).catch(() => ""),
  ])
  if (!tip || tip !== baseTip) return
  const start = await freshBase(repoRoot, base)
  if (start === base) return
  await gitAsync(repoRoot, ["branch", "--force", "--no-track", branch, start])
  log.info("session_branch_moved_to_fresh_base", { repoRoot, branch, base, start })
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
    if (!paths.isStrictlyInside(resolve(src), resolve(repoRoot)) || !paths.isStrictlyInside(resolve(dst), resolve(worktreeDir))) continue
    if (!existsSync(src)) continue
    try { await mkdir(dirname(dst), { recursive: true }); await files.copyTree(src, dst); copied++ } catch { /* best effort */ }
  }
  return copied
}

/** Runs `.mux/worktree-setup.sh` if the repo has one; says whether it did and how it ended. */
async function runSetupHook(repoRoot: string, worktreeDir: string, branch: string, base: string): Promise<{ present: boolean; code?: number }> {
  const hook = join(repoRoot, ".mux", "worktree-setup.sh")
  if (!existsSync(hook)) return { present: false }
  // A POSIX script: the bash on PATH, or on Windows Git for Windows' (never cmd.exe, never WSL's).
  const bash = proc.posixShell()
  if (!bash) {
    log.warn("worktree_setup_hook_skipped", { worktreeDir, reason: "no bash to run .mux/worktree-setup.sh (on Windows: install Git for Windows)" })
    return { present: true }
  }
  const r = await runAsync(bash, [hook], {
    cwd: worktreeDir, timeoutMs: 600_000,
    env: { ...process.env, MUX_WORKTREE_DIR: worktreeDir, MUX_REPO_ROOT: repoRoot, MUX_BASE_BRANCH: base, MUX_SESSION_BRANCH: branch },
  })
  // Non-fatal: deps install can fail; the session still spawns.
  if (r.code !== 0) log.warn("worktree_setup_hook_failed", { worktreeDir, code: r.code, timedOut: r.timedOut, tail: r.output.slice(-2000) })
  return { present: true, code: r.code }
}
