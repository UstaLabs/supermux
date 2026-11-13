// Links, the way each OS allows them (#37). A folder link is a junction on Windows, which any user may create (the
// type is ignored elsewhere). A file link is a symlink, which Windows lets a standard user create only with Developer
// Mode: without it the answer is Unsupported, and the caller falls back on purpose (Q5: copy a small file, or keep an
// account's files to itself) instead of failing halfway, or swallowing EPERM and losing the file.
import { execFileSync } from "node:child_process"
import { appendFileSync, chmodSync, copyFileSync, cpSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, readlinkSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync, type Stats } from "node:fs"
import { cp, mkdir as mkdirAsync, rename } from "node:fs/promises"
import { homedir, tmpdir } from "node:os"
import { dirname, isAbsolute, join, relative, resolve } from "node:path"
import { Unsupported } from "./errors.js"
import { flavor, isInside } from "./paths.js"

type LinkType = "file" | "dir" | "junction"

export interface LinkOptions {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** The link call (tests); node:fs's symlinkSync by default. */
  symlink?: (target: string, path: string, type: LinkType) => void
}

const realSymlink = (target: string, path: string, type: LinkType) => symlinkSync(target, path, type)

/** [path] → the folder [target]: a junction on Windows (no privilege needed), a symlink elsewhere. */
export function linkDirectory(target: string, path: string, options: LinkOptions = {}): void {
  ;(options.symlink ?? realSymlink)(target, path, "junction")
}

/**
 * [path] → the file [target]. On Windows without the symlink privilege (no Developer Mode, not elevated) it throws
 * [Unsupported]; any other failure is the OS's error as it is.
 */
export function linkFile(target: string, path: string, options: LinkOptions = {}): void {
  try {
    ;(options.symlink ?? realSymlink)(target, path, "file")
  } catch (error) {
    if ((options.platform ?? process.platform) === "win32" && (error as NodeJS.ErrnoException).code === "EPERM") {
      throw new Unsupported("files.linkFile", "creating a file link on Windows needs Developer Mode (or an elevated process)")
    }
    throw error
  }
}

export interface CanLinkOptions extends LinkOptions {
  /** Where to try (a folder of its own is made inside); the OS temp dir by default. */
  scratch?: string
  /** The answer per platform, kept for the process; a fresh map in tests. */
  cache?: Map<string, boolean>
}

const answers = new Map<string, boolean>()

/** Whether this process may create file links: tried once (a link in a scratch folder of its own) and remembered. */
export function canLinkFiles(options: CanLinkOptions = {}): boolean {
  const platform = options.platform ?? process.platform
  const cache = options.cache ?? answers
  const known = cache.get(platform)
  if (known !== undefined) return known
  const dir = mkdtempSync(join(options.scratch ?? tmpdir(), "mux-link-probe-"))
  let can: boolean
  try {
    writeFileSync(join(dir, "target"), "")
    linkFile(join(dir, "target"), join(dir, "link"), { platform, symlink: options.symlink })
    can = true
  } catch (error) {
    if (!(error instanceof Unsupported)) throw error
    can = false
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
  cache.set(platform, can)
  return can
}

/**
 * Copies [src] (a folder, a file or a link) to [dest], links as links, so that the copy stands on its own: a link to
 * something inside [src] points at the same thing inside [dest] (a relative one is kept as written), and a link to
 * anything else still points at it. The runtime's cp gets one or the other wrong: by default it points every relative
 * link back into [src], and with verbatimSymlinks a relative link out of [src] points nowhere. Files and folders are
 * the runtime's to copy. A folder link is made by [linkDirectory] (a junction on Windows), and a file link the OS
 * refuses (Windows without Developer Mode) is copied as the file it points at.
 */
export async function copyTree(src: string, dest: string, options: LinkOptions = {}): Promise<void> {
  await cp(src, dest, { recursive: true, filter: linksCopiedHere(src, dest, options) })
}

/** [copyTree], blocking. */
export function copyTreeSync(src: string, dest: string, options: LinkOptions = {}): void {
  cpSync(src, dest, { recursive: true, filter: linksCopiedHere(src, dest, options) })
}

/** cp's filter sees every entry before cp copies it, with the folder for it already made (in Bun and in Node). */
function linksCopiedHere(src: string, dest: string, options: LinkOptions): (from: string, to: string) => boolean {
  const root = resolve(src)
  const copyRoot = resolve(dest)
  return (from, to) => {
    if (!lstatSync(from).isSymbolicLink()) return true
    copyLink(from, to, root, copyRoot, options)
    return false
  }
}

function copyLink(from: string, to: string, root: string, copyRoot: string, options: LinkOptions): void {
  const written = readlinkSync(from)
  const target = resolve(dirname(from), written)
  // Inside the copied folder: the same thing in the copy, which a relative link already means. Outside: where it was.
  const pointAt = !isInside(target, root) ? target : isAbsolute(written) ? join(copyRoot, relative(root, target)) : written
  mkdirSync(dirname(to), { recursive: true })
  // An earlier copy's link or file gives way, as cp's own files do.
  if (lstatSync(to, { throwIfNoEntry: false })?.isDirectory() === false) rmSync(to)
  if (statSync(target, { throwIfNoEntry: false })?.isDirectory()) {
    // A junction's target is absolute: the folder a relative link means is the one beside the copy's link.
    const windows = (options.platform ?? process.platform) === "win32"
    linkDirectory(windows ? resolve(dirname(to), pointAt) : pointAt, to, options)
    return
  }
  try {
    linkFile(pointAt, to, options)
  } catch (error) {
    if (!(error instanceof Unsupported)) throw error
    copyFileSync(target, to)
  }
}

export interface EntryOptions {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** The lstat call (tests); node:fs's lstatSync by default. */
  lstat?: (path: string) => Stats
}

/**
 * What is at [path], never following a link: lstat's answer, or undefined when nothing is there. On Windows lstat
 * cannot read an AF_UNIX socket file (Bun answers EACCES; a link or a junction it reads), so there an entry it refuses
 * is a "socket", which only connecting to it tells live from stale.
 */
export function lstatEntry(path: string, options: EntryOptions = {}): Stats | "socket" | undefined {
  try {
    return (options.lstat ?? lstatSync)(path)
  } catch (error) {
    const code = (error as NodeJS.ErrnoException).code
    if (code === "ENOENT") return undefined
    if (code === "EACCES" && (options.platform ?? process.platform) === "win32") return "socket"
    throw error
  }
}

/**
 * Whether [error], from creating or opening a file, means its name is still held by a file being removed: Windows keeps
 * a removed file's name until the last handle to it closes, and creating that name meanwhile fails with EPERM
 * (ERROR_ACCESS_DENIED; EACCES on some paths). The name is free again in a moment: retry, as for a lock someone holds.
 */
export function nameStillHeld(error: unknown, platform: NodeJS.Platform = process.platform): boolean {
  const code = (error as NodeJS.ErrnoException | undefined)?.code
  return platform === "win32" && (code === "EPERM" || code === "EACCES")
}

/**
 * The bytes the files under [dir] take: `du` where the OS has one (fast, and what the disk holds), else a walk that sums
 * the files' lengths and never follows a link. Windows has no du (a repo's size read 0 there). 0 for no folder.
 */
export function folderSize(dir: string, options: { platform?: NodeJS.Platform } = {}): number {
  if ((options.platform ?? process.platform) !== "win32") {
    try {
      const kib = parseInt(execFileSync("du", ["-sk", dir], { encoding: "utf8", stdio: ["ignore", "pipe", "ignore"], windowsHide: true }).split(/\s+/)[0] ?? "", 10)
      if (Number.isFinite(kib)) return kib * 1024
    } catch { /* no du, or no folder: the walk answers */ }
  }
  return walkedSize(dir)
}

function walkedSize(path: string): number {
  let info: Stats
  try { info = lstatSync(path) } catch { return 0 }
  if (info.isSymbolicLink()) return 0
  if (!info.isDirectory()) return info.isFile() ? info.size : 0
  let total = 0
  let names: string[]
  try { names = readdirSync(path) } catch { return 0 }
  for (const name of names) total += walkedSize(join(path, name))
  return total
}

/** What a rename on Windows answers while another process has the target open. */
const BUSY = new Set(["EPERM", "EACCES", "EBUSY"])

export interface ReplaceOptions<R> {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** How long Windows may keep the target busy before the error is thrown; 2 s by default. */
  patienceMs?: number
  /** The rename call (tests). */
  rename?: R
}

function busyOnWindows(error: unknown, platform: NodeJS.Platform | undefined): boolean {
  return (platform ?? process.platform) === "win32" && BUSY.has((error as NodeJS.ErrnoException)?.code ?? "")
}

/**
 * Moves [from] onto [to], replacing it: the last step of an atomic write. On Windows a rename fails for a moment while
 * another process has [to] open (an antivirus or the search indexer looking at a file just written), with EPERM,
 * EACCES or EBUSY: retried there, backing off, for up to `patienceMs` (graceful-fs does the same). Elsewhere one rename.
 */
export function replaceSync(from: string, to: string, options: ReplaceOptions<(from: string, to: string) => void> = {}): void {
  const move = options.rename ?? renameSync
  const deadline = Date.now() + (options.patienceMs ?? 2000)
  for (let wait = 10; ; wait = Math.min(wait * 2, 100)) {
    try {
      move(from, to)
      return
    } catch (error) {
      if (!busyOnWindows(error, options.platform) || Date.now() + wait > deadline) throw error
      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, wait)
    }
  }
}

/** [replaceSync], without blocking. */
export async function replace(from: string, to: string, options: ReplaceOptions<(from: string, to: string) => Promise<void>> = {}): Promise<void> {
  const move = options.rename ?? rename
  const deadline = Date.now() + (options.patienceMs ?? 2000)
  for (let wait = 10; ; wait = Math.min(wait * 2, 100)) {
    try {
      await move(from, to)
      return
    } catch (error) {
      if (!busyOnWindows(error, options.platform) || Date.now() + wait > deadline) throw error
      await new Promise(resolve => setTimeout(resolve, wait))
    }
  }
}

// ── Owner-only files and folders (spec step 7) ──
// POSIX modes where they exist. Windows has no mode (chmod there only toggles read-only): a file is kept private by
// the inherited ACL of the user's profile, so what lives in it is private and what lives outside it may not be.

export interface PrivateOptions {
  /** The OS whose rules apply; the running one by default. */
  platform?: NodeJS.Platform
  /** The chmod call (tests); node:fs's chmodSync by default. */
  chmod?: (path: string, mode: number) => void
  /** The user's profile (Windows); os.homedir() by default. */
  home?: string
  /** A file only its owner may run too (a launcher, an askpass script): 0o700 instead of 0o600. */
  executable?: boolean
}

/**
 * Make [path] (a file or a folder) owner-only: 0o600 or 0o700 on POSIX. On Windows nothing is changed; true when the
 * path is inside the user's profile, whose ACL keeps it private, false when it is not (the caller may say so).
 */
export function makePrivate(path: string, options: PrivateOptions = {}): boolean {
  const platform = options.platform ?? process.platform
  if (platform === "win32") return isInside(path, options.home ?? homedir(), flavor("win32"))
  ;(options.chmod ?? chmodSync)(path, statSync(path).isDirectory() || options.executable ? 0o700 : 0o600)
  return true
}

/** Create the folder [dir] (and its parents) owner-only: 0o700 on POSIX for the folders it creates. */
export function mkdirPrivate(dir: string, options: { recursive?: boolean } = {}): void {
  mkdirSync(dir, { recursive: options.recursive ?? true, mode: 0o700 })
}

/** [dir], created owner-only when it is not there yet (async). */
export async function mkdirPrivateAsync(dir: string, options: { recursive?: boolean } = {}): Promise<void> {
  await mkdirAsync(dir, { recursive: options.recursive ?? true, mode: 0o700 })
}

/** Write [data] to [path], creating it owner-only (0o600 on POSIX). [options] are the write's own (flag, encoding). */
export function writePrivate(path: string, data: string | NodeJS.ArrayBufferView, options: { flag?: string; encoding?: BufferEncoding } = {}): void {
  writeFileSync(path, data, { ...options, mode: 0o600 })
}

/** Append [data] to [path], creating it owner-only (0o600 on POSIX) when it is not there yet. */
export function appendPrivate(path: string, data: string | Uint8Array): void {
  appendFileSync(path, data, { mode: 0o600 })
}

/**
 * Write [data] to [path], creating it executable (0o755 on POSIX; Windows runs by extension); [options.private]: only
 * its owner may read or run it (0o700).
 */
export function writeExecutable(path: string, data: string | NodeJS.ArrayBufferView, options: { private?: boolean } = {}): void {
  writeFileSync(path, data, { mode: options.private ? 0o700 : 0o755 })
}

/** Make [path] executable (0o755 on POSIX); nothing on Windows, which runs a file by its extension. */
export function makeExecutable(path: string, options: { platform?: NodeJS.Platform } = {}): void {
  if ((options.platform ?? process.platform) !== "win32") chmodSync(path, 0o755)
}
