import { readdirSync, statSync, readFileSync, writeFileSync, mkdirSync, renameSync, realpathSync } from "fs"
import { realpath, stat } from "fs/promises"
import { join, resolve, dirname, relative, sep } from "path"
import { execSync } from "child_process"

export interface FsEntry {
  name: string
  type: "file" | "dir"
  size: number
  modified: string // ISO timestamp
  /** True when git reports the path as ignored (non-git workdirs: always false). */
  ignored: boolean
}

export interface SearchResult {
  path: string
  name: string
  type: "file" | "dir"
  ignored: boolean
}

export interface DiffEntry {
  path: string
  status: string
  diff: string
  binary?: boolean
  modeChange?: boolean
}

export interface WriteResult {
  ok: true
  size: number
}

const MAX_FILE_SIZE = 1024 * 1024 // 1 MB
const BINARY_SCAN_SIZE = 8 * 1024 // 8 KB

export class FsService {
  private readonly workdir: string

  constructor(workdir: string) {
    // Resolve workdir to an absolute real path (follow symlinks)
    this.workdir = realpathSync(workdir)
  }

  /**
   * Resolves relPath relative to workdir, follows symlinks, and verifies
   * the resolved path stays within workdir. Throws on traversal.
   */
  private async safePath(relPath: string): Promise<string> {
    // Reject null bytes
    if (relPath.includes("\x00")) {
      throw new Error("Path contains null byte")
    }

    const cleaned = relPath.replace(/^\/+/, "")
    const joined = join(this.workdir, cleaned)

    // Resolve symlinks and canonicalize
    let resolved: string
    try {
      resolved = await realpath(joined)
    } catch {
      // File doesn't exist yet (e.g. writeFile target). Use a non-symlink resolution.
      resolved = resolve(joined)
    }

    // Ensure resolved path is within workdir
    const workdirWithSep = this.workdir.endsWith(sep) ? this.workdir : this.workdir + sep
    if (resolved !== this.workdir && !resolved.startsWith(workdirWithSep)) {
      throw new Error(`Path traversal detected: "${relPath}" resolves outside workdir`)
    }

    return resolved
  }

  /**
   * Same as safePath but for paths that may not exist yet (write operations).
   * Uses resolve() for the final path (file may not exist yet) but verifies
   * all existing ancestor directories via realpathSync to catch symlink escapes.
   */
  private safePathSync(relPath: string): string {
    if (relPath.includes("\x00")) {
      throw new Error("Path contains null byte")
    }

    const cleaned = relPath.replace(/^\/+/, "")
    const joined = join(this.workdir, cleaned)

    const resolved = resolve(joined)

    const workdirWithSep = this.workdir.endsWith(sep) ? this.workdir : this.workdir + sep
    if (resolved !== this.workdir && !resolved.startsWith(workdirWithSep)) {
      throw new Error(`Path traversal detected: "${relPath}" resolves outside workdir`)
    }

    // Walk up from the target path to find the deepest existing ancestor,
    // then verify its real path (resolving symlinks) stays within workdir.
    let ancestor = dirname(resolved)
    while (true) {
      try {
        const realAncestor = realpathSync(ancestor)
        const realAncestorWithSep = realAncestor.endsWith(sep) ? realAncestor : realAncestor + sep
        if (realAncestor !== this.workdir && !realAncestorWithSep.startsWith(workdirWithSep)) {
          throw new Error(`Path traversal detected: "${relPath}" resolves outside workdir via symlink`)
        }
        break // existing ancestor is safe
      } catch (err: unknown) {
        if (err instanceof Error && err.message.includes("traversal")) throw err
        // ancestor doesn't exist yet — walk up further
        const parent = dirname(ancestor)
        if (parent === ancestor) {
          // reached filesystem root without finding an existing dir — shouldn't happen
          throw new Error(`Path traversal detected: "${relPath}" resolves outside workdir`)
        }
        ancestor = parent
      }
    }

    return resolved
  }

  /** Relative path for a directory entry (posix-style, no leading slash). */
  private entryRelPath(parentRelPath: string, name: string): string {
    const parent = parentRelPath.replace(/^\/+/, "").replace(/\/$/, "")
    if (!parent || parent === ".") return name
    return `${parent}/${name}`
  }

  /** Returns the subset of relPaths that git check-ignore reports as ignored. */
  gitIgnoredRelPaths(relPaths: string[]): Set<string> {
    const ignored = new Set<string>()
    if (relPaths.length === 0) return ignored

    try {
      execSync("git rev-parse --git-dir", {
        cwd: this.workdir,
        stdio: ["pipe", "pipe", "pipe"],
        timeout: 5000,
      })
    } catch {
      return ignored
    }

    try {
      const out = execSync("git check-ignore --stdin", {
        cwd: this.workdir,
        input: relPaths.join("\n"),
        encoding: "utf-8",
        stdio: ["pipe", "pipe", "pipe"],
        timeout: 10000,
      })
      for (const line of out.trim().split("\n")) {
        if (line) ignored.add(line)
      }
    } catch {
      // exit 1 when none of the paths are ignored
    }

    return ignored
  }

  /**
   * Lists one level of directory. Sorts dirs first, then files, each alphabetically.
   * All entries are returned; gitignored paths are flagged with ignored: true.
   */
  async listDir(relPath: string): Promise<FsEntry[]> {
    const absPath = await this.safePath(relPath)

    const names = readdirSync(absPath)
    const entries: FsEntry[] = []

    for (const name of names) {
      const full = join(absPath, name)
      let info: ReturnType<typeof statSync>
      try {
        info = statSync(full)
      } catch {
        continue // skip entries we can't stat
      }

      entries.push({
        name,
        type: info.isDirectory() ? "dir" : "file",
        size: info.size,
        modified: info.mtime.toISOString(),
        ignored: false,
      })
    }

    const ignoredSet = this.gitIgnoredRelPaths(
      entries.map((e) => this.entryRelPath(relPath, e.name)),
    )
    for (const e of entries) {
      e.ignored = ignoredSet.has(this.entryRelPath(relPath, e.name))
    }

    // Sort: dirs first, then files; each group alphabetically
    entries.sort((a, b) => {
      if (a.type !== b.type) {
        return a.type === "dir" ? -1 : 1
      }
      return a.name.localeCompare(b.name)
    })

    return entries
  }

  /**
   * Reads file as UTF-8. Rejects files >1MB and binary files (null-byte scan).
   */
  async readFile(relPath: string): Promise<string> {
    const absPath = await this.safePath(relPath)

    const info = statSync(absPath)

    if (info.size > MAX_FILE_SIZE) {
      throw new Error(`File too large (${info.size} bytes); limit is 1MB`)
    }

    const buf = readFileSync(absPath)

    // Binary detection: scan first 8KB for null bytes
    const scanLen = Math.min(buf.length, BINARY_SCAN_SIZE)
    for (let i = 0; i < scanLen; i++) {
      if (buf[i] === 0) {
        throw new Error("File appears to be binary (null byte detected)")
      }
    }

    return buf.toString("utf-8")
  }

  /**
   * Atomically writes content to relPath. Creates parent dirs as needed.
   * Returns {ok: true, size}.
   */
  async writeFile(relPath: string, content: string): Promise<WriteResult> {
    // Use sync path check (file may not exist yet)
    const absPath = this.safePathSync(relPath)

    // Create parent directories
    mkdirSync(dirname(absPath), { recursive: true })

    const buf = Buffer.from(content, "utf-8")
    const tmpPath = absPath + ".tmp"

    // Atomic write: write to .tmp then rename
    writeFileSync(tmpPath, buf)
    renameSync(tmpPath, absPath)

    return { ok: true, size: buf.length }
  }

  /**
   * Recursively walks workdir, returning files/dirs whose name contains query
   * (case-insensitive). All paths are returned; gitignored matches are flagged.
   * Caps at maxResults.
   */
  async searchFiles(query: string, maxResults = 20): Promise<SearchResult[]> {
    const results: SearchResult[] = []
    const lowerQuery = query.toLowerCase()

    const walk = (dir: string) => {
      if (results.length >= maxResults) return

      let names: string[]
      try {
        names = readdirSync(dir)
      } catch {
        return
      }

      for (const name of names) {
        if (results.length >= maxResults) break

        const full = join(dir, name)
        let info: ReturnType<typeof statSync>
        try {
          info = statSync(full)
        } catch {
          continue
        }

        const type: "file" | "dir" = info.isDirectory() ? "dir" : "file"
        const relPath = relative(this.workdir, full)

        if (name.toLowerCase().includes(lowerQuery)) {
          results.push({ path: relPath, name, type, ignored: false })
        }

        if (type === "dir") {
          walk(full)
        }
      }
    }

    walk(this.workdir)

    const ignoredSet = this.gitIgnoredRelPaths(results.map((r) => r.path))
    for (const r of results) {
      r.ignored = ignoredSet.has(r.path)
    }

    return results
  }

  /**
   * Runs `git diff [baseCommit]` in workdir. Parses output into [{path, status, diff}].
   * Returns empty array if not a git repo or on error.
   * If baseCommit is provided, diffs against that commit; otherwise diffs HEAD.
   */
  async gitDiff(baseCommit?: string): Promise<DiffEntry[]> {
    if (baseCommit && !/^[0-9a-f]{4,40}$/i.test(baseCommit)) {
      return []
    }
    const cmd = baseCommit ? `git diff ${baseCommit}` : "git diff HEAD"
    let raw: string
    try {
      raw = execSync(cmd, {
        cwd: this.workdir,
        encoding: "utf-8",
        stdio: ["pipe", "pipe", "pipe"],
        maxBuffer: 5 * 1024 * 1024,
      })
    } catch (err: unknown) {
      // Not a git repo or git not available
      const msg = err instanceof Error ? err.message : String(err)
      if (msg.includes("not a git repository") || msg.includes("fatal")) {
        return []
      }
      return []
    }

    if (!raw.trim()) return []

    return parseDiff(raw)
  }
}

/**
 * Parses unified diff output into structured entries.
 */
export function parseDiff(raw: string): DiffEntry[] {
  const entries: DiffEntry[] = []

  // Split on "diff --git" headers
  const chunks = raw.split(/^(?=diff --git )/m).filter(Boolean)

  for (const chunk of chunks) {
    // Extract file path from "diff --git a/... b/..." (paths may be quoted by git)
    // Unquoted form:  diff --git a/foo b/foo
    // Quoted form:    diff --git "a/foo\"bar" "b/foo\"bar"
    const headerMatch =
      chunk.match(/^diff --git "a\/((?:[^"\\]|\\.)*)" "b\/((?:[^"\\]|\\.)*)"/m) ??
      chunk.match(/^diff --git a\/(.*?) b\/(.*)$/m)
    if (!headerMatch) continue

    // Unescape C-style backslash sequences git uses inside quoted paths
    const unescapeGitPath = (s: string) => s.replace(/\\(.)/g, "$1")
    const path = unescapeGitPath(headerMatch[2]!.trim())

    // Determine status
    let status = "modified"
    if (/^new file mode/m.test(chunk)) status = "added"
    else if (/^deleted file mode/m.test(chunk)) status = "deleted"
    else if (/^rename/m.test(chunk)) status = "renamed"

    const binary = /^Binary files /m.test(chunk)
    const modeChange = /^old mode /m.test(chunk) && !/^@@/m.test(chunk)

    entries.push({ path, status, diff: chunk, binary, modeChange })
  }

  return entries
}
