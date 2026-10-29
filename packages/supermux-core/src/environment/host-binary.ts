import { accessSync, constants, lstatSync, readlinkSync } from "node:fs"
import { posix } from "node:path"

/** What the check needs to know about one path, without following links. */
export type HostStat = { uid: number; mode: number; isFile(): boolean; isDirectory(): boolean; isSymbolicLink(): boolean }

/** The file-system calls the check makes: a test double stands in for them (a test cannot make a root-owned file). */
export type HostFs = {
  /** Like lstat: the path itself, a link is not followed. Throws when there is nothing there. */
  lstat(path: string): HostStat
  readlink(path: string): string
  /** Can the current user execute this regular file (a path with no links in it)? */
  canExecute(path: string): boolean
}

export const nodeHostFs: HostFs = {
  lstat: path => lstatSync(path),
  readlink: path => readlinkSync(path, "utf8"),
  canExecute(path) {
    try {
      accessSync(path, constants.X_OK)
      return true
    } catch {
      return false
    }
  },
}

/**
 * Where a system keeps its own programs, in the order they are tried: folders only root can change. NixOS keeps
 * them behind /run/current-system, a link that only root can repoint. PATH is never consulted: it names folders
 * (and links to folders, like ~/.nix-profile/bin) that the user can change, and Codex would run whatever they
 * lead to outside its sandbox.
 */
const SYSTEM_DIRS = ["/usr/bin", "/bin", "/run/current-system/sw/bin"]
const ROOT = 0
const STICKY = 0o1000
const MAX_LINKS = 40 // what Linux allows in one lookup

/**
 * A directory only root can change. Group and others must not be able to write to it, unless it is sticky
 * (/nix/store is `1775 root:nixbld`): then they can add entries but cannot rename or remove one they do not own,
 * and `physicalProgram` only ever walks through entries that are root's.
 */
function closed(dir: HostStat): boolean {
  return dir.uid === ROOT && ((dir.mode & 0o022) === 0 || (dir.mode & STICKY) !== 0)
}

/**
 * Walks `path` from `/` the way the kernel resolves it, one component at a time, and returns the file it ends in only
 * when nobody but root could have put it there or can change what it leads to. Nothing is followed before it has been
 * checked:
 * - every directory it reads an entry from is root's and closed (see `closed`);
 * - every entry it walks through, links included, is root's; a link is followed by walking its target the same way,
 *   from `/` if the target is absolute, from the link's folder if not (`..` is the folder above that one, not a
 *   textual edit), so a link only its owner can repoint, such as a user's own `profile-bin -> /usr/bin`, fails at the link;
 * - the end is a regular file that is root's, that neither the group nor others can write, and that can be executed.
 * The only ways to change what a path leads to are to write to a folder on the way (checked), to own an entry in a
 * sticky one (checked: root's), or to mount over it (root's): so only root can change the answer after this.
 * Returns undefined when any of it fails, when something is missing, and after more links than Linux follows.
 */
function physicalProgram(path: string, fs: HostFs): string | undefined {
  let dir = "/"
  let dirStat = fs.lstat("/")
  if (!dirStat.isDirectory()) return undefined
  const pending = path.split("/").filter(part => part !== "")
  let links = 0
  while (pending.length > 0) {
    const part = pending.shift()!
    if (part === ".") continue
    if (part === "..") {
      dir = posix.dirname(dir) // dir has no links in it: its parent is the physical one
      dirStat = fs.lstat(dir)
      continue
    }
    const entry = dir === "/" ? `/${part}` : `${dir}/${part}`
    const stat = fs.lstat(entry)
    if (!closed(dirStat) || stat.uid !== ROOT) return undefined
    if (stat.isSymbolicLink()) {
      if (++links > MAX_LINKS) return undefined
      const target = fs.readlink(entry)
      if (target.startsWith("/")) {
        dir = "/"
        dirStat = fs.lstat("/")
      }
      pending.unshift(...target.split("/").filter(part => part !== ""))
      continue
    }
    if (stat.isDirectory()) {
      if (pending.length === 0) return undefined // it ends in a folder, not a program
      dir = entry
      dirStat = stat
      continue
    }
    // Whatever is not a folder or a link ends the walk: it has to be the program itself.
    if (pending.length > 0 || !stat.isFile() || (stat.mode & 0o022) !== 0 || !fs.canExecute(entry)) return undefined
    return entry
  }
  return undefined
}

/**
 * The host's real program `name` as it is named in one of the system folders, or undefined when none of them leads
 * to a program only root controls (see `physicalProgram`). `fs` and `dirs` are test seams, not public API: they can
 * switch the check off. The answer is the path to type: a rule that allows a command lets it run outside the sandbox.
 */
export function resolveHostBinary(name: string, fs: HostFs = nodeHostFs, dirs: readonly string[] = SYSTEM_DIRS): string | undefined {
  if (!/^[A-Za-z0-9._+-]+$/.test(name) || name === "." || name === "..") return undefined
  for (const dir of dirs) {
    if (!posix.isAbsolute(dir) || posix.resolve(dir) !== dir) continue
    const path = dir === "/" ? `/${name}` : `${dir}/${name}`
    try {
      if (physicalProgram(path, fs) !== undefined) return path
    } catch {
      // missing, or not readable: not this one
    }
  }
  return undefined
}

/**
 * The host's real `cat`, `head`, `tail` or `wc` (any program name) as a path to type, or undefined when the system
 * folders hold none that only root controls. POSIX only: undefined on Windows.
 */
export function trustedHostBinary(name: string): string | undefined {
  return process.platform === "win32" ? undefined : resolveHostBinary(name)
}
