// src/core/worktree/manager.ts
import { existsSync, readFileSync } from "fs"
import { cp, mkdir } from "fs/promises"
import { basename, dirname, join, resolve, sep } from "path"
import { createHash, randomUUID } from "crypto"
import { home } from "../../shared/home"
import { normalizeName } from "../../shared/slug"
import { ensureUnique } from "../session-manager/naming"
import { gitAsync, runAsync } from "../git/exec"
import { makeLogger } from "../../shared/log"

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

export async function createWorktree(opts: {
  repoRoot: string; baseBranch: string; sessionName: string
}): Promise<WorktreeHandle> {
  const sessionBranch = deriveSessionBranch(opts.sessionName, await existingBranchNames(opts.repoRoot))
  const worktreeDir = join(worktreesRoot(), repoSlug(opts.repoRoot), randomUUID())
  await mkdir(dirname(worktreeDir), { recursive: true })
  await gitAsync(opts.repoRoot, ["worktree", "add", "-b", sessionBranch, worktreeDir, opts.baseBranch])
  await copyWorktreeIncludes(opts.repoRoot, worktreeDir)
  await runSetupHook(opts.repoRoot, worktreeDir, sessionBranch, opts.baseBranch)
  return { worktreeDir, sessionBranch, baseBranch: opts.baseBranch, repoRoot: opts.repoRoot }
}

export async function removeWorktree(repoRoot: string, worktreeDir: string, sessionBranch: string, opts?: { force?: boolean; keepBranch?: boolean }): Promise<void> {
  try { await gitAsync(repoRoot, ["worktree", "remove", ...(opts?.force ? ["--force"] : []), worktreeDir]) } catch { /* may already be gone */ }
  try { await gitAsync(repoRoot, ["worktree", "prune"]) } catch {}
  if (!opts?.keepBranch) { try { await gitAsync(repoRoot, ["branch", "-D", sessionBranch]) } catch {} }
}

export async function ensureWorktreeAt(opts: {
  repoRoot: string; workdir: string; sessionBranch: string; baseBranch: string
}): Promise<void> {
  if (existsSync(opts.workdir)) return
  await gitAsync(opts.repoRoot, ["worktree", "prune"])
  if ((await existingBranchNames(opts.repoRoot)).has(opts.sessionBranch)) {
    // Branch survived (only the dir was removed) — check it out at the same path.
    await gitAsync(opts.repoRoot, ["worktree", "add", opts.workdir, opts.sessionBranch])
  } else {
    // Branch was deleted (e.g., after merge) — recreate it from the base so work can continue.
    await gitAsync(opts.repoRoot, ["worktree", "add", "-b", opts.sessionBranch, opts.workdir, opts.baseBranch])
  }
}

async function copyWorktreeIncludes(repoRoot: string, worktreeDir: string): Promise<void> {
  const inc = join(repoRoot, ".worktreeinclude")
  if (!existsSync(inc)) return
  for (const raw of readFileSync(inc, "utf-8").split("\n")) {
    const line = raw.trim()
    if (!line || line.startsWith("#")) continue
    const src = join(repoRoot, line)
    const dst = join(worktreeDir, line)
    // Containment guard: never read outside the repo or write outside the worktree.
    if (!resolve(src).startsWith(resolve(repoRoot) + sep) || !resolve(dst).startsWith(resolve(worktreeDir) + sep)) continue
    if (!existsSync(src)) continue
    try { await mkdir(dirname(dst), { recursive: true }); await cp(src, dst, { recursive: true }) } catch { /* best effort */ }
  }
}

async function runSetupHook(repoRoot: string, worktreeDir: string, branch: string, base: string): Promise<void> {
  const hook = join(repoRoot, ".mux", "worktree-setup.sh")
  if (!existsSync(hook)) return
  const r = await runAsync("bash", [hook], {
    cwd: worktreeDir, timeoutMs: 600_000,
    env: { ...process.env, MUX_WORKTREE_DIR: worktreeDir, MUX_REPO_ROOT: repoRoot, MUX_BASE_BRANCH: base, MUX_SESSION_BRANCH: branch },
  })
  // Non-fatal: deps install can fail; the session still spawns.
  if (r.code !== 0) log.warn("worktree_setup_hook_failed", { worktreeDir, code: r.code, timedOut: r.timedOut, tail: r.output.slice(-2000) })
}
