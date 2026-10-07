// src/core/git/repo-info.ts
import { realpath } from "fs/promises"
import { scanReposAsync } from "../editor/repo-scanner"
import { gitAsync } from "./exec"

export interface RepoBranches { local: string[]; remote: string[] }
export interface RepoInfo {
  isGitRepo: boolean
  eligible: boolean          // true only when `path` itself is a single repo root
  repoRoot?: string
  currentBranch?: string
  branches?: RepoBranches
}

const git = (cwd: string, args: string[]) => gitAsync(cwd, args, { timeoutMs: 5000 })

async function safeLines(cwd: string, args: string[]): Promise<string[]> {
  try { return (await git(cwd, args)).split("\n").map((s) => s.trim()).filter(Boolean) } catch { return [] }
}

export async function getRepoInfo(path: string, opts?: { fetch?: boolean }): Promise<RepoInfo> {
  let real: string
  try { real = await realpath(path) } catch { return { isGitRepo: false, eligible: false } }

  let toplevel = ""
  try { toplevel = await realpath(await git(real, ["rev-parse", "--show-toplevel"])) } catch { /* not a repo */ }
  if (!toplevel) return { isGitRepo: false, eligible: false }

  // Optionally refresh remote-tracking refs from origin (best-effort, timeboxed)
  // so the branch list reflects what's been pushed since the last local fetch.
  if (opts?.fetch) {
    try { await gitAsync(real, ["fetch", "--quiet", "--prune"], { timeoutMs: 15_000 }) } catch { /* offline / no remote / auth → keep the local list */ }
  }

  // Eligible only when the picked path IS the repo root and there is no nested
  // second repo underneath (ambiguous which repo to branch).
  const nested = await scanReposAsync(real, 2)
  const eligible = toplevel === real && nested.length <= 1

  let currentBranch: string | undefined
  try { currentBranch = (await git(real, ["branch", "--show-current"])) || undefined } catch { /* detached */ }

  // %(refname:short) renders the origin/HEAD symref as "origin/HEAD" or bare
  // "origin" depending on git version — drop those, matching listBranches.
  const remote = (await safeLines(real, ["for-each-ref", "--format=%(refname:short)", "refs/remotes"]))
    .filter((n) => n.includes("/") && !n.endsWith("/HEAD"))

  return {
    isGitRepo: true,
    eligible,
    repoRoot: toplevel,
    currentBranch,
    branches: {
      local: await safeLines(real, ["for-each-ref", "--format=%(refname:short)", "refs/heads"]),
      remote,
    },
  }
}
