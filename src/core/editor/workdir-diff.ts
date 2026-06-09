// src/core/editor/workdir-diff.ts
import { execFileSync } from "child_process"
import { existsSync, readdirSync, realpathSync } from "fs"
import { join, relative } from "path"
import { parseDiff, type DiffEntry } from "./fs-service"
import { scanRepos } from "./repo-scanner"

export interface RepoDiff {
  repo: string // relPath; "" for workdir-as-repo
  files: DiffEntry[]
}

// git's well-known empty-tree object SHA — constant across all repos
const EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

// Dirs we should never descend into when doing extra sub-repo scanning
const SKIP_DIRS = new Set(["node_modules", ".git", "dist", "build", ".next", ".nuxt", "out", "vendor", "target"])

function runGit(cwd: string, args: string[]): string {
  return execFileSync("git", args, {
    cwd, encoding: "utf-8", stdio: ["pipe", "pipe", "pipe"], maxBuffer: 5 * 1024 * 1024,
  })
}

// Resolve the diff base for a repo. Precedence:
//   1. stored sha captured at spawn (exact, immune to commit-date skew)
//   2. the commit that was HEAD at session-creation time, found by timestamp
//      (robust fallback for legacy/missing/failed-capture sessions)
//   3. the empty tree (repo had no history before the session — show all as added)
function resolveBase(repoAbs: string, stored: string | undefined, createdAt?: string): string {
  if (stored && /^[0-9a-f]{4,40}$/i.test(stored)) return stored
  if (createdAt) {
    try {
      const sha = runGit(repoAbs, ["rev-list", "-1", `--before=${createdAt}`, "HEAD"]).trim()
      if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
    } catch {
      // no HEAD, or no commit at/before createdAt — fall through to empty tree
    }
  }
  return EMPTY_TREE
}

function trackedDiff(repoAbs: string, base: string): DiffEntry[] {
  try {
    const raw = runGit(repoAbs, ["diff", "-M", base])
    return raw.trim() ? parseDiff(raw) : []
  } catch {
    return []
  }
}

function untrackedDiff(repoAbs: string): DiffEntry[] {
  let listing: string
  try {
    listing = runGit(repoAbs, ["ls-files", "--others", "--exclude-standard", "-z"])
  } catch {
    return []
  }
  const files = listing.split("\0").filter(Boolean)
  const entries: DiffEntry[] = []
  for (const file of files) {
    // Skip sub-repo directories (they end with '/'); they are handled as separate repos
    if (file.endsWith("/")) continue
    try {
      // git diff --no-index takes exactly two paths and exits 1 when they
      // differ, so execFileSync throws — the diff is on err.stdout.
      let raw = ""
      try {
        raw = runGit(repoAbs, ["diff", "--no-index", "/dev/null", file])
      } catch (err: any) {
        raw = err?.stdout?.toString() ?? ""
      }
      if (!raw.trim()) continue
      const parsed = parseDiff(raw)
      for (const e of parsed) {
        entries.push({ ...e, path: file, status: "added" })
      }
    } catch {
      // skip unreadable file
    }
  }
  return entries
}

/**
 * Scan for repos that are nested *inside* other repos (i.e. git repos that
 * appear as untracked directories from the parent repo's perspective). These
 * are repos that `scanRepos` would miss because it stops walking when it finds
 * a .git directory.
 *
 * Returns RepoInfo-like objects with relPath relative to workdirReal.
 */
function scanNestedRepos(
  workdirReal: string,
  alreadyKnown: Set<string>,
  maxDepth = 5,
): Array<{ relPath: string; absPath: string }> {
  const found: Array<{ relPath: string; absPath: string }> = []
  const seen = new Set<string>(alreadyKnown)

  function walk(dir: string, depth: number) {
    if (depth > maxDepth) return
    let entries: import("fs").Dirent[]
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      return
    }

    for (const e of entries) {
      if (!e.isDirectory()) continue
      if (SKIP_DIRS.has(e.name)) continue

      const sub = join(dir, e.name)
      let canonical: string
      try {
        canonical = realpathSync(sub)
      } catch {
        canonical = sub
      }

      if (seen.has(canonical)) continue

      if (existsSync(join(canonical, ".git"))) {
        // It's a repo — record it and don't descend further
        seen.add(canonical)
        const rel = relative(workdirReal, canonical)
        found.push({ relPath: rel, absPath: canonical })
      } else {
        // Not a repo — keep descending
        walk(canonical, depth + 1)
      }
    }
  }

  // Walk starting from each already-known repo to find repos nested inside them
  for (const knownAbs of alreadyKnown) {
    walk(knownAbs, 1)
  }

  // Also walk workdir itself if it's not a repo (to handle multi-repo case)
  if (!alreadyKnown.has(workdirReal)) {
    walk(workdirReal, 0)
  }

  return found
}

export async function computeWorkdirDiff(
  workdir: string,
  baseCommits: Record<string, string>,
  createdAt?: string,
): Promise<RepoDiff[]> {
  let workdirReal: string
  try {
    workdirReal = realpathSync(workdir)
  } catch {
    return []
  }

  // Get primary repos from scanRepos
  const primaryRepos = scanRepos(workdir)

  // Find repos nested inside those primary repos (e.g. a new repo created inside
  // a workdir-as-repo during the session)
  const knownAbs = new Set(primaryRepos.map((r) => r.absPath))
  const nestedRepos = scanNestedRepos(workdirReal, knownAbs)

  const allRepos = [...primaryRepos, ...nestedRepos]

  const result: RepoDiff[] = []

  for (const repo of allRepos) {
    const effectiveBase = resolveBase(repo.absPath, baseCommits[repo.relPath], createdAt)
    const files = [
      ...trackedDiff(repo.absPath, effectiveBase),
      ...untrackedDiff(repo.absPath),
    ]
    if (files.length > 0) {
      result.push({ repo: repo.relPath, files })
    }
  }

  return result
}
