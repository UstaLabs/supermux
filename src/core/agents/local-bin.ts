// ~/.local/bin links made by the broker (macOS/Linux): OpenCode's after any install, and Cursor's
// two from the Linux builtin. Never clobbers what someone else put there: a regular file, or a
// symlink that points outside what we own, stays and the install log says so.
import { existsSync, lstatSync, mkdirSync, readlinkSync, symlinkSync, unlinkSync } from "fs"
import { posix } from "path"

export interface LocalBinLink {
  /** The name in ~/.local/bin. */
  name: string
  /** The link target, relative to the home dir. */
  target: string
  /**
   * A dir (relative to home) whose symlinks count as ours, so they may be replaced (e.g. an older
   * version). Defaults to the target's own dir.
   */
  ownedUnder?: string
}

/** The file operations [linkIntoLocalBin] needs. */
export interface LinkFs {
  /** What is at [p] (not following a symlink). */
  kind: (p: string) => "missing" | "symlink" | "other"
  exists: (p: string) => boolean
  readlink: (p: string) => string
  mkdir: (p: string) => void
  unlink: (p: string) => void
  symlink: (target: string, path: string) => void
}

export const realLinkFs: LinkFs = {
  kind: (p) => {
    try {
      return lstatSync(p).isSymbolicLink() ? "symlink" : "other"
    } catch {
      return "missing"
    }
  },
  exists: (p) => existsSync(p),
  readlink: (p) => readlinkSync(p),
  mkdir: (p) => { mkdirSync(p, { recursive: true }) },
  unlink: (p) => unlinkSync(p),
  symlink: (target, path) => symlinkSync(target, path),
}

/**
 * Link `~/.local/bin/<name>` → `~/<target>`. Replaces a symlink of ours (one pointing under
 * [LocalBinLink.ownedUnder] (by default the target's dir), or dangling. Never touches a file or someone
 * else's symlink: it says so
 * instead. Returns the line for the install log, or null when there is nothing to say.
 */
export function linkIntoLocalBin(home: string, link: LocalBinLink, fs: LinkFs): string | null {
  const target = posix.join(home, link.target)
  if (!fs.exists(target)) return null
  const dir = posix.join(home, ".local", "bin")
  const path = posix.join(dir, link.name)
  const what = fs.kind(path)
  if (what === "other") return `Not linking ${path}: a file someone else put there is in the way (${link.name} still runs in supermux).`
  if (what === "symlink") {
    const current = fs.readlink(path)
    const resolved = current.startsWith("/") ? current : posix.join(dir, current)
    if (resolved === target) return null
    const owned = link.ownedUnder ? posix.join(home, link.ownedUnder) : posix.dirname(target)
    const ours = resolved.startsWith(owned + "/") || !fs.exists(path)
    if (!ours) return `Not linking ${path}: it already points to ${current} (${link.name} still runs in supermux).`
    fs.unlink(path)
  }
  fs.mkdir(dir)
  fs.symlink(target, path)
  return `Linked ${path} -> ${target}, so ${link.name} also works in your own terminals.`
}

