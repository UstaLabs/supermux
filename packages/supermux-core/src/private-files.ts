/**
 * Files in a state directory, which unsandboxed processes (the core, the keepers) write: never reached through a
 * symbolic link. An agent sandboxed to write only its working directory or /tmp, running as the same user, could
 * otherwise plant a link there and have its own output appended to a file its sandbox keeps it from (~/.bashrc).
 * Directories are checked with lstat and realpath; files are opened with O_NOFOLLOW; a write goes to a file made new
 * (O_CREAT | O_EXCL, 0600) and renamed into place, so an existing name is never opened for writing.
 */
import { randomBytes } from "node:crypto"
import { closeSync, constants, fchmodSync, fstatSync, lstatSync, mkdirSync, openSync, readdirSync, readFileSync, realpathSync, renameSync, symlinkSync, unlinkSync, writeSync, type Stats } from "node:fs"
import { join } from "node:path"
import { CoreError } from "./errors.js"

/** Windows has no O_NOFOLLOW (nor keepers): there the lstat checks remain. */
const NOFOLLOW = constants.O_NOFOLLOW ?? 0
const LINK = "it is a symbolic link (nothing in a state directory is reached through a link)"

function refuse(path: string, why: string): CoreError {
  return new CoreError("state_unsafe", `Refusing ${path}: ${why}`)
}

/** Owned by this user (anything is, where there are no uids). */
function mine(info: Stats): boolean {
  return typeof process.getuid !== "function" || info.uid === process.getuid()
}

function code(error: unknown): string | undefined {
  return (error as NodeJS.ErrnoException)?.code
}

/** A directory of this user's, not a link, whose real path is `expected` (so no link leads to it either). */
export function assertRealDirectory(path: string, expected = path): void {
  const info = lstatSync(path)
  if (info.isSymbolicLink()) throw refuse(path, LINK)
  if (!info.isDirectory()) throw refuse(path, "it is not a directory")
  if (!mine(info)) throw refuse(path, "it belongs to another user")
  const real = realpathSync(path)
  if (real !== expected) throw refuse(path, `it leads to ${real} through a symbolic link`)
}

/** `parent/name`, made (0700) when missing, then checked with assertRealDirectory. `parent` must be a real path. */
export function realDirectoryIn(parent: string, name: string): string {
  const path = join(parent, name)
  try { mkdirSync(path, { mode: 0o700 }) } catch (error) { if (code(error) !== "EEXIST") throw error }
  assertRealDirectory(path)
  return path
}

/** The real path of state directory `path`, made (0700) when missing: the folders under it are checked against it. */
export function realStateDirectory(path: string): string {
  mkdirSync(path, { recursive: true, mode: 0o700 })
  return realpathSync(path)
}

/** Refuses a link, or anything of another user, among the entries of `dir`. */
export function assertNoLinksIn(dir: string): void {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name)
    let info: Stats
    try { info = lstatSync(path) } catch (error) {
      if (code(error) === "ENOENT") continue
      // A Windows AF_UNIX socket (a keeper's) cannot be stat'ed; a link or junction can.
      if (code(error) === "EACCES" && process.platform === "win32") continue
      throw error
    }
    if (info.isSymbolicLink()) throw refuse(path, LINK)
    if (!mine(info)) throw refuse(path, "it belongs to another user")
  }
}

/**
 * A new link at `path` to directory `target`. On Windows a junction: a symbolic link there needs
 * Developer Mode or an elevated process (EPERM otherwise), a junction needs neither.
 */
export function linkDirectory(target: string, path: string, platform: NodeJS.Platform = process.platform): void {
  symlinkSync(target, path, platform === "win32" ? "junction" : "dir")
}

/** Whether `path` names anything (a link included), without following it. */
export function present(path: string): boolean {
  try { lstatSync(path); return true } catch (error) { if (code(error) === "ENOENT") return false; throw error }
}

/** A new 0600 file of this user's at `path`, open with `flags`: never an existing file, never through a link. */
export function createPrivateFile(path: string, flags: number): number {
  let fd: number
  try {
    fd = openSync(path, flags | constants.O_CREAT | constants.O_EXCL | NOFOLLOW, 0o600)
  } catch (error) {
    if (code(error) !== "EEXIST") throw error
    let link = false
    try { link = lstatSync(path).isSymbolicLink() } catch { /* gone again */ }
    throw refuse(path, link ? LINK : "it appeared while it was being created")
  }
  // The umask can only have taken bits away; this also undoes one that took the owner's.
  fchmodSync(fd, 0o600)
  return fd
}

/** Writes all of `data` at the file position of `fd`. */
export function writeAll(fd: number, data: string | Buffer): void {
  const buffer = typeof data === "string" ? Buffer.from(data) : data
  for (let offset = 0; offset < buffer.length;) offset += writeSync(fd, buffer, offset, buffer.length - offset)
}

/** A name beside `path` for a file to be renamed onto it, new each time. */
export function stagingPath(path: string): string {
  return `${path}.${randomBytes(6).toString("hex")}.tmp`
}

/**
 * Replaces `path` with a new 0600 file holding `body`: written beside it, then renamed onto it (a rename replaces a
 * link at `path` instead of following it). `verify` runs right before the rename (to check the directory again).
 */
export function writePrivateFile(path: string, body: string | Buffer, verify?: () => void): void {
  const staging = stagingPath(path)
  const fd = createPrivateFile(staging, constants.O_WRONLY)
  try {
    try { writeAll(fd, body) } finally { closeSync(fd) }
    verify?.()
    renameSync(staging, path)
  } catch (error) {
    try { unlinkSync(staging) } catch { /* already gone */ }
    throw error
  }
}

/** The contents of `path`, a regular file of this user's, read without following a link; undefined when it is missing. */
export function readPrivateFile(path: string): string | undefined {
  let fd: number
  try {
    fd = openSync(path, constants.O_RDONLY | NOFOLLOW)
  } catch (error) {
    if (code(error) === "ENOENT") return undefined
    if (code(error) === "ELOOP" || code(error) === "EMLINK") throw refuse(path, LINK)
    throw error
  }
  try {
    const info = fstatSync(fd)
    if (!info.isFile()) throw refuse(path, "it is not a regular file")
    if (!mine(info)) throw refuse(path, "it belongs to another user")
    return readFileSync(fd, "utf8")
  } finally { closeSync(fd) }
}
