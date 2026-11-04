// Links, the way each OS allows them (#37). A folder link is a junction on Windows, which any user may create (the
// type is ignored elsewhere). A file link is a symlink, which Windows lets a standard user create only with Developer
// Mode: without it the answer is Unsupported, and the caller falls back on purpose (Q5: copy a small file, or keep an
// account's files to itself) instead of failing halfway, or swallowing EPERM and losing the file.
import { mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Unsupported } from "./errors.js"

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
