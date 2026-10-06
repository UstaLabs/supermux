// After unpacking a downloaded archive: every entry must stay inside the folder it was unpacked
// into. A `..` entry (zip-slip) or a symlink pointing outside is refused before anything is moved
// into place. Uses the host's own path rules (it runs where the archive was unpacked).
import { lstatSync, readdirSync, readlinkSync } from "fs"
import { dirname, isAbsolute, relative, resolve } from "path"

export interface WalkFs {
  list: (dir: string) => string[]
  isDir: (p: string) => boolean
  readlink: (p: string) => string | null
}

export const realWalkFs: WalkFs = {
  list: (dir) => readdirSync(dir),
  isDir: (p) => { try { const s = lstatSync(p); return s.isDirectory() && !s.isSymbolicLink() } catch { return false } },
  readlink: (p) => { try { return lstatSync(p).isSymbolicLink() ? readlinkSync(p) : null } catch { return null } },
}

function inside(root: string, p: string): boolean {
  const rel = relative(root, p)
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel))
}

/** The first entry under [root] that escapes it (a path, or a symlink target), or null. */
export function findEscape(root: string, fs: WalkFs = realWalkFs): string | null {
  const top = resolve(root)
  const walk = (dir: string): string | null => {
    for (const name of fs.list(dir)) {
      const p = resolve(dir, name)
      if (!inside(top, p)) return p
      const link = fs.readlink(p)
      if (link !== null) {
        const target = resolve(dirname(p), link)
        if (!inside(top, target)) return `${p} -> ${link}`
        continue
      }
      if (fs.isDir(p)) {
        const bad = walk(p)
        if (bad) return bad
      }
    }
    return null
  }
  return walk(top)
}

/** Throw when [root] holds an escaping entry. */
export function assertContained(root: string, fs: WalkFs = realWalkFs): void {
  const bad = findEscape(root, fs)
  if (bad) throw new Error(`the archive reaches outside its folder (${bad}); refusing it`)
}

/** A rename that failed because Windows holds the file open (a running agent). */
export function isInUse(err: unknown): boolean {
  const code = (err as { code?: string })?.code
  return code === "EPERM" || code === "EBUSY" || code === "EACCES"
}
