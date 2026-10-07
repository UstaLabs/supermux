// src/core/editor/workdir-diff.ts
// Runs on the Changes pane's request path (fs/diff, fs/refs) and on walkthrough
// authoring: every git call and directory read here is async, because the broker
// is one event loop and a sync child process freezes every session.
import { existsSync } from "fs"
import { readdir, realpath } from "fs/promises"
import { join, relative } from "path"
import { gitAsync } from "../git/exec"
import { mapLimit } from "../fs/pool"
import { parseDiff, type DiffEntry } from "./fs-service"
import { scanReposAsync } from "./repo-scanner"

export interface RepoDiff {
  repo: string // relPath; "" for workdir-as-repo
  files: DiffEntry[]
}

// git's well-known empty-tree object SHA — constant across all repos
export const EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

export type DiffBaseSpec =
  | { kind: "session-start" }
  | { kind: "head" }
  | { kind: "commit"; sha: string }
  | { kind: "branch"; name: string }

// A git ref-name safe enough to hand to git as an argument (no leading dash → no option injection).
function safeRefName(name: string): boolean {
  return /^[\w][\w./-]*$/.test(name)
}

export function parseBaseSpec(spec: string | undefined | null): DiffBaseSpec {
  if (!spec || spec === "session-start") return { kind: "session-start" }
  if (spec === "head") return { kind: "head" }
  if (spec.startsWith("commit:")) return { kind: "commit", sha: spec.slice(7) }
  if (spec.startsWith("branch:")) return { kind: "branch", name: spec.slice(7) }
  return { kind: "session-start" }
}

export interface RepoRefs {
  repo: string
  branches: string[]
  commits: Array<{ sha: string; subject: string }>
}

export async function listRepoRefs(workdir: string): Promise<RepoRefs[]> {
  return mapLimit(await scanReposAsync(workdir), REPO_CONCURRENCY, async (r) => {
    const [branches, commits] = await Promise.all([
      runGit(r.absPath, ["branch", "--format=%(refname:short)"])
        .then((out) => out.split("\n").map((s) => s.trim()).filter(Boolean))
        .catch(() => [] as string[]), // no branches yet
      runGit(r.absPath, ["log", "-30", "--format=%h%x00%s"])
        .then((out) => out.split("\n").filter(Boolean)
          .map((l) => { const i = l.indexOf("\0"); return { sha: l.slice(0, i), subject: l.slice(i + 1) } }))
        .catch(() => [] as Array<{ sha: string; subject: string }>), // no history
    ])
    return { repo: r.relPath, branches, commits }
  })
}

// Dirs we should never descend into when doing extra sub-repo scanning
const SKIP_DIRS = new Set(["node_modules", ".git", "dist", "build", ".next", ".nuxt", "out", "vendor", "target"])

// Repos diffed at once, and untracked files diffed at once within a repo: bounded so a
// workdir with hundreds of new files doesn't fork hundreds of gits together.
export const REPO_CONCURRENCY = 4
const UNTRACKED_CONCURRENCY = 8

// Byte-exact stdout: parseDiff and the `-z` listing need it untrimmed.
function runGit(cwd: string, args: string[], okExitCodes?: number[]): Promise<string> {
  return gitAsync(cwd, args, { trim: false, okExitCodes })
}

// Resolve the diff base for a repo. Precedence:
//   1. stored sha captured at spawn (exact, immune to commit-date skew)
//   2. the commit that was HEAD at session-creation time, found by timestamp
//      (robust fallback for legacy/missing/failed-capture sessions)
//   3. the empty tree (repo had no history before the session — show all as added)
async function resolveBase(repoAbs: string, stored: string | undefined, createdAt?: string): Promise<string> {
  if (stored && /^[0-9a-f]{4,40}$/i.test(stored)) return stored
  if (createdAt) {
    try {
      const sha = (await runGit(repoAbs, ["rev-list", "-1", `--before=${createdAt}`, "HEAD"])).trim()
      if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
    } catch {
      // no HEAD, or no commit at/before createdAt — fall through to empty tree
    }
  }
  return EMPTY_TREE
}

// Resolve a user-chosen base spec into an effective base commit for one repo.
// Any spec that can't be resolved in THIS repo falls back to session-start.
export async function resolveSpecBase(
  repoAbs: string,
  spec: DiffBaseSpec,
  stored: string | undefined,
  createdAt?: string,
): Promise<string> {
  switch (spec.kind) {
    case "session-start":
      return resolveBase(repoAbs, stored, createdAt)
    case "head":
      try {
        const sha = (await runGit(repoAbs, ["rev-parse", "--verify", "HEAD"])).trim()
        if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
      } catch { /* no HEAD */ }
      return EMPTY_TREE
    case "commit": {
      if (!/^[0-9a-f]{4,40}$/i.test(spec.sha)) return resolveBase(repoAbs, stored, createdAt)
      try {
        const sha = (await runGit(repoAbs, ["rev-parse", "--verify", `${spec.sha}^{commit}`])).trim()
        if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
      } catch { /* commit not in this repo */ }
      return resolveBase(repoAbs, stored, createdAt)
    }
    case "branch": {
      if (!safeRefName(spec.name)) return resolveBase(repoAbs, stored, createdAt)
      try {
        const mb = (await runGit(repoAbs, ["merge-base", spec.name, "HEAD"])).trim()
        if (/^[0-9a-f]{7,40}$/i.test(mb)) return mb
      } catch { /* branch missing here */ }
      return resolveBase(repoAbs, stored, createdAt)
    }
  }
}

async function trackedDiff(repoAbs: string, base: string): Promise<DiffEntry[]> {
  try {
    const raw = await runGit(repoAbs, ["diff", "-M", base])
    return raw.trim() ? parseDiff(raw) : []
  } catch {
    return []
  }
}

async function untrackedDiff(repoAbs: string): Promise<DiffEntry[]> {
  let listing: string
  try {
    listing = await runGit(repoAbs, ["ls-files", "--others", "--exclude-standard", "-z"])
  } catch {
    return []
  }
  // Skip sub-repo directories (they end with '/'); they are handled as separate repos
  const files = listing.split("\0").filter((f) => f && !f.endsWith("/"))
  const perFile = await mapLimit(files, UNTRACKED_CONCURRENCY, async (file) => {
    try {
      // git diff --no-index takes exactly two paths and exits 1 when they differ.
      const raw = await runGit(repoAbs, ["diff", "--no-index", "/dev/null", file], [1])
      if (!raw.trim()) return []
      return parseDiff(raw).map((e) => ({ ...e, path: file, status: "added" }))
    } catch {
      return [] // skip unreadable file
    }
  })
  return perFile.flat()
}

/**
 * Scan for repos that are nested *inside* other repos (i.e. git repos that
 * appear as untracked directories from the parent repo's perspective). These
 * are repos that `scanRepos` would miss because it stops walking when it finds
 * a .git directory.
 *
 * Returns RepoInfo-like objects with relPath relative to workdirReal.
 */
async function scanNestedRepos(
  workdirReal: string,
  alreadyKnown: Set<string>,
  maxDepth = 5,
): Promise<Array<{ relPath: string; absPath: string }>> {
  const found: Array<{ relPath: string; absPath: string }> = []
  const seen = new Set<string>(alreadyKnown)

  async function walk(dir: string, depth: number): Promise<void> {
    if (depth > maxDepth) return
    let entries: import("fs").Dirent[]
    try {
      entries = await readdir(dir, { withFileTypes: true })
    } catch {
      return
    }

    for (const e of entries) {
      if (!e.isDirectory()) continue
      if (SKIP_DIRS.has(e.name)) continue

      const sub = join(dir, e.name)
      const canonical = await realpath(sub).catch(() => sub)

      if (seen.has(canonical)) continue

      if (existsSync(join(canonical, ".git"))) {
        // It's a repo — record it and don't descend further
        seen.add(canonical)
        const rel = relative(workdirReal, canonical)
        found.push({ relPath: rel, absPath: canonical })
      } else {
        // Not a repo — keep descending
        await walk(canonical, depth + 1)
      }
    }
  }

  // Walk starting from each already-known repo to find repos nested inside them
  for (const knownAbs of alreadyKnown) {
    await walk(knownAbs, 1)
  }

  // Also walk workdir itself if it's not a repo (to handle multi-repo case)
  if (!alreadyKnown.has(workdirReal)) {
    await walk(workdirReal, 0)
  }

  return found
}

/** Every repo under a workdir: the primary ones plus repos nested inside them (e.g. a new
 *  repo created inside a workdir-as-repo during the session). Empty when the workdir is gone. */
export async function discoverRepos(workdir: string): Promise<Array<{ relPath: string; absPath: string }>> {
  let workdirReal: string
  try {
    workdirReal = await realpath(workdir)
  } catch {
    return []
  }
  const primaryRepos = await scanReposAsync(workdir)
  const knownAbs = new Set(primaryRepos.map((r) => r.absPath))
  const nestedRepos = await scanNestedRepos(workdirReal, knownAbs)
  return [...primaryRepos, ...nestedRepos]
}

export async function computeWorkdirDiff(
  workdir: string,
  baseCommits: Record<string, string>,
  createdAt?: string,
  baseSpec?: string,
): Promise<RepoDiff[]> {
  const allRepos = await discoverRepos(workdir)

  const spec = parseBaseSpec(baseSpec)

  // mapLimit keeps input order, so the repo list comes back in scan order as before.
  const perRepo = await mapLimit(allRepos, REPO_CONCURRENCY, async (repo): Promise<RepoDiff> => {
    const effectiveBase = await resolveSpecBase(repo.absPath, spec, baseCommits[repo.relPath], createdAt)
    const [tracked, untracked] = await Promise.all([
      trackedDiff(repo.absPath, effectiveBase),
      untrackedDiff(repo.absPath),
    ])
    return { repo: repo.relPath, files: [...tracked, ...untracked] }
  })

  return perRepo.filter((r) => r.files.length > 0)
}
