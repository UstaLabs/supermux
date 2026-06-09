// src/core/git/repo-info.ts
import { execFileSync } from "child_process"
import { realpathSync } from "fs"
import { scanRepos } from "../editor/repo-scanner"

export interface RepoBranches { local: string[]; remote: string[] }
export interface RepoInfo {
  isGitRepo: boolean
  eligible: boolean          // true only when `path` itself is a single repo root
  repoRoot?: string
  currentBranch?: string
  branches?: RepoBranches
}

function git(cwd: string, args: string[]): string {
  return execFileSync("git", args, {
    cwd, encoding: "utf-8", timeout: 5000, stdio: ["pipe", "pipe", "pipe"],
  }).trim()
}

function safeLines(cwd: string, args: string[]): string[] {
  try { return git(cwd, args).split("\n").map((s) => s.trim()).filter(Boolean) } catch { return [] }
}

export function getRepoInfo(path: string, opts?: { fetch?: boolean }): RepoInfo {
  let real: string
  try { real = realpathSync(path) } catch { return { isGitRepo: false, eligible: false } }

  let toplevel = ""
  try { toplevel = realpathSync(git(real, ["rev-parse", "--show-toplevel"])) } catch { /* not a repo */ }
  if (!toplevel) return { isGitRepo: false, eligible: false }

  // Optionally refresh remote-tracking refs from origin (best-effort, timeboxed)
  // so the branch list reflects what's been pushed since the last local fetch.
  if (opts?.fetch) {
    try {
      execFileSync("git", ["-C", real, "fetch", "--quiet", "--prune"], { timeout: 15000, stdio: ["pipe", "pipe", "pipe"] })
    } catch { /* offline / no remote / auth → keep the local list */ }
  }

  // Eligible only when the picked path IS the repo root and there is no nested
  // second repo underneath (ambiguous which repo to branch).
  const nested = scanRepos(real, 2)
  const eligible = toplevel === real && nested.length <= 1

  let currentBranch: string | undefined
  try { currentBranch = git(real, ["branch", "--show-current"]) || undefined } catch { /* detached */ }

  return {
    isGitRepo: true,
    eligible,
    repoRoot: toplevel,
    currentBranch,
    branches: {
      local: safeLines(real, ["for-each-ref", "--format=%(refname:short)", "refs/heads"]),
      remote: safeLines(real, ["for-each-ref", "--format=%(refname:short)", "refs/remotes"]),
    },
  }
}
