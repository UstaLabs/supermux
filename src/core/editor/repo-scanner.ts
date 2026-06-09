// src/core/editor/repo-scanner.ts
import { readdirSync, existsSync, realpathSync } from "fs"
import { join, relative } from "path"
import { execSync } from "child_process"

export interface RepoInfo {
  relPath: string // relative to workdir; "" if workdir itself is a repo
  absPath: string // canonical (realpath) repo root
}

const DEFAULT_MAX_DEPTH = 5
const SKIP_DIRS = new Set([
  "node_modules", ".git", "dist", "build", ".next", ".nuxt", "out", "vendor", "target",
])

export function scanRepos(workdir: string, maxDepth = DEFAULT_MAX_DEPTH): RepoInfo[] {
  let workdirReal: string
  try {
    workdirReal = realpathSync(workdir)
  } catch {
    return []
  }

  const found: RepoInfo[] = []
  const seen = new Set<string>()
  const queue: Array<{ dir: string; depth: number }> = [{ dir: workdirReal, depth: 0 }]

  while (queue.length > 0) {
    const { dir, depth } = queue.shift()!

    if (existsSync(join(dir, ".git"))) {
      let toplevel: string
      try {
        toplevel = execSync("git rev-parse --show-toplevel", {
          cwd: dir, encoding: "utf-8", timeout: 5000, stdio: ["pipe", "pipe", "pipe"],
        }).trim()
      } catch {
        toplevel = ""
      }
      if (toplevel) {
        let canonical: string
        try { canonical = realpathSync(toplevel) } catch { canonical = toplevel }
        if (!seen.has(canonical)) {
          seen.add(canonical)
          const rel = relative(workdirReal, canonical)
          found.push({ relPath: rel, absPath: canonical })
        }
        continue
      }
    }

    if (depth >= maxDepth) continue
    let entries: import("fs").Dirent[]
    try {
      entries = readdirSync(dir, { withFileTypes: true })
    } catch {
      continue
    }
    for (const e of entries) {
      if (!e.isDirectory()) continue
      if (SKIP_DIRS.has(e.name)) continue
      queue.push({ dir: join(dir, e.name), depth: depth + 1 })
    }
  }

  return found
}
