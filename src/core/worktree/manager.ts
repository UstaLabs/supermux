// src/core/worktree/manager.ts
import { execFileSync } from "child_process"
import { existsSync, mkdirSync, readFileSync } from "fs"
import { basename, dirname, join, resolve, sep } from "path"
import { createHash, randomUUID } from "crypto"
import { home } from "../../shared/home"
import { normalizeName } from "../../shared/slug"
import { ensureUnique } from "../session-manager/naming"

export interface WorktreeHandle {
  worktreeDir: string
  sessionBranch: string
  baseBranch: string
  repoRoot: string
}

function git(cwd: string, args: string[], timeout = 30_000): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8", timeout, stdio: ["pipe", "pipe", "pipe"] }).trim()
}

export function worktreesRoot(): string { return join(home(), ".mux", "worktrees") }

export function repoSlug(repoRoot: string): string {
  const h = createHash("sha1").update(repoRoot).digest("hex").slice(0, 8)
  return `${basename(repoRoot)}-${h}`
}

export function existingBranchNames(repoRoot: string): Set<string> {
  try {
    return new Set(git(repoRoot, ["for-each-ref", "--format=%(refname:short)", "refs/heads"]).split("\n").map((s) => s.trim()).filter(Boolean))
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
  const sessionBranch = deriveSessionBranch(opts.sessionName, existingBranchNames(opts.repoRoot))
  const worktreeDir = join(worktreesRoot(), repoSlug(opts.repoRoot), randomUUID())
  mkdirSync(dirname(worktreeDir), { recursive: true })
  git(opts.repoRoot, ["worktree", "add", "-b", sessionBranch, worktreeDir, opts.baseBranch])
  copyWorktreeIncludes(opts.repoRoot, worktreeDir)
  runSetupHook(opts.repoRoot, worktreeDir, sessionBranch, opts.baseBranch)
  return { worktreeDir, sessionBranch, baseBranch: opts.baseBranch, repoRoot: opts.repoRoot }
}

export async function removeWorktree(repoRoot: string, worktreeDir: string, sessionBranch: string, opts?: { force?: boolean; keepBranch?: boolean }): Promise<void> {
  try { git(repoRoot, ["worktree", "remove", ...(opts?.force ? ["--force"] : []), worktreeDir]) } catch { /* may already be gone */ }
  try { git(repoRoot, ["worktree", "prune"]) } catch {}
  if (!opts?.keepBranch) { try { git(repoRoot, ["branch", "-D", sessionBranch]) } catch {} }
}

function copyWorktreeIncludes(repoRoot: string, worktreeDir: string): void {
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
    try { mkdirSync(dirname(dst), { recursive: true }); execFileSync("cp", ["-R", src, dst]) } catch { /* best effort */ }
  }
}

function runSetupHook(repoRoot: string, worktreeDir: string, branch: string, base: string): void {
  const hook = join(repoRoot, ".mux", "worktree-setup.sh")
  if (!existsSync(hook)) return
  try {
    execFileSync("bash", [hook], {
      cwd: worktreeDir, timeout: 600_000, stdio: ["ignore", "pipe", "pipe"],
      env: { ...process.env, MUX_WORKTREE_DIR: worktreeDir, MUX_REPO_ROOT: repoRoot, MUX_BASE_BRANCH: base, MUX_SESSION_BRANCH: branch },
    })
  } catch { /* non-fatal: deps install can fail; surfaced by caller log, session still spawns */ }
}
