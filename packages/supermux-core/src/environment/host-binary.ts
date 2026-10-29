import { accessSync, constants, realpathSync, statSync, type Stats } from "node:fs"
import { delimiter, posix } from "node:path"

export type HostBinaryOptions = {
  /** Absolute folders searched, in order. Default: /usr/bin and /bin, then the absolute folders of PATH. */
  dirs?: readonly string[]
  /** The user id that must own the program and the folders it sits in. Default 0 (root). */
  trustedUid?: number
}

const SYSTEM_DIRS = ["/usr/bin", "/bin"]

/** Owned by `uid` and writable by neither the group nor others: nobody else can change it. */
function sealed(stat: Stats, uid: number): boolean {
  return stat.uid === uid && (stat.mode & 0o022) === 0
}

function sealedFolder(path: string, uid: number): boolean {
  try {
    const stat = statSync(path)
    return stat.isDirectory() && sealed(stat, uid)
  } catch {
    return false
  }
}

function trusted(path: string, uid: number): boolean {
  try {
    const stat = statSync(path) // follows links: the program behind it
    if (!stat.isFile() || !sealed(stat, uid)) return false
    accessSync(path, constants.X_OK)
    // The folder it is named in and the folder it really lives in: whoever can write there can swap it.
    return sealedFolder(posix.dirname(path), uid) && sealedFolder(posix.dirname(realpathSync(path)), uid)
  } catch {
    return false
  }
}

/**
 * The absolute path of the host's real program `name`, or undefined when none can be trusted. An exec-policy
 * rule that allows a command lets it run OUTSIDE Codex's sandbox, so it must name a binary an agent cannot
 * have planted: a regular executable owned by root (`trustedUid`) that neither the group nor others can write,
 * in a folder that is the same. Only absolute, canonical folders count (a relative PATH entry, or an empty one,
 * is the current folder); the answer is the path as it is named there, which is what a command has to type.
 * POSIX only: undefined on Windows.
 */
export function trustedHostBinary(name: string, options: HostBinaryOptions = {}): string | undefined {
  if (process.platform === "win32" || !/^[A-Za-z0-9._+-]+$/.test(name) || name === "." || name === "..") return undefined
  const uid = options.trustedUid ?? 0
  const dirs = options.dirs ?? [...SYSTEM_DIRS, ...(process.env.PATH ?? "").split(delimiter)]
  for (const dir of dirs) {
    if (!posix.isAbsolute(dir) || posix.resolve(dir) !== dir) continue
    const path = dir === "/" ? `/${name}` : `${dir}/${name}`
    if (trusted(path, uid)) return path
  }
  return undefined
}
